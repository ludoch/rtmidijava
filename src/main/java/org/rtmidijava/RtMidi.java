package org.rtmidijava;

import java.util.ArrayList;
import java.util.List;

/**
 * Base class for RtMidiJava, a pure Java 25 implementation of the RtMidi API.
 * Uses Foreign Function &amp; Memory (FFM) API to interface with OS-native MIDI stacks.
 */
public abstract class RtMidi {

    /** Version of the RtMidiJava port. */
    public static final String VERSION = "1.0.2";

    /**
     * Enumeration of supported native MIDI APIs.
     *
     * <p>Each constant carries a stable short {@code name} (guaranteed identical
     * across library versions, matching the upstream RtMidi C/C++ identifiers)
     * and a human-readable {@code displayName}.
     */
    public enum Api {
        UNSPECIFIED("unspecified", "Unknown"),
        MACOS_CORE("core", "CoreMidi"),
        LINUX_ALSA("alsa", "ALSA"),
        LINUX_JACK("jack", "Jack"),
        WINDOWS_MM("winmm", "Windows MultiMedia"),
        WINDOWS_UWP("winuwp", "Windows UWP"), // Windows MIDI Services
        RTMIDI_DUMMY("dummy", "Dummy");

        private final String shortName;
        private final String displayName;

        Api(String shortName, String displayName) {
            this.shortName = shortName;
            this.displayName = displayName;
        }
    }

    protected long data;
    protected boolean connected;

    /** Client name used when registering with the native MIDI system. */
    protected String clientName = "RtMidi Client";
    /** Default name applied to ports created by this instance. */
    protected String portName = "RtMidi";

    protected volatile ErrorCallback errorCallback;

    /**
     * Functional interface for receiving asynchronous error notifications,
     * mirroring RtMidi's {@code setErrorCallback}.
     */
    public interface ErrorCallback {
        void onError(RtMidiException.Type type, String message);
    }

    /**
     * @return a string identifying the RtMidiJava version.
     */
    public static String getVersion() {
        return VERSION;
    }

    /**
     * @return the list of MIDI APIs usable on the current platform, in the
     *         order RtMidi would attempt to use them. The dummy API is always
     *         available as a fallback.
     */
    public static List<Api> getCompiledApi() {
        List<Api> apis = new ArrayList<>();
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) {
            apis.add(Api.MACOS_CORE);
        } else if (os.contains("nix") || os.contains("nux")) {
            apis.add(Api.LINUX_ALSA);
            apis.add(Api.LINUX_JACK);
        } else if (os.contains("win")) {
            apis.add(Api.WINDOWS_MM);
            apis.add(Api.WINDOWS_UWP);
        }
        apis.add(Api.RTMIDI_DUMMY);
        return apis;
    }

    /**
     * @return the stable short identifier of the given API (e.g. {@code "alsa"}),
     *         or the empty string if {@code api} is null.
     */
    public static String getApiName(Api api) {
        return api == null ? "" : api.shortName;
    }

    /**
     * @return the human-readable display name of the given API
     *         (e.g. {@code "ALSA"}), or {@code "Unknown"} if {@code api} is null.
     */
    public static String getApiDisplayName(Api api) {
        return api == null ? "Unknown" : api.displayName;
    }

    /**
     * Looks up a compiled API by its stable short name (case-insensitive).
     * @param name the short identifier (e.g. {@code "core"}, {@code "alsa"}).
     * @return the matching API, or {@link Api#UNSPECIFIED} if none matches.
     */
    public static Api getCompiledApiByName(String name) {
        if (name != null) {
            for (Api api : Api.values()) {
                if (api.shortName.equalsIgnoreCase(name)) {
                    return api;
                }
            }
        }
        return Api.UNSPECIFIED;
    }

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
     * Opens a connection to a specific MIDI port using the name set via
     * {@link #setPortName} (or the default if none was set).
     * @param portNumber the index of the port to open.
     */
    public void openPort(int portNumber) {
        openPort(portNumber, portName);
    }

    /**
     * Creates a virtual MIDI port that other applications can connect to.
     * Note: Virtual ports are not supported on Windows WinMM.
     * @param portName the name of the virtual port.
     */
    public abstract void openVirtualPort(String portName);

    /**
     * Creates a virtual MIDI port using the name set via {@link #setPortName}
     * (or the default if none was set).
     */
    public void openVirtualPort() {
        openVirtualPort(portName);
    }

    /**
     * Closes the current port connection.
     */
    public abstract void closePort();

    /**
     * Sets the client name used to group ports created by this instance.
     * Must be called before opening a port to take effect.
     * @param clientName the client name.
     */
    public void setClientName(String clientName) {
        this.clientName = clientName;
    }

    /**
     * Sets the default name applied to ports created by this instance.
     * @param portName the port name.
     */
    public void setPortName(String portName) {
        this.portName = portName;
    }

    /**
     * Registers a callback invoked when an error occurs. When no callback is
     * set, errors are reported by throwing {@link RtMidiException}.
     * @param errorCallback the callback, or {@code null} to clear it.
     */
    public void setErrorCallback(ErrorCallback errorCallback) {
        this.errorCallback = errorCallback;
    }

    /**
     * Reports an error: invokes the registered {@link ErrorCallback} if present,
     * otherwise throws an {@link RtMidiException}.
     */
    protected void error(RtMidiException.Type type, String message) {
        ErrorCallback cb = this.errorCallback;
        if (cb != null) {
            cb.onError(type, message);
        } else {
            throw new RtMidiException(message, type);
        }
    }

    /**
     * @return true if a port is currently open.
     */
    public boolean isPortOpen() {
        return connected;
    }
}
