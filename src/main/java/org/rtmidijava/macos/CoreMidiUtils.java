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
