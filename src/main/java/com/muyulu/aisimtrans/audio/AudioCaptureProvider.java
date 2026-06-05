package com.muyulu.aisimtrans.audio;

import java.util.List;

public interface AudioCaptureProvider {
    void start(AudioChunkListener listener);

    void stop();

    boolean isRunning();

    List<String> listOutputDevices();
}
