package org.rtmidijava.windows;

import org.rtmidijava.RtMidiIn;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

public class WinMidiIn extends RtMidiIn {
    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup WINMM = SymbolLookup.libraryLookup("winmm.dll", Arena.global());

    // Method Handles
    private static final MethodHandle midiInGetNumDevs = LINKER.downcallHandle(
            WINMM.find("midiInGetNumDevs").get(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT)
    );

    private static final StructLayout MIDIINCAPS = MemoryLayout.structLayout(
            ValueLayout.JAVA_SHORT.withName("wMid"),
            ValueLayout.JAVA_SHORT.withName("wPid"),
            ValueLayout.JAVA_INT.withName("vDriverVersion"),
            MemoryLayout.sequenceLayout(32, ValueLayout.JAVA_CHAR).withName("szPname"), // 32 WCHARs
            ValueLayout.JAVA_INT.withName("dwSupport")
    );

    private static final MethodHandle midiInGetDevCaps = LINKER.downcallHandle(
            WINMM.find("midiInGetDevCapsW").get(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
    );

    private static final MethodHandle midiInOpen = LINKER.downcallHandle(
            WINMM.find("midiInOpen").get(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT)
    );

    private static final MethodHandle midiInClose = LINKER.downcallHandle(
            WINMM.find("midiInClose").get(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)
    );

    private static final MethodHandle midiInStart = LINKER.downcallHandle(
            WINMM.find("midiInStart").get(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)
    );

    private static final MethodHandle midiInStop = LINKER.downcallHandle(
            WINMM.find("midiInStop").get(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)
    );

    private MemorySegment hMidiIn = MemorySegment.NULL;
    private MemorySegment upcallStub;
    private long startTime;

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

    private void midiInProc(MemorySegment hMidiIn, int wMsg, long dwInstance, long dwParam1, long dwParam2) {
        if (wMsg == 0x3C1) { // MIM_DATA
            byte status = (byte) (dwParam1 & 0xFF);
            byte data1 = (byte) ((dwParam1 >> 8) & 0xFF);
            byte data2 = (byte) ((dwParam1 >> 16) & 0xFF);
            byte[] msg = new byte[]{status, data1, data2};
            
            double timestamp = (dwParam2 - startTime) / 1000.0;
            onIncomingMessage(timestamp, msg);
        }
        // MIM_LONGDATA for sysex would be handled here
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
                MemorySegment nameSegment = caps.asSlice(8, 64);
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
    public void openPort(int portNumber, String portName) {
        if (connected) closePort();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment phmi = arena.allocate(ValueLayout.ADDRESS);
            // CALLBACK_FUNCTION = 0x30000
            int result = (int) midiInOpen.invokeExact(phmi, portNumber, upcallStub, 0L, 0x30000);
            if (result == 0) {
                hMidiIn = phmi.get(ValueLayout.ADDRESS, 0);
                startTime = System.currentTimeMillis(); // Simplified, Windows uses its own clock
                midiInStart.invokeExact(hMidiIn);
                connected = true;
            } else {
                throw new RuntimeException("Could not open MIDI in port: " + result);
            }
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    @Override
    public void openVirtualPort(String portName) {
        throw new UnsupportedOperationException("Virtual ports not supported on Windows WinMM");
    }

    @Override
    public void closePort() {
        if (connected && !hMidiIn.equals(MemorySegment.NULL)) {
            try {
                midiInStop.invokeExact(hMidiIn);
                midiInClose.invokeExact(hMidiIn);
            } catch (Throwable t) {
            }
            hMidiIn = MemorySegment.NULL;
            connected = false;
        }
    }
}
