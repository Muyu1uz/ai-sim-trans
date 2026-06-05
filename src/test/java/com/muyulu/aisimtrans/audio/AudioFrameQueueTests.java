package com.muyulu.aisimtrans.audio;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.muyulu.aisimtrans.config.SimTransProperties;

class AudioFrameQueueTests {
    @Test
    void dropsOldestChunkWhenQueueIsFull() throws Exception {
        AudioFrameQueue queue = new AudioFrameQueue(properties(2));

        queue.offerLatest(chunk(1));
        queue.offerLatest(chunk(2));
        queue.offerLatest(chunk(3));

        assertThat(queue.droppedChunks()).isEqualTo(1);
        assertThat(queue.poll(1).sequence()).isEqualTo(2);
        assertThat(queue.poll(1).sequence()).isEqualTo(3);
    }

    private AudioChunk chunk(long sequence) {
        return AudioChunk.fromPcm16(new short[]{(short) sequence}, 16000, 1, sequence, sequence);
    }

    private SimTransProperties properties(int capacity) {
        return new SimTransProperties(
                new SimTransProperties.Audio("windows-wasapi", null, 16000, 1, 512, capacity, "LiveTranslateAudio"),
                new SimTransProperties.Asr("dashscope", "", "", "", "auto", "server_vad", 500, 300, 0.5),
                new SimTransProperties.Translation("openai-compatible", "http://localhost:11434/v1", "", "model", "zh-CN", true, 12, 0.2, java.time.Duration.ofSeconds(45)),
                new SimTransProperties.Subtitle(2, true, 0.86, 28)
        );
    }
}
