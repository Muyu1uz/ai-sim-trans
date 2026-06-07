package com.muyulu.aisimtrans.asr.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muyulu.aisimtrans.asr.AsrException;
import com.muyulu.aisimtrans.audio.AudioFrameQueue;
import com.muyulu.aisimtrans.config.SimTransProperties;
import com.muyulu.aisimtrans.runtime.ModelStatus;
import com.muyulu.aisimtrans.service.RuntimeConfigService;
import com.muyulu.aisimtrans.vad.VadDetectorFactory;

class LocalAsrProviderTests {
    @Test
    void doesNotRemainRunningWhenModelLoadFails() {
        SimTransProperties properties = properties();
        RuntimeConfigService runtimeConfigService = new RuntimeConfigService(properties);
        LocalAsrModelManager modelManager = mock(LocalAsrModelManager.class);
        when(modelManager.ensureLoaded()).thenReturn(ModelStatus.error("python service unavailable"));
        LocalAsrProvider provider = new LocalAsrProvider(
                properties,
                runtimeConfigService,
                modelManager,
                new VadDetectorFactory(properties),
                new ObjectMapper()
        );

        assertThatThrownBy(() -> provider.start(new AudioFrameQueue(properties), ignored -> {
        }))
                .isInstanceOf(AsrException.class)
                .hasMessageContaining("python service unavailable");
        assertThat(provider.isRunning()).isFalse();
    }

    private SimTransProperties properties() {
        return new SimTransProperties(
                "local-asr",
                new SimTransProperties.Audio("windows-wasapi", null, 16000, 1, 512, 100, "LiveTranslateAudio"),
                new SimTransProperties.Asr("dashscope", "", "", "qwen-asr-realtime", "auto", "server_vad", 500, 300, 0.5),
                new SimTransProperties.Vad("silero", 250, 700, 8000, 0.5, 0.012, 0.25, true, 2000, "models/vad/silero/silero_vad.onnx"),
                new SimTransProperties.LocalAsr("faster-whisper", "Systran/faster-whisper-small", "models", "modelscope", "cuda", "float16", "py", 18765, Duration.ofSeconds(120), Duration.ofHours(2)),
                new SimTransProperties.Translation("openai-compatible", "https://dashscope.aliyuncs.com/compatible-mode/v1", "", "qwen-turbo", "zh-CN", "", true, 0, 0.2, Duration.ofSeconds(45)),
                new SimTransProperties.Subtitle(2, true, 0.86, 28)
        );
    }
}
