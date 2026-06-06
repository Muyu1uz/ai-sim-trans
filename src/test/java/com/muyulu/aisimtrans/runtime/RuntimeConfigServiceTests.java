package com.muyulu.aisimtrans.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.muyulu.aisimtrans.config.SimTransProperties;

class RuntimeConfigServiceTests {
    @Test
    void keepsQwenTurboTranslationDefaults() {
        RuntimeConfigService service = new RuntimeConfigService(properties());

        RuntimeConfig config = service.current();

        assertThat(config.translationBaseUrl()).isEqualTo("https://dashscope.aliyuncs.com/compatible-mode/v1");
        assertThat(config.translationModel()).isEqualTo("qwen-turbo");
    }

    @Test
    void usesSmallFasterWhisperAsRealtimeDefault() {
        RuntimeConfigService service = new RuntimeConfigService(properties());

        RuntimeConfig config = service.current();

        assertThat(config.asrModelId()).isEqualTo("Systran/faster-whisper-small");
        assertThat(service.options().defaultModels()).containsEntry("faster-whisper", "Systran/faster-whisper-small");
    }

    @Test
    void switchesDefaultModelWhenEngineChanges() {
        RuntimeConfigService service = new RuntimeConfigService(properties());

        RuntimeConfig config = service.update(new RuntimeConfigUpdate(
                null, null, null, "sensevoice", null, null, null, null, null, null
        ));

        assertThat(config.asrModelId()).isEqualTo("iic/SenseVoiceSmall");
        assertThat(service.options().modelStatus()).isEqualTo("missing");
    }

    @Test
    void exposesAnimeWhisperEngine() {
        RuntimeConfigService service = new RuntimeConfigService(properties());

        assertThat(service.options().asrEngines()).contains("anime-whisper");
        assertThat(service.options().defaultModels()).containsEntry("anime-whisper", "litagin/anime-whisper");
    }

    private SimTransProperties properties() {
        return new SimTransProperties(
                "local-asr",
                new SimTransProperties.Audio("windows-wasapi", null, 16000, 1, 512, 100, "LiveTranslateAudio"),
                new SimTransProperties.Asr("dashscope", "", "", "qwen-asr-realtime", "auto", "server_vad", 500, 300, 0.5),
                new SimTransProperties.Vad("silero", 250, 700, 8000, 0.5, 0.012, 0.25, true, 2000, "models/vad/silero/silero_vad.onnx"),
                new SimTransProperties.LocalAsr("faster-whisper", "", "models", "modelscope", "cuda", "float16", "py", 18765, Duration.ofSeconds(120), Duration.ofHours(2)),
                new SimTransProperties.Translation("openai-compatible", "https://dashscope.aliyuncs.com/compatible-mode/v1", "", "qwen-turbo", "zh-CN", true, 0, 0.2, Duration.ofSeconds(45)),
                new SimTransProperties.Subtitle(2, true, 0.86, 28)
        );
    }
}
