package org.rtmidijava;

/**
 * Base class for RtMidiJava, a pure Java 25 implementation of the RtMidi API.
 * Uses Foreign Function & Memory (FFM) API to interface with OS-native MIDI stacks.
 */
public abstract class RtMidi {
    /**
     * Enumeration of supported native MIDI APIs.
     */
    public enum Api {
        UNSPECIFIED,
        MACOS_CORE,
        LINUX_ALSA,
        LINUX_JACK,
        WINDOWS_MM,
        WINDOWS_UWP, // Windows MIDI Services
        RTMIDI_DUMMY
    }

    protected long data;
    protected boolean connected;

    /**
     * @return the Api being used by this instance.
     */
    public abstract Api getCurrentApi();

    /**
     * @return the number of available MIDI ports.
     */
    public abstract int getPortCount();

    /**
     * @param portNumber the index of the port (0 to getPortCount()-1)
     * @return the human-readable name of the port.
     */
    public abstract String getPortName(int portNumber);

    /**
     * Opens a connection to a specific MIDI port.
     * @param portNumber the index of the port to open.
     * @param portName the name to assign to our local connection port.
     */
    public abstract void openPort(int portNumber, String portName);

    /**
     * Creates a virtual MIDI port that other applications can connect to.
     * Note: Virtual ports are not supported on Windows WinMM.
     * @param portName the name of the virtual port.
     */
    public abstract void openVirtualPort(String portName);

    /**
     * Closes the current port connection.
     */
    public abstract void closePort();

    /**
     * @return true if a port is currently open.
     */
    public boolean isPortOpen() {
        return connected;
    }
}
