package org.rtmidijava.linux;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.rtmidijava.RtMidi;

import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.LINUX)
public class AlsaMidiTest {

    @Test
    public void testAlsaAvailability() {
        AlsaMidiOut out = new AlsaMidiOut();
        assertEquals(RtMidi.Api.LINUX_ALSA, out.getCurrentApi());
        
        // This might return 0 if no devices or no permission, but shouldn't crash
        int count = out.getPortCount();
        System.out.println("ALSA Port Count: " + count);
        for (int i = 0; i < count; i++) {
            System.out.println("Port " + i + ": " + out.getPortName(i));
        }
    }

    @Test
    public void testVirtualPort() {
        AlsaMidiOut out = new AlsaMidiOut();
        try {
            out.openVirtualPort("Test Virtual Out");
            assertTrue(out.isPortOpen());
            out.closePort();
            assertFalse(out.isPortOpen());
        } catch (RuntimeException e) {
            if (e.getMessage().contains("snd_seq_open failed: -13")) {
                System.out.println("Skipping virtual port test: Permission denied to ALSA sequencer");
            } else {
                throw e;
            }
        }
    }
}
