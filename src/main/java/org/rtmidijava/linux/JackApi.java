package org.rtmidijava.linux;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

public class JackApi {
    public static final Linker LINKER = Linker.nativeLinker();
    public static final SymbolLookup JACK = SymbolLookup.libraryLookup("libjack.so.0", Arena.global());

    public static final int JackNullOption = 0x00;
    public static final int JackNoStartServer = 0x01;

    public static final int JackPortIsInput = 0x1;
    public static final int JackPortIsOutput = 0x2;
    public static final int JackPortIsPhysical = 0x4;
    
    public static final String JACK_DEFAULT_MIDI_TYPE = "32 bit float mono audio"; // Wait, MIDI type is "8 bit raw midi" or similar
    // Actually JACK MIDI type is usually "8-bit raw midi"
    public static final String JACK_MIDI_TYPE = "8-bit raw midi";

    public static final MethodHandle jack_client_open = downcall("jack_client_open",
        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

    public static final MethodHandle jack_client_close = downcall("jack_client_close",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

    public static final MethodHandle jack_activate = downcall("jack_activate",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

    public static final MethodHandle jack_port_register = downcall("jack_port_register",
        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));

    public static final MethodHandle jack_set_process_callback = downcall("jack_set_process_callback",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    public static final MethodHandle jack_port_get_buffer = downcall("jack_port_get_buffer",
        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

    public static final MethodHandle jack_midi_clear_buffer = downcall("jack_midi_clear_buffer",
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

    public static final MethodHandle jack_midi_event_get = downcall("jack_midi_event_get",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

    public static final MethodHandle jack_midi_get_event_count = downcall("jack_midi_get_event_count",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

    public static final MethodHandle jack_midi_event_write = downcall("jack_midi_event_write",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));

    public static final MethodHandle jack_get_client_name = downcall("jack_get_client_name",
        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    public static final MethodHandle jack_get_ports = downcall("jack_get_ports",
        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));

    public static final MethodHandle jack_port_name = downcall("jack_port_name",
        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    public static final MethodHandle jack_connect = downcall("jack_connect",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    public static final MethodHandle jack_free = downcall("jack_free",
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

    public static final MethodHandle jack_ringbuffer_create = downcall("jack_ringbuffer_create",
        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));

    public static final MethodHandle jack_ringbuffer_free = downcall("jack_ringbuffer_free",
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

    public static final MethodHandle jack_ringbuffer_write = downcall("jack_ringbuffer_write",
        FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));

    public static final MethodHandle jack_ringbuffer_read = downcall("jack_ringbuffer_read",
        FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));

    public static final MethodHandle jack_ringbuffer_read_space = downcall("jack_ringbuffer_read_space",
        FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));

    // jack_midi_event_t: time(4), size(8), buffer(8)
    // Depends on arch, but usually:
    public static final StructLayout jack_midi_event_t = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("time"),
        MemoryLayout.paddingLayout(4),
        ValueLayout.JAVA_LONG.withName("size"),
        ValueLayout.ADDRESS.withName("buffer")
    );

    private static MethodHandle downcall(String name, FunctionDescriptor desc) {
        return JACK.find(name).map(s -> LINKER.downcallHandle(s, desc)).orElse(null);
    }

    private static MemorySegment controlClient = MemorySegment.NULL;

    public static synchronized MemorySegment getControlClient() {
        if (controlClient.equals(MemorySegment.NULL)) {
            try (Arena arena = Arena.ofConfined()) {
                controlClient = (MemorySegment) jack_client_open.invokeExact(arena.allocateFrom("RtMidiJavaControl"), JackNoStartServer, MemorySegment.NULL);
            } catch (Throwable t) {}
        }
        return controlClient;
    }
}
