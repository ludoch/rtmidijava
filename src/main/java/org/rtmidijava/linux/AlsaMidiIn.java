package org.rtmidijava.linux;

import org.rtmidijava.RtMidiIn;
import org.rtmidijava.RtMidiException;
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
            error(RtMidiException.Type.INVALID_PARAMETER, "Invalid port number");
            return;
        }
        AlsaPortInfo src = ports.get(portNumber);

        try (Arena arena = Arena.ofConfined()) {
            if (seqHandle.equals(MemorySegment.NULL)) {
                MemorySegment pHandle = arena.allocate(ValueLayout.ADDRESS);
                int result = (int) snd_seq_open.invokeExact(pHandle, arena.allocateFrom("default"), SND_SEQ_OPEN_INPUT, SND_SEQ_NONBLOCK);
                if (result < 0) {
                    MemorySegment errPtr = (MemorySegment) UnixApi.strerror.invokeExact(-result);
                    String errMsg = errPtr.reinterpret(256).getString(0);
                    error(RtMidiException.Type.DRIVER_ERROR, "snd_seq_open failed: " + errMsg + " (" + result + ")");
                    return;
                }
                seqHandle = pHandle.get(ValueLayout.ADDRESS, 0).reinterpret(Long.MAX_VALUE);
                
                // Pro-Audio: Increase client pool for large Sysex
                int _ = (int) snd_seq_set_client_pool_input.invokeExact(seqHandle, 4096L);

                // Pre-allocate reassembly buffer in shared arena
                sysexNativeBuffer = sharedArena.allocate(8192);
            }
            
            // Pro-Audio: Create wakeup pipe for clean shutdown
            MemorySegment fds = arena.allocate(ValueLayout.JAVA_INT, 2);
            int _ = (int) UnixApi.pipe.invokeExact(fds);
            pipeRead = fds.get(ValueLayout.JAVA_INT, 0);
            pipeWrite = fds.get(ValueLayout.JAVA_INT, 4);

            int _ = (int) snd_seq_set_client_name.invokeExact(seqHandle, arena.allocateFrom(clientName));
            vPort = (int) snd_seq_create_simple_port.invokeExact(seqHandle, arena.allocateFrom(portName), 
                SND_SEQ_PORT_CAP_WRITE | SND_SEQ_PORT_CAP_SUBS_WRITE, SND_SEQ_PORT_TYPE_MIDI_GENERIC | SND_SEQ_PORT_TYPE_APPLICATION);
            
            // Input subscription: connect the source (sender) port TO our read port. This is
            // snd_seq_connect_from(seq, myPort, srcClient, srcPort) — NOT connect_to (which is for
            // output). The previous connect_to call left the input port unsubscribed, so no events
            // were ever received on Linux/ALSA.
            int _ = (int) snd_seq_connect_from.invokeExact(seqHandle, vPort, src.client, src.port);

            connected = true;
            startWorker();
        } catch (Throwable t) {
            error(RtMidiException.Type.DRIVER_ERROR, String.valueOf(t.getMessage()));
        }
    }

    @Override
    public synchronized void openVirtualPort(String portName) {
        try (Arena arena = Arena.ofConfined()) {
            if (seqHandle.equals(MemorySegment.NULL)) {
                MemorySegment pHandle = arena.allocate(ValueLayout.ADDRESS);
                int result = (int) snd_seq_open.invokeExact(pHandle, arena.allocateFrom("default"), SND_SEQ_OPEN_INPUT, SND_SEQ_NONBLOCK);
                if (result < 0) {
                    MemorySegment errPtr = (MemorySegment) UnixApi.strerror.invokeExact(-result);
                    String errMsg = errPtr.reinterpret(256).getString(0);
                    error(RtMidiException.Type.DRIVER_ERROR, "snd_seq_open failed: " + errMsg + " (" + result + ")");
                    return;
                }
                seqHandle = pHandle.get(ValueLayout.ADDRESS, 0).reinterpret(Long.MAX_VALUE);
                
                // Pro-Audio: Increase client pool for large Sysex
                int _ = (int) snd_seq_set_client_pool_input.invokeExact(seqHandle, 4096L);
                sysexNativeBuffer = sharedArena.allocate(8192);
            }
            
            // Pro-Audio: Create wakeup pipe for clean shutdown
            MemorySegment fds = arena.allocate(ValueLayout.JAVA_INT, 2);
            int _ = (int) UnixApi.pipe.invokeExact(fds);
            pipeRead = fds.get(ValueLayout.JAVA_INT, 0);
            pipeWrite = fds.get(ValueLayout.JAVA_INT, 4);

            int _ = (int) snd_seq_set_client_name.invokeExact(seqHandle, arena.allocateFrom(clientName));
            vPort = (int) snd_seq_create_simple_port.invokeExact(seqHandle, arena.allocateFrom(portName), 
                SND_SEQ_PORT_CAP_WRITE | SND_SEQ_PORT_CAP_SUBS_WRITE, SND_SEQ_PORT_TYPE_MIDI_GENERIC | SND_SEQ_PORT_TYPE_APPLICATION);
            
            connected = true;
            startWorker();
        } catch (Throwable t) {
            error(RtMidiException.Type.DRIVER_ERROR, String.valueOf(t.getMessage()));
        }
    }

    private void startWorker() {
        worker = new Thread(() -> {
            org.rtmidijava.utils.ThreadUtils.makeRealTime();
            try (Arena arena = Arena.ofShared()) {
                int alsaCount = (int) snd_seq_poll_descriptors_count.invokeExact(seqHandle, UnixApi.POLLIN);
                int totalCount = alsaCount + 1;
                MemorySegment pollFds = arena.allocate(UnixApi.pollfd, totalCount);
                
                MemorySegment pEv = arena.allocate(ValueLayout.ADDRESS);
                MemorySegment drainBuf = arena.allocate(8);
                while (connected) {
                    // Fetch from the sequencer fd into the library buffer (arg 1 = fetch).
                    // When nothing is pending, block in poll() until an event or the
                    // shutdown pipe wakes us.
                    int pending = (int) snd_seq_event_input_pending.invokeExact(seqHandle, 1);
                    if (pending == 0) {
                        int _ = (int) snd_seq_poll_descriptors.invokeExact(seqHandle, pollFds, alsaCount, UnixApi.POLLIN);

                        MemorySegment pipeFd = pollFds.asSlice(alsaCount * UnixApi.pollfd.byteSize());
                        pipeFd.set(ValueLayout.JAVA_INT, 0, pipeRead);
                        pipeFd.set(ValueLayout.JAVA_SHORT, 4, UnixApi.POLLIN);
                        pipeFd.set(ValueLayout.JAVA_SHORT, 6, (short) 0);

                        int pollResult = (int) UnixApi.poll.invokeExact(pollFds, (long) totalCount, -1);
                        if (pollResult > 0 && (pipeFd.get(ValueLayout.JAVA_SHORT, 6) & UnixApi.POLLIN) != 0) {
                            // Shutdown signalled: drain the pipe and exit the loop.
                            long _ = (long) UnixApi.read.invokeExact(pipeRead, drainBuf, 8L);
                            break;
                        }
                        continue;
                    }

                    int result = (int) snd_seq_event_input.invokeExact(seqHandle, pEv);
                    if (result <= 0) continue; // -EAGAIN / -ENOSPC (overrun) / error
                    MemorySegment ev = pEv.get(ValueLayout.ADDRESS, 0).reinterpret(snd_seq_event_t.byteSize());
                    parseAndDispatch(ev);
                }
            } catch (Throwable t) {
                System.err.println("rtmidijava: MIDI input worker stopped: " + t);
            }
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
                case SND_SEQ_EVENT_KEYPRESS -> onIncomingMessage(ts, local.allocateFrom(ValueLayout.JAVA_BYTE, new byte[]{(byte) (0xA0 | channel), note, velocity}));
                case SND_SEQ_EVENT_CONTROLLER -> {
                    int param = ctrlParam(ev);
                    int value = ctrlValue(ev);
                    onIncomingMessage(ts, local.allocateFrom(ValueLayout.JAVA_BYTE, new byte[]{(byte) (0xB0 | channel), (byte) param, (byte) value}));
                }
                case SND_SEQ_EVENT_PGMCHANGE -> onIncomingMessage(ts, local.allocateFrom(ValueLayout.JAVA_BYTE, new byte[]{(byte) (0xC0 | channel), (byte) (ctrlValue(ev) & 0x7F)}));
                case SND_SEQ_EVENT_CHANPRESS -> onIncomingMessage(ts, local.allocateFrom(ValueLayout.JAVA_BYTE, new byte[]{(byte) (0xD0 | channel), (byte) (ctrlValue(ev) & 0x7F)}));
                case SND_SEQ_EVENT_PITCHBEND -> {
                    // ALSA pitch bend value is signed (-8192..8191); MIDI wants 0..16383.
                    int bend = ctrlValue(ev) + 8192;
                    onIncomingMessage(ts, local.allocateFrom(ValueLayout.JAVA_BYTE, new byte[]{(byte) (0xE0 | channel), (byte) (bend & 0x7F), (byte) ((bend >> 7) & 0x7F)}));
                }
                case SND_SEQ_EVENT_SYSEX -> {
                    int len = ev.get(ValueLayout.JAVA_INT, snd_seq_event_t.byteOffset(MemoryLayout.PathElement.groupElement("data"), MemoryLayout.PathElement.groupElement("ext"), MemoryLayout.PathElement.groupElement("len")));
                    // ext.ptr sits at a 4-aligned offset in ALSA's packed 28-byte snd_seq_event_t;
                    // the default ADDRESS layout demands 8-alignment and throws. Use 4-aligned.
                    MemorySegment ptr = ev.get(ValueLayout.ADDRESS.withByteAlignment(4), snd_seq_event_t.byteOffset(MemoryLayout.PathElement.groupElement("data"), MemoryLayout.PathElement.groupElement("ext"), MemoryLayout.PathElement.groupElement("ptr")));
                    if (len <= 0) break;
                    MemorySegment data = ptr.reinterpret(len);

                    // Resync: a SysEx always begins with 0xF0. If a new fragment starts with 0xF0
                    // while we still hold a partial message, the previous one's tail was lost
                    // (e.g. an input overrun under heavy traffic dropped a fragment). Discard the
                    // orphaned partial so we don't splice two messages together and corrupt both.
                    if (sysexOffset != 0 && data.get(ValueLayout.JAVA_BYTE, 0) == (byte) 0xF0) {
                        sysexOffset = 0;
                    }
                    // Guard against buffer overflow from a runaway/corrupt accumulation.
                    if (sysexOffset + len > sysexNativeBuffer.byteSize()) {
                        sysexOffset = 0;
                        if (len > sysexNativeBuffer.byteSize()) break; // single fragment too large
                    }

                    MemorySegment.copy(data, 0, sysexNativeBuffer, sysexOffset, len);
                    sysexOffset += len;

                    if (data.get(ValueLayout.JAVA_BYTE, len - 1) == (byte)0xF7) {
                        onIncomingMessage(ts, sysexNativeBuffer.asSlice(0, sysexOffset));
                        sysexOffset = 0;
                    }
                }
            }
        } catch (Throwable t) {
            System.err.println("rtmidijava: MIDI dispatch failed: " + t);
        }
    }

    private static int ctrlParam(MemorySegment ev) {
        return ev.get(ValueLayout.JAVA_INT, snd_seq_event_t.byteOffset(MemoryLayout.PathElement.groupElement("data"), MemoryLayout.PathElement.groupElement("control"), MemoryLayout.PathElement.groupElement("param")));
    }

    private static int ctrlValue(MemorySegment ev) {
        return ev.get(ValueLayout.JAVA_INT, snd_seq_event_t.byteOffset(MemoryLayout.PathElement.groupElement("data"), MemoryLayout.PathElement.groupElement("control"), MemoryLayout.PathElement.groupElement("value")));
    }

    @Override
    public synchronized void closePort() {
        if (!connected) return;
        connected = false;

        // Wake the worker out of poll() via the shutdown pipe.
        if (pipeWrite != -1) {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment b = arena.allocate(1);
                b.set(ValueLayout.JAVA_BYTE, 0, (byte) 1);
                long _ = (long) UnixApi.write.invokeExact(pipeWrite, b, 1L);
            } catch (Throwable t) {}
        }

        // Join the worker BEFORE freeing native resources: otherwise it may
        // still be inside snd_seq_event_input()/poll() on a freed handle/fd,
        // which crashes the JVM (use-after-free).
        if (worker != null) {
            try {
                worker.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            worker = null;
        }

        if (!seqHandle.equals(MemorySegment.NULL)) {
            try {
                int _ = (int) snd_seq_close.invokeExact(seqHandle);
            } catch (Throwable t) {}
            seqHandle = MemorySegment.NULL;
        }

        if (pipeRead != -1) {
            try {
                int _ = (int) UnixApi.close.invokeExact(pipeRead);
                int _ = (int) UnixApi.close.invokeExact(pipeWrite);
            } catch (Throwable t) {}
            pipeRead = -1;
            pipeWrite = -1;
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
