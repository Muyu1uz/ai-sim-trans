package com.muyulu.aisimtrans.translation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.muyulu.aisimtrans.config.SimTransProperties;

class TranslationMemoryTests {
    @Test
    void keepsOnlyConfiguredRecentEntries() {
        TranslationMemory memory = new TranslationMemory(properties(2));

        memory.remember("one", "first");
        memory.remember("two", "second");
        memory.remember("three", "third");

        assertThat(memory.snapshot())
                .extracting(TranslationMemory.Entry::sourceText)
                .containsExactly("two", "three");
    }

    private SimTransProperties properties(int segments) {
        return new SimTransProperties(
                "local-asr",
                new SimTransProperties.Audio("windows-wasapi", null, 16000, 1, 512, 100, "LiveTranslateAudio"),
                new SimTransProperties.Asr("dashscope", "", "", "", "auto", "server_vad", 500, 300, 0.5),
                new SimTransProperties.Vad("energy", 250, 700, 8000, 0.5, 0.012, 0.25, true, 2000, "models/vad/silero/silero_vad.onnx"),
                new SimTransProperties.LocalAsr("faster-whisper", "Systran/faster-whisper-large-v3", "models", "modelscope", "cuda", "float16", "py", 18765, Duration.ofSeconds(120), Duration.ofHours(2)),
                new SimTransProperties.Translation("openai-compatible", "http://localhost:11434/v1", "", "model", "zh-CN", "", true, segments, 0.2, Duration.ofSeconds(45)),
                new SimTransProperties.Subtitle(2, true, 0.86, 28)
        );
    }
}
