package org.rtmidijava;

import java.util.concurrent.ConcurrentLinkedQueue;

public abstract class RtMidiIn extends RtMidi {
    
    public record MidiMessage(double timeStamp, byte[] data) {}

    protected ConcurrentLinkedQueue<MidiMessage> queue = new ConcurrentLinkedQueue<>();
    protected boolean ignoreSysex = true;
    protected boolean ignoreTime = true;
    protected boolean ignoreSense = true;
    protected Callback callback;

    public interface Callback {
        void onMessage(double timeStamp, byte[] message);
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    public void cancelCallback() {
        this.callback = null;
    }

    public void ignoreTypes(boolean midiSysex, boolean midiTime, boolean midiSense) {
        this.ignoreSysex = midiSysex;
        this.ignoreTime = midiTime;
        this.ignoreSense = midiSense;
    }

    /**
     * Returns the next message from the queue if no callback is set.
     */
    public byte[] getMessage() {
        MidiMessage msg = queue.poll();
        return msg != null ? msg.data : null;
    }

    public MidiMessage getMessageWithTimestamp() {
        return queue.poll();
    }

    protected void onIncomingMessage(double timeStamp, byte[] data) {
        // Filtering logic based on ignoreTypes
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
            // RtMidi usually caps the queue size, let's keep it simple for now
            if (queue.size() > 1024) queue.poll();
        }
    }
}
