package com.muyulu.aisimtrans.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "simtrans")
public record SimTransProperties(
        Audio audio,
        Asr asr,
        Translation translation,
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

    public record Translation(
            String provider,
            String baseUrl,
            String apiKey,
            String model,
            String targetLanguage,
            boolean stream,
            int contextSegments,
            double temperature,
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
