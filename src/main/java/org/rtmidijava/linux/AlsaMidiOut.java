package org.rtmidijava.linux;

import org.rtmidijava.RtMidiOut;
import java.lang.foreign.*;
import java.util.List;

import static org.rtmidijava.linux.AlsaApi.*;

public class AlsaMidiOut extends RtMidiOut {
    private MemorySegment seqHandle = MemorySegment.NULL;
    private int vPort = -1;

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

        try (Arena arena = Arena.ofConfined()) {
            if (seqHandle.equals(MemorySegment.NULL)) {
                MemorySegment pHandle = arena.allocate(ValueLayout.ADDRESS);
                int result = (int) snd_seq_open.invokeExact(pHandle, arena.allocateFrom("default"), SND_SEQ_OPEN_OUTPUT, 0);
                if (result < 0) {
                    MemorySegment errPtr = (MemorySegment) UnixApi.strerror.invokeExact(-result);
                    String errMsg = errPtr.reinterpret(256).getString(0);
                    throw new RuntimeException("snd_seq_open failed: " + errMsg + " (" + result + ")");
                }
                seqHandle = pHandle.get(ValueLayout.ADDRESS, 0).reinterpret(Long.MAX_VALUE);

                // Pro-Audio: Increase client pool for large Sysex
                snd_seq_set_client_pool_output.invokeExact(seqHandle, 4096L);
            }
            
            snd_seq_set_client_name.invokeExact(seqHandle, arena.allocateFrom("RtMidiJava Client"));
            vPort = (int) snd_seq_create_simple_port.invokeExact(seqHandle, arena.allocateFrom(portName), 
                SND_SEQ_PORT_CAP_READ | SND_SEQ_PORT_CAP_SUBS_READ, SND_SEQ_PORT_TYPE_MIDI_GENERIC | SND_SEQ_PORT_TYPE_APPLICATION);
            
            snd_seq_connect_to.invokeExact(seqHandle, vPort, dest.client, dest.port);
            
            connected = true;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    @Override
    public synchronized void openVirtualPort(String portName) {
        try (Arena arena = Arena.ofConfined()) {
            if (seqHandle.equals(MemorySegment.NULL)) {
                MemorySegment pHandle = arena.allocate(ValueLayout.ADDRESS);
                int result = (int) snd_seq_open.invokeExact(pHandle, arena.allocateFrom("default"), SND_SEQ_OPEN_OUTPUT, 0);
                if (result < 0) {
                    MemorySegment errPtr = (MemorySegment) UnixApi.strerror.invokeExact(-result);
                    String errMsg = errPtr.reinterpret(256).getString(0);
                    throw new RuntimeException("snd_seq_open failed: " + errMsg + " (" + result + ")");
                }
                seqHandle = pHandle.get(ValueLayout.ADDRESS, 0).reinterpret(Long.MAX_VALUE);

                // Pro-Audio: Increase client pool for large Sysex
                snd_seq_set_client_pool_output.invokeExact(seqHandle, 4096L);
            }
            
            snd_seq_set_client_name.invokeExact(seqHandle, arena.allocateFrom("RtMidiJava Client"));
            vPort = (int) snd_seq_create_simple_port.invokeExact(seqHandle, arena.allocateFrom(portName), 
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
        connected = false;
    }

    @Override
    public synchronized void sendMessage(byte[] message) {
        if (!connected) return;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment ev = arena.allocate(snd_seq_event_t);
            ev.fill((byte) 0);
            
            byte status = message[0];
            int type = (status & 0xFF) >> 4;
            int channel = status & 0x0F;
            
            if ((status & 0xFF) == 0xF0) {
                ev.set(ValueLayout.JAVA_BYTE, snd_seq_event_t.byteOffset(MemoryLayout.PathElement.groupElement("type")), SND_SEQ_EVENT_SYSEX);
                MemorySegment sysexData = arena.allocateFrom(ValueLayout.JAVA_BYTE, message);
                ev.set(ValueLayout.JAVA_INT, snd_seq_event_t.byteOffset(MemoryLayout.PathElement.groupElement("data"), MemoryLayout.PathElement.groupElement("ext"), MemoryLayout.PathElement.groupElement("len")), message.length);
                ev.set(ValueLayout.ADDRESS, snd_seq_event_t.byteOffset(MemoryLayout.PathElement.groupElement("data"), MemoryLayout.PathElement.groupElement("ext"), MemoryLayout.PathElement.groupElement("ptr")), sysexData);
            } else {
                byte alsaType = 0;
                switch (type) {
                    case 0x9: alsaType = SND_SEQ_EVENT_NOTEON; break;
                    case 0x8: alsaType = SND_SEQ_EVENT_NOTEOFF; break;
                    case 0xA: alsaType = SND_SEQ_EVENT_KEYPRESS; break;
                    case 0xB: alsaType = SND_SEQ_EVENT_CONTROLLER; break;
                    case 0xC: alsaType = SND_SEQ_EVENT_PGMCHANGE; break;
                    case 0xD: alsaType = SND_SEQ_EVENT_CHANPRESS; break;
                    case 0xE: alsaType = SND_SEQ_EVENT_PITCHBEND; break;
                }
                
                if (alsaType != 0) {
                    ev.set(ValueLayout.JAVA_BYTE, snd_seq_event_t.byteOffset(MemoryLayout.PathElement.groupElement("type")), alsaType);
                    ev.set(ValueLayout.JAVA_BYTE, snd_seq_event_t.byteOffset(MemoryLayout.PathElement.groupElement("data"), MemoryLayout.PathElement.groupElement("note"), MemoryLayout.PathElement.groupElement("channel")), (byte)channel);
                    if (message.length > 1)
                        ev.set(ValueLayout.JAVA_BYTE, snd_seq_event_t.byteOffset(MemoryLayout.PathElement.groupElement("data"), MemoryLayout.PathElement.groupElement("note"), MemoryLayout.PathElement.groupElement("note")), message[1]);
                    if (message.length > 2)
                        ev.set(ValueLayout.JAVA_BYTE, snd_seq_event_t.byteOffset(MemoryLayout.PathElement.groupElement("data"), MemoryLayout.PathElement.groupElement("note"), MemoryLayout.PathElement.groupElement("velocity")), message[2]);
                }
            }

            ev.set(ValueLayout.JAVA_BYTE, snd_seq_event_t.byteOffset(MemoryLayout.PathElement.groupElement("source"), MemoryLayout.PathElement.groupElement("port")), (byte) vPort);
            ev.set(ValueLayout.JAVA_BYTE, snd_seq_event_t.byteOffset(MemoryLayout.PathElement.groupElement("dest"), MemoryLayout.PathElement.groupElement("client")), (byte) 254);
            ev.set(ValueLayout.JAVA_BYTE, snd_seq_event_t.byteOffset(MemoryLayout.PathElement.groupElement("dest"), MemoryLayout.PathElement.groupElement("port")), (byte) 254);

            snd_seq_event_output_direct.invokeExact(seqHandle, ev);
        } catch (Throwable t) {
            // t.printStackTrace();
        }
    }
}
