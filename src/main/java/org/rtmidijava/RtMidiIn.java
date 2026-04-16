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
    protected FastCallback fastCallback;

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
     * High-performance functional interface for MIDI input callbacks.
     * Provides a MemorySegment to avoid byte[] allocations.
     */
    public interface FastCallback {
        /**
         * Called when a new MIDI message is received.
         * @param timeStamp the time in seconds since the port was opened.
         * @param message the segment containing the raw MIDI bytes.
         */
        void onMessage(double timeStamp, java.lang.foreign.MemorySegment message);
    }

    /**
     * Sets the callback function to be called when a new message arrives.
     * Setting a callback disables the internal message queue.
     */
    public void setCallback(Callback callback) {
        this.callback = callback;
        this.fastCallback = null;
    }

    /**
     * Sets a high-performance zero-copy callback.
     */
    public void setFastCallback(FastCallback fastCallback) {
        this.fastCallback = fastCallback;
        this.callback = null;
    }

    /**
     * Cancels the currently set callback and enables the message queue.
     */
    public void cancelCallback() {
        this.callback = null;
        this.fastCallback = null;
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
        try (java.lang.foreign.Arena arena = java.lang.foreign.Arena.ofConfined()) {
            onIncomingMessage(timeStamp, arena.allocateFrom(java.lang.foreign.ValueLayout.JAVA_BYTE, data));
        }
    }

    /**
     * Internal method called by backends when a message arrives in a MemorySegment.
     */
    protected void onIncomingMessage(double timeStamp, java.lang.foreign.MemorySegment data) {
        if (data.byteSize() > 0) {
            byte status = data.get(java.lang.foreign.ValueLayout.JAVA_BYTE, 0);
            if (ignoreSysex && (status == (byte)0xF0 || status == (byte)0xF7)) return;
            if (ignoreTime && (status >= (byte)0xF8 && status <= (byte)0xFA)) return;
            if (ignoreSense && status == (byte)0xFE) return;
        }

        if (fastCallback != null) {
            fastCallback.onMessage(timeStamp, data);
        } else if (callback != null) {
            callback.onMessage(timeStamp, data.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE));
        } else {
            queue.add(new MidiMessage(timeStamp, data.toArray(java.lang.foreign.ValueLayout.JAVA_BYTE)));
            if (queue.size() > 1024) queue.poll();
        }
    }
}
