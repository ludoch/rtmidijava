package org.rtmidijava.macos;

import org.rtmidijava.RtMidiIn;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import org.rtmidijava.RtMidiException;

public class CoreMidiIn extends RtMidiIn {
    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup CORE_MIDI = SymbolLookup.libraryLookup("/System/Library/Frameworks/CoreMIDI.framework/Versions/Current/CoreMIDI", Arena.global());
    private static final SymbolLookup CORE_FOUNDATION = SymbolLookup.libraryLookup("/System/Library/Frameworks/CoreFoundation.framework/Versions/Current/CoreFoundation", Arena.global());

    private static final MethodHandle cfRelease = LINKER.downcallHandle(
            CORE_FOUNDATION.find("CFRelease").get(),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
    );

    private static final MethodHandle midiClientCreate = LINKER.downcallHandle(
            CORE_MIDI.find("MIDIClientCreate").get(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
    );

    private static final MethodHandle midiClientDispose = LINKER.downcallHandle(
            CORE_MIDI.find("MIDIClientDispose").get(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
    );

    private static final MethodHandle midiInputPortCreate = LINKER.downcallHandle(
            CORE_MIDI.find("MIDIInputPortCreate").get(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
    );

    private static final MethodHandle midiPortConnectSource = LINKER.downcallHandle(
            CORE_MIDI.find("MIDIPortConnectSource").get(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS)
    );

    private static final MethodHandle midiGetNumberOfSources = LINKER.downcallHandle(
            CORE_MIDI.find("MIDIGetNumberOfSources").get(),
            FunctionDescriptor.of(ValueLayout.JAVA_LONG)
    );

    private static final MethodHandle midiGetSource = LINKER.downcallHandle(
            CORE_MIDI.find("MIDIGetSource").get(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG)
    );

    private static final MethodHandle midiObjectGetStringProperty = LINKER.downcallHandle(
            CORE_MIDI.find("MIDIObjectGetStringProperty").get(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
    );

    private static MemorySegment kMIDIPropertyName;

    static {
        try {
            kMIDIPropertyName = CORE_MIDI.find("kMIDIPropertyName").get().reinterpret(8).get(ValueLayout.ADDRESS, 0);
        } catch (Exception e) {}
    }

    private int client = 0;
    private int port = 0;
    private int source = 0;
    private MemorySegment upcallStub;

    public CoreMidiIn() {
        try {
            MethodHandle handle = MethodHandles.lookup().findStatic(CoreMidiIn.class, "onMidiMessageStatic", 
                MethodType.methodType(void.class, CoreMidiIn.class, MemorySegment.class, MemorySegment.class, MemorySegment.class));
            handle = handle.bindTo(this);
            
            upcallStub = LINKER.upcallStub(handle, 
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS), 
                Arena.global());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void onMidiMessageStatic(CoreMidiIn instance, MemorySegment pktList, MemorySegment readProcRefCon, MemorySegment srcConnRefCon) {
        instance.onMidiMessage(pktList, readProcRefCon, srcConnRefCon);
    }

    // This method is called by the native thread via upcallStub
    private synchronized void onMidiMessage(MemorySegment pktList, MemorySegment readProcRefCon, MemorySegment srcConnRefCon) {
        if (!connected) return;
        int numPackets = pktList.get(ValueLayout.JAVA_INT, 0);
        long offset = 4; // Skip numPackets (uint32)
        
        for (int i = 0; i < numPackets; i++) {
            // MIDIPacket alignment is usually 4 bytes in the list on both Intel and ARM
            if (offset % 4 != 0) offset += (4 - (offset % 4));
            
            long timeStamp = pktList.get(ValueLayout.JAVA_LONG, offset);
            short length = pktList.get(ValueLayout.JAVA_SHORT, offset + 8);
            
            if (length > 0) {
                byte[] data = new byte[length];
                MemorySegment.copy(pktList, ValueLayout.JAVA_BYTE, offset + 10, data, 0, length);
                onIncomingMessage(CoreMidiUtils.convertTimestamp(timeStamp), data);
            }
            
            offset += 10 + length;
        }
    }

    @Override
    public Api getCurrentApi() {
        return Api.MACOS_CORE;
    }

    private void checkStatus(int status, String message) {
        if (status != 0) {
            throw new RtMidiException(message + " (OSStatus: " + status + ")", RtMidiException.Type.DRIVER_ERROR);
        }
    }

    @Override
    public int getPortCount() {
        try {
            return (int) (long) midiGetNumberOfSources.invokeExact();
        } catch (Throwable t) {
            return 0;
        }
    }

    @Override
    public String getPortName(int portNumber) {
        try (Arena arena = Arena.ofConfined()) {
            int src = (int) midiGetSource.invokeExact((long) portNumber);
            if (src == 0) return "Unknown Port";

            MemorySegment pString = arena.allocate(ValueLayout.ADDRESS);
            MemorySegment propName = kMIDIPropertyName;
            if (propName == null || propName.address() == 0) {
                propName = CoreMidiUtils.createCFString("name", arena);
            }
            int result = (int) midiObjectGetStringProperty.invokeExact(src, propName, pString);
            if (result == 0) {
                MemorySegment cfStr = pString.get(ValueLayout.ADDRESS, 0);
                String name = CoreMidiUtils.cfStringToString(cfStr);
                CoreMidiUtils.release(cfStr);
                return name;
            }
        } catch (Throwable t) {}
        return "CoreMIDI Source " + portNumber;
    }

    @Override
    public synchronized void openPort(int portNumber, String portName) {
        if (connected) closePort();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pClient = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment cfClientName = CoreMidiUtils.createCFString("RtMidi Input Client", arena);
            int status = (int) midiClientCreate.invokeExact(cfClientName, MemorySegment.NULL, MemorySegment.NULL, pClient);
            checkStatus(status, "MIDIClientCreate failed");
            client = pClient.get(ValueLayout.JAVA_INT, 0);
            CoreMidiUtils.release(cfClientName);

            MemorySegment pPort = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment cfPortName = CoreMidiUtils.createCFString(portName, arena);
            status = (int) midiInputPortCreate.invokeExact(client, cfPortName, upcallStub, MemorySegment.NULL, pPort);
            checkStatus(status, "MIDIInputPortCreate failed");
            port = pPort.get(ValueLayout.JAVA_INT, 0);
            CoreMidiUtils.release(cfPortName);

            source = (int) midiGetSource.invokeExact((long) portNumber);
            if (source == 0) throw new RtMidiException("Invalid port number", RtMidiException.Type.INVALID_PARAMETER);
            status = (int) midiPortConnectSource.invokeExact(port, source, MemorySegment.NULL);
            checkStatus(status, "MIDIPortConnectSource failed");
            connected = true;
        } catch (RtMidiException e) {
            throw e;
        } catch (Throwable t) {
            throw new RtMidiException(t.getMessage(), RtMidiException.Type.DRIVER_ERROR);
        }
    }

    @Override
    public synchronized void openVirtualPort(String portName) {
        if (connected) closePort();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pClient = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment cfClientName = CoreMidiUtils.createCFString("RtMidi Input Client", arena);
            int status = (int) midiClientCreate.invokeExact(cfClientName, MemorySegment.NULL, MemorySegment.NULL, pClient);
            checkStatus(status, "MIDIClientCreate failed");
            client = pClient.get(ValueLayout.JAVA_INT, 0);
            CoreMidiUtils.release(cfClientName);

            MemorySegment pDest = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment cfPortName = CoreMidiUtils.createCFString(portName, arena);
            MethodHandle midiDestinationCreate = LINKER.downcallHandle(
                    CORE_MIDI.find("MIDIDestinationCreate").get(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
            );
            status = (int) midiDestinationCreate.invokeExact(client, cfPortName, upcallStub, MemorySegment.NULL, pDest);
            checkStatus(status, "MIDIDestinationCreate failed");
            source = pDest.get(ValueLayout.JAVA_INT, 0);
            CoreMidiUtils.setPropertyName(source, portName);
            port = 0;
            CoreMidiUtils.release(cfPortName);
            connected = true;
        } catch (RtMidiException e) {
            throw e;
        } catch (Throwable t) {
            throw new RtMidiException(t.getMessage(), RtMidiException.Type.DRIVER_ERROR);
        }
    }

    @Override
    public synchronized void closePort() {
        if (client != 0) {
            try {
                midiClientDispose.invokeExact(client);
            } catch (Throwable t) {}
            client = 0;
            port = 0;
            source = 0;
        }
        connected = false;
    }
}
