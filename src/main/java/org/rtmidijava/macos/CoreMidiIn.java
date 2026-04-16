package org.rtmidijava.macos;

import org.rtmidijava.RtMidiIn;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

public class CoreMidiIn extends RtMidiIn {
    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup CORE_MIDI = SymbolLookup.libraryLookup("/System/Library/Frameworks/CoreMIDI.framework/Versions/Current/CoreMIDI", Arena.global());
    private static final SymbolLookup CORE_FOUNDATION = SymbolLookup.libraryLookup("/System/Library/Frameworks/CoreFoundation.framework/Versions/Current/CoreFoundation", Arena.global());

    private static final MethodHandle cfRelease = LINKER.downcallHandle(
            CORE_FOUNDATION.find("CFRelease").get(),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
    );

    private static final MethodHandle cfStringCreateWithCharacters = LINKER.downcallHandle(
            CORE_FOUNDATION.find("CFStringCreateWithCharacters").get(),
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)
    );

    private static final MethodHandle midiClientCreate = LINKER.downcallHandle(
            CORE_MIDI.find("MIDIClientCreate").get(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
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

    private Callback javaCallback;
    private int client = 0;
    private int port = 0;
    private MemorySegment upcallStub;

    public CoreMidiIn() {
        try {
            MethodHandle onMidiMessage = MethodHandles.lookup().findVirtual(CoreMidiIn.class, "onMidiMessage", 
                MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class, MemorySegment.class));
            onMidiMessage = onMidiMessage.bindTo(this);
            
            // MIDIReadProc(const MIDIPacketList *pktlist, void *readProcRefCon, void *srcConnRefCon)
            upcallStub = LINKER.upcallStub(onMidiMessage, 
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS), 
                Arena.global());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // This method is called by the native thread via upcallStub
    private void onMidiMessage(MemorySegment pktList, MemorySegment readProcRefCon, MemorySegment srcConnRefCon) {
        if (javaCallback == null) return;
        
        // Simplified parsing of MIDIPacketList
        int numPackets = pktList.get(ValueLayout.JAVA_INT, 0);
        long offset = 4;
        for (int i = 0; i < numPackets; i++) {
            long timeStamp = pktList.get(ValueLayout.JAVA_LONG, offset);
            short length = pktList.get(ValueLayout.JAVA_SHORT, offset + 8);
            byte[] data = new byte[length];
            MemorySegment.copy(pktList, ValueLayout.JAVA_BYTE, offset + 10, data, 0, length);
            
            javaCallback.onMessage(timeStamp / 1000000000.0, data); // Nanoseconds to seconds approx
            
            offset += 10 + length;
            // Alignment: packets are often padded to 4 or 8 bytes
            if (offset % 4 != 0) offset += (4 - (offset % 4));
        }
    }

    @Override
    public Api getCurrentApi() {
        return Api.MACOS_CORE;
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
        return "CoreMIDI Source " + portNumber;
    }

    private MemorySegment createCFString(String s, Arena arena) throws Throwable {
        char[] chars = s.toCharArray();
        MemorySegment mem = arena.allocateFrom(ValueLayout.JAVA_CHAR, chars);
        return (MemorySegment) cfStringCreateWithCharacters.invokeExact(MemorySegment.NULL, mem, (long) chars.length);
    }

    @Override
    public void openPort(int portNumber, String portName) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pClient = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment cfName = createCFString("RtMidi Input Client", arena);
            midiClientCreate.invokeExact(cfName, MemorySegment.NULL, MemorySegment.NULL, pClient);
            client = pClient.get(ValueLayout.JAVA_INT, 0);
            cfRelease.invokeExact(cfName);

            MemorySegment pPort = arena.allocate(ValueLayout.JAVA_INT);
            cfName = createCFString(portName, arena);
            midiInputPortCreate.invokeExact(client, cfName, upcallStub, MemorySegment.NULL, pPort);
            port = pPort.get(ValueLayout.JAVA_INT, 0);
            cfRelease.invokeExact(cfName);

            int source = (int) midiGetSource.invokeExact((long) portNumber);
            midiPortConnectSource.invokeExact(port, source, MemorySegment.NULL);
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
    }

    @Override
    public void setCallback(Callback callback) {
        this.javaCallback = callback;
    }

    @Override
    public void cancelCallback() {
        this.javaCallback = null;
    }

    @Override
    public void ignoreTypes(boolean midiSysex, boolean midiTime, boolean midiSense) {}

    @Override
    public byte[] getMessage() {
        return new byte[0];
    }
}
