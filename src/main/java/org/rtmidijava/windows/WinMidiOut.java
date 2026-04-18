package org.rtmidijava.windows;

import org.rtmidijava.RtMidiOut;
import java.lang.foreign.*;

import static org.rtmidijava.windows.WinMidiApi.*;

public class WinMidiOut extends RtMidiOut {
    private long hMidiOut = 0;
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

    public int getManufacturerId(int portNumber) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment caps = arena.allocate(MIDIOUTCAPS);
            int result = (int) midiOutGetDevCaps.invokeExact((long) portNumber, caps, (int) MIDIOUTCAPS.byteSize());
            if (result == 0) {
                return caps.get(ValueLayout.JAVA_SHORT, MIDIOUTCAPS.byteOffset(MemoryLayout.PathElement.groupElement("wMid"))) & 0xFFFF;
            }
        } catch (Throwable t) {}
        return 0;
    }

    public int getProductId(int portNumber) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment caps = arena.allocate(MIDIOUTCAPS);
            int result = (int) midiOutGetDevCaps.invokeExact((long) portNumber, caps, (int) MIDIOUTCAPS.byteSize());
            if (result == 0) {
                return caps.get(ValueLayout.JAVA_SHORT, MIDIOUTCAPS.byteOffset(MemoryLayout.PathElement.groupElement("wPid"))) & 0xFFFF;
            }
        } catch (Throwable t) {}
        return 0;
    }

    private String getErrorText(int errorCode) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(256 * 2);
            int res = (int) midiOutGetErrorText.invokeExact(errorCode, buffer, 256);
            return buffer.getString(0, java.nio.charset.StandardCharsets.UTF_16LE);
        } catch (Throwable t) {
            return "Unknown error (" + errorCode + ")";
        }
    }

    @Override
    public synchronized void openPort(int portNumber, String portName) {
        if (connected) closePort();
        instanceArena = Arena.ofShared();
        try {
            MemorySegment phmo = instanceArena.allocate(ValueLayout.ADDRESS);
            int result = (int) midiOutOpen.invokeExact(phmo, portNumber, 0L, 0L, 0);
            if (result != 0) {
                String errMsg = getErrorText(result);
                instanceArena.close();
                throw new org.rtmidijava.RtMidiException("WinMidiOut::openPort: " + errMsg, org.rtmidijava.RtMidiException.Type.DRIVER_ERROR);
            }
            hMidiOut = phmo.get(ValueLayout.ADDRESS, 0).address();
            sysexHeader = instanceArena.allocate(MIDIHDR);
            connected = true;
        } catch (org.rtmidijava.RtMidiException e) {
            throw e;
        } catch (Throwable t) {
            if (instanceArena != null) instanceArena.close();
            throw new org.rtmidijava.RtMidiException("WinMidiOut::openPort: " + t.getMessage(), org.rtmidijava.RtMidiException.Type.SYSTEM_ERROR);
        }
    }

    @Override
    public void openVirtualPort(String portName) {
        throw new UnsupportedOperationException("Virtual ports not supported on Windows WinMM");
    }

    @Override
    public synchronized void closePort() {
        if (connected && hMidiOut != 0) {
            try {
                int res = (int) midiOutClose.invokeExact(hMidiOut);
            } catch (Throwable t) {
            }
            hMidiOut = 0;
            connected = false;
        }
        if (instanceArena != null) {
            instanceArena.close();
            instanceArena = null;
        }
    }

    @Override
    public synchronized void sendMessage(byte[] message) {
        if (!connected || hMidiOut == 0) return;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment msgSegment = arena.allocateFrom(ValueLayout.JAVA_BYTE, message);
            sendMessage(msgSegment);
        }
    }

    @Override
    public synchronized void sendMessage(MemorySegment message) {
        if (!connected || hMidiOut == 0) return;
        
        long len = message.byteSize();
        if (len <= 3 && (message.get(ValueLayout.JAVA_BYTE, 0) & 0xFF) < 0xF0) {
            int msg = 0;
            for (int i = 0; i < len; i++) {
                msg |= (message.get(ValueLayout.JAVA_BYTE, i) & 0xFF) << (i * 8);
            }
            try {
                int res = (int) midiOutShortMsg.invokeExact(hMidiOut, msg);
            } catch (Throwable t) {}
        } else {
            // Sysex
            try {
                sysexHeader.fill((byte) 0);
                sysexHeader.set(ValueLayout.ADDRESS, MIDIHDR.byteOffset(MemoryLayout.PathElement.groupElement("lpData")), message);
                sysexHeader.set(ValueLayout.JAVA_INT, MIDIHDR.byteOffset(MemoryLayout.PathElement.groupElement("dwBufferLength")), (int)len);
                
                int resPrep = (int) midiOutPrepareHeader.invokeExact(hMidiOut, sysexHeader, (int) MIDIHDR.byteSize());
                int resLong = (int) midiOutLongMsg.invokeExact(hMidiOut, sysexHeader, (int) MIDIHDR.byteSize());
                
                while ((sysexHeader.get(ValueLayout.JAVA_INT, MIDIHDR.byteOffset(MemoryLayout.PathElement.groupElement("dwFlags"))) & 1) == 0) {
                    Thread.onSpinWait();
                }
                int resUnprep = (int) midiOutUnprepareHeader.invokeExact(hMidiOut, sysexHeader, (int) MIDIHDR.byteSize());
            } catch (Throwable t) {}
        }
    }
}
