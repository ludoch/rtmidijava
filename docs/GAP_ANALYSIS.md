# RtMidiJava — Upstream Parity & Gap Analysis

This document tracks how the Java port compares to upstream
[RtMidi](https://github.com/thestk/rtmidi) (C++ `RtMidi.h` / C `rtmidi_c.h`,
v6.0.0) and what remains to be done. It is the reference for "what is missing".

## 1. Public API parity

| Upstream `RtMidi` member | Java equivalent | Status |
| :--- | :--- | :---: |
| `getVersion()` | `RtMidi.getVersion()` | ✅ |
| `getCompiledApi()` | `RtMidi.getCompiledApi()` → `List<Api>` | ✅ |
| `getApiName()` | `RtMidi.getApiName(Api)` | ✅ |
| `getApiDisplayName()` | `RtMidi.getApiDisplayName(Api)` | ✅ |
| `getCompiledApiByName()` | `RtMidi.getCompiledApiByName(String)` | ✅ |
| `getCurrentApi()` | `getCurrentApi()` | ✅ |
| `openPort(num, name)` | `openPort(int, String)` | ✅ |
| `openVirtualPort(name)` | `openVirtualPort(String)` | ✅ (not WinMM) |
| `closePort()` | `closePort()` | ✅ |
| `getPortCount()` | `getPortCount()` | ✅ |
| `getPortName(num)` | `getPortName(int)` | ✅ |
| `isPortOpen()` | `isPortOpen()` | ✅ |
| `setClientName(name)` | `setClientName(String)` | ✅ ALSA, JACK, CoreMIDI (WinMM has no client concept) |
| `setPortName(name)` | `setPortName(String)` + no-arg `openPort`/`openVirtualPort` | ✅ |
| `setErrorCallback()` | `setErrorCallback(ErrorCallback)` | ✅ wired into all backends |
| **RtMidiIn** | | |
| `setCallback()` | `setCallback` / `setFastCallback` | ✅ (FastCallback is zero-GC extra) |
| `cancelCallback()` | `cancelCallback()` | ✅ |
| `ignoreTypes()` | `ignoreTypes()` | ✅ |
| `getMessage()` | `getMessage()` / `getMessage(byte[], double[])` | ✅ |
| `setBufferSize(size, count)` | `setBufferSize(int, int)` | ✅ WinMM allocates `count` buffers of `size`; other backends are dynamic |
| ctor `queueSizeLimit` | `setQueueSizeLimit(int)` + factory overload | ✅ resizes the off-heap input ring buffer |
| **RtMidiOut** | | |
| `sendMessage()` | `sendMessage(byte[])` / `sendMessage(MemorySegment)` | ✅ |

## 2. What is missing / incomplete

### Resolved
The following gaps from earlier revisions are now implemented (see §1):
- Error callback routing — every backend now reports via `error(type, message)`,
  which invokes the registered `ErrorCallback` or throws a typed
  `RtMidiException` when none is set.
- `setClientName` threaded through ALSA, JACK, and CoreMIDI.
- `setPortName` honored, with no-arg `openPort(int)` / `openVirtualPort()`
  overloads that use the stored name.
- `setBufferSize` wired into the Windows MM `MIDIHDR` allocation.
- `setQueueSizeLimit(int)` + factory overload resize the off-heap input queue.

### Still missing / intentionally excluded
- **`Api` enum divergence.** Java uses `MACOS_CORE` / `LINUX_JACK`; upstream uses
  `MACOSX_CORE` / `UNIX_JACK`. The `WEB_MIDI_API` and `ANDROID_AMIDI` backends are
  **out of scope** for this port. (The stable short names — `core`, `jack`, … —
  match upstream, so the C-binding identifiers stay compatible.)
- **`setClientName` on Windows.** WinMM has no client-name concept; the WMS
  (MIDI 2.0) path could expose one but doesn't yet.

### Platform backends (cross-checked on Linux only)
- **CoreMIDI input caps the packet list at 1024 bytes** (`pktList.reinterpret(1024)`)
  and assumes 4-byte packet alignment. Large SysEx (>1 KB) will be truncated, and
  the alignment assumption is **unverified on Apple Silicon**. Validate the packet
  stride and reinterpret with the true list size.
- **Removed a "println as barrier" hack in CoreMIDI.** `onMidiMessageStatic` /
  `sendMessage` previously printed (and `flush`ed) on every message, with a comment
  claiming the print "ensures enough delay/barrier." A `println` is not a memory
  barrier — if it was masking a visibility/ordering race (e.g. the `instances` map
  not yet populated when the read callback first fires), that race may now surface
  on macOS. **Needs hardware testing.**
- **CoreMIDI send now throws on non-zero `OSStatus`** (routed through `error()`),
  where it previously only printed. Confirm no benign non-zero status paths exist.
- **WinMM has no virtual ports** (expected — upstream is the same). The README's
  virtual-port support for Windows depends on the WMS/MIDI-2.0 path; confirm
  `WindowsMidiServices` actually creates app-to-app ports.
- **macOS/Windows `invokeExact` fixes are untested here.** The return-type
  capture fixes (see below) were verified end-to-end on ALSA only; run CI on
  `macos-latest` / `windows-latest` to confirm CoreMIDI and WinMM now work.

### Tooling / tests
- No unit tests exercise JACK, CoreMIDI, or WinMM (they self-skip off-platform).
  CI runners lack a real MIDI sequencer, which historically **masked completely
  broken backends** — see the note below.
- No `getMessage` polling test, no error-callback-from-backend test, no Javadoc
  jar verification beyond the build.

## 3. Reliability fixes already applied

These were latent runtime bugs (not API gaps) discovered while bringing up the
ALSA backend on a real sequencer:

- **`invokeExact` return-type mismatch (all backends).** Native handles returning
  `int`/`long` were invoked as bare `void` statements; `invokeExact` throws
  `WrongMethodTypeException` at runtime. All non-`void` calls now capture the
  result. This is why ALSA/JACK never actually worked on hardware.
- **ALSA output delivery.** Events now set `queue = SND_SEQ_QUEUE_DIRECT`
  (`snd_seq_ev_set_direct` equivalent); previously they landed on the unstarted
  queue 0 and were never delivered.
- **ALSA input decoding.** Added Poly Aftertouch, Program Change, Channel
  Pressure, and Pitch Bend (previously silently dropped).
- **Real-time priority.** `ThreadUtils.makeRealTime()` discarded the native
  return on Windows/macOS/Linux, so RT scheduling was never applied — fixed.
- **ALSA sequencer-handle leak.** Port enumeration discarded `snd_seq_close`'s
  result, so the handle was never closed; fixed.

> **CI caveat:** `ubuntu-latest` / `macos-latest` GitHub runners have no MIDI
> sequencer, so `snd_seq_open` fails and the send/receive test self-skips. This
> hides backend bugs. Prefer a runner with a software loopback (e.g. ALSA
> `snd-virmidi`) for meaningful integration coverage.
