package com.muyulu.aisimtrans.audio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public record AudioChunk(
        byte[] pcm16le,
        int sampleRate,
        int channels,
        int sampleCount,
        long sequence,
        long capturedAtNanos
) {
    public AudioChunk {
        pcm16le = Arrays.copyOf(pcm16le, pcm16le.length);
    }

    public static AudioChunk fromPcm16(short[] samples, int sampleRate, int channels, long sequence, long capturedAtNanos) {
        ByteBuffer buffer = ByteBuffer.allocate(samples.length * Short.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (short sample : samples) {
            buffer.putShort(sample);
        }
        return new AudioChunk(buffer.array(), sampleRate, channels, samples.length, sequence, capturedAtNanos);
    }
}
