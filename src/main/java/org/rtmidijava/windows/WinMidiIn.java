package org.rtmidijava.windows;

import org.rtmidijava.RtMidiIn;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import static org.rtmidijava.windows.WinMidiApi.*;

public class WinMidiIn extends RtMidiIn {
    private MemorySegment hMidiIn = MemorySegment.NULL;
    private MemorySegment upcallStub;
    private long startTime;
    private Arena instanceArena;
    private static final int RT_SYSEX_BUFFER_SIZE = 1024;
    private static final int RT_SYSEX_BUFFER_COUNT = 4;
    private MemorySegment[] sysexBuffers = new MemorySegment[RT_SYSEX_BUFFER_COUNT];

    public WinMidiIn() {
        try {
            MethodHandle onMidiInProc = MethodHandles.lookup().findVirtual(WinMidiIn.class, "midiInProc",
                    MethodType.methodType(void.class, MemorySegment.class, int.class, long.class, long.class, long.class));
            onMidiInProc = onMidiInProc.bindTo(this);
            upcallStub = LINKER.upcallStub(onMidiInProc,
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG),
                    Arena.global());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private synchronized void midiInProc(MemorySegment hMidiIn, int wMsg, long dwInstance, long dwParam1, long dwParam2) {
        if (!connected) return;
        double timestamp = (dwParam2 - startTime) / 1000.0;
        if (wMsg == MIM_DATA) {
            byte status = (byte) (dwParam1 & 0xFF);
            byte data1 = (byte) ((dwParam1 >> 8) & 0xFF);
            byte data2 = (byte) ((dwParam1 >> 16) & 0xFF);
            byte[] msg;
            if ((status & 0xFF) < 0xC0 || (status & 0xFF) >= 0xE0) {
                msg = new byte[]{status, data1, data2};
            } else {
                msg = new byte[]{status, data1};
            }
            onIncomingMessage(timestamp, msg);
        } else if (wMsg == MIM_LONGDATA) {
            MemorySegment header = MemorySegment.ofAddress(dwParam1).reinterpret(MIDIHDR.byteSize());
            int bytesRecorded = header.get(ValueLayout.JAVA_INT, MIDIHDR.byteOffset(MemoryLayout.PathElement.groupElement("dwBytesRecorded")));
            if (bytesRecorded > 0) {
                MemorySegment dataPtr = header.get(ValueLayout.ADDRESS, MIDIHDR.byteOffset(MemoryLayout.PathElement.groupElement("lpData")));
                byte[] data = dataPtr.reinterpret(bytesRecorded).toArray(ValueLayout.JAVA_BYTE);
                onIncomingMessage(timestamp, data);
            }
            // Re-queue the buffer
            try {
                midiInAddBuffer.invokeExact(hMidiIn, header, (int) MIDIHDR.byteSize());
            } catch (Throwable t) {}
        }
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
            int result = (int) midiInOpen.invokeExact(phmi, portNumber, upcallStub, 0L, CALLBACK_FUNCTION);
            if (result == 0) {
                hMidiIn = phmi.get(ValueLayout.ADDRESS, 0).reinterpret(Long.MAX_VALUE);
                
                // Allocate and add Sysex buffers
                for (int i = 0; i < RT_SYSEX_BUFFER_COUNT; i++) {
                    sysexBuffers[i] = instanceArena.allocate(MIDIHDR);
                    sysexBuffers[i].fill((byte) 0);
                    MemorySegment data = instanceArena.allocate(RT_SYSEX_BUFFER_SIZE);
                    sysexBuffers[i].set(ValueLayout.ADDRESS, MIDIHDR.byteOffset(MemoryLayout.PathElement.groupElement("lpData")), data);
                    sysexBuffers[i].set(ValueLayout.JAVA_INT, MIDIHDR.byteOffset(MemoryLayout.PathElement.groupElement("dwBufferLength")), RT_SYSEX_BUFFER_SIZE);
                    
                    midiInPrepareHeader.invokeExact(hMidiIn, sysexBuffers[i], (int) MIDIHDR.byteSize());
                    midiInAddBuffer.invokeExact(hMidiIn, sysexBuffers[i], (int) MIDIHDR.byteSize());
                }

                startTime = System.currentTimeMillis();
                midiInStart.invokeExact(hMidiIn);
                connected = true;
            } else {
                instanceArena.close();
                throw new RuntimeException("Could not open MIDI in port: " + result);
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
        if (connected && !hMidiIn.equals(MemorySegment.NULL)) {
            try {
                midiInStop.invokeExact(hMidiIn);
                for (int i = 0; i < RT_SYSEX_BUFFER_COUNT; i++) {
                    midiInUnprepareHeader.invokeExact(hMidiIn, sysexBuffers[i], (int) MIDIHDR.byteSize());
                }
                midiInClose.invokeExact(hMidiIn);
            } catch (Throwable t) {
            }
            hMidiIn = MemorySegment.NULL;
            connected = false;
        }
        if (instanceArena != null) {
            instanceArena.close();
            instanceArena = null;
        }
    }
}
