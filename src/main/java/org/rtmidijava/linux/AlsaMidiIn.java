package org.rtmidijava.linux;

import org.rtmidijava.RtMidiIn;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

public class AlsaMidiIn extends RtMidiIn {
    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup ALSA = SymbolLookup.libraryLookup("libasound.so.2", Arena.global());

    private static final MethodHandle snd_seq_open = LINKER.downcallHandle(
            ALSA.find("snd_seq_open").get(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
    );

    private static final MethodHandle snd_seq_event_input = LINKER.downcallHandle(
            ALSA.find("snd_seq_event_input").get(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
    );

    private MemorySegment seqHandle = MemorySegment.NULL;
    private Thread worker;
    private Callback javaCallback;

    @Override
    public Api getCurrentApi() {
        return Api.LINUX_ALSA;
    }

    @Override
    public int getPortCount() {
        return 0;
    }

    @Override
    public String getPortName(int portNumber) {
        return "ALSA Source " + portNumber;
    }

    @Override
    public void openPort(int portNumber, String portName) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pHandle = arena.allocate(ValueLayout.ADDRESS);
            snd_seq_open.invokeExact(pHandle, arena.allocateFrom("default"), 1, 0); // 1 = SND_SEQ_OPEN_INPUT
            seqHandle = pHandle.get(ValueLayout.ADDRESS, 0);
            
            connected = true;
            startWorker();
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private void startWorker() {
        worker = new Thread(() -> {
            try (Arena arena = Arena.ofShared()) {
                MemorySegment pEv = arena.allocate(ValueLayout.ADDRESS);
                while (connected) {
                    int result = (int) snd_seq_event_input.invokeExact(seqHandle, pEv);
                    if (result >= 0) {
                        MemorySegment ev = pEv.get(ValueLayout.ADDRESS, 0);
                        // Parse ALSA event to MIDI bytes
                        if (javaCallback != null) {
                            javaCallback.onMessage(System.nanoTime() / 1000000000.0, new byte[]{0}); 
                        }
                    }
                }
            } catch (Throwable t) {}
        });
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public void openVirtualPort(String portName) {
        openPort(0, portName);
    }

    @Override
    public void closePort() {
        connected = false;
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
