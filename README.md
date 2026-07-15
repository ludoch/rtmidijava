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
- **Windows**: Modern **Windows MIDI Services (WMS/MIDI 2.0)** with automatic fallback to legacy WinMM (Full In/Out with Sysex reassembly and improved error diagnostics).
- **Linux**: ALSA and JACK (Full In/Out with cached client discovery)
- **macOS**: CoreMIDI (Full In/Out with nanosecond precision timestamps)

## Project Status

| Feature | Windows (WMS/MM) | macOS (CoreMIDI) | Linux (ALSA) | Linux (JACK) | Dummy |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **Port Enumeration** | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Message Output** | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Message Input** | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Zero-GC Path** | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Sysex Reassembly**| ✅ | ✅ | ✅ | ✅ | ✅ |
| **Virtual Ports** | ✅ (WMS) | ✅ | ✅ | ✅ | ✅ |
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

RtMidiJava is available on Maven Central. Add the dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>io.github.ludoch</groupId>
    <artifactId>rtmidijava</artifactId>
    <version>1.0.6</version>
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

### API Introspection
The static helpers ported from upstream RtMidi let you query the library and the
available backends without constructing a port:

```java
RtMidi.getVersion();                                  // e.g. "1.0.6"
RtMidi.getCompiledApi();                               // List<Api> usable on this OS
RtMidi.getApiName(RtMidi.Api.LINUX_ALSA);              // "alsa" (stable identifier)
RtMidi.getApiDisplayName(RtMidi.Api.LINUX_ALSA);       // "ALSA"
RtMidi.getCompiledApiByName("alsa");                   // RtMidi.Api.LINUX_ALSA
```

You can also set the client name (used to group ports), register an error
callback, and tune the input buffer before opening a port:

```java
RtMidiIn in = RtMidiFactory.createIn(RtMidi.Api.LINUX_ALSA, "MyApp");
in.setErrorCallback((type, message) -> log.warn("{}: {}", type, message));
in.setBufferSize(4096, 4); // honored by backends with manual buffers (Windows MM)
```

## Development & Releases

### Release Process (Maven Release Plugin)
This repository uses the [Maven Release Plugin](https://maven.apache.org/maven-release/maven-release-plugin/) along with the [Sonatype Central Publishing Plugin](https://github.com/sonatype/central-publishing-maven-plugin) to automate versioning, Git tagging, and publishing directly to Maven Central.

#### 1. Prerequisites
- Ensure your `~/.m2/settings.xml` has valid credentials for the `central` server ID (Sonatype User Token) and GPG signing credentials.
- Ensure your local `main` branch is clean and up to date with `origin/main`:
  ```bash
  git checkout main
  git pull origin main
  git status
  ```

#### 2. Perform the Release
Run the automated batch release command, passing your desired Git tag in `vX.Y.Z` format (for example, `v1.0.7` for releasing version `1.0.7`):

```bash
mvn release:prepare release:perform -B -Dtag=v1.0.7
```

This single command automatically performs the following steps:
1. **Verifies:** Runs all unit tests across available OS/MIDI backends to ensure a clean build.
2. **Releases:** Removes `-SNAPSHOT` from `pom.xml`, creates git commit `[maven-release-plugin] prepare release v1.0.7`, and creates git tag `v1.0.7`.
3. **Pushes Tag:** Pushes the new release commit and tag (`v1.0.7`) to GitHub (`origin/main`).
4. **Publishes:** Checkouts the `v1.0.7` tag in `target/checkout`, builds, signs with GPG, and uploads the artifacts (`jar`, `sources`, `javadoc`, and `.asc` signatures) to Maven Central.
5. **Next Iteration:** Bumps the `pom.xml` version to the next development snapshot (`1.0.8-SNAPSHOT`), commits, and pushes to GitHub.

#### 3. Aborting / Cleaning Up a Failed Release
If a release attempt fails partway through or needs to be aborted before completion, clean up the local repository state before trying again:

```bash
git reset --hard origin/main
mvn release:clean
rm -f pom.xml.releaseBackup release.properties .DS_Store
git tag -d v1.0.7 2>/dev/null || true
```

### Continuous Integration
This library is validated via an on-demand GitHub Action across:
- `windows-latest`
- `macos-latest`
- `ubuntu-latest` (including ALSA and JACK setup)

## Upstream Parity
For a detailed comparison against the upstream RtMidi C/C++ API — including the
implemented surface, known gaps, and platform caveats — see
[`docs/GAP_ANALYSIS.md`](docs/GAP_ANALYSIS.md).

## Credits
This is a Java implementation of the concepts and API established by the [RtMidi](https://github.com/thestk/rtmidi) C++ library.
