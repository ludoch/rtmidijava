package org.rtmidijava.linux;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

public class AlsaApi {
    public static final Linker LINKER = Linker.nativeLinker();
    public static final SymbolLookup ALSA = SymbolLookup.libraryLookup("libasound.so.2", Arena.global());

    // Constants
    public static final int SND_SEQ_OPEN_OUTPUT = 1;
    public static final int SND_SEQ_OPEN_INPUT = 2;
    public static final int SND_SEQ_OPEN_DUPLEX = 3;
    public static final int SND_SEQ_NONBLOCK = 0x0001;

    public static final int SND_SEQ_PORT_CAP_READ = (1<<0);
    public static final int SND_SEQ_PORT_CAP_WRITE = (1<<1);
    public static final int SND_SEQ_PORT_CAP_SYNC_READ = (1<<2);
    public static final int SND_SEQ_PORT_CAP_SYNC_WRITE = (1<<3);
    public static final int SND_SEQ_PORT_CAP_DUPLEX = (1<<4);
    public static final int SND_SEQ_PORT_CAP_SUBS_READ = (1<<5);
    public static final int SND_SEQ_PORT_CAP_SUBS_WRITE = (1<<6);
    public static final int SND_SEQ_PORT_CAP_NO_EXPORT = (1<<7);

    public static final int SND_SEQ_PORT_TYPE_SPECIFIC = (1<<0);
    public static final int SND_SEQ_PORT_TYPE_MIDI_GENERIC = (1<<1);
    public static final int SND_SEQ_PORT_TYPE_MIDI_GM = (1<<2);
    public static final int SND_SEQ_PORT_TYPE_MIDI_GS = (1<<3);
    public static final int SND_SEQ_PORT_TYPE_MIDI_XG = (1<<4);
    public static final int SND_SEQ_PORT_TYPE_MIDI_MT32 = (1<<5);
    public static final int SND_SEQ_PORT_TYPE_SYNTH = (1<<10);
    public static final int SND_SEQ_PORT_TYPE_DIRECT_SAMPLE = (1<<11);
    public static final int SND_SEQ_PORT_TYPE_SAMPLE = (1<<12);
    public static final int SND_SEQ_PORT_TYPE_HARDWARE = (1<<16);
    public static final int SND_SEQ_PORT_TYPE_SOFTWARE = (1<<17);
    public static final int SND_SEQ_PORT_TYPE_SYNTHESIZER = (1<<18);
    public static final int SND_SEQ_PORT_TYPE_PORT = (1<<19);
    public static final int SND_SEQ_PORT_TYPE_APPLICATION = (1<<20);

    // Event types
    public static final byte SND_SEQ_EVENT_NOTEON = 6;
    public static final byte SND_SEQ_EVENT_NOTEOFF = 7;
    public static final byte SND_SEQ_EVENT_KEYPRESS = 8;
    public static final byte SND_SEQ_EVENT_CONTROLLER = 10;
    public static final byte SND_SEQ_EVENT_PGMCHANGE = 11;
    public static final byte SND_SEQ_EVENT_CHANPRESS = 12;
    public static final byte SND_SEQ_EVENT_PITCHBEND = 13;
    public static final byte SND_SEQ_EVENT_SYSEX = (byte) 130;

    // Functions
    public static final MethodHandle snd_seq_open = downcall("snd_seq_open", 
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
    
    public static final MethodHandle snd_seq_close = downcall("snd_seq_close",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

    public static final MethodHandle snd_seq_set_client_name = downcall("snd_seq_set_client_name",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    public static final MethodHandle snd_seq_create_simple_port = downcall("snd_seq_create_simple_port",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

    public static final MethodHandle snd_seq_delete_port = downcall("snd_seq_delete_port",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

    public static final MethodHandle snd_seq_connect_to = downcall("snd_seq_connect_to",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

    public static final MethodHandle snd_seq_disconnect_to = downcall("snd_seq_disconnect_to",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

    public static final MethodHandle snd_seq_event_output_direct = downcall("snd_seq_event_output_direct",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    public static final MethodHandle snd_seq_event_input = downcall("snd_seq_event_input",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    public static final MethodHandle snd_seq_client_info_sizeof = downcall("snd_seq_client_info_sizeof",
        FunctionDescriptor.of(ValueLayout.JAVA_LONG));

    public static final MethodHandle snd_seq_client_info_set_client = downcall("snd_seq_client_info_set_client",
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

    public static final MethodHandle snd_seq_query_next_client = downcall("snd_seq_query_next_client",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    public static final MethodHandle snd_seq_client_info_get_client = downcall("snd_seq_client_info_get_client",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

    public static final MethodHandle snd_seq_client_info_get_name = downcall("snd_seq_client_info_get_name",
        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    public static final MethodHandle snd_seq_port_info_sizeof = downcall("snd_seq_port_info_sizeof",
        FunctionDescriptor.of(ValueLayout.JAVA_LONG));

    public static final MethodHandle snd_seq_port_info_set_client = downcall("snd_seq_port_info_set_client",
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

    public static final MethodHandle snd_seq_port_info_set_port = downcall("snd_seq_port_info_set_port",
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

    public static final MethodHandle snd_seq_query_next_port = downcall("snd_seq_query_next_port",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    public static final MethodHandle snd_seq_port_info_get_port = downcall("snd_seq_port_info_get_port",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

    public static final MethodHandle snd_seq_port_info_get_name = downcall("snd_seq_port_info_get_name",
        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    public static final MethodHandle snd_seq_port_info_get_capability = downcall("snd_seq_port_info_get_capability",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

    public static final MethodHandle snd_seq_port_info_get_type = downcall("snd_seq_port_info_get_type",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

    private static MethodHandle downcall(String name, FunctionDescriptor desc) {
        return LINKER.downcallHandle(ALSA.find(name).get(), desc);
    }

    // Struct layouts
    // snd_seq_addr_t: client(1), port(1)
    public static final StructLayout snd_seq_addr_t = MemoryLayout.structLayout(
        ValueLayout.JAVA_BYTE.withName("client"),
        ValueLayout.JAVA_BYTE.withName("port")
    );

    // snd_seq_event_t: type(1), flags(1), tag(1), queue(1), time(8), source(2), dest(2), data(12)
    // Total size 28 bytes usually, but let's be careful with alignment.
    // In ALSA, time is a union of tick and real time.
    // Real time is struct { unsigned int tv_sec; unsigned int tv_nsec; } (8 bytes)
    // Source and dest are snd_seq_addr_t (2 bytes each)
    public static final StructLayout snd_seq_event_t = MemoryLayout.structLayout(
        ValueLayout.JAVA_BYTE.withName("type"),
        ValueLayout.JAVA_BYTE.withName("flags"),
        ValueLayout.JAVA_BYTE.withName("tag"),
        ValueLayout.JAVA_BYTE.withName("queue"),
        MemoryLayout.unionLayout(
            MemoryLayout.structLayout(
                ValueLayout.JAVA_INT.withName("tv_sec"),
                ValueLayout.JAVA_INT.withName("tv_nsec")
            ).withName("real"),
            ValueLayout.JAVA_INT.withName("tick")
        ).withName("time"),
        snd_seq_addr_t.withName("source"),
        snd_seq_addr_t.withName("dest"),
        MemoryLayout.unionLayout(
            // note data
            MemoryLayout.structLayout(
                ValueLayout.JAVA_BYTE.withName("channel"),
                ValueLayout.JAVA_BYTE.withName("note"),
                ValueLayout.JAVA_BYTE.withName("velocity"),
                ValueLayout.JAVA_BYTE.withName("off_velocity"),
                ValueLayout.JAVA_INT.withName("duration")
            ).withName("note"),
            // control data
            MemoryLayout.structLayout(
                ValueLayout.JAVA_BYTE.withName("channel"),
                MemoryLayout.paddingLayout(3),
                ValueLayout.JAVA_INT.withName("param"),
                ValueLayout.JAVA_INT.withName("value")
            ).withName("control"),
            // raw8
            MemoryLayout.sequenceLayout(12, ValueLayout.JAVA_BYTE).withName("raw8"),
            // ext (for sysex)
            MemoryLayout.structLayout(
                ValueLayout.JAVA_INT.withName("len"),
                ValueLayout.ADDRESS.withName("ptr").withByteAlignment(4)
            ).withName("ext")
        ).withName("data")
    );

    public static class AlsaPortInfo {
        public final int client;
        public final int port;
        public final String name;

        public AlsaPortInfo(int client, int port, String name) {
            this.client = client;
            this.port = port;
            this.name = name;
        }
    }

    public static java.util.List<AlsaPortInfo> getPorts(boolean input) {
        java.util.List<AlsaPortInfo> ports = new java.util.ArrayList<>();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pHandle = arena.allocate(ValueLayout.ADDRESS);
            int result = (int) snd_seq_open.invokeExact(pHandle, arena.allocateFrom("default"), SND_SEQ_OPEN_DUPLEX, 0);
            if (result < 0) return ports;
            MemorySegment h = pHandle.get(ValueLayout.ADDRESS, 0);

            MemorySegment cInfo = arena.allocate((long) snd_seq_client_info_sizeof.invokeExact());
            snd_seq_client_info_set_client.invokeExact(cInfo, -1);
            
            while ((int) snd_seq_query_next_client.invokeExact(h, cInfo) == 0) {
                int client = (int) snd_seq_client_info_get_client.invokeExact(cInfo);
                String clientName = ((MemorySegment) snd_seq_client_info_get_name.invokeExact(cInfo)).reinterpret(256).getString(0);

                MemorySegment pInfo = arena.allocate((long) snd_seq_port_info_sizeof.invokeExact());
                snd_seq_port_info_set_client.invokeExact(pInfo, client);
                snd_seq_port_info_set_port.invokeExact(pInfo, -1);

                while ((int) snd_seq_query_next_port.invokeExact(h, pInfo) == 0) {
                    int caps = (int) snd_seq_port_info_get_capability.invokeExact(pInfo);
                    boolean match = input ? 
                        ((caps & SND_SEQ_PORT_CAP_READ) != 0 && (caps & SND_SEQ_PORT_CAP_SUBS_READ) != 0) :
                        ((caps & SND_SEQ_PORT_CAP_WRITE) != 0 && (caps & SND_SEQ_PORT_CAP_SUBS_WRITE) != 0);
                    
                    if (match) {
                        int port = (int) snd_seq_port_info_get_port.invokeExact(pInfo);
                        String portName = ((MemorySegment) snd_seq_port_info_get_name.invokeExact(pInfo)).reinterpret(256).getString(0);
                        ports.add(new AlsaPortInfo(client, port, clientName + ":" + portName));
                    }
                }
            }
            snd_seq_close.invokeExact(h);
        } catch (Throwable t) {
            // t.printStackTrace();
        }
        return ports;
    }
}
