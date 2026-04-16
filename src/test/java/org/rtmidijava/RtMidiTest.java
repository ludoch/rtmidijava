package org.rtmidijava;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class RtMidiTest {

    @Test
    public void testApi() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            System.out.println("Windows MIDI 2.0 Status: " + org.rtmidijava.windows.WindowsMidiServices.getStatusMessage());
        }

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

        final java.util.concurrent.atomic.AtomicReference<byte[]> receivedData = new java.util.concurrent.atomic.AtomicReference<>();
        midiIn.ignoreTypes(false, false, false);
        try {
            midiIn.openVirtualPort("Test Virtual In");
            // Give system time to propagate virtual port
            Thread.sleep(1000);
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
            receivedData.set(message);
        });

        // Find the port we just created in the output list
        int outPort = -1;
        int portCount = midiOut.getPortCount();
        for (int i = 0; i < portCount; i++) {
            String name = midiOut.getPortName(i);
            if (name.contains("Test Virtual In")) {
                outPort = i;
                break;
            }
        }

        if (outPort != -1) {
            midiOut.openPort(outPort, "Test Out");
            byte[] msg = new byte[]{(byte)0x90, 0x3C, 0x7F};
            midiOut.sendMessage(msg);
            
            // Wait for callback with timeout
            for (int i = 0; i < 100; i++) {
                byte[] data = receivedData.get();
                if (data != null) break;
                if (midiOut.getCurrentApi() == RtMidi.Api.MACOS_CORE) {
                    org.rtmidijava.macos.CoreMidiUtils.runRunLoop(0.01);
                    Thread.sleep(10);
                } else {
                    Thread.sleep(100);
                }
            }
            
            assertNotNull(receivedData.get(), "Message was not received");
            assertArrayEquals(msg, receivedData.get());
            midiOut.closePort();
        } else {
            System.out.println("Could not find virtual input port in output list. This might happen in some restricted CI environments.");
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
