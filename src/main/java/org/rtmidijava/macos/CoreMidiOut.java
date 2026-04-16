package org.rtmidijava.macos;

import org.rtmidijava.RtMidiException;
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

    // CoreMIDI Handles
    private static final MethodHandle midiClientCreate = LINKER.downcallHandle(
            CORE_MIDI.find("MIDIClientCreate").get(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
    );

    private static final MethodHandle midiClientDispose = LINKER.downcallHandle(
            CORE_MIDI.find("MIDIClientDispose").get(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT)
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
            kMIDIPropertyName = CORE_MIDI.find("kMIDIPropertyName").get().reinterpret(8).get(ValueLayout.ADDRESS, 0);
        } catch (Exception e) {}
    }

    private int client = 0;
    private int port = 0;
    private int destination = 0;

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
            return (int) (long) midiGetNumberOfDestinations.invokeExact();
        } catch (Throwable t) {
            return 0;
        }
    }

    @Override
    public String getPortName(int portNumber) {
        try (Arena arena = Arena.ofConfined()) {
            int dest = (int) midiGetDestination.invokeExact((long) portNumber);
            if (dest == 0) return "Unknown Port";
            
            MemorySegment pString = arena.allocate(ValueLayout.ADDRESS);
            MemorySegment propName = kMIDIPropertyName;
            if (propName == null || propName.address() == 0) {
                propName = CoreMidiUtils.createCFString("name", arena);
            }
            int result = (int) midiObjectGetStringProperty.invokeExact(dest, propName, pString);
            if (result == 0) {
                MemorySegment cfStr = pString.get(ValueLayout.ADDRESS, 0);
                String name = CoreMidiUtils.cfStringToString(cfStr);
                CoreMidiUtils.release(cfStr);
                return name;
            }
        } catch (Throwable t) {}
        return "CoreMIDI Port " + portNumber;
    }

    @Override
    public synchronized void openPort(int portNumber, String portName) {
        if (connected) closePort();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pClient = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment cfClientName = CoreMidiUtils.createCFString("RtMidi Client", arena);
            int status = (int) midiClientCreate.invokeExact(cfClientName, MemorySegment.NULL, MemorySegment.NULL, pClient);
            checkStatus(status, "MIDIClientCreate failed");
            client = pClient.get(ValueLayout.JAVA_INT, 0);
            CoreMidiUtils.release(cfClientName);

            MemorySegment pPort = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment cfPortName = CoreMidiUtils.createCFString(portName, arena);
            status = (int) midiOutputPortCreate.invokeExact(client, cfPortName, pPort);
            checkStatus(status, "MIDIOutputPortCreate failed");
            port = pPort.get(ValueLayout.JAVA_INT, 0);
            CoreMidiUtils.release(cfPortName);

            destination = (int) midiGetDestination.invokeExact((long) portNumber);
            if (destination == 0) throw new RtMidiException("Invalid port number", RtMidiException.Type.INVALID_PARAMETER);
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
             MemorySegment cfClientName = CoreMidiUtils.createCFString("RtMidi Client", arena);
             int status = (int) midiClientCreate.invokeExact(cfClientName, MemorySegment.NULL, MemorySegment.NULL, pClient);
             checkStatus(status, "MIDIClientCreate failed");
             client = pClient.get(ValueLayout.JAVA_INT, 0);
             CoreMidiUtils.release(cfClientName);

             MemorySegment pSrc = arena.allocate(ValueLayout.JAVA_INT);
             MemorySegment cfPortName = CoreMidiUtils.createCFString(portName, arena);
             MethodHandle midiSourceCreate = LINKER.downcallHandle(
                     CORE_MIDI.find("MIDISourceCreate").get(),
                     FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
             );
             status = (int) midiSourceCreate.invokeExact(client, cfPortName, pSrc);
             checkStatus(status, "MIDISourceCreate failed");
             destination = pSrc.get(ValueLayout.JAVA_INT, 0);
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
            destination = 0;
        }
        connected = false;
    }

    @Override
    public synchronized void sendMessage(byte[] message) {
        if (!connected) return;
        try (Arena arena = Arena.ofConfined()) {
            int packetSize = 8 + 2 + message.length;
            MemorySegment packetList = arena.allocate(8 + packetSize);
            packetList.set(ValueLayout.JAVA_INT, 0, 1);
            packetList.set(ValueLayout.JAVA_LONG, 8, 0L);
            packetList.set(ValueLayout.JAVA_SHORT, 16, (short) message.length);
            for (int i = 0; i < message.length; i++) {
                packetList.set(ValueLayout.JAVA_BYTE, 18 + i, message[i]);
            }
            
            if (port != 0) {
                midiSend.invokeExact(port, destination, packetList);
            } else {
                MethodHandle midiReceived = LINKER.downcallHandle(
                        CORE_MIDI.find("MIDIReceived").get(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS)
                );
                midiReceived.invokeExact(destination, packetList);
            }
        } catch (Throwable t) {}
    }
}
