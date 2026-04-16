package org.rtmidijava.dummy;

import org.rtmidijava.RtMidiOut;

public class DummyMidiOut extends RtMidiOut {
    @Override
    public Api getCurrentApi() {
        return Api.RTMIDI_DUMMY;
    }

    @Override
    public int getPortCount() {
        return 0;
    }

    @Override
    public String getPortName(int portNumber) {
        return null;
    }

    @Override
    public void openPort(int portNumber, String portName) {
        connected = true;
    }

    @Override
    public void openVirtualPort(String portName) {
        connected = true;
    }

    @Override
    public void closePort() {
        connected = false;
    }

    @Override
    public void sendMessage(byte[] message) {
        // Do nothing
    }

    @Override
    public void sendMessage(java.lang.foreign.MemorySegment message) {
        // Do nothing
    }
}
