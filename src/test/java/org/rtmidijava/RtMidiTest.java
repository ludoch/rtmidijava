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
    public void testSendReceive() throws InterruptedException {
        RtMidiOut midiOut = RtMidiFactory.createDefaultOut();
        RtMidiIn midiIn = RtMidiFactory.createDefaultIn();
        
        if (midiOut.getCurrentApi() != RtMidi.Api.MACOS_CORE && midiOut.getCurrentApi() != RtMidi.Api.LINUX_ALSA) {
            System.out.println("Skipping send/receive test on this platform");
            return;
        }

        final byte[][] receivedData = new byte[1][];
        try {
            midiIn.openVirtualPort("Test Virtual In");
            // Settle time for OS MIDI server
            Thread.sleep(500);
        } catch (RuntimeException e) {
            String msg = e.getMessage();
            if (e.getCause() != null) msg += " " + e.getCause().getMessage();
            
            if (msg.contains("snd_seq_open failed")) {
                System.out.println("Skipping send/receive test: ALSA sequencer not available or permission denied (" + msg + ")");
                midiIn.closePort();
                midiOut.closePort();
                return;
            }
            throw e;
        }
        midiIn.setCallback((timeStamp, message) -> {
            System.out.println("Received message: " + bytesToHex(message));
            receivedData[0] = message;
        });

        // Find the port we just created in the output list
        int outPort = -1;
        for (int i = 0; i < midiOut.getPortCount(); i++) {
            if (midiOut.getPortName(i).equals("Test Virtual In")) {
                outPort = i;
                break;
            }
        }

        if (outPort != -1) {
            midiOut.openPort(outPort, "Test Out");
            byte[] msg = new byte[]{(byte)0x90, 0x3C, 0x7F};
            System.out.println("Sending message: " + bytesToHex(msg));
            midiOut.sendMessage(msg);
            
            // Wait for callback - increased for CI environments
            for (int i = 0; i < 20; i++) {
                if (receivedData[0] != null) break;
                Thread.sleep(100);
            }
            
            assertNotNull(receivedData[0], "Message not received within timeout");
            assertArrayEquals(msg, receivedData[0]);
            midiOut.closePort();
        } else {
            System.out.println("Could not find virtual input port in output list");
        }
        
        midiIn.closePort();
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }
}
