package com.muyulu.aisimtrans.vad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import com.muyulu.aisimtrans.audio.AudioChunk;
import com.muyulu.aisimtrans.config.SimTransProperties;

class VadDetectorFactoryTests {
    @Test
    void createsEnergyDetectorOnlyWhenEnergyIsSelected() {
        VadDetectorFactory factory = new VadDetectorFactory(properties("missing.onnx"));

        VadDetector detector = factory.create("energy");

        assertThat(detector).isInstanceOf(EnergyVadDetector.class);
    }

    @Test
    void sileroRequiresOnnxModelInsteadOfFallingBackToEnergy() {
        VadDetectorFactory factory = new VadDetectorFactory(properties("missing.onnx"));

        assertThatThrownBy(() -> factory.create("silero"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Silero VAD ONNX model not found");
    }

    @Test
    @EnabledIf("sileroModelExists")
    void runsSileroOnnxInferenceWhenModelExists() {
        VadDetectorFactory factory = new VadDetectorFactory(properties("models/vad/silero/silero_vad.onnx"));
        VadDetector detector = factory.create("silero");

        boolean speech = detector.isSpeech(new AudioChunk(new byte[1024], 16000, 1, 512, 1, 1));

        assertThat(speech).isFalse();
    }

    static boolean sileroModelExists() {
        return java.nio.file.Files.isRegularFile(java.nio.file.Path.of("models/vad/silero/silero_vad.onnx"));
    }

    private SimTransProperties properties(String sileroModel) {
        return new SimTransProperties(
                "local-asr",
                new SimTransProperties.Audio("windows-wasapi", null, 16000, 1, 512, 100, "LiveTranslateAudio"),
                new SimTransProperties.Asr("dashscope", "", "", "qwen-asr-realtime", "auto", "server_vad", 500, 300, 0.5),
                new SimTransProperties.Vad("silero", 1000, 400, 8000, 0.5, 0.02, 0.25, true, 1000, sileroModel),
                new SimTransProperties.LocalAsr("faster-whisper", "Systran/faster-whisper-small", "models", "modelscope", "cpu", "int8", "py", 18765, Duration.ofSeconds(120), Duration.ofHours(2)),
                new SimTransProperties.Translation("openai-compatible", "https://dashscope.aliyuncs.com/compatible-mode/v1", "", "qwen-turbo", "zh-CN", "", true, 0, 0.2, Duration.ofSeconds(45)),
                new SimTransProperties.Subtitle(2, true, 0.86, 28)
        );
    }
}
