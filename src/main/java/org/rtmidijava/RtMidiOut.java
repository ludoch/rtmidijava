package org.rtmidijava;

public abstract class RtMidiOut extends RtMidi {
    public abstract void sendMessage(byte[] message);
}
