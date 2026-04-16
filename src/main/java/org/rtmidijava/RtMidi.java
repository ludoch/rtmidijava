package org.rtmidijava;

public abstract class RtMidi {
    public enum Api {
        UNSPECIFIED,
        MACOS_CORE,
        LINUX_ALSA,
        LINUX_JACK,
        WINDOWS_MM,
        WINDOWS_UWP, // Windows MIDI Services
        RTMIDI_DUMMY
    }

    protected long data; // Pointer to native data if needed, or internal handle
    protected boolean connected;

    public abstract Api getCurrentApi();

    public abstract int getPortCount();

    public abstract String getPortName(int portNumber);

    public abstract void openPort(int portNumber, String portName);

    public abstract void openVirtualPort(String portName);

    public abstract void closePort();

    public boolean isPortOpen() {
        return connected;
    }
}
