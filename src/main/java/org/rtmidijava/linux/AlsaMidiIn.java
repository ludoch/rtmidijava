package org.rtmidijava.linux;

import org.rtmidijava.RtMidiIn;
import java.lang.foreign.*;
import java.util.List;
import java.io.ByteArrayOutputStream;

import static org.rtmidijava.linux.AlsaApi.*;

public class AlsaMidiIn extends RtMidiIn {
    private MemorySegment seqHandle = MemorySegment.NULL;
    private int vPort = -1;
    private Thread worker;
    private final ByteArrayOutputStream sysexBuffer = new ByteArrayOutputStream();
    private Callback javaCallback;

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
                if (result < 0) throw new RuntimeException("snd_seq_open failed: " + result);
                seqHandle = pHandle.get(ValueLayout.ADDRESS, 0).reinterpret(Long.MAX_VALUE);
            }
            
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
                if (result < 0) throw new RuntimeException("snd_seq_open failed: " + result);
                seqHandle = pHandle.get(ValueLayout.ADDRESS, 0).reinterpret(Long.MAX_VALUE);
            }
            
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
            UnixApi.setThreadPriority(99);
            try (Arena arena = Arena.ofShared()) {
                MemorySegment pEv = arena.allocate(ValueLayout.ADDRESS);
                while (connected) {
                    int result = (int) snd_seq_event_input.invokeExact(seqHandle, pEv);
                    if (result >= 0) {
                        MemorySegment ev = pEv.get(ValueLayout.ADDRESS, 0).reinterpret(snd_seq_event_t.byteSize());
                        byte[] midi = parseEvent(ev);
                        if (midi != null) {
                            synchronized(this) {
                                if (connected) {
                                    onIncomingMessage(System.nanoTime() / 1_000_000_000.0, midi);
                                }
                            }
                        }
                    }
                }
            } catch (Throwable t) {
            }
        });
        worker.setDaemon(true);
        worker.start();
    }

    private byte[] parseEvent(MemorySegment ev) {
        byte type = ev.get(ValueLayout.JAVA_BYTE, snd_seq_event_t.byteOffset(MemoryLayout.PathElement.groupElement("type")));
        byte channel = ev.get(ValueLayout.JAVA_BYTE, snd_seq_event_t.byteOffset(MemoryLayout.PathElement.groupElement("data"), MemoryLayout.PathElement.groupElement("note"), MemoryLayout.PathElement.groupElement("channel")));
        byte note = ev.get(ValueLayout.JAVA_BYTE, snd_seq_event_t.byteOffset(MemoryLayout.PathElement.groupElement("data"), MemoryLayout.PathElement.groupElement("note"), MemoryLayout.PathElement.groupElement("note")));
        byte velocity = ev.get(ValueLayout.JAVA_BYTE, snd_seq_event_t.byteOffset(MemoryLayout.PathElement.groupElement("data"), MemoryLayout.PathElement.groupElement("note"), MemoryLayout.PathElement.groupElement("velocity")));

        switch (type) {
            case SND_SEQ_EVENT_NOTEON:
                return new byte[]{(byte) (0x90 | channel), note, velocity};
            case SND_SEQ_EVENT_NOTEOFF:
                return new byte[]{(byte) (0x80 | channel), note, velocity};
            case SND_SEQ_EVENT_CONTROLLER: {
                int param = ev.get(ValueLayout.JAVA_INT, snd_seq_event_t.byteOffset(MemoryLayout.PathElement.groupElement("data"), MemoryLayout.PathElement.groupElement("control"), MemoryLayout.PathElement.groupElement("param")));
                int value = ev.get(ValueLayout.JAVA_INT, snd_seq_event_t.byteOffset(MemoryLayout.PathElement.groupElement("data"), MemoryLayout.PathElement.groupElement("control"), MemoryLayout.PathElement.groupElement("value")));
                return new byte[]{(byte) (0xB0 | channel), (byte) param, (byte) value};
            }
            case SND_SEQ_EVENT_PGMCHANGE: {
                int value = ev.get(ValueLayout.JAVA_INT, snd_seq_event_t.byteOffset(MemoryLayout.PathElement.groupElement("data"), MemoryLayout.PathElement.groupElement("control"), MemoryLayout.PathElement.groupElement("value")));
                return new byte[]{(byte) (0xC0 | channel), (byte) value};
            }
            case SND_SEQ_EVENT_CHANPRESS: {
                int value = ev.get(ValueLayout.JAVA_INT, snd_seq_event_t.byteOffset(MemoryLayout.PathElement.groupElement("data"), MemoryLayout.PathElement.groupElement("control"), MemoryLayout.PathElement.groupElement("value")));
                return new byte[]{(byte) (0xD0 | channel), (byte) value};
            }
            case SND_SEQ_EVENT_PITCHBEND: {
                int value = ev.get(ValueLayout.JAVA_INT, snd_seq_event_t.byteOffset(MemoryLayout.PathElement.groupElement("data"), MemoryLayout.PathElement.groupElement("control"), MemoryLayout.PathElement.groupElement("value")));
                value += 8192;
                return new byte[]{(byte) (0xE0 | channel), (byte) (value & 0x7F), (byte) ((value >> 7) & 0x7F)};
            }
            case SND_SEQ_EVENT_SYSEX: {
                int len = ev.get(ValueLayout.JAVA_INT, snd_seq_event_t.byteOffset(MemoryLayout.PathElement.groupElement("data"), MemoryLayout.PathElement.groupElement("ext"), MemoryLayout.PathElement.groupElement("len")));
                MemorySegment ptr = ev.get(ValueLayout.ADDRESS, snd_seq_event_t.byteOffset(MemoryLayout.PathElement.groupElement("data"), MemoryLayout.PathElement.groupElement("ext"), MemoryLayout.PathElement.groupElement("ptr")));
                byte[] data = ptr.reinterpret(len).toArray(ValueLayout.JAVA_BYTE);
                
                // Check if it's a chunk or a complete message
                // In ALSA, if the message is large, it comes in chunks.
                // We reassemble until we see 0xF7 at the end.
                try {
                    sysexBuffer.write(data);
                    if (data[data.length - 1] == (byte)0xF7) {
                        byte[] full = sysexBuffer.toByteArray();
                        sysexBuffer.reset();
                        return full;
                    }
                } catch (Exception e) {}
                return null; // Not finished yet
            }
        }
        return null;
    }

    @Override
    public void closePort() {
        connected = false;
        if (!seqHandle.equals(MemorySegment.NULL)) {
            try {
                snd_seq_close.invokeExact(seqHandle);
            } catch (Throwable t) {}
            seqHandle = MemorySegment.NULL;
        }
        if (worker != null) worker.interrupt();
    }

    @Override
    public void setCallback(Callback callback) {
        this.javaCallback = callback;
    }

    @Override
    public void cancelCallback() {
        this.javaCallback = null;
    }

    @Override
    public void ignoreTypes(boolean midiSysex, boolean midiTime, boolean midiSense) {}

    @Override
    public byte[] getMessage() {
        return new byte[0];
    }
}
