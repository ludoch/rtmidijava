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

## Windows MIDI 2.0 / MIDI Services Setup

To enable modern MIDI features on Windows, including **Native Virtual Ports** (App-to-App MIDI) and **MIDI 2.0 (UMP)** support, you must install the modern Windows MIDI Services stack.

### 1. Prerequisites
- **OS**: Windows 11 (22H2 or later) or Windows 10 (limited support).
- **Service**: The `MidiSrv.exe` must be running (installed via the runtime).

### 2. Installation
1.  Go to the [Microsoft MIDI GitHub Releases](https://github.com/microsoft/MIDI/releases).
2.  Download and install the latest **Windows MIDI Services Runtime** (e.g., `WindowsMidiServices-x64-vX.X.X.msi`).
3.  (Optional) Install the **MIDI Console** for device management.

### 3. Verification
You can verify if your system is ready for MIDI 2.0 by running the included diagnostic tool:

```bash
mvn exec:java -Dexec.mainClass="org.rtmidijava.windows.WindowsMidiServices"
```

If detected, the library will automatically unlock `openVirtualPort()` support. If not detected, the library falls back to the legacy **WinMM** backend (which requires third-party drivers like loopMIDI for virtual ports).

## Installation (Maven)

To use RtMidiJava in your project, add the GitHub Packages repository to your `pom.xml`:

```xml
<repositories>
    <repository>
        <id>github</id>
        <url>https://maven.pkg.github.com/ludoch/rtmidijava</url>
    </repository>
</repositories>
```

Then add the dependency:

```xml
<dependency>
    <groupId>org.rtmidijava</groupId>
    <artifactId>rtmidijava</artifactId>
    <version>1.0.0</version>
</dependency>
```

### Developer Note: FFM Access
Since this library uses the Foreign Function & Memory API, you must run your application with the following JVM flag:
`--enable-native-access=ALL-UNNAMED`

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

## Development & Releases

### Bumping the Version
To release a new version:
1. Update the version in `pom.xml` (e.g., from `1.0.1-SNAPSHOT` to `1.0.1`).
2. Run `mvn deploy -DskipTests` to push the artifact to GitHub Packages.
3. Update the version to the next snapshot (e.g., `1.0.2-SNAPSHOT`).

### Continuous Integration
This library is validated via an on-demand GitHub Action across:
- `windows-latest`
- `macos-latest`
- `ubuntu-latest` (including ALSA and JACK setup)

## Credits
This is a Java implementation of the concepts and API established by the [RtMidi](https://github.com/thestk/rtmidi) C++ library.
