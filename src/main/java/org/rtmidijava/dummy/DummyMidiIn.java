package org.rtmidijava.dummy;

import org.rtmidijava.RtMidiIn;

public class DummyMidiIn extends RtMidiIn {
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
}
