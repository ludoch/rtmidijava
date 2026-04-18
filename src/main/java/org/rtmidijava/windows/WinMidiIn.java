package org.rtmidijava.windows;

import org.rtmidijava.RtMidiIn;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import static org.rtmidijava.windows.WinMidiApi.*;

public class WinMidiIn extends RtMidiIn {
    private long hMidiIn = 0;
    private long startTime;
    private Arena instanceArena;
    private static final int RT_SYSEX_BUFFER_SIZE = 1024;
    private static final int RT_SYSEX_BUFFER_COUNT = 4;
    private MemorySegment[] sysexBuffers = new MemorySegment[RT_SYSEX_BUFFER_COUNT];
    private MemorySegment upcallStub;
    private MemorySegment sysexNativeBuffer;
    private int sysexOffset = 0;
    private Thread worker;

    public WinMidiIn() {
        try {
            MethodHandle onMidiInProc = MethodHandles.lookup().findVirtual(WinMidiIn.class, "midiInProc",
                    MethodType.methodType(void.class, MemorySegment.class, int.class, long.class, long.class, long.class));
            onMidiInProc = onMidiInProc.bindTo(this);
            upcallStub = LINKER.upcallStub(onMidiInProc,
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG),
                    Arena.global());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private synchronized void midiInProc(MemorySegment handle, int wMsg, long dwInstance, long dwParam1, long dwParam2) {
        if (!connected) return;
        double timestamp = (dwParam2 - startTime) / 1000.0;
        if (wMsg == MIM_DATA) {
            byte status = (byte) (dwParam1 & 0xFF);
            byte data1 = (byte) ((dwParam1 >> 8) & 0xFF);
            byte data2 = (byte) ((dwParam1 >> 16) & 0xFF);
            
            try (Arena local = Arena.ofConfined()) {
                byte[] msg;
                if ((status & 0xFF) < 0xC0 || (status & 0xFF) >= 0xE0) {
                    msg = new byte[]{status, data1, data2};
                } else {
                    msg = new byte[]{status, data1};
                }
                ringBuffer.write(timestamp, local.allocateFrom(ValueLayout.JAVA_BYTE, msg));
            }
        } else if (wMsg == MIM_LONGDATA) {
            try {
                // dwParam1 is the address of the MIDIHDR
                MemorySegment header = MemorySegment.ofAddress(dwParam1).reinterpret(MIDIHDR.byteSize(), instanceArena, null);
                int bytesRecorded = header.get(ValueLayout.JAVA_INT, MIDIHDR.byteOffset(MemoryLayout.PathElement.groupElement("dwBytesRecorded")));
                if (bytesRecorded > 0) {
                    MemorySegment dataPtr = header.get(ValueLayout.ADDRESS, MIDIHDR.byteOffset(MemoryLayout.PathElement.groupElement("lpData")));
                    MemorySegment data = dataPtr.reinterpret(bytesRecorded, instanceArena, null);
                    
                    MemorySegment.copy(data, 0, sysexNativeBuffer, sysexOffset, (long)bytesRecorded);
                    sysexOffset += bytesRecorded;
                    
                    if (data.get(ValueLayout.JAVA_BYTE, bytesRecorded - 1) == (byte)0xF7) {
                        ringBuffer.write(timestamp, sysexNativeBuffer.asSlice(0, sysexOffset));
                        sysexOffset = 0;
                    }
                }
                int resAdd = (int) midiInAddBuffer.invokeExact(hMidiIn, header, (int) MIDIHDR.byteSize());
            } catch (Throwable t) {}
        }
    }

    private void startWorker() {
        worker = new Thread(() -> {
            byte[] msgBuffer = new byte[8192];
            double[] tsOut = new double[1];
            while (connected) {
                int len = ringBuffer.read(msgBuffer, tsOut);
                if (len > 0) {
                    byte[] data = new byte[len];
                    System.arraycopy(msgBuffer, 0, data, 0, len);
                    super.onIncomingMessage(tsOut[0], data);
                } else {
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
        }, "RtMidiJava-WinWorker");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public Api getCurrentApi() {
        return Api.WINDOWS_MM;
    }

    @Override
    public int getPortCount() {
        try {
            return (int) midiInGetNumDevs.invokeExact();
        } catch (Throwable t) {
            return 0;
        }
    }

    @Override
    public String getPortName(int portNumber) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment caps = arena.allocate(MIDIINCAPS);
            int result = (int) midiInGetDevCaps.invokeExact((long) portNumber, caps, (int) MIDIINCAPS.byteSize());
            if (result == 0) {
                MemorySegment nameSegment = caps.asSlice(MIDIINCAPS.byteOffset(MemoryLayout.PathElement.groupElement("szPname")), 64);
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
        return "Windows MIDI In Port " + portNumber;
    }

    public int getManufacturerId(int portNumber) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment caps = arena.allocate(MIDIINCAPS);
            int result = (int) midiInGetDevCaps.invokeExact((long) portNumber, caps, (int) MIDIINCAPS.byteSize());
            if (result == 0) {
                return caps.get(ValueLayout.JAVA_SHORT, MIDIINCAPS.byteOffset(MemoryLayout.PathElement.groupElement("wMid"))) & 0xFFFF;
            }
        } catch (Throwable t) {}
        return 0;
    }

    public int getProductId(int portNumber) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment caps = arena.allocate(MIDIINCAPS);
            int result = (int) midiInGetDevCaps.invokeExact((long) portNumber, caps, (int) MIDIINCAPS.byteSize());
            if (result == 0) {
                return caps.get(ValueLayout.JAVA_SHORT, MIDIINCAPS.byteOffset(MemoryLayout.PathElement.groupElement("wPid"))) & 0xFFFF;
            }
        } catch (Throwable t) {}
        return 0;
    }

    private String getErrorText(int errorCode) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(256 * 2);
            int res = (int) midiInGetErrorText.invokeExact(errorCode, buffer, 256);
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
            MemorySegment phmi = instanceArena.allocate(ValueLayout.ADDRESS);
            int result = (int) midiInOpen.invokeExact(phmi, portNumber, upcallStub, 0L, CALLBACK_FUNCTION);
            if (result != 0) {
                String errMsg = getErrorText(result);
                instanceArena.close();
                
                String extra = "";
                if (result == 4) { // MMSYSERR_ALLOCATED
                    extra = "\nHint: This port is already in use by another application. Windows WinMM is not multi-client. Please install Windows MIDI Services (MIDI 2.0) for multi-client support.";
                }
                
                throw new org.rtmidijava.RtMidiException("WinMidiIn::openPort: " + errMsg + extra, org.rtmidijava.RtMidiException.Type.DRIVER_ERROR);
            }
            
            hMidiIn = phmi.get(ValueLayout.ADDRESS, 0).address();
            
            sysexNativeBuffer = instanceArena.allocate(8192);

            startTime = System.currentTimeMillis();

            // Allocate and add Sysex buffers
            for (int i = 0; i < RT_SYSEX_BUFFER_COUNT; i++) {
                sysexBuffers[i] = instanceArena.allocate(MIDIHDR);
                sysexBuffers[i].fill((byte) 0);
                MemorySegment data = instanceArena.allocate(RT_SYSEX_BUFFER_SIZE);
                sysexBuffers[i].set(ValueLayout.ADDRESS, MIDIHDR.byteOffset(MemoryLayout.PathElement.groupElement("lpData")), data);
                sysexBuffers[i].set(ValueLayout.JAVA_INT, MIDIHDR.byteOffset(MemoryLayout.PathElement.groupElement("dwBufferLength")), RT_SYSEX_BUFFER_SIZE);
                sysexBuffers[i].set(ValueLayout.JAVA_INT, MIDIHDR.byteOffset(MemoryLayout.PathElement.groupElement("dwFlags")), 0);
                
                int resPrep = (int) midiInPrepareHeader.invokeExact(hMidiIn, sysexBuffers[i], (int) MIDIHDR.byteSize());
                if (resPrep == 0) {
                    int resAdd = (int) midiInAddBuffer.invokeExact(hMidiIn, sysexBuffers[i], (int) MIDIHDR.byteSize());
                }
            }

            int resStart = (int) midiInStart.invokeExact(hMidiIn);
            if (resStart != 0) {
                throw new org.rtmidijava.RtMidiException("WinMidiIn::openPort: Error starting input device (" + resStart + ")", org.rtmidijava.RtMidiException.Type.DRIVER_ERROR);
            }

            try {
                org.rtmidijava.utils.ThreadUtils.makeRealTime();
            } catch (Throwable t) {}
            connected = true;
            startWorker();
        } catch (org.rtmidijava.RtMidiException e) {
            throw e;
        } catch (Throwable t) {
            if (instanceArena != null) instanceArena.close();
            throw new org.rtmidijava.RtMidiException("WinMidiIn::openPort: " + t.getMessage(), org.rtmidijava.RtMidiException.Type.SYSTEM_ERROR);
        }
    }

    @Override
    public void openVirtualPort(String portName) {
        throw new UnsupportedOperationException("Virtual ports not supported on Windows WinMM");
    }

    @Override
    public synchronized void closePort() {
        connected = false;
        if (worker != null) {
            try { worker.join(500); } catch (InterruptedException e) {}
        }
        if (hMidiIn != 0) {
            try {
                int resStop = (int) midiInStop.invokeExact(hMidiIn);
                for (int i = 0; i < RT_SYSEX_BUFFER_COUNT; i++) {
                    int resUnprep = (int) midiInUnprepareHeader.invokeExact(hMidiIn, sysexBuffers[i], (int) MIDIHDR.byteSize());
                }
                int resClose = (int) midiInClose.invokeExact(hMidiIn);
            } catch (Throwable t) {
            }
            hMidiIn = 0;
        }
        if (instanceArena != null) {
            instanceArena.close();
            instanceArena = null;
        }
    }
}
