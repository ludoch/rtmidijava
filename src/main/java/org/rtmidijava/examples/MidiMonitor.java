package org.rtmidijava.examples;

import org.rtmidijava.RtMidiIn;
import org.rtmidijava.RtMidiFactory;

/**
 * A simple MIDI monitor that prints incoming messages to the console.
 */
public class MidiMonitor {
    public static void main(String[] args) throws InterruptedException {
        RtMidiIn midiIn = RtMidiFactory.createDefaultIn();
        
        System.out.println("RtMidiJava Monitor");
        System.out.println("API: " + midiIn.getCurrentApi());
        
        int portCount = midiIn.getPortCount();
        if (portCount == 0) {
            System.out.println("No input ports found.");
            return;
        }

        System.out.println("Available Ports:");
        for (int i = 0; i < portCount; i++) {
            System.out.println("[" + i + "] " + midiIn.getPortName(i));
        }

        // Open the first available port
        System.out.println("Opening port 0: " + midiIn.getPortName(0));
        midiIn.openPort(0, "MidiMonitor");

        midiIn.setCallback((timeStamp, message) -> {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("[%f] ", timeStamp));
            for (byte b : message) {
                sb.append(String.format("%02X ", b));
            }
            System.out.println(sb.toString());
        });

        System.out.println("Monitoring... Press Ctrl+C to stop.");
        while (true) {
            Thread.sleep(1000);
        }
    }
}
