package com.muyulu.aisimtrans.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "simtrans")
public record SimTransProperties(
        String mode,
        Audio audio,
        Asr asr,
        Vad vad,
        LocalAsr localAsr,
        Translation translation,
        Correction correction,
        Subtitle subtitle
) {
    public record Audio(
            String provider,
            String deviceName,
            int sampleRate,
            int channels,
            int chunkSamples,
            int queueCapacity,
            String nativeLibrary
    ) {
    }

    public record Asr(
            String provider,
            String baseUrl,
            String apiKey,
            String model,
            String language,
            String turnDetection,
            int silenceDurationMs,
            int prefixPaddingMs,
            double threshold
    ) {
    }

    public record Vad(
            String provider,
            int minSpeechMs,
            int minSilenceMs,
            int maxSpeechMs,
            double threshold,
            double energyThreshold,
            double speechDensityThreshold,
            boolean incrementalAsr,
            int interimIntervalMs,
            String sileroModel
    ) {
    }

    public record LocalAsr(
            String engine,
            String modelId,
            String modelsDir,
            String downloadSource,
            String device,
            String computeType,
            String python,
            int servicePort,
            Duration startupTimeout,
            Duration modelTimeout
    ) {
    }

    public record Translation(
            String provider,
            String baseUrl,
            String apiKey,
            String model,
            String targetLanguage,
            String prompt,
            boolean stream,
            int contextSegments,
            double temperature,
            Duration timeout
    ) {
    }

    public record Correction(
            boolean enabled,
            int reviewWindowSegments,
            int minCompletedSegments,
            int debounceMs,
            double confidenceThreshold,
            int maxCorrectionsPerSegment,
            Duration timeout
    ) {
    }

    public record Subtitle(
            int maxLines,
            boolean showSource,
            double opacity,
            int fontSize
    ) {
    }
}
