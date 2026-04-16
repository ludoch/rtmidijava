package org.rtmidijava.macos;

import org.rtmidijava.RtMidiOut;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

public class CoreMidiOut extends RtMidiOut {
    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup CORE_MIDI = SymbolLookup.libraryLookup("/System/Library/Frameworks/CoreMIDI.framework/Versions/Current/CoreMIDI", Arena.global());
    private static final SymbolLookup CORE_FOUNDATION = SymbolLookup.libraryLookup("/System/Library/Frameworks/CoreFoundation.framework/Versions/Current/CoreFoundation", Arena.global());

    // CoreFoundation Handles
    private static final MethodHandle cfRelease = LINKER.downcallHandle(
            CORE_FOUNDATION.find("CFRelease").get(),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
    );

    private static final MethodHandle cfStringCreateWithCharacters = LINKER.downcallHandle(
            CORE_FOUNDATION.find("CFStringCreateWithCharacters").get(),
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)
    );

    // CoreMIDI Handles
    private static final MethodHandle midiClientCreate = LINKER.downcallHandle(
            CORE_MIDI.find("MIDIClientCreate").get(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
    );

    private static final MethodHandle midiOutputPortCreate = LINKER.downcallHandle(
            CORE_MIDI.find("MIDIOutputPortCreate").get(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
    );

    private static final MethodHandle midiGetNumberOfDestinations = LINKER.downcallHandle(
            CORE_MIDI.find("MIDIGetNumberOfDestinations").get(),
            FunctionDescriptor.of(ValueLayout.JAVA_LONG)
    );

    private static final MethodHandle midiGetDestination = LINKER.downcallHandle(
            CORE_MIDI.find("MIDIGetDestination").get(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG)
    );

    private static final MethodHandle midiSend = LINKER.downcallHandle(
            CORE_MIDI.find("MIDISend").get(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS)
    );

    private static final MethodHandle midiObjectGetStringProperty = LINKER.downcallHandle(
            CORE_MIDI.find("MIDIObjectGetStringProperty").get(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
    );

    private static MemorySegment kMIDIPropertyName;

    static {
        try {
            kMIDIPropertyName = CORE_MIDI.find("kMIDIPropertyName").get().get(ValueLayout.ADDRESS, 0);
        } catch (Exception e) {
            // If symbol lookup fails, we'll use placeholder
        }
    }

    private int client = 0;
    private int port = 0;
    private int destination = 0;

    @Override
    public Api getCurrentApi() {
        return Api.MACOS_CORE;
    }

    @Override
    public int getPortCount() {
        try {
            return (int) (long) midiGetNumberOfDestinations.invokeExact();
        } catch (Throwable t) {
            return 0;
        }
    }

    private MemorySegment createCFString(String s, Arena arena) throws Throwable {
        char[] chars = s.toCharArray();
        MemorySegment mem = arena.allocateFrom(ValueLayout.JAVA_CHAR, chars);
        return (MemorySegment) cfStringCreateWithCharacters.invokeExact(MemorySegment.NULL, mem, (long) chars.length);
    }

    @Override
    public String getPortName(int portNumber) {
        try (Arena arena = Arena.ofConfined()) {
            int dest = (int) midiGetDestination.invokeExact((long) portNumber);
            MemorySegment pString = arena.allocate(ValueLayout.ADDRESS);
            int result = (int) midiObjectGetStringProperty.invokeExact(dest, kMIDIPropertyName, pString);
            if (result == 0) {
                MemorySegment cfStr = pString.get(ValueLayout.ADDRESS, 0);
                // Extracting characters from CFString is complex, using placeholder for now
                cfRelease.invokeExact(cfStr);
                return "CoreMIDI Port " + portNumber;
            }
        } catch (Throwable t) {}
        return "CoreMIDI Port " + portNumber;
    }

    @Override
    public void openPort(int portNumber, String portName) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pClient = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment cfName = createCFString("RtMidi Client", arena);
            midiClientCreate.invokeExact(cfName, MemorySegment.NULL, MemorySegment.NULL, pClient);
            client = pClient.get(ValueLayout.JAVA_INT, 0);
            cfRelease.invokeExact(cfName);

            MemorySegment pPort = arena.allocate(ValueLayout.JAVA_INT);
            cfName = createCFString(portName, arena);
            midiOutputPortCreate.invokeExact(client, cfName, pPort);
            port = pPort.get(ValueLayout.JAVA_INT, 0);
            cfRelease.invokeExact(cfName);

            destination = (int) midiGetDestination.invokeExact((long) portNumber);
            connected = true;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    @Override
    public void openVirtualPort(String portName) {
        throw new UnsupportedOperationException("Virtual ports implementation pending");
    }

    @Override
    public void closePort() {
        connected = false;
        // Should also close client/port
    }

    @Override
    public void sendMessage(byte[] message) {
        if (!connected) return;
        try (Arena arena = Arena.ofConfined()) {
            // MIDIPacketList: numPackets(4), [timeStamp(8), length(2), data(N)]
            int packetSize = 8 + 2 + message.length;
            MemorySegment packetList = arena.allocate(4 + packetSize);
            packetList.set(ValueLayout.JAVA_INT, 0, 1);
            packetList.set(ValueLayout.JAVA_LONG, 4, 0L); // 0 = now
            packetList.set(ValueLayout.JAVA_SHORT, 12, (short) message.length);
            for (int i = 0; i < message.length; i++) {
                packetList.set(ValueLayout.JAVA_BYTE, 14 + i, message[i]);
            }
            midiSend.invokeExact(port, destination, packetList);
        } catch (Throwable t) {}
    }
}
