package org.rtmidijava;

import org.rtmidijava.utils.MidiRingBuffer;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Class for handling MIDI input.
 * Can be used with either a callback or by polling the message queue.
 * Optimized for Zero-GC in the callback path.
 */
public abstract class RtMidiIn extends RtMidi {
    
    /**
     * A simple record representing a MIDI message with its timestamp.
     */
    public record MidiMessage(double timeStamp, byte[] data) {}

    protected final Arena sharedArena = Arena.ofShared();
    protected final MidiRingBuffer ringBuffer = new MidiRingBuffer(65536, sharedArena);
    
    protected boolean ignoreSysex = true;
    protected boolean ignoreTime = true;
    protected boolean ignoreSense = true;
    protected Callback callback;
    protected FastCallback fastCallback;

    /**
     * Functional interface for MIDI input callbacks.
     */
    public interface Callback {
        void onMessage(double timeStamp, byte[] message);
    }

    /**
     * High-performance functional interface for MIDI input callbacks.
     * Provides a MemorySegment to avoid byte[] allocations.
     */
    public interface FastCallback {
        void onMessage(double timeStamp, MemorySegment message);
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
        this.fastCallback = null;
    }

    public void setFastCallback(FastCallback fastCallback) {
        this.fastCallback = fastCallback;
        this.callback = null;
    }

    public void cancelCallback() {
        this.callback = null;
        this.fastCallback = null;
    }

    public void ignoreTypes(boolean midiSysex, boolean midiTime, boolean midiSense) {
        this.ignoreSysex = midiSysex;
        this.ignoreTime = midiTime;
        this.ignoreSense = midiSense;
    }

    /**
     * Returns the next message from the internal off-heap ring buffer.
     * Note: This implementation allocates a small byte[] to return the data.
     * For Zero-GC, use setFastCallback instead.
     */
    public byte[] getMessage() {
        MidiMessage msg = getMessageWithTimestamp();
        return msg != null ? msg.data : null;
    }

    /**
     * Returns the next message with its timestamp from the off-heap ring buffer.
     */
    public MidiMessage getMessageWithTimestamp() {
        byte[] data = new byte[1024];
        double[] tsOut = new double[1];
        int len = ringBuffer.read(data, tsOut);
        if (len < 0) return null;
        
        byte[] actual = new byte[len];
        System.arraycopy(data, 0, actual, 0, len);
        return new MidiMessage(tsOut[0], actual);
    }

    protected void onIncomingMessage(double timeStamp, byte[] data) {
        try (Arena local = Arena.ofConfined()) {
            onIncomingMessage(timeStamp, local.allocateFrom(ValueLayout.JAVA_BYTE, data));
        }
    }

    protected void onIncomingMessage(double timeStamp, MemorySegment data) {
        if (data.byteSize() > 0) {
            byte status = data.get(ValueLayout.JAVA_BYTE, 0);
            if (ignoreSysex && (status == (byte)0xF0 || status == (byte)0xF7)) return;
            if (ignoreTime && (status >= (byte)0xF8 && status <= (byte)0xFA)) return;
            if (ignoreSense && status == (byte)0xFE) return;
        }

        if (fastCallback != null) {
            fastCallback.onMessage(timeStamp, data);
        } else if (callback != null) {
            callback.onMessage(timeStamp, data.toArray(ValueLayout.JAVA_BYTE));
        } else {
            // Internal off-heap storage, no GC pressure on write
            ringBuffer.write(timeStamp, data);
        }
    }

    @Override
    public void closePort() {
        // Implementation in backends, but we should clear buffer
        ringBuffer.clear();
        connected = false;
    }
}
