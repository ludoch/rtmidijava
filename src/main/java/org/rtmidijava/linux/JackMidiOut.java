package org.rtmidijava.linux;

import org.rtmidijava.RtMidiOut;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import static org.rtmidijava.linux.JackApi.*;

public class JackMidiOut extends RtMidiOut {
    private MemorySegment client = MemorySegment.NULL;
    private MemorySegment port = MemorySegment.NULL;
    private MemorySegment ringBuffer = MemorySegment.NULL;
    private MemorySegment processStub;

    public JackMidiOut() {
        try {
            MethodHandle processHandle = MethodHandles.lookup().findVirtual(JackMidiOut.class, "process",
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
            jack_midi_clear_buffer.invokeExact(buffer);

            long space = (long) jack_ringbuffer_read_space.invokeExact(ringBuffer);
            while (space > 0) {
                MemorySegment pSize = arena.allocate(ValueLayout.JAVA_INT);
                jack_ringbuffer_read.invokeExact(ringBuffer, pSize, 4L);
                int len = pSize.get(ValueLayout.JAVA_INT, 0);
                MemorySegment pMidi = arena.allocate(len);
                jack_ringbuffer_read.invokeExact(ringBuffer, pMidi, (long) len);

                jack_midi_event_write.invokeExact(buffer, 0, pMidi, (long) len);
                space = (long) jack_ringbuffer_read_space.invokeExact(ringBuffer);
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
            MemorySegment ports = (MemorySegment) jack_get_ports.invokeExact(ctrl, MemorySegment.NULL, arena.allocateFrom(JACK_MIDI_TYPE), (long) JackPortIsInput);
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
            try (Arena arena = Arena.ofConfined()) {
                client = (MemorySegment) jack_client_open.invokeExact(arena.allocateFrom("RtMidiJava Out"), JackNoStartServer, MemorySegment.NULL);
                if (client.equals(MemorySegment.NULL)) throw new RuntimeException("JACK server not running?");
                ringBuffer = (MemorySegment) jack_ringbuffer_create.invokeExact(16384L);
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
            MemorySegment ports = (MemorySegment) jack_get_ports.invokeExact(ctrl, MemorySegment.NULL, arena.allocateFrom(JACK_MIDI_TYPE), (long) JackPortIsInput);
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
        String destName = getPortName(portNumber);
        if (destName == null) throw new RuntimeException("Invalid port number");
        
        initClient();
        openVirtualPort(portName);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pName = (MemorySegment) jack_port_name.invokeExact(port);
            jack_connect.invokeExact(client, pName, arena.allocateFrom(destName));
        } catch (Throwable t) {}
    }

    @Override
    public synchronized void openVirtualPort(String portName) {
        initClient();
        try (Arena arena = Arena.ofConfined()) {
            port = (MemorySegment) jack_port_register.invokeExact(client, arena.allocateFrom(portName), arena.allocateFrom(JACK_MIDI_TYPE), (long) JackPortIsOutput, 0L);
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
                jack_ringbuffer_free.invokeExact(ringBuffer);
            } catch (Throwable t) {}
            client = MemorySegment.NULL;
            port = MemorySegment.NULL;
            ringBuffer = MemorySegment.NULL;
        }
        connected = false;
    }

    @Override
    public synchronized void sendMessage(byte[] message) {
        if (!connected) return;
        try (Arena arena = Arena.ofConfined()) {
            sendMessage(arena.allocateFrom(ValueLayout.JAVA_BYTE, message));
        }
    }

    @Override
    public synchronized void sendMessage(MemorySegment message) {
        if (!connected) return;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pSize = arena.allocate(ValueLayout.JAVA_INT);
            pSize.set(ValueLayout.JAVA_INT, 0, (int)message.byteSize());
            jack_ringbuffer_write.invokeExact(ringBuffer, pSize, 4L);
            jack_ringbuffer_write.invokeExact(ringBuffer, message, message.byteSize());
        } catch (Throwable t) {}
    }
}
