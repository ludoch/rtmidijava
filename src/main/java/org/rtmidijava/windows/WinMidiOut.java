package org.rtmidijava.windows;

import org.rtmidijava.RtMidiOut;
import java.lang.foreign.*;

import static org.rtmidijava.windows.WinMidiApi.*;

public class WinMidiOut extends RtMidiOut {
    private MemorySegment hMidiOut = MemorySegment.NULL;
    private Arena instanceArena;
    private MemorySegment sysexHeader;

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
    public synchronized void openPort(int portNumber, String portName) {
        if (connected) closePort();
        instanceArena = Arena.ofShared();
        try {
            MemorySegment phmo = instanceArena.allocate(ValueLayout.ADDRESS);
            int result = (int) midiOutOpen.invokeExact(phmo, portNumber, 0L, 0L, 0);
            if (result == 0) {
                hMidiOut = phmo.get(ValueLayout.ADDRESS, 0).reinterpret(Long.MAX_VALUE);
                sysexHeader = instanceArena.allocate(MIDIHDR);
                connected = true;
            } else {
                MemorySegment errBuf = instanceArena.allocate(256 * 2);
                midiOutGetErrorText.invokeExact(result, errBuf, 256);
                String errMsg = errBuf.getString(0, java.nio.charset.StandardCharsets.UTF_16LE);
                instanceArena.close();
                throw new RuntimeException("Could not open MIDI out port: " + errMsg + " (" + result + ")");
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
    public synchronized void closePort() {
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
    public synchronized void sendMessage(byte[] message) {
        if (!connected || hMidiOut.equals(MemorySegment.NULL)) return;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment msgSegment = arena.allocateFrom(ValueLayout.JAVA_BYTE, message);
            sendMessage(msgSegment);
        }
    }

    @Override
    public synchronized void sendMessage(MemorySegment message) {
        if (!connected || hMidiOut.equals(MemorySegment.NULL)) return;
        
        long len = message.byteSize();
        if (len <= 3 && (message.get(ValueLayout.JAVA_BYTE, 0) & 0xFF) < 0xF0) {
            int msg = 0;
            for (int i = 0; i < len; i++) {
                msg |= (message.get(ValueLayout.JAVA_BYTE, i) & 0xFF) << (i * 8);
            }
            try {
                midiOutShortMsg.invokeExact(hMidiOut, msg);
            } catch (Throwable t) {}
        } else {
            // Sysex
            try {
                sysexHeader.fill((byte) 0);
                sysexHeader.set(ValueLayout.ADDRESS, MIDIHDR.byteOffset(MemoryLayout.PathElement.groupElement("lpData")), message);
                sysexHeader.set(ValueLayout.JAVA_INT, MIDIHDR.byteOffset(MemoryLayout.PathElement.groupElement("dwBufferLength")), (int)len);
                
                midiOutPrepareHeader.invokeExact(hMidiOut, sysexHeader, (int) MIDIHDR.byteSize());
                midiOutLongMsg.invokeExact(hMidiOut, sysexHeader, (int) MIDIHDR.byteSize());
                
                while ((sysexHeader.get(ValueLayout.JAVA_INT, MIDIHDR.byteOffset(MemoryLayout.PathElement.groupElement("dwFlags"))) & 1) == 0) {
                    Thread.onSpinWait();
                }
                midiOutUnprepareHeader.invokeExact(hMidiOut, sysexHeader, (int) MIDIHDR.byteSize());
            } catch (Throwable t) {}
        }
    }
}
