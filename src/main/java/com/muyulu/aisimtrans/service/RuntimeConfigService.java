package com.muyulu.aisimtrans.service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import com.muyulu.aisimtrans.runtime.ModelStatus;
import com.muyulu.aisimtrans.runtime.RuntimeConfig;
import com.muyulu.aisimtrans.runtime.RuntimeConfigUpdate;
import com.muyulu.aisimtrans.runtime.RuntimeOptions;
import org.springframework.stereotype.Service;

import com.muyulu.aisimtrans.config.SimTransProperties;

@Service
public class RuntimeConfigService {
    public static final String MODE_LOCAL_ASR = "local-asr";
    public static final String MODE_DASHSCOPE_LIVETRANSLATE = "dashscope-livetranslate";

    private static final Map<String, String> DEFAULT_MODELS = Map.of(
            "faster-whisper", "Systran/faster-whisper-small",
            "sensevoice", "iic/SenseVoiceSmall",
            "funasr-nano", "FunAudioLLM/Fun-ASR-Nano-2512",
            "anime-whisper", "litagin/anime-whisper"
    );

    private final AtomicReference<RuntimeConfig> config;
    private final AtomicReference<ModelStatus> modelStatus = new AtomicReference<>(ModelStatus.missing("model not checked"));

    public RuntimeConfigService(SimTransProperties properties) {
        this.config = new AtomicReference<>(new RuntimeConfig(
                valueOrDefault(properties.mode(), MODE_LOCAL_ASR),
                properties.audio().deviceName() == null ? "" : properties.audio().deviceName(),
                normalizeVad(valueOrDefault(properties.vad().provider(), "energy")),
                valueOrDefault(properties.localAsr().engine(), "sensevoice"),
                valueOrDefault(properties.localAsr().modelId(), DEFAULT_MODELS.get("sensevoice")),
                valueOrDefault(properties.localAsr().device(), "cuda"),
                valueOrDefault(properties.localAsr().computeType(), "float16"),
                valueOrDefault(properties.translation().baseUrl(), "https://dashscope.aliyuncs.com/compatible-mode/v1"),
                properties.translation().apiKey() == null ? "" : properties.translation().apiKey(),
                valueOrDefault(properties.translation().model(), "qwen-turbo"),
                properties.translation().prompt() == null ? "" : properties.translation().prompt()
        ));
    }

    public RuntimeConfig current() {
        return config.get();
    }

    public RuntimeConfig update(RuntimeConfigUpdate update) {
        RuntimeConfig current = config.get();
        RuntimeConfig next = new RuntimeConfig(
                normalizeMode(valueOrDefault(update.mode(), current.mode())),
                update.audioDeviceName() == null ? current.audioDeviceName() : update.audioDeviceName(),
                normalizeVad(valueOrDefault(update.vadProvider(), current.vadProvider())),
                normalizeEngine(valueOrDefault(update.asrEngine(), current.asrEngine())),
                null,
                valueOrDefault(update.asrDevice(), current.asrDevice()),
                valueOrDefault(update.asrComputeType(), current.asrComputeType()),
                valueOrDefault(update.translationBaseUrl(), current.translationBaseUrl()),
                update.translationApiKey() == null ? current.translationApiKey() : update.translationApiKey(),
                valueOrDefault(update.translationModel(), current.translationModel()),
                update.translationPrompt() == null ? current.translationPrompt() : update.translationPrompt()
        );
        String modelId = valueOrDefault(update.asrModelId(), DEFAULT_MODELS.getOrDefault(next.asrEngine(), current.asrModelId()));
        next = new RuntimeConfig(
                next.mode(),
                next.audioDeviceName(),
                next.vadProvider(),
                next.asrEngine(),
                modelId,
                next.asrDevice(),
                next.asrComputeType(),
                next.translationBaseUrl(),
                next.translationApiKey(),
                next.translationModel(),
                next.translationPrompt()
        );
        if (!Objects.equals(current.asrEngine(), next.asrEngine()) || !Objects.equals(current.asrModelId(), next.asrModelId())) {
            modelStatus.set(ModelStatus.missing("model selection changed"));
        }
        config.set(next);
        return next;
    }

    public RuntimeOptions options() {
        ModelStatus status = modelStatus.get();
        return new RuntimeOptions(
                current(),
                status.status(),
                status.message(),
                List.of(MODE_LOCAL_ASR, MODE_DASHSCOPE_LIVETRANSLATE),
                List.of("energy", "disabled"),
                List.of("faster-whisper", "sensevoice", "funasr-nano", "anime-whisper"),
                DEFAULT_MODELS
        );
    }

    public void setModelStatus(ModelStatus status) {
        modelStatus.set(status);
    }

    private String normalizeMode(String mode) {
        return MODE_DASHSCOPE_LIVETRANSLATE.equals(mode) ? mode : MODE_LOCAL_ASR;
    }

    private String normalizeVad(String provider) {
        return switch (provider) {
            case "energy", "disabled" -> provider;
            default -> "energy";
        };
    }

    private String normalizeEngine(String engine) {
        return DEFAULT_MODELS.containsKey(engine) ? engine : "faster-whisper";
    }

    private static String valueOrDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
