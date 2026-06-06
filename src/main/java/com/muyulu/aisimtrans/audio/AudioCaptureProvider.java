package com.muyulu.aisimtrans.audio;

import java.util.List;

public interface AudioCaptureProvider {
    void start(AudioChunkListener listener);

    void stop();

    boolean isRunning();

    List<String> listOutputDevices();

    default double lastRms() {
        return 0.0;
    }

    default long lastAudioAtMillis() {
        return 0;
    }

    default long audioCallbackCount() {
        return 0;
    }
}
