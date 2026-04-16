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
}
