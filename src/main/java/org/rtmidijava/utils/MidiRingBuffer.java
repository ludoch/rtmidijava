package org.rtmidijava.utils;

import java.lang.foreign.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A lock-free, off-heap ring buffer for MIDI messages.
 * Eliminates GC pressure by storing messages in a pre-allocated MemorySegment.
 */
public class MidiRingBuffer {
    private final MemorySegment buffer;
    private final long capacity;
    private final AtomicLong head = new AtomicLong(0); // Read pointer
    private final AtomicLong tail = new AtomicLong(0); // Write pointer

    public MidiRingBuffer(long sizeInBytes, Arena arena) {
        this.buffer = arena.allocate(sizeInBytes);
        this.capacity = sizeInBytes;
    }

    /**
     * Stores a MIDI message: [8 bytes timestamp][4 bytes length][N bytes data]
     */
    public boolean write(double timestamp, MemorySegment data) {
        long length = data.byteSize();
        long required = 8 + 4 + length;
        // Align to 8 bytes for next entry
        if (required % 8 != 0) required += (8 - (required % 8));

        long currentHead = head.get();
        long currentTail = tail.get();
        
        if (capacity - (currentTail - currentHead) < required) {
            // Buffer full, drop oldest
            // In a real pro-audio app, we might want to skip or signal overflow
            return false; 
        }

        long pos = currentTail % capacity;
        // Simple wrap-around check for the header + data
        if (pos + required > capacity) {
            // For simplicity in this implementation, we don't split messages across the boundary
            // We just "waste" the end of the buffer and wrap to the beginning
            tail.addAndGet(capacity - pos);
            return write(timestamp, data);
        }

        buffer.set(ValueLayout.JAVA_DOUBLE, pos, timestamp);
        buffer.set(ValueLayout.JAVA_INT, pos + 8, (int)length);
        MemorySegment.copy(data, 0, buffer, pos + 12, length);
        
        tail.addAndGet(required);
        return true;
    }

    /**
     * Non-allocating read logic would be complex for the user.
     * We'll provide a way to get the latest message into a provided byte[].
     */
    public int read(byte[] target, double[] timeStampOut) {
        long currentHead = head.get();
        long currentTail = tail.get();
        
        if (currentHead >= currentTail) return -1;

        long pos = currentHead % capacity;
        // Handle the "skip" padding we might have added at the end
        double ts = buffer.get(ValueLayout.JAVA_DOUBLE, pos);
        if (Double.isNaN(ts)) { // We use NaN to mark skipped padding
             head.addAndGet(capacity - pos);
             return read(target, timeStampOut);
        }

        int len = buffer.get(ValueLayout.JAVA_INT, pos + 8);
        timeStampOut[0] = ts;
        
        int bytesToCopy = Math.min(len, target.length);
        MemorySegment.copy(buffer, pos + 12, MemorySegment.ofArray(target), 0, bytesToCopy);
        
        long consumed = 8 + 4 + len;
        if (consumed % 8 != 0) consumed += (8 - (consumed % 8));
        head.addAndGet(consumed);
        
        return len;
    }

    public void clear() {
        head.set(0);
        tail.set(0);
    }
}
