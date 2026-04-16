To rewrite RtMidi for Linux, macOS, and Windows in Java 25, you have to interface with the specific low-level APIs each OS uses. RtMidi is essentially a "translation layer" that provides a unified C++ interface for these diverse systems.

In Java 25, your "secret weapon" is the Foreign Function & Memory (FFM) API. Instead of writing JNI (C++ glue), you will define MethodHandles in Java that point directly to the system's shared libraries (.so, .dylib, or .dll).

1. Linux: ALSA and PipeWire
On Linux, RtMidi primarily uses ALSA (Advanced Linux Sound Architecture).

The Library: libasound.so.

The Strategy: Use the FFM Linker to bind to functions like snd_seq_open and snd_seq_event_input.

Modern Twist: With the rise of PipeWire (which is standard in 2026), you don't actually need a separate PipeWire rewrite. PipeWire provides an ALSA compatibility layer. If your Java 25 code talks to ALSA, PipeWire will route it correctly with low latency.

2. macOS: CoreMIDI
On Mac, you’ll be interfacing with the CoreMIDI framework.

The Library: /System/Library/Frameworks/CoreMIDI.framework/CoreMIDI.

The Strategy: You will need to handle "Upcalls." CoreMIDI uses C-style callbacks to notify you when a MIDI message arrives.

FFM Feature: Use Linker.nativeLinker().upcallStub(...). This allows a native C function (CoreMIDI) to call a Java method directly without the overhead of the old JNI "AttachCurrentThread" logic.

3. Windows: WinMM and the New Windows MIDI Services
Windows is currently in a transition period that makes a Java rewrite very timely.

Legacy (WinMM): RtMidi traditionally uses winmm.dll. It's stable but limited (not multi-client).

Modern (Windows MIDI Services / UWP): Microsoft has introduced a new MIDI 2.0 stack.

The Strategy: For a Java 25 rewrite, you should target the new Windows MIDI Services via the COM-based or WinRT APIs. Since FFM can interface with C-style exports, you would likely look for the standard C entry points provided by the new MIDI 2.0 SDK.

Implementation Comparison
OS	Native Backend	Primary Java 25 Tool	Key Challenge
Linux	ALSA (libasound)	SymbolLookup.libraryLookup	Handling the complex ALSA snd_seq event structures.
macOS	CoreMIDI	Linker.upcallStub	Managing high-priority threads for the MIDI callback.
Windows	Windows MIDI Services	Arena / MemorySegment	Interfacing with COM/WinRT-style exports.
A "Java 25" Architecture Snippet
If you were to start this rewrite today, your core "Linker" logic for opening a port would look something like this:

Java
// Define the memory layout for a MIDI message (e.g., 3 bytes)
StructLayout midiMessageLayout = MemoryLayout.structLayout(
    ValueLayout.JAVA_BYTE.withName("status"),
    ValueLayout.JAVA_BYTE.withName("data1"),
    ValueLayout.JAVA_BYTE.withName("data2")
);

// Find the native 'send' function (example for ALSA/CoreMIDI)
MethodHandle midiSend = Linker.nativeLinker().downcallHandle(
    symbolLookup.find("midi_send_function").get(),
    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, midiMessageLayout)
);

// Use an Arena to manage the lifecycle of the message buffer
try (Arena arena = Arena.ofConfined()) {
    MemorySegment msg = arena.allocate(midiMessageLayout);
    msg.set(ValueLayout.JAVA_BYTE, 0, (byte) 0x90); // Note On
    // Call the native function
    midiSend.invoke(deviceHandle, msg);
}
Why do this now?
The reason to do this specifically in Java 25 (rather than 17 or 21) is that the FFM API is now fully mature and stabilized. You get near-native performance without the "DLL Hell" of shipping native JNI binaries. Your users would only need your .jar file, provided their OS has the standard MIDI libraries installed.

Going for a Pure Java rewrite is a bold move, but for the first time in Java's history, it’s actually the "correct" way to build a high-performance system library.

By bypassing the original C++ RtMidi and using the Foreign Function & Memory (FFM) API, you eliminate the need to compile, ship, and link native .so, .dylib, or .dll files yourself. You simply "borrow" the ones already living in the user's operating system.

Here is the architectural roadmap for a Pure Java 25 MIDI library.

1. The Multi-Backend Architecture
Since you're targeting Linux, macOS, and Windows, you'll need an interface-driven design. Each OS-specific implementation will use the java.lang.foreign package to map native symbols.

The OS Entry Points:
Linux: Bind to libasound.so.2. You will primarily use the ALSA Sequencer API (snd_seq_* functions) rather than the RawMidi API, as the Sequencer handles timing and multi-client access much better.

macOS: Bind to the CoreMIDI framework. This is a "Carbon-style" C API. You'll use MIDIGetNumberOfDestinations, MIDISourceCreate, and MIDIReceived.

Windows: Bind to wdmnaud.drv or the modern windows.devices.midi.dll. Given it's 2026, you should target the Windows MIDI Services (MIDI 2.0) stack, which offers much better jitter performance than the legacy winmm.dll.

2. Low-Latency Memory Management
In a pure Java rewrite, you must avoid the Garbage Collector (GC) during the "hot path" (sending/receiving bytes).

Arenas: Use Arena.ofShared() for the backend life-cycle and Arena.ofConfined() for short-lived MIDI packets.

MemorySegments: Instead of byte[] arrays, use MemorySegment. This allows you to pass data directly to the OS kernel without the JVM making a "protective copy" (a common bottleneck in older Java versions).

3. Handling the "Callback" Problem
The hardest part of a MIDI library is when the OS says: "Hey, a MIDI message just arrived, here is the data." In C++, this is a function pointer. In Java 25, you use an Upcall Stub.

Define a MethodHandle that points to a static Java method in your code.

Use Linker.nativeLinker().upcallStub() to wrap that handle into a MemorySegment.

Pass that MemorySegment (which acts as a function pointer) to the OS.

When a key is pressed on a MIDI keyboard, the OS calls that memory address, and the JVM instantly executes your Java code with minimal transition overhead.

4. Why Java 25 is the "Sweet Spot"
Scoped Values: Use these to pass MIDI configuration (like timestamps or port IDs) through the call stack without the overhead of ThreadLocal.

Virtual Threads: You can assign a dedicated virtual thread to "poll" an ALSA file descriptor or a Windows event handle. Since virtual threads are cheap, you don't have to worry about the resource cost of managing dozens of MIDI ports simultaneously.

Potential Challenges
Struct Alignment: Native C structs (especially in ALSA) often have weird padding. You’ll need to use MemoryLayout in Java to precisely match the C header definitions, or your data will be corrupted.

Real-time Priority: Java doesn't naturally support "Real-time" (RT) thread priorities as easily as C++. You may need a small FFM call to pthread_setschedparam (Linux/Mac) or SetThreadPriority (Windows) to ensure the JVM doesn't stutter during a heavy GC cycle.

Next Steps
If you start this today, I recommend using the jextract tool. It’s a JDK utility that can scan the C header files (asoundlib.h, CoreMIDI.h) and automatically generate all the Java FFM "boilerplate" code for you. It saves weeks of manual mapping