package org.rtmidijava.windows;

import org.rtmidijava.RtMidiOut;
import java.lang.foreign.*;

import static org.rtmidijava.windows.WinMidiApi.*;

public class WinMidiOut extends RtMidiOut {
    private MemorySegment hMidiOut = MemorySegment.NULL;
    private Arena instanceArena;

    @Override
    public Api getCurrentApi() {
        return Api.WINDOWS_MM;
    }

    @Override
    public int getPortCount() {
        try {
            return (int) midiOutGetNumDevs.invokeExact();
        } catch (Throwable t) {
            return 0;
        }
    }

    @Override
    public String getPortName(int portNumber) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment caps = arena.allocate(MIDIOUTCAPS);
            int result = (int) midiOutGetDevCaps.invokeExact((long) portNumber, caps, (int) MIDIOUTCAPS.byteSize());
            if (result == 0) {
                MemorySegment nameSegment = caps.asSlice(MIDIOUTCAPS.byteOffset(MemoryLayout.PathElement.groupElement("szPname")), 64);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 32; i++) {
                    char c = nameSegment.get(ValueLayout.JAVA_CHAR, i * 2L);
                    if (c == 0) break;
                    sb.append(c);
                }
                return sb.toString();
            }
        } catch (Throwable t) {
        }
        return "Windows MIDI Out Port " + portNumber;
    }

    @Override
    public void openPort(int portNumber, String portName) {
        if (connected) closePort();
        instanceArena = Arena.ofShared();
        try {
            MemorySegment phmo = instanceArena.allocate(ValueLayout.ADDRESS);
            int result = (int) midiOutOpen.invokeExact(phmo, portNumber, 0L, 0L, 0);
            if (result == 0) {
                hMidiOut = phmo.get(ValueLayout.ADDRESS, 0).reinterpret(Long.MAX_VALUE);
                connected = true;
            } else {
                instanceArena.close();
                throw new RuntimeException("Could not open MIDI out port: " + result);
            }
        } catch (Throwable t) {
            if (instanceArena != null) instanceArena.close();
            throw new RuntimeException(t);
        }
    }

    @Override
    public void openVirtualPort(String portName) {
        throw new UnsupportedOperationException("Virtual ports not supported on Windows WinMM");
    }

    @Override
    public void closePort() {
        if (connected && !hMidiOut.equals(MemorySegment.NULL)) {
            try {
                midiOutClose.invokeExact(hMidiOut);
            } catch (Throwable t) {
            }
            hMidiOut = MemorySegment.NULL;
            connected = false;
        }
        if (instanceArena != null) {
            instanceArena.close();
            instanceArena = null;
        }
    }

    @Override
    public void sendMessage(byte[] message) {
        if (!connected || hMidiOut.equals(MemorySegment.NULL)) return;
        
        if (message.length <= 3 && (message[0] & 0xFF) < 0xF0) {
            int msg = 0;
            for (int i = 0; i < message.length; i++) {
                msg |= (message[i] & 0xFF) << (i * 8);
            }
            try {
                midiOutShortMsg.invokeExact(hMidiOut, msg);
            } catch (Throwable t) {
            }
        } else {
            // Sysex or longer message
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment data = arena.allocateFrom(ValueLayout.JAVA_BYTE, message);
                MemorySegment header = arena.allocate(MIDIHDR);
                header.fill((byte) 0);
                header.set(ValueLayout.ADDRESS, MIDIHDR.byteOffset(MemoryLayout.PathElement.groupElement("lpData")), data);
                header.set(ValueLayout.JAVA_INT, MIDIHDR.byteOffset(MemoryLayout.PathElement.groupElement("dwBufferLength")), message.length);
                
                midiOutPrepareHeader.invokeExact(hMidiOut, header, (int) MIDIHDR.byteSize());
                midiOutLongMsg.invokeExact(hMidiOut, header, (int) MIDIHDR.byteSize());
                
                // Wait for MOM_DONE would be better, but for now we wait a bit or let it be
                // In a real implementation, we should unprepare only when done.
                // For simplified port, we might just leak or wait.
                // Better: RtMidi usually waits or manages a queue of headers.
                while ((header.get(ValueLayout.JAVA_INT, MIDIHDR.byteOffset(MemoryLayout.PathElement.groupElement("dwFlags"))) & 1) == 0) {
                    // MHDR_DONE = 1
                    Thread.onSpinWait();
                }
                midiOutUnprepareHeader.invokeExact(hMidiOut, header, (int) MIDIHDR.byteSize());
            } catch (Throwable t) {
            }
        }
    }
}
