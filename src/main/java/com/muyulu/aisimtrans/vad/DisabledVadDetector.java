package com.muyulu.aisimtrans.vad;

import com.muyulu.aisimtrans.audio.AudioChunk;

public class DisabledVadDetector implements VadDetector {
    @Override
    public boolean isSpeech(AudioChunk chunk) {
        return true;
    }
}
