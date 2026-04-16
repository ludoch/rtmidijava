package org.rtmidijava.examples;

import org.rtmidijava.RtMidiOut;
import org.rtmidijava.RtMidiFactory;

/**
 * A simple MIDI sender that plays a scale and sends a Sysex message.
 */
public class MidiSender {
    public static void main(String[] args) throws InterruptedException {
        RtMidiOut midiOut = RtMidiFactory.createDefaultOut();
        
        System.out.println("RtMidiJava Sender");
        System.out.println("API: " + midiOut.getCurrentApi());
        
        int portCount = midiOut.getPortCount();
        if (portCount == 0) {
            System.out.println("No output ports found.");
            return;
        }

        System.out.println("Opening port 0: " + midiOut.getPortName(0));
        midiOut.openPort(0, "MidiSender");

        // Play a simple scale
        int[] scale = {60, 62, 64, 65, 67, 69, 71, 72};
        for (int note : scale) {
            System.out.println("Playing note: " + note);
            // Note On: 0x90, note, velocity
            midiOut.sendMessage(new byte[]{(byte)0x90, (byte)note, (byte)100});
            Thread.sleep(200);
            // Note Off: 0x80, note, velocity
            midiOut.sendMessage(new byte[]{(byte)0x80, (byte)note, (byte)0});
        }

        // Send a Sysex Identity Request
        System.out.println("Sending Sysex Identity Request...");
        midiOut.sendMessage(new byte[]{(byte)0xF0, 0x7E, 0x7F, 0x06, 0x01, (byte)0xF7});

        Thread.sleep(500);
        midiOut.closePort();
        System.out.println("Done.");
    }
}
