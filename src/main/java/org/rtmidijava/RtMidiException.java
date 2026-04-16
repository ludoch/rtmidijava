package org.rtmidijava;

public class RtMidiException extends RuntimeException {
    public enum Type {
        WARNING,
        DEBUG_WARNING,
        UNSPECIFIED,
        NO_DEVICES_FOUND,
        INVALID_PARAMETER,
        INVALID_DEVICE,
        MEMORY_ERROR,
        SYSTEM_ERROR,
        DRIVER_ERROR,
        INVALID_USE,
        THREAD_ERROR
    }

    private final Type type;

    public RtMidiException(String message, Type type) {
        super(message);
        this.type = type;
    }

    public Type getType() {
        return type;
    }
}
