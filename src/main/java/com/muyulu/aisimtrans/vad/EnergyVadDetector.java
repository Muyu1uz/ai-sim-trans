package com.muyulu.aisimtrans.vad;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import com.muyulu.aisimtrans.audio.AudioChunk;

public class EnergyVadDetector implements VadDetector {
    private final double threshold;

    public EnergyVadDetector(double threshold) {
        this.threshold = threshold;
    }

    @Override
    public boolean isSpeech(AudioChunk chunk) {
        ByteBuffer buffer = ByteBuffer.wrap(chunk.pcm16le()).order(ByteOrder.LITTLE_ENDIAN);
        long squares = 0;
        int samples = 0;
        while (buffer.remaining() >= Short.BYTES) {
            short sample = buffer.getShort();
            squares += (long) sample * sample;
            samples++;
        }
        if (samples == 0) {
            return false;
        }
        double rms = Math.sqrt(squares / (double) samples) / Short.MAX_VALUE;
        return rms >= threshold;
    }
}
