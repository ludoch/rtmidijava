package org.rtmidijava;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Class for handling MIDI input.
 * Can be used with either a callback or by polling the message queue.
 */
public abstract class RtMidiIn extends RtMidi {
    
    /**
     * A simple record representing a MIDI message with its timestamp.
     */
    public record MidiMessage(double timeStamp, byte[] data) {}

    protected ConcurrentLinkedQueue<MidiMessage> queue = new ConcurrentLinkedQueue<>();
    protected boolean ignoreSysex = true;
    protected boolean ignoreTime = true;
    protected boolean ignoreSense = true;
    protected Callback callback;

    /**
     * Functional interface for MIDI input callbacks.
     */
    public interface Callback {
        /**
         * Called when a new MIDI message is received.
         * @param timeStamp the time in seconds since the port was opened.
         * @param message the raw MIDI message bytes.
         */
        void onMessage(double timeStamp, byte[] message);
    }

    /**
     * Sets the callback function to be called when a new message arrives.
     * Setting a callback disables the internal message queue.
     */
    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    /**
     * Cancels the currently set callback and enables the message queue.
     */
    public void cancelCallback() {
        this.callback = null;
    }

    /**
     * Specify types of MIDI messages to ignore.
     * @param midiSysex ignore system exclusive messages if true.
     * @param midiTime ignore timing clock messages if true.
     * @param midiSense ignore active sensing messages if true.
     */
    public void ignoreTypes(boolean midiSysex, boolean midiTime, boolean midiSense) {
        this.ignoreSysex = midiSysex;
        this.ignoreTime = midiTime;
        this.ignoreSense = midiSense;
    }

    /**
     * Returns the next message from the queue if no callback is set.
     * @return the raw message bytes, or null if no message is available.
     */
    public byte[] getMessage() {
        MidiMessage msg = queue.poll();
        return msg != null ? msg.data : null;
    }

    /**
     * Returns the next message with its timestamp from the queue.
     * @return the MidiMessage, or null if no message is available.
     */
    public MidiMessage getMessageWithTimestamp() {
        return queue.poll();
    }

    /**
     * Internal method called by backends when a message arrives.
     */
    protected void onIncomingMessage(double timeStamp, byte[] data) {
        if (data.length > 0) {
            byte status = data[0];
            if (ignoreSysex && (status == (byte)0xF0 || status == (byte)0xF7)) return;
            if (ignoreTime && (status >= (byte)0xF8 && status <= (byte)0xFA)) return;
            if (ignoreSense && status == (byte)0xFE) return;
        }

        if (callback != null) {
            callback.onMessage(timeStamp, data);
        } else {
            queue.add(new MidiMessage(timeStamp, data));
            if (queue.size() > 1024) queue.poll();
        }
    }
}
