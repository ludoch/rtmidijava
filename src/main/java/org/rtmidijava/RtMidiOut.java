package org.rtmidijava;

/**
 * Class for handling MIDI output.
 */
public abstract class RtMidiOut extends RtMidi {
    /**
     * Sends a MIDI message to the opened port.
     * @param message the raw MIDI message bytes to send.
     */
    public abstract void sendMessage(byte[] message);

    /**
     * Sends a MIDI message directly from a native memory segment.
     * This is the highest performance way to send MIDI as it avoids array copies.
     * @param message the segment containing the raw MIDI bytes.
     */
    public abstract void sendMessage(java.lang.foreign.MemorySegment message);
}
