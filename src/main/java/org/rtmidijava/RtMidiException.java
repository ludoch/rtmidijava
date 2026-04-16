package org.rtmidijava;

/**
 * Exception thrown by RtMidiJava for native errors or invalid parameters.
 */
public class RtMidiException extends RuntimeException {
    /**
     * Types of errors that can be reported by RtMidi.
     */
    public enum Type {
        WARNING,
        DEBUG_WARNING,
        UNSPECIFIED,
        NO_DEVICES_FOUND,
        INVALID_DEVICE,
        MEMORY_ERROR,
        INVALID_PARAMETER,
        INVALID_USE,
        DRIVER_ERROR,
        SYSTEM_ERROR,
        THREAD_ERROR
    }

    private final Type type;

    public RtMidiException(String message, Type type) {
        super(message);
        this.type = type;
    }

    /**
     * @return the type of error that occurred.
     */
    public Type getType() {
        return type;
    }
}
