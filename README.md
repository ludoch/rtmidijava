# RtMidiJava

A Pure Java 25 port of [RtMidi](https://github.com/thestk/rtmidi) using the Foreign Function & Memory (FFM) API.

## Goal
To provide a **low-latency, zero-dependency, zero-GC** MIDI library for Java, interfacing directly with OS-native MIDI APIs without JNI binaries.

## Key Features
- **Zero-GC Architecture**: High-performance "Fast Path" using `MemorySegment` and `FastCallback` to eliminate Java heap allocations during MIDI processing.
- **Pro-Audio Grade**:
    - **Real-Time Priority**: Automated thread scheduling (Mach Time-Constraint on macOS, Time-Critical on Windows, SCHED_RR on Linux).
    - **Shutdown Pipe**: ALSA implementation uses a wakeup pipe to ensure worker threads never get stuck in native blocking calls.
    - **Sysex Reassembly**: Automatic stateful buffering for large Sysex messages on Windows and Linux.
- **Pure Java 25**: No native libraries to compile or ship. Uses the modern FFM API (`java.lang.foreign`).
- **Thread Safe**: All backends hardened with synchronization for concurrent port management and message sending.

## Supported Backends
- **Windows**: WinMM (Full In/Out with Sysex reassembly)
- **Linux**: ALSA and JACK (Full In/Out with cached client discovery)
- **macOS**: CoreMIDI (Full In/Out with nanosecond precision timestamps)

## Project Status

| Feature | Windows (WinMM) | macOS (CoreMIDI) | Linux (ALSA) | Linux (JACK) | Dummy |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **Port Enumeration** | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Message Output** | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Message Input** | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Zero-GC Path** | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Sysex Reassembly**| ✅ | ✅ | ✅ | ✅ | ✅ |
| **Virtual Ports** | ❌ (N/A) | ✅ | ✅ | ✅ | ✅ |
| **Real-Time Priority**| ✅ | ✅ | ✅ | ✅ | ✅ |

## Requirements
- Java 25+
- Maven

## Example Usage

### Standard Callback (Easy)
```java
RtMidiIn midiIn = RtMidiFactory.createDefaultIn();
midiIn.openPort(0, "MyMonitor");
midiIn.setCallback((timeStamp, message) -> {
    System.out.println("Received " + message.length + " bytes");
});
```

### High-Performance Zero-GC (Pro)
```java
midiIn.setFastCallback((timeStamp, segment) -> {
    // Process MIDI data directly in native memory
    byte status = segment.get(ValueLayout.JAVA_BYTE, 0);
});
```

See `src/main/java/org/rtmidijava/examples/` for complete examples of `MidiMonitor` and `MidiSender`.

## Continuous Integration
This library is validated via an on-demand GitHub Action across:
- `windows-latest`
- `macos-latest`
- `ubuntu-latest` (including ALSA and JACK setup)

## Credits
This is a Java implementation of the concepts and API established by the [RtMidi](https://github.com/thestk/rtmidi) C++ library.
