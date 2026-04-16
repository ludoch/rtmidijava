package org.rtmidijava.linux;

import org.rtmidijava.RtMidiIn;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import static org.rtmidijava.linux.JackApi.*;

public class JackMidiIn extends RtMidiIn {
    private MemorySegment client = MemorySegment.NULL;
    private MemorySegment port = MemorySegment.NULL;
    private MemorySegment processStub;

    public JackMidiIn() {
        try {
            MethodHandle processHandle = MethodHandles.lookup().findVirtual(JackMidiIn.class, "process",
                    MethodType.methodType(int.class, int.class, MemorySegment.class));
            processHandle = processHandle.bindTo(this);
            processStub = LINKER.upcallStub(processHandle,
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
                    Arena.global());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private int process(int nframes, MemorySegment arg) {
        if (port.equals(MemorySegment.NULL)) return 0;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = (MemorySegment) jack_port_get_buffer.invokeExact(port, nframes);
            
            MemorySegment event = arena.allocate(jack_midi_event_t);
            int count = (int) jack_midi_get_event_count.invokeExact(buffer);

            for (int i = 0; i < count; i++) {
                jack_midi_event_get.invokeExact(event, buffer, i);
                int len = (int) event.get(ValueLayout.JAVA_LONG, jack_midi_event_t.byteOffset(MemoryLayout.PathElement.groupElement("size")));
                MemorySegment dataPtr = event.get(ValueLayout.ADDRESS, jack_midi_event_t.byteOffset(MemoryLayout.PathElement.groupElement("buffer")));
                byte[] data = dataPtr.reinterpret(len).toArray(ValueLayout.JAVA_BYTE);
                
                synchronized(this) {
                    if (connected) {
                        onIncomingMessage(System.nanoTime() / 1_000_000_000.0, data);
                    }
                }
            }
        } catch (Throwable t) {}
        return 0;
    }

    @Override
    public Api getCurrentApi() {
        return Api.LINUX_JACK;
    }

    @Override
    public int getPortCount() {
        initClient();
        int count = 0;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment ports = (MemorySegment) jack_get_ports.invokeExact(client, MemorySegment.NULL, arena.allocateFrom(JACK_MIDI_TYPE), (long) JackPortIsOutput);
            if (ports.equals(MemorySegment.NULL)) return 0;
            while (!ports.getAtIndex(ValueLayout.ADDRESS, count).equals(MemorySegment.NULL)) {
                count++;
            }
        } catch (Throwable t) {}
        return count;
    }

    private void initClient() {
        if (client.equals(MemorySegment.NULL)) {
            try (Arena arena = Arena.ofConfined()) {
                client = (MemorySegment) jack_client_open.invokeExact(arena.allocateFrom("RtMidiJava"), JackNoStartServer, MemorySegment.NULL);
                if (client.equals(MemorySegment.NULL)) throw new RuntimeException("JACK server not running?");
                jack_set_process_callback.invokeExact(client, processStub, MemorySegment.NULL);
                jack_activate.invokeExact(client);
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        }
    }

    @Override
    public String getPortName(int portNumber) {
        initClient();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment ports = (MemorySegment) jack_get_ports.invokeExact(client, MemorySegment.NULL, arena.allocateFrom(JACK_MIDI_TYPE), (long) JackPortIsOutput);
            if (ports.equals(MemorySegment.NULL)) return null;
            MemorySegment p = ports.getAtIndex(ValueLayout.ADDRESS, portNumber);
            if (p.equals(MemorySegment.NULL)) return null;
            return p.reinterpret(256).getString(0);
        } catch (Throwable t) {}
        return null;
    }

    @Override
    public synchronized void openPort(int portNumber, String portName) {
        initClient();
        String srcName = getPortName(portNumber);
        openVirtualPort(portName);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pName = (MemorySegment) jack_port_name.invokeExact(port);
            jack_connect.invokeExact(client, arena.allocateFrom(srcName), pName);
        } catch (Throwable t) {}
    }

    @Override
    public synchronized void openVirtualPort(String portName) {
        initClient();
        try (Arena arena = Arena.ofConfined()) {
            port = (MemorySegment) jack_port_register.invokeExact(client, arena.allocateFrom(portName), arena.allocateFrom(JACK_MIDI_TYPE), (long) JackPortIsInput, 0L);
            connected = true;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    @Override
    public synchronized void closePort() {
        if (!client.equals(MemorySegment.NULL)) {
            try {
                jack_client_close.invokeExact(client);
            } catch (Throwable t) {}
            client = MemorySegment.NULL;
            port = MemorySegment.NULL;
        }
        connected = false;
    }
}
