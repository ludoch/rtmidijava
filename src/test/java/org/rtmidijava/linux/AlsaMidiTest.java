package org.rtmidijava.linux;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.rtmidijava.RtMidi;
import org.rtmidijava.RtMidiException;

import java.util.concurrent.atomic.AtomicReference;

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
            String msg = e.getMessage();
            if (e.getCause() != null) msg += " " + e.getCause().getMessage();
            
            if (msg.contains("snd_seq_open failed")) {
                System.out.println("Skipping virtual port test: ALSA sequencer not available or permission denied (" + msg + ")");
            } else {
                throw e;
            }
        }
    }

    @Test
    public void testInvalidPortThrowsTypedException() {
        AlsaMidiOut out = new AlsaMidiOut();
        // No error callback set: the error must surface as a typed RtMidiException.
        RtMidiException ex = assertThrows(RtMidiException.class, () -> out.openPort(99999, "Nope"));
        assertEquals(RtMidiException.Type.INVALID_PARAMETER, ex.getType());
        assertFalse(out.isPortOpen());
    }

    @Test
    public void testInvalidPortRoutedToErrorCallback() {
        AlsaMidiOut out = new AlsaMidiOut();
        AtomicReference<RtMidiException.Type> seen = new AtomicReference<>();
        out.setErrorCallback((type, message) -> seen.set(type));
        // With a callback set, the error is delivered instead of thrown.
        assertDoesNotThrow(() -> out.openPort(99999, "Nope"));
        assertEquals(RtMidiException.Type.INVALID_PARAMETER, seen.get());
        assertFalse(out.isPortOpen());
    }

    @Test
    public void testSetPortNameAndNoArgVirtualPort() {
        AlsaMidiIn in = new AlsaMidiIn();
        in.setPortName("Named Via Setter");
        try {
            in.openVirtualPort(); // uses the name from setPortName
            assertTrue(in.isPortOpen());

            // An input virtual port is writable by others, so it shows up in the
            // output (writable) enumeration.
            AlsaMidiOut out = new AlsaMidiOut();
            boolean found = false;
            int count = out.getPortCount();
            for (int i = 0; i < count; i++) {
                if (String.valueOf(out.getPortName(i)).contains("Named Via Setter")) {
                    found = true;
                    break;
                }
            }
            assertTrue(found, "virtual port should be advertised under the name from setPortName");
            in.closePort();
        } catch (RtMidiException e) {
            if (String.valueOf(e.getMessage()).contains("snd_seq_open failed")) {
                System.out.println("Skipping: ALSA sequencer not available (" + e.getMessage() + ")");
            } else {
                throw e;
            }
        }
    }

    @Test
    public void testSetQueueSizeLimitBeforeOpen() {
        AlsaMidiIn in = new AlsaMidiIn();
        // Must not throw and must leave the instance usable.
        assertDoesNotThrow(() -> in.setQueueSizeLimit(500));
        assertDoesNotThrow(() -> in.setQueueSizeLimit(0)); // restore default
        assertFalse(in.isPortOpen());
    }
}
