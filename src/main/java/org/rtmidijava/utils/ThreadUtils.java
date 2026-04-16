package org.rtmidijava.utils;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

public class ThreadUtils {
    private static final Linker LINKER = Linker.nativeLinker();

    // Windows
    private static final MethodHandle getCurrentThread;
    private static final MethodHandle setThreadPriority;
    private static final int THREAD_PRIORITY_TIME_CRITICAL = 15;

    // Unix (Linux/Mac)
    private static final MethodHandle pthread_self;
    private static final MethodHandle pthread_setschedparam;
    private static final int SCHED_RR = 2;

    // Mac specific
    private static final MethodHandle thread_self;
    private static final MethodHandle thread_policy_set;
    private static final int THREAD_TIME_CONSTRAINT_POLICY = 2;

    // mach_timebase_info and thread_time_constraint_policy_data_t
    public static final StructLayout thread_time_constraint_policy_data_t = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("period"),
        ValueLayout.JAVA_INT.withName("computation"),
        ValueLayout.JAVA_INT.withName("constraint"),
        ValueLayout.JAVA_BYTE.withName("preemptible")
    );

    static {
        String os = System.getProperty("os.name").toLowerCase();
        MethodHandle gct = null, stp = null, ps = null, pss = null, ts = null, tps = null;

        try {
            if (os.contains("win")) {
                SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32.dll", Arena.global());
                gct = LINKER.downcallHandle(kernel32.find("GetCurrentThread").get(), FunctionDescriptor.of(ValueLayout.ADDRESS));
                stp = LINKER.downcallHandle(kernel32.find("SetThreadPriority").get(), FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            } else {
                SymbolLookup libc = LINKER.defaultLookup();
                ps = LINKER.downcallHandle(libc.find("pthread_self").get(), FunctionDescriptor.of(ValueLayout.JAVA_LONG));
                pss = LINKER.downcallHandle(libc.find("pthread_setschedparam").get(), 
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
                
                if (os.contains("mac")) {
                    ts = LINKER.downcallHandle(libc.find("mach_thread_self").get(), FunctionDescriptor.of(ValueLayout.JAVA_INT));
                    tps = LINKER.downcallHandle(libc.find("thread_policy_set").get(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
                }
            }
        } catch (Throwable t) {}

        getCurrentThread = gct;
        setThreadPriority = stp;
        pthread_self = ps;
        pthread_setschedparam = pss;
        thread_self = ts;
        thread_policy_set = tps;
    }

    public static void makeRealTime() {
        try {
            if (setThreadPriority != null) { // Windows
                MemorySegment handle = (MemorySegment) getCurrentThread.invokeExact();
                setThreadPriority.invokeExact(handle, THREAD_PRIORITY_TIME_CRITICAL);
            } else if (thread_policy_set != null) { // Mac
                int thread = (int) thread_self.invokeExact();
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment policy = arena.allocate(thread_time_constraint_policy_data_t);
                    // Values matching RtMidi/JUCE recommendations for MIDI
                    policy.set(ValueLayout.JAVA_INT, 0, 10000000); // period
                    policy.set(ValueLayout.JAVA_INT, 4, 1000000);  // computation
                    policy.set(ValueLayout.JAVA_INT, 8, 1000000);  // constraint
                    policy.set(ValueLayout.JAVA_BYTE, 12, (byte) 1); // preemptible
                    thread_policy_set.invokeExact(thread, THREAD_TIME_CONSTRAINT_POLICY, policy, 4); // 4 = count of ints
                }
            } else if (pthread_setschedparam != null) { // Linux
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment param = arena.allocate(ValueLayout.JAVA_INT);
                    param.set(ValueLayout.JAVA_INT, 0, 99);
                    pthread_setschedparam.invokeExact((long)pthread_self.invokeExact(), SCHED_RR, param);
                }
            }
        } catch (Throwable t) {}
    }
}
