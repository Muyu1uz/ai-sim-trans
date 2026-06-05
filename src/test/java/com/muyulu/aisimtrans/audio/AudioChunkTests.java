package com.muyulu.aisimtrans.audio;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AudioChunkTests {
    @Test
    void convertsShortSamplesToLittleEndianBytes() {
        AudioChunk chunk = AudioChunk.fromPcm16(new short[]{0x1234, (short) 0x8001}, 16000, 1, 7, 99);

        assertThat(chunk.pcm16le()).containsExactly(0x34, 0x12, 0x01, 0x80);
        assertThat(chunk.sampleCount()).isEqualTo(2);
        assertThat(chunk.sequence()).isEqualTo(7);
        assertThat(chunk.capturedAtNanos()).isEqualTo(99);
    }
}
