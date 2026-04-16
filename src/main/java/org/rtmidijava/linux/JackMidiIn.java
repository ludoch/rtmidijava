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
    private Arena jackArena;
    private MemorySegment preallocatedEvent;
    private MethodHandle getCountHandle;

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
        try {
            MemorySegment buffer = (MemorySegment) jack_port_get_buffer.invokeExact(port, nframes);
            int count = (int) getCountHandle.invokeExact(buffer);

            for (int i = 0; i < count; i++) {
                jack_midi_event_get.invokeExact(preallocatedEvent, buffer, i);
                int len = (int) preallocatedEvent.get(ValueLayout.JAVA_LONG, jack_midi_event_t.byteOffset(MemoryLayout.PathElement.groupElement("size")));
                MemorySegment dataPtr = preallocatedEvent.get(ValueLayout.ADDRESS, jack_midi_event_t.byteOffset(MemoryLayout.PathElement.groupElement("buffer")));
                
                synchronized(this) {
                    if (connected) {
                        onIncomingMessage(System.nanoTime() / 1_000_000_000.0, dataPtr.reinterpret(len));
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
        MemorySegment ctrl = getControlClient();
        if (ctrl.equals(MemorySegment.NULL)) return 0;
        int count = 0;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment ports = (MemorySegment) jack_get_ports.invokeExact(ctrl, MemorySegment.NULL, arena.allocateFrom(JACK_MIDI_TYPE), (long) JackPortIsOutput);
            if (ports.equals(MemorySegment.NULL)) return 0;
            while (!ports.getAtIndex(ValueLayout.ADDRESS, count).equals(MemorySegment.NULL)) {
                count++;
            }
            jack_free.invokeExact(ports);
        } catch (Throwable t) {}
        return count;
    }

    private void initClient() {
        if (client.equals(MemorySegment.NULL)) {
            try {
                jackArena = Arena.ofShared();
                client = (MemorySegment) jack_client_open.invokeExact(jackArena.allocateFrom("RtMidiJava In"), JackNoStartServer, MemorySegment.NULL);
                if (client.equals(MemorySegment.NULL)) throw new RuntimeException("JACK server not running?");
                
                preallocatedEvent = jackArena.allocate(jack_midi_event_t);
                getCountHandle = LINKER.downcallHandle(JACK.find("jack_midi_get_event_count").get(), FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
                
                jack_set_process_callback.invokeExact(client, processStub, MemorySegment.NULL);
                jack_activate.invokeExact(client);
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        }
    }

    @Override
    public String getPortName(int portNumber) {
        MemorySegment ctrl = getControlClient();
        if (ctrl.equals(MemorySegment.NULL)) return null;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment ports = (MemorySegment) jack_get_ports.invokeExact(ctrl, MemorySegment.NULL, arena.allocateFrom(JACK_MIDI_TYPE), (long) JackPortIsOutput);
            if (ports.equals(MemorySegment.NULL)) return null;
            MemorySegment p = ports.getAtIndex(ValueLayout.ADDRESS, portNumber);
            if (p.equals(MemorySegment.NULL)) {
                jack_free.invokeExact(ports);
                return null;
            }
            String name = p.reinterpret(256).getString(0);
            jack_free.invokeExact(ports);
            return name;
        } catch (Throwable t) {}
        return null;
    }

    @Override
    public synchronized void openPort(int portNumber, String portName) {
        String srcName = getPortName(portNumber);
        if (srcName == null) throw new RuntimeException("Invalid port number");

        initClient();
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
        if (jackArena != null) {
            jackArena.close();
            jackArena = null;
        }
        connected = false;
    }
}
