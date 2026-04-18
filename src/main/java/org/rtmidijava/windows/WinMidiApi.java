package org.rtmidijava.windows;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

public class WinMidiApi {
    public static final Linker LINKER = Linker.nativeLinker();
    public static final SymbolLookup WINMM = SymbolLookup.libraryLookup("winmm.dll", Arena.global());

    // Messages
    public static final int MIM_DATA = 0x3C1;
    public static final int MIM_LONGDATA = 0x3C2;
    public static final int MIM_OPEN = 0x3C1;
    public static final int MIM_CLOSE = 0x3C2;
    public static final int MOM_OPEN = 0x3C7;
    public static final int MOM_CLOSE = 0x3C8;
    public static final int MOM_DONE = 0x3C9;

    public static final int CALLBACK_FUNCTION = 0x00030000;

    // Struct Layouts
    public static final StructLayout MIDIOUTCAPS = MemoryLayout.structLayout(
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

    public static final StructLayout MIDIINCAPS = MemoryLayout.structLayout(
            ValueLayout.JAVA_SHORT.withName("wMid"),
            ValueLayout.JAVA_SHORT.withName("wPid"),
            ValueLayout.JAVA_INT.withName("vDriverVersion"),
            MemoryLayout.sequenceLayout(32, ValueLayout.JAVA_CHAR).withName("szPname"),
            ValueLayout.JAVA_INT.withName("dwSupport")
    );

    // MIDIHDR: lpData(8), dwBufferLength(4), dwBytesRecorded(4), dwUser(8), dwFlags(4), lpNext(8), reserved(8)
    // On 64-bit Windows, pointer alignment is 8.
    public static final StructLayout MIDIHDR = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("lpData"),
            ValueLayout.JAVA_INT.withName("dwBufferLength"),
            ValueLayout.JAVA_INT.withName("dwBytesRecorded"),
            ValueLayout.JAVA_LONG.withName("dwUser"),
            ValueLayout.JAVA_INT.withName("dwFlags"),
            MemoryLayout.paddingLayout(4),
            ValueLayout.ADDRESS.withName("lpNext"),
            ValueLayout.JAVA_LONG.withName("reserved")
    );

    // Method Handles
    public static final MethodHandle midiOutGetNumDevs = downcall("midiOutGetNumDevs", FunctionDescriptor.of(ValueLayout.JAVA_INT));
    public static final MethodHandle midiOutGetDevCaps = downcall("midiOutGetDevCapsW", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
    public static final MethodHandle midiOutOpen = downcall("midiOutOpen", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));
    public static final MethodHandle midiOutClose = downcall("midiOutClose", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));
    public static final MethodHandle midiOutShortMsg = downcall("midiOutShortMsg", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));
    public static final MethodHandle midiOutLongMsg = downcall("midiOutLongMsg", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
    public static final MethodHandle midiOutPrepareHeader = downcall("midiOutPrepareHeader", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
    public static final MethodHandle midiOutUnprepareHeader = downcall("midiOutUnprepareHeader", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

    public static final MethodHandle midiInGetNumDevs = downcall("midiInGetNumDevs", FunctionDescriptor.of(ValueLayout.JAVA_INT));
    public static final MethodHandle midiInGetDevCaps = downcall("midiInGetDevCapsW", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
    public static final MethodHandle midiInOpen = downcall("midiInOpen", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));
    public static final MethodHandle midiInClose = downcall("midiInClose", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));
    public static final MethodHandle midiInStart = downcall("midiInStart", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));
    public static final MethodHandle midiInStop = downcall("midiInStop", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));
    public static final MethodHandle midiInAddBuffer = downcall("midiInAddBuffer", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
    public static final MethodHandle midiInPrepareHeader = downcall("midiInPrepareHeader", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
    public static final MethodHandle midiInUnprepareHeader = downcall("midiInUnprepareHeader", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

    public static final MethodHandle midiOutGetErrorText = downcall("midiOutGetErrorTextW", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
    public static final MethodHandle midiInGetErrorText = downcall("midiInGetErrorTextW", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

    private static MethodHandle downcall(String name, FunctionDescriptor desc) {
        return LINKER.downcallHandle(WINMM.find(name).get(), desc);
    }
}
