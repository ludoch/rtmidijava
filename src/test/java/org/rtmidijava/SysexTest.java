package org.rtmidijava;

import org.junit.jupiter.api.Test;
import org.rtmidijava.windows.WinMidiIn;
import org.rtmidijava.windows.WinMidiOut;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

public class SysexTest {

    @Test
    public void testSysexLoopback() throws InterruptedException {
        String os = System.getProperty("os.name").toLowerCase();
        if (!os.contains("win")) {
            System.out.println("Skipping Windows Sysex test on " + os);
            return;
        }

        RtMidiIn midiIn = RtMidiFactory.createDefaultIn();
        RtMidiOut midiOut = RtMidiFactory.createDefaultOut();

        int inPort = -1;
        int outPort = -1;

        // Try to find loopMIDI port
        for (int i = 0; i < midiIn.getPortCount(); i++) {
            if (midiIn.getPortName(i).contains("loopMIDI")) {
                inPort = i;
                break;
            }
        }
        for (int i = 0; i < midiOut.getPortCount(); i++) {
            if (midiOut.getPortName(i).contains("loopMIDI")) {
                outPort = i;
                break;
            }
        }

        if (inPort == -1 || outPort == -1) {
            System.out.println("loopMIDI port not found, skipping Sysex loopback test");
            return;
        }

        byte[] msgToSend = new byte[]{(byte) 0x90, 0x3C, 0x7F};
        AtomicReference<byte[]> receivedData = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        midiIn.openPort(inPort, "Sysex Test In");
        midiIn.setCallback((timeStamp, message) -> {
            receivedData.set(message);
            latch.countDown();
        });

        midiOut.openPort(outPort, "Sysex Test Out");
        
        // Give it a moment to settle
        Thread.sleep(100);

        midiOut.sendMessage(msgToSend);

        boolean received = latch.await(2, TimeUnit.SECONDS);

        midiIn.closePort();
        midiOut.closePort();

        assertTrue(received, "Message not received");
        assertArrayEquals(msgToSend, receivedData.get(), "Received message does not match sent message");
    }
}
