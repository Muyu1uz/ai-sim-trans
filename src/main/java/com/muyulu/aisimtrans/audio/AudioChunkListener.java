package com.muyulu.aisimtrans.audio;

@FunctionalInterface
public interface AudioChunkListener {
    void onAudioChunk(AudioChunk chunk);
}
