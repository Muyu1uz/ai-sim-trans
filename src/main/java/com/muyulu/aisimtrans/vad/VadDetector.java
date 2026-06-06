package com.muyulu.aisimtrans.vad;

import com.muyulu.aisimtrans.audio.AudioChunk;

public interface VadDetector {
    boolean isSpeech(AudioChunk chunk);
}
