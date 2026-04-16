package org.rtmidijava.macos;

import org.rtmidijava.RtMidiIn;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

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

    private static final Map<Long, CoreMidiIn> instances = new ConcurrentHashMap<>();
    private static final AtomicLong nextId = new AtomicLong(1);
    private final long instanceId;

    private int client = 0;
    private int port = 0;
    private int source = 0;
    private static final MemorySegment upcallStub;
    private static final MemorySegment notifyStub;

    // Unaligned layouts for packed structures
    private static final ValueLayout.OfLong JAVA_LONG_U = ValueLayout.JAVA_LONG.withByteAlignment(1);
    private static final ValueLayout.OfShort JAVA_SHORT_U = ValueLayout.JAVA_SHORT.withByteAlignment(1);
    private static final ValueLayout.OfInt JAVA_INT_U = ValueLayout.JAVA_INT.withByteAlignment(1);

    static {
        try {
            MethodHandle handle = MethodHandles.lookup().findStatic(CoreMidiIn.class, "onMidiMessageStatic", 
                MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class, MemorySegment.class));
            
            upcallStub = LINKER.upcallStub(handle, 
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS), 
                Arena.global());

            MethodHandle notifyHandle = MethodHandles.lookup().findStatic(CoreMidiIn.class, "onNotifyStatic", 
                MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class));
            notifyStub = LINKER.upcallStub(notifyHandle, 
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS), 
                Arena.global());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void onNotifyStatic(MemorySegment message, MemorySegment refCon) {
        System.out.println("DEBUG: onNotifyStatic triggered!");
    }

    public static void onMidiMessageStatic(MemorySegment pktList, MemorySegment readProcRefCon, MemorySegment srcConnRefCon) {
        long id = readProcRefCon.address();
        // Keep this print as it seems to ensure enough delay/barrier for CoreMIDI
        System.out.println("DEBUG: onMidiMessageStatic triggered for id " + id);
        try {
            CoreMidiIn instance = instances.get(id);
            if (instance != null) {
                instance.onMidiMessage(pktList);
            } else {
                System.out.println("CRITICAL: No instance found for id " + id);
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
        System.out.flush();
    }

    public CoreMidiIn() {
        this.instanceId = nextId.getAndIncrement();
        instances.put(instanceId, this);
        System.out.println("DEBUG: Created CoreMidiIn instance " + instanceId);
    }

    private synchronized void onMidiMessage(MemorySegment pktList) {
        if (!connected) return;
        // Reinterpret to allow reading from the address provided by CoreMIDI
        MemorySegment safePktList = pktList.reinterpret(1024);
        int numPackets = safePktList.get(JAVA_INT_U, 0);
        
        long offset = 4; // numPackets (4)
        for (int i = 0; i < numPackets; i++) {
            // Unpadded: timeStamp (8), length (2), data
            long timeStamp = safePktList.get(JAVA_LONG_U, offset);
            short length = safePktList.get(JAVA_SHORT_U, offset + 8);
            
            if (length > 0) {
                onIncomingMessage(CoreMidiUtils.convertTimestamp(timeStamp), safePktList.asSlice(offset + 10, length));
            }
            
            offset += 10 + length;
            if (offset % 4 != 0) offset += (4 - (offset % 4));
        }
        System.out.flush();
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
            MemorySegment propName = CoreMidiUtils.kMIDIPropertyName;
            if (propName == null || propName.equals(MemorySegment.NULL)) {
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
            int status = (int) midiClientCreate.invokeExact(cfClientName, notifyStub, MemorySegment.NULL, pClient);
            checkStatus(status, "MIDIClientCreate failed");
            client = pClient.get(ValueLayout.JAVA_INT, 0);
            CoreMidiUtils.release(cfClientName);

            MemorySegment pPort = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment cfPortName = CoreMidiUtils.createCFString(portName, arena);
            status = (int) midiInputPortCreate.invokeExact(client, cfPortName, upcallStub, MemorySegment.ofAddress(instanceId), pPort);
            checkStatus(status, "MIDIInputPortCreate failed");
            port = pPort.get(ValueLayout.JAVA_INT, 0);
            CoreMidiUtils.release(cfPortName);

            source = (int) midiGetSource.invokeExact((long) portNumber);
            status = (int) midiPortConnectSource.invokeExact(port, source, MemorySegment.NULL);
            checkStatus(status, "MIDIPortConnectSource failed");
            connected = true;
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
            int status = (int) midiClientCreate.invokeExact(cfClientName, notifyStub, MemorySegment.NULL, pClient);
            checkStatus(status, "MIDIClientCreate failed");
            client = pClient.get(ValueLayout.JAVA_INT, 0);
            CoreMidiUtils.release(cfClientName);

            MemorySegment pDest = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment cfPortName = CoreMidiUtils.createCFString(portName, arena);
            MethodHandle midiDestinationCreate = LINKER.downcallHandle(
                    CORE_MIDI.find("MIDIDestinationCreate").get(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
            );
            // Pass instanceId as refCon to MIDIDestinationCreate
            status = (int) midiDestinationCreate.invokeExact(client, cfPortName, upcallStub, MemorySegment.ofAddress(instanceId), pDest);
            checkStatus(status, "MIDIDestinationCreate failed");
            source = pDest.get(ValueLayout.JAVA_INT, 0);
            CoreMidiUtils.setPropertyName(source, portName);
            CoreMidiUtils.release(cfPortName);
            connected = true;
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
        super.closePort();
    }
    
    @Override
    protected void finalize() throws Throwable {
        instances.remove(instanceId);
        super.finalize();
    }
}
