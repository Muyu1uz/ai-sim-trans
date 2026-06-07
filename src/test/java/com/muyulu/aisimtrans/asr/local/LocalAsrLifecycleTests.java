package com.muyulu.aisimtrans.asr.local;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.muyulu.aisimtrans.config.SimTransProperties;
import com.muyulu.aisimtrans.service.RuntimeConfigService;

class LocalAsrLifecycleTests {
    @Test
    void startsServiceWhenApplicationIsReadyInLocalAsrMode() throws Exception {
        RuntimeConfigService runtimeConfigService = new RuntimeConfigService(properties("local-asr"));
        LocalAsrServiceProcess serviceProcess = mock(LocalAsrServiceProcess.class);
        LocalAsrLifecycle lifecycle = new LocalAsrLifecycle(runtimeConfigService, serviceProcess);

        lifecycle.startWithApplication();

        verify(serviceProcess).ensureRunning();
    }

    @Test
    void doesNotStartServiceWhenApplicationIsReadyInCloudMode() throws Exception {
        RuntimeConfigService runtimeConfigService = new RuntimeConfigService(properties("dashscope-livetranslate"));
        LocalAsrServiceProcess serviceProcess = mock(LocalAsrServiceProcess.class);
        LocalAsrLifecycle lifecycle = new LocalAsrLifecycle(runtimeConfigService, serviceProcess);

        lifecycle.startWithApplication();

        verifyNoInteractions(serviceProcess);
    }

    @Test
    void stopsServiceWhenApplicationCloses() {
        RuntimeConfigService runtimeConfigService = new RuntimeConfigService(properties("local-asr"));
        LocalAsrServiceProcess serviceProcess = mock(LocalAsrServiceProcess.class);
        LocalAsrLifecycle lifecycle = new LocalAsrLifecycle(runtimeConfigService, serviceProcess);

        lifecycle.stopWithApplication();

        verify(serviceProcess).stop();
    }

    private SimTransProperties properties(String mode) {
        return new SimTransProperties(
                mode,
                new SimTransProperties.Audio("windows-wasapi", null, 16000, 1, 512, 100, "LiveTranslateAudio"),
                new SimTransProperties.Asr("dashscope", "", "", "qwen-asr-realtime", "auto", "server_vad", 500, 300, 0.5),
                new SimTransProperties.Vad("silero", 250, 700, 8000, 0.5, 0.012, 0.25, true, 2000, "models/vad/silero/silero_vad.onnx"),
                new SimTransProperties.LocalAsr("faster-whisper", "Systran/faster-whisper-small", "models", "modelscope", "cuda", "float16", "py", 18765, Duration.ofSeconds(120), Duration.ofHours(2)),
                new SimTransProperties.Translation("openai-compatible", "https://dashscope.aliyuncs.com/compatible-mode/v1", "", "qwen-turbo", "zh-CN", "", true, 0, 0.2, Duration.ofSeconds(45)),
                new SimTransProperties.Subtitle(2, true, 0.86, 28)
        );
    }
}
