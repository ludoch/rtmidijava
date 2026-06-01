package org.rtmidijava;

import org.rtmidijava.linux.AlsaMidiIn;
import org.rtmidijava.linux.AlsaMidiOut;
import org.rtmidijava.linux.JackMidiIn;
import org.rtmidijava.linux.JackMidiOut;
import org.rtmidijava.macos.CoreMidiIn;
import org.rtmidijava.macos.CoreMidiOut;
import org.rtmidijava.windows.WinMidiIn;
import org.rtmidijava.windows.WinMidiOut;
import org.rtmidijava.windows.WindowsMidiServices;

/**
 * Factory class for creating RtMidi instances.
 * Automatically detects the operating system and selects the appropriate backend.
 */
public class RtMidiFactory {
    
    /**
     * Creates a MIDI input instance for a specific API.
     * @param api the API to use (e.g. LINUX_ALSA, MACOS_CORE).
     * @return a new RtMidiIn instance.
     */
    public static RtMidiIn createIn(RtMidi.Api api) {
        return switch (api) {
            case LINUX_ALSA -> new AlsaMidiIn();
            case LINUX_JACK -> new JackMidiIn();
            case MACOS_CORE -> new CoreMidiIn();
            case WINDOWS_MM -> new WinMidiIn();
            case WINDOWS_UWP -> new org.rtmidijava.windows.WindowsMidiServices.In();
            case RTMIDI_DUMMY -> new org.rtmidijava.dummy.DummyMidiIn();
            default -> createDefaultIn();
        };
    }

    /**
     * Creates a MIDI output instance for a specific API.
     * @param api the API to use.
     * @return a new RtMidiOut instance.
     */
    public static RtMidiOut createOut(RtMidi.Api api) {
        return switch (api) {
            case LINUX_ALSA -> new AlsaMidiOut();
            case LINUX_JACK -> new JackMidiOut();
            case MACOS_CORE -> new CoreMidiOut();
            case WINDOWS_MM -> new WinMidiOut();
            case WINDOWS_UWP -> new org.rtmidijava.windows.WindowsMidiServices.Out();
            case RTMIDI_DUMMY -> new org.rtmidijava.dummy.DummyMidiOut();
            default -> createDefaultOut();
        };
    }

    /**
     * Creates a MIDI input instance for a specific API with the given client name.
     * @param api the API to use.
     * @param clientName the client name used to group created ports.
     * @return a new RtMidiIn instance.
     */
    public static RtMidiIn createIn(RtMidi.Api api, String clientName) {
        RtMidiIn in = createIn(api);
        in.setClientName(clientName);
        return in;
    }

    /**
     * Creates a MIDI input instance with the given client name and input queue limit.
     * @param api the API to use.
     * @param clientName the client name used to group created ports.
     * @param queueSizeLimit the maximum number of messages held in the input queue.
     * @return a new RtMidiIn instance.
     */
    public static RtMidiIn createIn(RtMidi.Api api, String clientName, int queueSizeLimit) {
        RtMidiIn in = createIn(api);
        in.setClientName(clientName);
        in.setQueueSizeLimit(queueSizeLimit);
        return in;
    }

    /**
     * Creates a MIDI output instance for a specific API with the given client name.
     * @param api the API to use.
     * @param clientName the client name used to group created ports.
     * @return a new RtMidiOut instance.
     */
    public static RtMidiOut createOut(RtMidi.Api api, String clientName) {
        RtMidiOut out = createOut(api);
        out.setClientName(clientName);
        return out;
    }

    /**
     * Creates a MIDI input instance using the default API for the current OS.
     */
    public static RtMidiIn createDefaultIn() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            if (WindowsMidiServices.isAvailable()) {
                return new WindowsMidiServices.In();
            }
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
            if (WindowsMidiServices.isAvailable()) {
                return new WindowsMidiServices.Out();
            }
            return new WinMidiOut();
        } else if (os.contains("mac")) {
            return new CoreMidiOut();
        } else if (os.contains("nix") || os.contains("nux")) {
            return new AlsaMidiOut();
        }
        throw new UnsupportedOperationException("Unsupported OS: " + os);
    }
}
