package org.rtmidijava.linux;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

public class UnixApi {
    public static final Linker LINKER = Linker.nativeLinker();
    public static final SymbolLookup LIBC = LINKER.defaultLookup();

    public static final MethodHandle pthread_self = downcall("pthread_self", FunctionDescriptor.of(ValueLayout.JAVA_LONG));
    
    // int pthread_setschedparam(pthread_t thread, int policy, const struct sched_param *param);
    // struct sched_param { int sched_priority; }
    public static final StructLayout sched_param = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("sched_priority")
    );

    public static final MethodHandle pthread_setschedparam = downcall("pthread_setschedparam", 
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

    public static final MethodHandle strerror = downcall("strerror",
        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

    public static final MethodHandle pipe = downcall("pipe",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

    public static final MethodHandle read = downcall("read",
        FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));

    public static final MethodHandle write = downcall("write",
        FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));

    public static final MethodHandle close = downcall("close",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

    public static final MethodHandle poll = downcall("poll",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));

    // struct pollfd { int fd; short events; short revents; }
    public static final StructLayout pollfd = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("fd"),
        ValueLayout.JAVA_SHORT.withName("events"),
        ValueLayout.JAVA_SHORT.withName("revents")
    );

    public static final short POLLIN = 0x0001;

    public static final int SCHED_OTHER = 0;
    public static final int SCHED_FIFO = 1;
    public static final int SCHED_RR = 2;

    private static MethodHandle downcall(String name, FunctionDescriptor desc) {
        return LIBC.find(name).map(s -> LINKER.downcallHandle(s, desc)).orElse(null);
    }

    public static void setThreadPriority(int priority) {
        if (pthread_setschedparam == null) return;
        try (Arena arena = Arena.ofConfined()) {
            long self = (long) pthread_self.invokeExact();
            MemorySegment param = arena.allocate(sched_param);
            param.set(ValueLayout.JAVA_INT, 0, priority);
            // We try SCHED_RR for real-time
            pthread_setschedparam.invokeExact(self, SCHED_RR, param);
        } catch (Throwable t) {
            // Might fail if not root or lacking CAP_SYS_NICE
        }
    }
}
