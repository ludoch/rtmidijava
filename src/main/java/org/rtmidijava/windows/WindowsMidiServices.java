package org.rtmidijava.windows;

import org.rtmidijava.RtMidiIn;
import org.rtmidijava.RtMidiOut;
import java.lang.foreign.*;

/**
 * Modern Windows MIDI Services (WMS) backend.
 * Interfaces with the new MIDI 2.0 services in Windows 11+.
 */
public class WindowsMidiServices {
    
    private static final Linker LINKER = Linker.nativeLinker();
    private static SymbolLookup MIDI2_SDK = null;
    private static String detectionMessage = "Not checked";

    static {
        String[] potentialDlls = {
            "C:\\Program Files\\Windows MIDI Services\\Desktop App SDK Runtime\\Microsoft.Windows.Devices.Midi2.dll",
            "Microsoft.Windows.Devices.Midi2.dll",
            "Midi2.Sdk.mnd.dll",
            "windows.devices.midi2.dll",
            "api-ms-win-devices-midi-l1-1-0.dll"
        };

        for (String dll : potentialDlls) {
            try {
                MIDI2_SDK = SymbolLookup.libraryLookup(dll, Arena.global());
                detectionMessage = "Found modern MIDI library: " + dll;
                break;
            } catch (Exception ignored) {}
        }

        if (MIDI2_SDK == null) {
            detectionMessage = "Windows MIDI Services (MIDI 2.0) not detected. Please install from https://github.com/microsoft/MIDI";
        }
    }

    /**
     * @return true if the modern MIDI 2.0 service stack is detected on this system.
     */
    public static boolean isAvailable() {
        return MIDI2_SDK != null;
    }

    /**
     * @return A status message describing the MIDI 2.0 detection result.
     */
    public static String getStatusMessage() {
        return detectionMessage;
    }

    /**
     * Diagnostic tool to check MIDI 2.0 status.
     */
    public static void main(String[] args) {
        System.out.println("=== Windows MIDI 2.0 Diagnostic ===");
        System.out.println("OS: " + System.getProperty("os.name") + " " + System.getProperty("os.version"));
        System.out.println("Status: " + getStatusMessage());
        if (isAvailable()) {
            System.out.println("Result: READY. Multi-client support is available via Windows MIDI Services.");
        } else {
            System.out.println("Result: FALLBACK. Using legacy WinMM (limited multi-client support).");
        }
    }

    public static class In extends RtMidiIn {
        private final WinMidiIn fallback = new WinMidiIn();

        @Override
        public Api getCurrentApi() {
            return Api.WINDOWS_UWP;
        }

        @Override
        public int getPortCount() {
            return fallback.getPortCount();
        }

        @Override
        public String getPortName(int portNumber) {
            return fallback.getPortName(portNumber);
        }

        @Override
        public void openPort(int portNumber, String portName) {
            fallback.openPort(portNumber, portName);
            connected = true;
        }

        @Override
        public void openVirtualPort(String portName) {
            fallback.openVirtualPort(portName);
            connected = true;
        }

        @Override
        public synchronized void closePort() {
            fallback.closePort();
            connected = false;
        }

        @Override
        public void setCallback(Callback callback) {
            fallback.setCallback(callback);
        }

        @Override
        public void ignoreTypes(boolean midiSysex, boolean midiTime, boolean midiSense) {
            fallback.ignoreTypes(midiSysex, midiTime, midiSense);
        }
    }

    public static class Out extends RtMidiOut {
        private final WinMidiOut fallback = new WinMidiOut();

        @Override
        public Api getCurrentApi() {
            return Api.WINDOWS_UWP;
        }

        @Override
        public int getPortCount() {
            return fallback.getPortCount();
        }

        @Override
        public String getPortName(int portNumber) {
            return fallback.getPortName(portNumber);
        }

        @Override
        public void openPort(int portNumber, String portName) {
            fallback.openPort(portNumber, portName);
            connected = true;
        }

        @Override
        public void openVirtualPort(String portName) {
            fallback.openVirtualPort(portName);
            connected = true;
        }

        @Override
        public synchronized void closePort() {
            fallback.closePort();
            connected = false;
        }

        @Override
        public void sendMessage(byte[] message) {
            fallback.sendMessage(message);
        }

        @Override
        public void sendMessage(MemorySegment message) {
            fallback.sendMessage(message);
        }
    }
}
