package org.rtmidijava;

import org.rtmidijava.linux.AlsaMidiIn;
import org.rtmidijava.linux.AlsaMidiOut;
import org.rtmidijava.linux.JackMidiIn;
import org.rtmidijava.linux.JackMidiOut;
import org.rtmidijava.macos.CoreMidiIn;
import org.rtmidijava.macos.CoreMidiOut;
import org.rtmidijava.windows.WinMidiIn;
import org.rtmidijava.windows.WinMidiOut;

public class RtMidiFactory {
    
    public static RtMidiIn createIn(RtMidi.Api api) {
        return switch (api) {
            case LINUX_ALSA -> new AlsaMidiIn();
            case LINUX_JACK -> new JackMidiIn();
            case MACOS_CORE -> new CoreMidiIn();
            case WINDOWS_MM -> new WinMidiIn();
            default -> createDefaultIn();
        };
    }

    public static RtMidiOut createOut(RtMidi.Api api) {
        return switch (api) {
            case LINUX_ALSA -> new AlsaMidiOut();
            case LINUX_JACK -> new JackMidiOut();
            case MACOS_CORE -> new CoreMidiOut();
            case WINDOWS_MM -> new WinMidiOut();
            default -> createDefaultOut();
        };
    }

    public static RtMidiIn createDefaultIn() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            return new WinMidiIn();
        } else if (os.contains("mac")) {
            return new CoreMidiIn();
        } else if (os.contains("nix") || os.contains("nux")) {
            // Default to ALSA for now, common on Linux
            return new AlsaMidiIn();
        }
        throw new UnsupportedOperationException("Unsupported OS: " + os);
    }

    public static RtMidiOut createDefaultOut() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            return new WinMidiOut();
        } else if (os.contains("mac")) {
            return new CoreMidiOut();
        } else if (os.contains("nix") || os.contains("nux")) {
            return new AlsaMidiOut();
        }
        throw new UnsupportedOperationException("Unsupported OS: " + os);
    }
}
