package com.muyulu.aisimtrans.asr;

import java.util.function.Consumer;

import com.muyulu.aisimtrans.audio.AudioFrameQueue;

public interface AsrProvider {
    void start(AudioFrameQueue audioQueue, Consumer<AsrEvent> eventConsumer);

    void stop();

    boolean isRunning();
}
