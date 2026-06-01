package org.rtmidijava;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the static RtMidi API surface ported from the upstream C/C++ library:
 * version, compiled API enumeration, and API name lookups.
 */
public class ApiSurfaceTest {

    @Test
    public void testGetVersion() {
        assertEquals(RtMidi.VERSION, RtMidi.getVersion());
        assertNotNull(RtMidi.getVersion());
        assertFalse(RtMidi.getVersion().isBlank());
    }

    @Test
    public void testApiNames() {
        assertEquals("alsa", RtMidi.getApiName(RtMidi.Api.LINUX_ALSA));
        assertEquals("core", RtMidi.getApiName(RtMidi.Api.MACOS_CORE));
        assertEquals("jack", RtMidi.getApiName(RtMidi.Api.LINUX_JACK));
        assertEquals("winmm", RtMidi.getApiName(RtMidi.Api.WINDOWS_MM));
        assertEquals("winuwp", RtMidi.getApiName(RtMidi.Api.WINDOWS_UWP));
        assertEquals("dummy", RtMidi.getApiName(RtMidi.Api.RTMIDI_DUMMY));
        assertEquals("", RtMidi.getApiName(null));
    }

    @Test
    public void testApiDisplayNames() {
        assertEquals("ALSA", RtMidi.getApiDisplayName(RtMidi.Api.LINUX_ALSA));
        assertEquals("Unknown", RtMidi.getApiDisplayName(null));
        for (RtMidi.Api api : RtMidi.Api.values()) {
            assertNotNull(RtMidi.getApiDisplayName(api));
        }
    }

    @Test
    public void testCompiledApiByNameRoundTrip() {
        for (RtMidi.Api api : RtMidi.Api.values()) {
            assertEquals(api, RtMidi.getCompiledApiByName(RtMidi.getApiName(api)),
                    "round-trip failed for " + api);
        }
        // Case-insensitive.
        assertEquals(RtMidi.Api.LINUX_ALSA, RtMidi.getCompiledApiByName("ALSA"));
        // Unknown name falls back to UNSPECIFIED.
        assertEquals(RtMidi.Api.UNSPECIFIED, RtMidi.getCompiledApiByName("nope"));
        assertEquals(RtMidi.Api.UNSPECIFIED, RtMidi.getCompiledApiByName(null));
    }

    @Test
    public void testGetCompiledApi() {
        List<RtMidi.Api> apis = RtMidi.getCompiledApi();
        assertNotNull(apis);
        assertFalse(apis.isEmpty());
        // The dummy API is always available as a fallback.
        assertTrue(apis.contains(RtMidi.Api.RTMIDI_DUMMY));
        assertFalse(apis.contains(RtMidi.Api.UNSPECIFIED));
    }

    @Test
    public void testClientAndPortNameDefaults() {
        RtMidiIn in = RtMidiFactory.createIn(RtMidi.Api.RTMIDI_DUMMY, "MyClient");
        assertFalse(in.isPortOpen());
        in.setPortName("MyPort");
        // setBufferSize is a no-op for the dummy backend but must not throw.
        in.setBufferSize(2048, 8);
    }

    @Test
    public void testErrorCallbackInvoked() {
        RtMidiIn in = RtMidiFactory.createIn(RtMidi.Api.RTMIDI_DUMMY);
        final RtMidiException.Type[] seen = new RtMidiException.Type[1];
        in.setErrorCallback((type, message) -> seen[0] = type);
        in.error(RtMidiException.Type.INVALID_PARAMETER, "boom");
        assertEquals(RtMidiException.Type.INVALID_PARAMETER, seen[0]);
    }

    @Test
    public void testErrorThrowsWithoutCallback() {
        RtMidiIn in = RtMidiFactory.createIn(RtMidi.Api.RTMIDI_DUMMY);
        RtMidiException ex = assertThrows(RtMidiException.class,
                () -> in.error(RtMidiException.Type.DRIVER_ERROR, "fail"));
        assertEquals(RtMidiException.Type.DRIVER_ERROR, ex.getType());
    }
}
