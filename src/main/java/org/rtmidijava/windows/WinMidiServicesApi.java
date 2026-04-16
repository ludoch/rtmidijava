package org.rtmidijava.windows;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

/**
 * Skeleton for modern Windows MIDI Services (MIDI 2.0 / UWP).
 * This typically interfaces with api-ms-win-devices-midi-l1-1-0.dll
 */
public class WinMidiServicesApi {
    public static final Linker LINKER = Linker.nativeLinker();
    // In 2026, this is the standard DLL for modern MIDI
    public static final SymbolLookup MIDI_UWP = SymbolLookup.libraryLookup("windows.devices.midi.dll", Arena.global());

    // Skeleton for future expansion
    // midi_uwp_open_port, midi_uwp_send_message, etc.
}
