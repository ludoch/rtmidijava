package org.rtmidijava.windows;

import org.rtmidijava.RtMidiIn;
import org.rtmidijava.RtMidiOut;

/**
 * Partial implementation of modern Windows MIDI Services (WMS).
 * Note: This backend is intended for Windows 10/11+ MIDI 2.0 services.
 */
public class WindowsMidiServices {
    
    public static class In extends RtMidiIn {
        @Override public Api getCurrentApi() { return Api.WINDOWS_UWP; }
        @Override public int getPortCount() { return 0; } // Requires WinRT COM integration
        @Override public String getPortName(int portNumber) { return null; }
        @Override public void openPort(int portNumber, String portName) {}
        @Override public void openVirtualPort(String portName) {}
        @Override public void closePort() { connected = false; }
    }

    public static class Out extends RtMidiOut {
        @Override public Api getCurrentApi() { return Api.WINDOWS_UWP; }
        @Override public int getPortCount() { return 0; }
        @Override public String getPortName(int portNumber) { return null; }
        @Override public void openPort(int portNumber, String portName) {}
        @Override public void openVirtualPort(String portName) {}
        @Override public void closePort() { connected = false; }
        @Override public void sendMessage(byte[] message) {}
        @Override public void sendMessage(java.lang.foreign.MemorySegment message) {}
    }
}
