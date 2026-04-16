package org.rtmidijava.macos;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

public class CoreMidiUtils {
    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup CORE_FOUNDATION = SymbolLookup.libraryLookup("/System/Library/Frameworks/CoreFoundation.framework/Versions/Current/CoreFoundation", Arena.global());

    private static final MethodHandle cfStringCreateWithCharacters = LINKER.downcallHandle(
            CORE_FOUNDATION.find("CFStringCreateWithCharacters").get(),
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)
    );

    private static final MethodHandle cfStringGetLength = LINKER.downcallHandle(
            CORE_FOUNDATION.find("CFStringGetLength").get(),
            FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS)
    );

    private static final MethodHandle cfStringGetCharacters = LINKER.downcallHandle(
            CORE_FOUNDATION.find("CFStringGetCharacters").get(),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, MemoryLayout.structLayout(ValueLayout.JAVA_LONG.withName("location"), ValueLayout.JAVA_LONG.withName("length")), ValueLayout.ADDRESS)
    );

    private static final MethodHandle cfRelease = LINKER.downcallHandle(
            CORE_FOUNDATION.find("CFRelease").get(),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
    );

    private static final SymbolLookup CORE_MIDI = SymbolLookup.libraryLookup("/System/Library/Frameworks/CoreMIDI.framework/Versions/Current/CoreMIDI", Arena.global());
    public static final MemorySegment kMIDIPropertyName;
    
    private static final MethodHandle midiObjectSetProperty = LINKER.downcallHandle(
            CORE_MIDI.find("MIDIObjectSetStringProperty").get(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
    );

    private static final StructLayout mach_timebase_info = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("numer"),
        ValueLayout.JAVA_INT.withName("denom")
    );
    private static final MethodHandle mach_timebase_info_func = LINKER.downcallHandle(
        LINKER.defaultLookup().find("mach_timebase_info").get(),
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)
    );
    private static final double timeFactor;

    static {
        MemorySegment name = MemorySegment.NULL;
        try {
            name = CORE_MIDI.find("kMIDIPropertyName").get().reinterpret(8).get(ValueLayout.ADDRESS, 0);
        } catch (Exception e) {}
        kMIDIPropertyName = name;

        double factor = 1.0;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment info = arena.allocate(mach_timebase_info);
            mach_timebase_info_func.invokeExact(info);
            int numer = info.get(ValueLayout.JAVA_INT, 0);
            int denom = info.get(ValueLayout.JAVA_INT, 4);
            factor = (double) numer / denom / 1_000_000_000.0;
        } catch (Throwable t) {}
        timeFactor = factor;
    }

    public static double convertTimestamp(long machTime) {
        return machTime * timeFactor;
    }

    public static void setPropertyName(int obj, String name) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cfStr = createCFString(name, arena);
            midiObjectSetProperty.invokeExact(obj, kMIDIPropertyName, cfStr);
            release(cfStr);
        } catch (Throwable t) {}
    }

    public static MemorySegment createCFString(String s, Arena arena) {
        try {
            char[] chars = s.toCharArray();
            MemorySegment mem = arena.allocateFrom(ValueLayout.JAVA_CHAR, chars);
            return (MemorySegment) cfStringCreateWithCharacters.invokeExact(MemorySegment.NULL, mem, (long) chars.length);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static String cfStringToString(MemorySegment cfString) {
        if (cfString.equals(MemorySegment.NULL)) return null;
        try (Arena arena = Arena.ofConfined()) {
            long length = (long) cfStringGetLength.invokeExact(cfString);
            if (length == 0) return "";
            
            MemorySegment buffer = arena.allocate(ValueLayout.JAVA_CHAR, length);
            MemorySegment range = arena.allocate(MemoryLayout.structLayout(ValueLayout.JAVA_LONG.withName("location"), ValueLayout.JAVA_LONG.withName("length")));
            range.set(ValueLayout.JAVA_LONG, 0, 0L);
            range.set(ValueLayout.JAVA_LONG, 8, length);
            
            cfStringGetCharacters.invokeExact(cfString, range, buffer);
            
            char[] chars = new char[(int) length];
            for (int i = 0; i < length; i++) {
                chars[i] = buffer.get(ValueLayout.JAVA_CHAR, i * 2);
            }
            return new String(chars);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static void release(MemorySegment cfObject) {
        if (cfObject.equals(MemorySegment.NULL)) return;
        try {
            cfRelease.invokeExact(cfObject);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }
}
