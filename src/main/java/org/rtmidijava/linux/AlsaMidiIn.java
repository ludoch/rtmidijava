package org.rtmidijava.linux;

import org.rtmidijava.RtMidiIn;
import java.lang.foreign.*;
import java.util.List;

import static org.rtmidijava.linux.AlsaApi.*;

public class AlsaMidiIn extends RtMidiIn {
    private MemorySegment seqHandle = MemorySegment.NULL;
    private int vPort = -1;
    private Thread worker;
    private MemorySegment sysexNativeBuffer;
    private int sysexOffset = 0;
    private int pipeRead = -1;
    private int pipeWrite = -1;

    @Override
    public Api getCurrentApi() {
        return Api.LINUX_ALSA;
    }

    @Override
    public int getPortCount() {
        return getPorts(true).size();
    }

    @Override
    public String getPortName(int portNumber) {
        List<AlsaPortInfo> ports = getPorts(true);
        if (portNumber >= 0 && portNumber < ports.size()) {
            return ports.get(portNumber).name;
        }
        return null;
    }

    @Override
    public synchronized void openPort(int portNumber, String portName) {
        List<AlsaPortInfo> ports = getPorts(true);
        if (portNumber < 0 || portNumber >= ports.size()) {
            throw new RuntimeException("Invalid port number");
        }
        AlsaPortInfo src = ports.get(portNumber);

        try (Arena arena = Arena.ofConfined()) {
            if (seqHandle.equals(MemorySegment.NULL)) {
                MemorySegment pHandle = arena.allocate(ValueLayout.ADDRESS);
                int result = (int) snd_seq_open.invokeExact(pHandle, arena.allocateFrom("default"), SND_SEQ_OPEN_INPUT, 0);
                if (result < 0) {
                    MemorySegment errPtr = (MemorySegment) UnixApi.strerror.invokeExact(-result);
                    String errMsg = errPtr.reinterpret(256).getString(0);
                    throw new RuntimeException("snd_seq_open failed: " + errMsg + " (" + result + ")");
                }
                seqHandle = pHandle.get(ValueLayout.ADDRESS, 0).reinterpret(Long.MAX_VALUE);
                
                // Pro-Audio: Increase client pool for large Sysex
                snd_seq_set_client_pool_input.invokeExact(seqHandle, 4096L);

                // Pre-allocate reassembly buffer in shared arena
                sysexNativeBuffer = sharedArena.allocate(8192);
            }
            
            // Pro-Audio: Create wakeup pipe for clean shutdown
            MemorySegment fds = arena.allocate(ValueLayout.JAVA_INT, 2);
            UnixApi.pipe.invokeExact(fds);
            pipeRead = fds.get(ValueLayout.JAVA_INT, 0);
            pipeWrite = fds.get(ValueLayout.JAVA_INT, 4);

            snd_seq_set_client_name.invokeExact(seqHandle, arena.allocateFrom("RtMidiJava Client"));
            vPort = (int) snd_seq_create_simple_port.invokeExact(seqHandle, arena.allocateFrom(portName), 
                SND_SEQ_PORT_CAP_WRITE | SND_SEQ_PORT_CAP_SUBS_WRITE, SND_SEQ_PORT_TYPE_MIDI_GENERIC | SND_SEQ_PORT_TYPE_APPLICATION);
            
            snd_seq_connect_to.invokeExact(seqHandle, src.client, src.port, vPort);
            
            connected = true;
            startWorker();
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    @Override
    public synchronized void openVirtualPort(String portName) {
        try (Arena arena = Arena.ofConfined()) {
            if (seqHandle.equals(MemorySegment.NULL)) {
                MemorySegment pHandle = arena.allocate(ValueLayout.ADDRESS);
                int result = (int) snd_seq_open.invokeExact(pHandle, arena.allocateFrom("default"), SND_SEQ_OPEN_INPUT, 0);
                if (result < 0) {
                    MemorySegment errPtr = (MemorySegment) UnixApi.strerror.invokeExact(-result);
                    String errMsg = errPtr.reinterpret(256).getString(0);
                    throw new RuntimeException("snd_seq_open failed: " + errMsg + " (" + result + ")");
                }
                seqHandle = pHandle.get(ValueLayout.ADDRESS, 0).reinterpret(Long.MAX_VALUE);
                
                // Pro-Audio: Increase client pool for large Sysex
                snd_seq_set_client_pool_input.invokeExact(seqHandle, 4096L);
                sysexNativeBuffer = sharedArena.allocate(8192);
            }
            
            // Pro-Audio: Create wakeup pipe for clean shutdown
            MemorySegment fds = arena.allocate(ValueLayout.JAVA_INT, 2);
            UnixApi.pipe.invokeExact(fds);
            pipeRead = fds.get(ValueLayout.JAVA_INT, 0);
            pipeWrite = fds.get(ValueLayout.JAVA_INT, 4);

            snd_seq_set_client_name.invokeExact(seqHandle, arena.allocateFrom("RtMidiJava Client"));
            vPort = (int) snd_seq_create_simple_port.invokeExact(seqHandle, arena.allocateFrom(portName), 
                SND_SEQ_PORT_CAP_WRITE | SND_SEQ_PORT_CAP_SUBS_WRITE, SND_SEQ_PORT_TYPE_MIDI_GENERIC | SND_SEQ_PORT_TYPE_APPLICATION);
            
            connected = true;
            startWorker();
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private void startWorker() {
        worker = new Thread(() -> {
            org.rtmidijava.utils.ThreadUtils.makeRealTime();
            try (Arena arena = Arena.ofShared()) {
                int alsaCount = (int) snd_seq_poll_descriptors_count.invokeExact(seqHandle, UnixApi.POLLIN);
                int totalCount = alsaCount + 1;
                MemorySegment pollFds = arena.allocate(UnixApi.pollfd, totalCount);
                
                while (connected) {
                    snd_seq_poll_descriptors.invokeExact(seqHandle, pollFds, alsaCount, UnixApi.POLLIN);
                    
                    MemorySegment pipeFd = pollFds.asSlice(alsaCount * UnixApi.pollfd.byteSize());
                    pipeFd.set(ValueLayout.JAVA_INT, 0, pipeRead);
                    pipeFd.set(ValueLayout.JAVA_SHORT, 4, UnixApi.POLLIN);
                    pipeFd.set(ValueLayout.JAVA_SHORT, 6, (short)0);

                    int pollResult = (int) UnixApi.poll.invokeExact(pollFds, (long) totalCount, -1);
                    if (pollResult <= 0) continue;

                    if ((pipeFd.get(ValueLayout.JAVA_SHORT, 6) & UnixApi.POLLIN) != 0) break;

                    MemorySegment pEv = arena.allocate(ValueLayout.ADDRESS);
                    while ((int) snd_seq_event_input.invokeExact(seqHandle, pEv) >= 0) {
                        MemorySegment ev = pEv.get(ValueLayout.ADDRESS, 0).reinterpret(snd_seq_event_t.byteSize());
                        parseAndDispatch(ev);
                    }
                }
            } catch (Throwable t) {}
        });
        worker.setDaemon(true);
        worker.start();
    }

    private void parseAndDispatch(MemorySegment ev) {
        byte type = ev.get(ValueLayout.JAVA_BYTE, snd_seq_event_t.byteOffset(MemoryLayout.PathElement.groupElement("type")));
        byte channel = ev.get(ValueLayout.JAVA_BYTE, snd_seq_event_t.byteOffset(MemoryLayout.PathElement.groupElement("data"), MemoryLayout.PathElement.groupElement("note"), MemoryLayout.PathElement.groupElement("channel")));
        byte note = ev.get(ValueLayout.JAVA_BYTE, snd_seq_event_t.byteOffset(MemoryLayout.PathElement.groupElement("data"), MemoryLayout.PathElement.groupElement("note"), MemoryLayout.PathElement.groupElement("note")));
        byte velocity = ev.get(ValueLayout.JAVA_BYTE, snd_seq_event_t.byteOffset(MemoryLayout.PathElement.groupElement("data"), MemoryLayout.PathElement.groupElement("note"), MemoryLayout.PathElement.groupElement("velocity")));

        try (Arena local = Arena.ofConfined()) {
            double ts = System.nanoTime() / 1_000_000_000.0;
            switch (type) {
                case SND_SEQ_EVENT_NOTEON -> onIncomingMessage(ts, local.allocateFrom(ValueLayout.JAVA_BYTE, new byte[]{(byte) (0x90 | channel), note, velocity}));
                case SND_SEQ_EVENT_NOTEOFF -> onIncomingMessage(ts, local.allocateFrom(ValueLayout.JAVA_BYTE, new byte[]{(byte) (0x80 | channel), note, velocity}));
                case SND_SEQ_EVENT_CONTROLLER -> {
                    int param = ev.get(ValueLayout.JAVA_INT, snd_seq_event_t.byteOffset(MemoryLayout.PathElement.groupElement("data"), MemoryLayout.PathElement.groupElement("control"), MemoryLayout.PathElement.groupElement("param")));
                    int value = ev.get(ValueLayout.JAVA_INT, snd_seq_event_t.byteOffset(MemoryLayout.PathElement.groupElement("data"), MemoryLayout.PathElement.groupElement("control"), MemoryLayout.PathElement.groupElement("value")));
                    onIncomingMessage(ts, local.allocateFrom(ValueLayout.JAVA_BYTE, new byte[]{(byte) (0xB0 | channel), (byte) param, (byte) value}));
                }
                case SND_SEQ_EVENT_SYSEX -> {
                    int len = ev.get(ValueLayout.JAVA_INT, snd_seq_event_t.byteOffset(MemoryLayout.PathElement.groupElement("data"), MemoryLayout.PathElement.groupElement("ext"), MemoryLayout.PathElement.groupElement("len")));
                    MemorySegment ptr = ev.get(ValueLayout.ADDRESS, snd_seq_event_t.byteOffset(MemoryLayout.PathElement.groupElement("data"), MemoryLayout.PathElement.groupElement("ext"), MemoryLayout.PathElement.groupElement("ptr")));
                    MemorySegment data = ptr.reinterpret(len);
                    
                    MemorySegment.copy(data, 0, sysexNativeBuffer, sysexOffset, len);
                    sysexOffset += len;
                    
                    if (data.get(ValueLayout.JAVA_BYTE, len - 1) == (byte)0xF7) {
                        onIncomingMessage(ts, sysexNativeBuffer.asSlice(0, sysexOffset));
                        sysexOffset = 0;
                    }
                }
            }
        } catch (Throwable t) {}
    }

    @Override
    public synchronized void closePort() {
        if (!connected) return;
        connected = false;

        if (pipeWrite != -1) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment b = arena.allocate(1);
                b.set(ValueLayout.JAVA_BYTE, 0, (byte) 1);
                UnixApi.write.invokeExact(pipeWrite, b, 1L);
            } catch (Throwable t) {}
        }

        if (!seqHandle.equals(MemorySegment.NULL)) {
            try {
                snd_seq_close.invokeExact(seqHandle);
            } catch (Throwable t) {}
            seqHandle = MemorySegment.NULL;
        }

        if (pipeRead != -1) {
            try {
                UnixApi.close.invokeExact(pipeRead);
                UnixApi.close.invokeExact(pipeWrite);
            } catch (Throwable t) {}
            pipeRead = -1;
            pipeWrite = -1;
        }
        
        if (worker != null) {
            try {
                worker.join(500);
            } catch (InterruptedException e) {}
            worker = null;
        }
    }

    @Override
    public void setCallback(Callback callback) {
        this.callback = callback;
        this.fastCallback = null;
    }

    @Override
    public void cancelCallback() {
        this.callback = null;
        this.fastCallback = null;
    }

    @Override
    public void ignoreTypes(boolean midiSysex, boolean midiTime, boolean midiSense) {
        this.ignoreSysex = midiSysex;
        this.ignoreTime = midiTime;
        this.ignoreSense = midiSense;
    }

    @Override
    public byte[] getMessage() {
        MidiMessage msg = getMessageWithTimestamp();
        return msg != null ? msg.data() : null;
    }
}
