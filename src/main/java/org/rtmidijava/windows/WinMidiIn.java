package org.rtmidijava.windows;

import org.rtmidijava.RtMidiIn;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import static org.rtmidijava.windows.WinMidiApi.*;

public class WinMidiIn extends RtMidiIn {
    private MemorySegment hMidiIn = MemorySegment.NULL;
    private long startTime;
    private Arena instanceArena;
    private Thread worker;
    private static final int RT_SYSEX_BUFFER_SIZE = 1024;
    private static final int RT_SYSEX_BUFFER_COUNT = 4;
    private MemorySegment[] sysexBuffers = new MemorySegment[RT_SYSEX_BUFFER_COUNT];

    public WinMidiIn() {
    }

    @Override
    public Api getCurrentApi() {
        return Api.WINDOWS_MM;
    }

    @Override
    public int getPortCount() {
        try {
            return (int) midiInGetNumDevs.invokeExact();
        } catch (Throwable t) {
            return 0;
        }
    }

    @Override
    public String getPortName(int portNumber) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment caps = arena.allocate(MIDIINCAPS);
            int result = (int) midiInGetDevCaps.invokeExact((long) portNumber, caps, (int) MIDIINCAPS.byteSize());
            if (result == 0) {
                MemorySegment nameSegment = caps.asSlice(MIDIINCAPS.byteOffset(MemoryLayout.PathElement.groupElement("szPname")), 64);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 32; i++) {
                    char c = nameSegment.get(ValueLayout.JAVA_CHAR, i * 2L);
                    if (c == 0) break;
                    sb.append(c);
                }
                return sb.toString();
            }
        } catch (Throwable t) {
        }
        return "Windows MIDI In Port " + portNumber;
    }

    @Override
    public synchronized void openPort(int portNumber, String portName) {
        if (connected) closePort();
        instanceArena = Arena.ofShared();
        try {
            MemorySegment phmi = instanceArena.allocate(ValueLayout.ADDRESS);
            // Use CALLBACK_NULL and poll for now, or just provide a dummy stable state
            int result = (int) midiInOpen.invokeExact(phmi, portNumber, MemorySegment.NULL, 0L, 0);
            if (result == 0) {
                hMidiIn = phmi.get(ValueLayout.ADDRESS, 0);
                startTime = System.currentTimeMillis();
                int resStart = (int) midiInStart.invokeExact(hMidiIn);
                connected = true;
                // No worker actually polls WinMM yet, it's complex without CALLBACK_EVENT
            } else {
                MemorySegment errBuf = instanceArena.allocate(256 * 2);
                int resErr = (int) midiInGetErrorText.invokeExact(result, errBuf, 256);
                String errMsg = errBuf.getString(0, java.nio.charset.StandardCharsets.UTF_16LE);
                instanceArena.close();
                throw new RuntimeException("Could not open MIDI in port: " + errMsg + " (" + result + ")");
            }
        } catch (Throwable t) {
            if (instanceArena != null) instanceArena.close();
            throw new RuntimeException(t);
        }
    }

    @Override
    public void openVirtualPort(String portName) {
        throw new UnsupportedOperationException("Virtual ports not supported on Windows WinMM");
    }

    @Override
    public synchronized void closePort() {
        connected = false;
        if (!hMidiIn.equals(MemorySegment.NULL)) {
            try {
                midiInStop.invokeExact(hMidiIn);
                midiInClose.invokeExact(hMidiIn);
            } catch (Throwable t) {
            }
            hMidiIn = MemorySegment.NULL;
        }
        if (instanceArena != null) {
            instanceArena.close();
            instanceArena = null;
        }
    }
}
