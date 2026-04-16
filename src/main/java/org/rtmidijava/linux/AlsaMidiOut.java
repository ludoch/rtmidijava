package org.rtmidijava.linux;

import org.rtmidijava.RtMidiOut;
import java.lang.foreign.*;
import java.util.List;

import static org.rtmidijava.linux.AlsaApi.*;

public class AlsaMidiOut extends RtMidiOut {
    private MemorySegment seqHandle = MemorySegment.NULL;
    private int vPort = -1;
    private MemorySegment eventTemplate;
    private Arena outArena;

    @Override
    public Api getCurrentApi() {
        return Api.LINUX_ALSA;
    }

    @Override
    public int getPortCount() {
        return getPorts(false).size();
    }

    @Override
    public String getPortName(int portNumber) {
        List<AlsaPortInfo> ports = getPorts(false);
        if (portNumber >= 0 && portNumber < ports.size()) {
            return ports.get(portNumber).name;
        }
        return null;
    }

    @Override
    public synchronized void openPort(int portNumber, String portName) {
        List<AlsaPortInfo> ports = getPorts(false);
        if (portNumber < 0 || portNumber >= ports.size()) {
            throw new RuntimeException("Invalid port number");
        }
        AlsaPortInfo dest = ports.get(portNumber);

        try {
            if (seqHandle.equals(MemorySegment.NULL)) {
                outArena = Arena.ofShared();
                MemorySegment pHandle = outArena.allocate(ValueLayout.ADDRESS);
                int result = (int) snd_seq_open.invokeExact(pHandle, outArena.allocateFrom("default"), SND_SEQ_OPEN_OUTPUT, 0);
                if (result < 0) {
                    MemorySegment errPtr = (MemorySegment) UnixApi.strerror.invokeExact(-result);
                    String errMsg = errPtr.reinterpret(256).getString(0);
                    throw new RuntimeException("snd_seq_open failed: " + errMsg + " (" + result + ")");
                }
                seqHandle = pHandle.get(ValueLayout.ADDRESS, 0).reinterpret(Long.MAX_VALUE);

                // Pro-Audio: Increase client pool for large Sysex
                snd_seq_set_client_pool_output.invokeExact(seqHandle, 4096L);
                
                // Pre-allocate event struct
                eventTemplate = outArena.allocate(snd_seq_event_t);
            }
            
            snd_seq_set_client_name.invokeExact(seqHandle, outArena.allocateFrom("RtMidiJava Client"));
            vPort = (int) snd_seq_create_simple_port.invokeExact(seqHandle, outArena.allocateFrom(portName), 
                SND_SEQ_PORT_CAP_READ | SND_SEQ_PORT_CAP_SUBS_READ, SND_SEQ_PORT_TYPE_MIDI_GENERIC | SND_SEQ_PORT_TYPE_APPLICATION);
            
            snd_seq_connect_to.invokeExact(seqHandle, vPort, dest.client, dest.port);
            
            connected = true;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    @Override
    public synchronized void openVirtualPort(String portName) {
        try {
            if (seqHandle.equals(MemorySegment.NULL)) {
                outArena = Arena.ofShared();
                MemorySegment pHandle = outArena.allocate(ValueLayout.ADDRESS);
                int result = (int) snd_seq_open.invokeExact(pHandle, outArena.allocateFrom("default"), SND_SEQ_OPEN_OUTPUT, 0);
                if (result < 0) {
                    MemorySegment errPtr = (MemorySegment) UnixApi.strerror.invokeExact(-result);
                    String errMsg = errPtr.reinterpret(256).getString(0);
                    throw new RuntimeException("snd_seq_open failed: " + errMsg + " (" + result + ")");
                }
                seqHandle = pHandle.get(ValueLayout.ADDRESS, 0).reinterpret(Long.MAX_VALUE);
                
                // Pro-Audio: Increase client pool for large Sysex
                snd_seq_set_client_pool_output.invokeExact(seqHandle, 4096L);
                eventTemplate = outArena.allocate(snd_seq_event_t);
            }
            
            snd_seq_set_client_name.invokeExact(seqHandle, outArena.allocateFrom("RtMidiJava Client"));
            vPort = (int) snd_seq_create_simple_port.invokeExact(seqHandle, outArena.allocateFrom(portName), 
                SND_SEQ_PORT_CAP_WRITE | SND_SEQ_PORT_CAP_SUBS_WRITE, SND_SEQ_PORT_TYPE_MIDI_GENERIC | SND_SEQ_PORT_TYPE_APPLICATION);
            
            connected = true;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    @Override
    public synchronized void closePort() {
        if (!seqHandle.equals(MemorySegment.NULL)) {
            try {
                snd_seq_close.invokeExact(seqHandle);
            } catch (Throwable t) {}
            seqHandle = MemorySegment.NULL;
        }
        if (outArena != null) {
            outArena.close();
            outArena = null;
        }
        connected = false;
    }

    @Override
    public synchronized void sendMessage(byte[] message) {
        if (!connected) return;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment msgSegment = arena.allocateFrom(ValueLayout.JAVA_BYTE, message);
            sendMessage(msgSegment);
        }
    }

    @Override
    public synchronized void sendMessage(MemorySegment message) {
        if (!connected) return;
        try {
            eventTemplate.fill((byte) 0);
            byte status = message.get(ValueLayout.JAVA_BYTE, 0);
            int channel = status & 0x0F;
            long len = message.byteSize();

            if ((status & 0xFF) == 0xF0) {
                eventTemplate.set(ValueLayout.JAVA_BYTE, snd_seq_event_t.byteOffset(MemoryLayout.PathElement.groupElement("type")), SND_SEQ_EVENT_SYSEX);
                eventTemplate.set(ValueLayout.JAVA_INT, snd_seq_event_t.byteOffset(MemoryLayout.PathElement.groupElement("data"), MemoryLayout.PathElement.groupElement("ext"), MemoryLayout.PathElement.groupElement("len")), (int)len);
                eventTemplate.set(ValueLayout.ADDRESS, snd_seq_event_t.byteOffset(MemoryLayout.PathElement.groupElement("data"), MemoryLayout.PathElement.groupElement("ext"), MemoryLayout.PathElement.groupElement("ptr")), message);
            } else {
                int type = (status & 0xFF) >> 4;
                byte alsaType = switch (type) {
                    case 0x9 -> SND_SEQ_EVENT_NOTEON;
                    case 0x8 -> SND_SEQ_EVENT_NOTEOFF;
                    case 0xA -> SND_SEQ_EVENT_KEYPRESS;
                    case 0xB -> SND_SEQ_EVENT_CONTROLLER;
                    case 0xC -> SND_SEQ_EVENT_PGMCHANGE;
                    case 0xD -> SND_SEQ_EVENT_CHANPRESS;
                    case 0xE -> SND_SEQ_EVENT_PITCHBEND;
                    default -> 0;
                };

                if (alsaType != 0) {
                    eventTemplate.set(ValueLayout.JAVA_BYTE, snd_seq_event_t.byteOffset(MemoryLayout.PathElement.groupElement("type")), alsaType);
                    eventTemplate.set(ValueLayout.JAVA_BYTE, snd_seq_event_t.byteOffset(MemoryLayout.PathElement.groupElement("data"), MemoryLayout.PathElement.groupElement("note"), MemoryLayout.PathElement.groupElement("channel")), (byte)channel);
                    if (len > 1) eventTemplate.set(ValueLayout.JAVA_BYTE, snd_seq_event_t.byteOffset(MemoryLayout.PathElement.groupElement("data"), MemoryLayout.PathElement.groupElement("note"), MemoryLayout.PathElement.groupElement("note")), message.get(ValueLayout.JAVA_BYTE, 1));
                    if (len > 2) eventTemplate.set(ValueLayout.JAVA_BYTE, snd_seq_event_t.byteOffset(MemoryLayout.PathElement.groupElement("data"), MemoryLayout.PathElement.groupElement("note"), MemoryLayout.PathElement.groupElement("velocity")), message.get(ValueLayout.JAVA_BYTE, 2));
                }
            }

            eventTemplate.set(ValueLayout.JAVA_BYTE, snd_seq_event_t.byteOffset(MemoryLayout.PathElement.groupElement("source"), MemoryLayout.PathElement.groupElement("port")), (byte) vPort);
            eventTemplate.set(ValueLayout.JAVA_BYTE, snd_seq_event_t.byteOffset(MemoryLayout.PathElement.groupElement("dest"), MemoryLayout.PathElement.groupElement("client")), (byte) 254);
            eventTemplate.set(ValueLayout.JAVA_BYTE, snd_seq_event_t.byteOffset(MemoryLayout.PathElement.groupElement("dest"), MemoryLayout.PathElement.groupElement("port")), (byte) 254);

            snd_seq_event_output_direct.invokeExact(seqHandle, eventTemplate);
        } catch (Throwable t) {}
    }
}
