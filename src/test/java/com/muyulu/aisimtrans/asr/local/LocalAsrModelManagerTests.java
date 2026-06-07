package com.muyulu.aisimtrans.asr.local;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muyulu.aisimtrans.config.SimTransProperties;

class LocalAsrModelManagerTests {
    @TempDir
    Path tempDir;

    @Test
    void treatsCompleteFasterWhisperCacheWithStaleTempDirectoryAsReady() throws Exception {
        Path modelPath = tempDir.resolve("model");
        Files.createDirectories(modelPath.resolve("._____temp"));
        Files.writeString(modelPath.resolve("model.bin"), "model");

        LocalAsrModelManager manager = new LocalAsrModelManager(properties(), null, null, new ObjectMapper());

        boolean ready = cacheReady(manager, modelPath, "faster-whisper");

        assertThat(ready).isTrue();
        assertThat(modelPath.resolve("._____temp")).doesNotExist();
    }

    @Test
    void treatsAnimeWhisperCacheWithTransformerFilesAsReady() throws Exception {
        Path modelPath = tempDir.resolve("anime");
        Files.createDirectories(modelPath);
        Files.writeString(modelPath.resolve("config.json"), "{}");

        LocalAsrModelManager manager = new LocalAsrModelManager(properties(), null, null, new ObjectMapper());

        boolean ready = cacheReady(manager, modelPath, "anime-whisper");

        assertThat(ready).isTrue();
    }

    @Test
    void treatsSenseVoiceCacheWithRequiredFilesAsReady() throws Exception {
        Path modelPath = tempDir.resolve("sensevoice");
        Files.createDirectories(modelPath);
        Files.writeString(modelPath.resolve("configuration.json"), "{}");
        Files.writeString(modelPath.resolve("config.yaml"), "config");
        Files.writeString(modelPath.resolve("model.pt"), "model");
        Files.writeString(modelPath.resolve("chn_jpn_yue_eng_ko_spectok.bpe.model"), "tokenizer");
        Files.writeString(modelPath.resolve("am.mvn"), "mvn");

        LocalAsrModelManager manager = new LocalAsrModelManager(properties(), null, null, new ObjectMapper());

        boolean ready = cacheReady(manager, modelPath, "sensevoice");

        assertThat(ready).isTrue();
    }

    @Test
    void treatsPartialSenseVoiceCacheAsNotReady() throws Exception {
        Path modelPath = tempDir.resolve("sensevoice-partial");
        Files.createDirectories(modelPath);
        Files.writeString(modelPath.resolve("configuration.json"), "{}");
        Files.writeString(modelPath.resolve("model.pt"), "model");

        LocalAsrModelManager manager = new LocalAsrModelManager(properties(), null, null, new ObjectMapper());

        boolean ready = cacheReady(manager, modelPath, "sensevoice");

        assertThat(ready).isFalse();
    }

    private SimTransProperties properties() {
        return new SimTransProperties(
                "local-asr",
                new SimTransProperties.Audio("windows-wasapi", null, 16000, 1, 512, 100, "LiveTranslateAudio"),
                new SimTransProperties.Asr("dashscope", "", "", "qwen-asr-realtime", "auto", "server_vad", 500, 300, 0.5),
                new SimTransProperties.Vad("silero", 250, 700, 8000, 0.5, 0.012, 0.25, true, 2000, "models/vad/silero/silero_vad.onnx"),
                new SimTransProperties.LocalAsr("faster-whisper", "Systran/faster-whisper-large-v3", "models", "modelscope", "cuda", "float16", "py", 18765, Duration.ofSeconds(120), Duration.ofHours(2)),
                new SimTransProperties.Translation("openai-compatible", "https://dashscope.aliyuncs.com/compatible-mode/v1", "", "qwen-turbo", "zh-CN", "", true, 0, 0.2, Duration.ofSeconds(45)),
                new SimTransProperties.Subtitle(2, true, 0.86, 28)
        );
    }

    private boolean cacheReady(LocalAsrModelManager manager, Path path, String engine) throws Exception {
        Method method = LocalAsrModelManager.class.getDeclaredMethod("cacheReady", Path.class, String.class);
        method.setAccessible(true);
        return (boolean) method.invoke(manager, path, engine);
    }
}
