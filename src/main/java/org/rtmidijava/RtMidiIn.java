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

    /** Default off-heap input queue size in bytes when no message limit is set. */
    private static final long DEFAULT_QUEUE_BYTES = 65536;
    /** Bytes reserved per message when sizing the queue from a message-count limit. */
    private static final long BYTES_PER_MESSAGE = 1024;

    protected final Arena sharedArena = Arena.ofShared();
    protected MidiRingBuffer ringBuffer = new MidiRingBuffer(DEFAULT_QUEUE_BYTES, sharedArena);

    protected boolean ignoreSysex = true;
    protected boolean ignoreTime = true;
    protected boolean ignoreSense = true;
    protected volatile Callback callback;
    protected volatile FastCallback fastCallback;

    protected int bufferSize = 1024;
    protected int bufferCount = 4;

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

    /**
     * Sets the maximum number of messages the input queue can hold when no
     * callback is used. Resizes the off-heap ring buffer; must be called before
     * {@link #openPort}. A limit of {@code <= 0} restores the default size.
     * @param queueSizeLimit the maximum number of queued messages.
     */
    public void setQueueSizeLimit(int queueSizeLimit) {
        long bytes = queueSizeLimit > 0
                ? Math.max(queueSizeLimit * BYTES_PER_MESSAGE, DEFAULT_QUEUE_BYTES)
                : DEFAULT_QUEUE_BYTES;
        this.ringBuffer = new MidiRingBuffer(bytes, sharedArena);
    }

    public void ignoreTypes(boolean midiSysex, boolean midiTime, boolean midiSense) {
        this.ignoreSysex = midiSysex;
        this.ignoreTime = midiTime;
        this.ignoreSense = midiSense;
    }

    /**
     * Sets the maximum expected incoming message size for APIs that require
     * manual buffer management (principally the Windows MM backend). Has no
     * effect when called after {@link #openPort}. Backends with dynamically
     * sized buffers ignore these hints.
     * @param size  the buffer size in bytes.
     * @param count the number of buffers.
     */
    public void setBufferSize(int size, int count) {
        this.bufferSize = size;
        this.bufferCount = count;
    }

    /**
     * Returns the next message from the internal off-heap ring buffer.
     * Note: This implementation allocates a small byte[] to return the data.
     * For Zero-GC, use setFastCallback or the getMessage(byte[], double[]) instead.
     */
    public byte[] getMessage() {
        MidiMessage msg = getMessageWithTimestamp();
        return msg != null ? msg.data() : null;
    }

    /**
     * Non-allocating way to poll for messages.
     * @param target the array to fill with MIDI data.
     * @param timeStampOut a 1-element array to receive the timestamp.
     * @return the number of bytes read, or -1 if no message.
     */
    public int getMessage(byte[] target, double[] timeStampOut) {
        return ringBuffer.read(target, timeStampOut);
    }

    /**
     * Returns the next message with its timestamp from the off-heap ring buffer.
     */
    public MidiMessage getMessageWithTimestamp() {
        byte[] data = new byte[1024];
        double[] tsOut = new double[1];
        int len = ringBuffer.read(data, tsOut);
        if (len < 0) return null;

        if (len > data.length) {
            // The message is larger than our scratch buffer. read() reports the required length
            // without consuming, so retry with an exact-sized buffer — otherwise large messages
            // (e.g. multi-KB SysEx) would overrun the copy below / be silently truncated.
            data = new byte[len];
            len = ringBuffer.read(data, tsOut);
            if (len < 0) return null;
        }

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
