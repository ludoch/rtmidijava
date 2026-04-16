package org.rtmidijava.windows;

import org.rtmidijava.RtMidiOut;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

public class WinMidiOut extends RtMidiOut {
    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup WINMM = SymbolLookup.libraryLookup("winmm.dll", Arena.global());

    private static final MethodHandle midiOutGetNumDevs = LINKER.downcallHandle(
            WINMM.find("midiOutGetNumDevs").get(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT)
    );

    private static final StructLayout MIDIOUTCAPS = MemoryLayout.structLayout(
            ValueLayout.JAVA_SHORT.withName("wMid"),
            ValueLayout.JAVA_SHORT.withName("wPid"),
            ValueLayout.JAVA_INT.withName("vDriverVersion"),
            MemoryLayout.sequenceLayout(32, ValueLayout.JAVA_CHAR).withName("szPname"),
            ValueLayout.JAVA_SHORT.withName("wTechnology"),
            ValueLayout.JAVA_SHORT.withName("wVoices"),
            ValueLayout.JAVA_SHORT.withName("wNotes"),
            ValueLayout.JAVA_SHORT.withName("wChannelMask"),
            ValueLayout.JAVA_INT.withName("dwSupport")
    );

    private static final MethodHandle midiOutGetDevCaps = LINKER.downcallHandle(
            WINMM.find("midiOutGetDevCapsW").get(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
    );

    private static final MethodHandle midiOutOpen = LINKER.downcallHandle(
            WINMM.find("midiOutOpen").get(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT)
    );

    private static final MethodHandle midiOutClose = LINKER.downcallHandle(
            WINMM.find("midiOutClose").get(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)
    );

    private static final MethodHandle midiOutShortMsg = LINKER.downcallHandle(
            WINMM.find("midiOutShortMsg").get(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
    );

    private MemorySegment hMidiOut = MemorySegment.NULL;

    @Override
    public Api getCurrentApi() {
        return Api.WINDOWS_MM;
    }

    @Override
    public int getPortCount() {
        try {
            return (int) midiOutGetNumDevs.invokeExact();
        } catch (Throwable t) {
            return 0;
        }
    }

    @Override
    public String getPortName(int portNumber) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment caps = arena.allocate(MIDIOUTCAPS);
            int result = (int) midiOutGetDevCaps.invokeExact((long) portNumber, caps, (int) MIDIOUTCAPS.byteSize());
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
        return "Windows MIDI Out Port " + portNumber;
    }

    @Override
    public void openPort(int portNumber, String portName) {
        if (connected) closePort();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment phmo = arena.allocate(ValueLayout.ADDRESS);
            int result = (int) midiOutOpen.invokeExact(phmo, portNumber, 0L, 0L, 0);
            if (result == 0) {
                hMidiOut = phmo.get(ValueLayout.ADDRESS, 0);
                connected = true;
            } else {
                throw new RuntimeException("Could not open MIDI out port: " + result);
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
        if (connected && !hMidiOut.equals(MemorySegment.NULL)) {
            try {
                midiOutClose.invokeExact(hMidiOut);
            } catch (Throwable t) {
            }
            hMidiOut = MemorySegment.NULL;
            connected = false;
        }
    }

    @Override
    public void sendMessage(byte[] message) {
        if (!connected || hMidiOut.equals(MemorySegment.NULL)) return;
        if (message.length > 3) {
            // Sysex not implemented in this snippet
            return;
        }
        int msg = 0;
        for (int i = 0; i < message.length; i++) {
            msg |= (message[i] & 0xFF) << (i * 8);
        }
        try {
            midiOutShortMsg.invokeExact(hMidiOut, msg);
        } catch (Throwable t) {
        }
    }
}
