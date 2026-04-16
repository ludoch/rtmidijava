package org.rtmidijava;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class RtMidiTest {

    @Test
    public void testCreateIn() {
        RtMidiIn midiIn = RtMidiFactory.createDefaultIn();
        assertNotNull(midiIn);
        System.out.println("API: " + midiIn.getCurrentApi());
        int portCount = midiIn.getPortCount();
        System.out.println("Input Ports: " + portCount);
        for (int i = 0; i < portCount; i++) {
            System.out.println("Port " + i + ": " + midiIn.getPortName(i));
        }
    }

    @Test
    public void testCreateOut() {
        RtMidiOut midiOut = RtMidiFactory.createDefaultOut();
        assertNotNull(midiOut);
        System.out.println("API: " + midiOut.getCurrentApi());
        int portCount = midiOut.getPortCount();
        System.out.println("Output Ports: " + portCount);
        for (int i = 0; i < portCount; i++) {
            System.out.println("Port " + i + ": " + midiOut.getPortName(i));
        }
    }

    @Test
    public void testOpenPort() {
        RtMidiOut midiOut = RtMidiFactory.createDefaultOut();
        if (midiOut.getPortCount() > 0) {
            midiOut.openPort(0, "Test Port");
            assertTrue(midiOut.isPortOpen());
            midiOut.sendMessage(new byte[]{(byte)0x90, 0x3C, 0x7F}); // Note On
            midiOut.sendMessage(new byte[]{(byte)0x80, 0x3C, 0x00}); // Note Off
            midiOut.closePort();
            assertFalse(midiOut.isPortOpen());
        } else {
            System.out.println("No output ports available to test openPort");
        }
    }
}
