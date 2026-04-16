package org.rtmidijava;

public class RtMidiFactory {
    public static RtMidiIn createDefaultIn() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            return new org.rtmidijava.windows.WinMidiIn();
        } else if (os.contains("mac")) {
            return new org.rtmidijava.macos.CoreMidiIn();
        } else if (os.contains("nix") || os.contains("nux")) {
            return new org.rtmidijava.linux.AlsaMidiIn();
        }
        throw new UnsupportedOperationException("Unsupported OS: " + os);
    }

    public static RtMidiOut createDefaultOut() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            return new org.rtmidijava.windows.WinMidiOut();
        } else if (os.contains("mac")) {
            return new org.rtmidijava.macos.CoreMidiOut();
        } else if (os.contains("nix") || os.contains("nux")) {
            return new org.rtmidijava.linux.AlsaMidiOut();
        }
        throw new UnsupportedOperationException("Unsupported OS: " + os);
    }
}
