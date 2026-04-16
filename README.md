# RtMidiJava

A Pure Java 25 port of RtMidi using the Foreign Function & Memory (FFM) API.

## Goal
To provide a low-latency, zero-dependency (no native JNI binaries) MIDI library for Java, interfacing directly with OS-native MIDI APIs.

## Supported Backends
- **Windows**: WinMM (Functional discovery and output)
- **Linux**: ALSA (Skeleton provided)
- **macOS**: CoreMIDI (Skeleton provided)

## Requirements
- Java 25+
- Maven

## How it works
Instead of JNI, this library uses `java.lang.foreign` to:
1. Load native libraries (`winmm.dll`, `libasound.so`, `CoreMIDI.framework`).
2. Bind to native functions using `MethodHandle`s.
3. Manage native memory using `Arena` and `MemorySegment`.
4. (Planned) Handle callbacks using `upcallStub`.

## Project Status

| Feature | Windows (WinMM) | macOS (CoreMIDI) | Linux (ALSA) |
| :--- | :---: | :---: | :---: |
| **Port Enumeration** | ✅ | ✅ (Generic Name) | 🚧 (Skeleton) |
| **Message Output** | ✅ | ✅ | ✅ |
| **Message Input** | ✅ (Callbacks) | ✅ (Upcalls) | 🚧 (Polling) |
| **Port Names** | ✅ | 🚧 (Placeholder) | 🚧 (Skeleton) |
| **Sysex Support** | 🚧 | 🚧 | 🚧 |
| **Virtual Ports** | ❌ (N/A) | 🚧 | 🚧 |

## Implementation Progress
- [x] Core Multi-Backend Architecture
- [x] Windows WinMM (In/Out/Enumeration)
- [x] macOS CoreMIDI (Out/In-Upcall-Skeleton)
- [x] Linux ALSA (Out-Skeleton/In-Thread-Skeleton)
- [ ] Windows Sysex Support
- [ ] macOS CFString Real Name Extraction
- [ ] Linux ALSA Port Enumeration Loop
- [ ] Linux ALSA Event Parsing
