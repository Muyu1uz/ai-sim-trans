package com.muyulu.aisimtrans.correction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.muyulu.aisimtrans.config.SimTransProperties;
import com.muyulu.aisimtrans.runtime.RuntimeConfig;
import com.muyulu.aisimtrans.service.RuntimeConfigService;
import com.muyulu.aisimtrans.translation.openai.OpenAiStreamParser;

@Component
public class OpenAiCompatibleCorrectionReviewer implements CorrectionReviewer {
    private final SimTransProperties properties;
    private final RuntimeConfigService runtimeConfigService;
    private final OpenAiStreamParser streamParser;
    private final ObjectMapper objectMapper;
    private volatile String webClientBaseUrl;
    private volatile WebClient webClient;

    public OpenAiCompatibleCorrectionReviewer(
            SimTransProperties properties,
            RuntimeConfigService runtimeConfigService,
            OpenAiStreamParser streamParser,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.runtimeConfigService = runtimeConfigService;
        this.streamParser = streamParser;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<CorrectionResult> review(CorrectionRequest request) {
        if (request.segments().isEmpty()) {
            return List.of();
        }
        RuntimeConfig config = runtimeConfigService.current();
        StringBuilder fullText = new StringBuilder();
        webClient(config.translationBaseUrl()).post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .headers(headers -> {
                    String apiKey = config.translationApiKey();
                    if (apiKey != null && !apiKey.isBlank()) {
                        headers.setBearerAuth(apiKey);
                    }
                })
                .bodyValue(requestBody(request, config))
                .retrieve()
                .bodyToFlux(String.class)
                .timeout(properties.correction().timeout())
                .doOnNext(chunk -> {
                    for (String delta : streamParser.parseDeltas(chunk)) {
                        fullText.append(delta);
                    }
                })
                .blockLast();
        return parseResults(fullText.toString(), request.segments());
    }

    private Map<String, Object> requestBody(CorrectionRequest request, RuntimeConfig config) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", config.translationModel());
        body.put("stream", properties.translation().stream());
        body.put("temperature", 0);
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt(request.targetLanguage())),
                Map.of("role", "user", "content", userPrompt(request.segments()))
        ));
        return body;
    }

    private String systemPrompt(String targetLanguage) {
        return """
                You are reviewing live subtitles after more context arrived.
                Correct only obvious ASR or translation errors that are proven by neighboring segments.
                Keep each segment concise and faithful. Do not paraphrase correct text.
                Return only JSON with this shape:
                {"corrections":[{"segmentId":"...","sourceText":"...","translationText":"...","confidence":0.0}]}
                Omit segments that do not need correction.
                Target translation language: %s.
                """.formatted(targetLanguage);
    }

    private String userPrompt(List<CorrectionSegment> segments) {
        try {
            return objectMapper.writeValueAsString(Map.of("segments", segments));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize correction context.", ex);
        }
    }

    private List<CorrectionResult> parseResults(String text, List<CorrectionSegment> segments) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String json = extractJson(text);
        Set<String> allowedIds = segments.stream()
                .map(CorrectionSegment::segmentId)
                .collect(java.util.stream.Collectors.toSet());
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode corrections = root.path("corrections");
            if (!corrections.isArray()) {
                return List.of();
            }
            List<CorrectionResult> results = new ArrayList<>();
            for (JsonNode item : corrections) {
                String segmentId = item.path("segmentId").asText("");
                if (!allowedIds.contains(segmentId)) {
                    continue;
                }
                results.add(new CorrectionResult(
                        segmentId,
                        item.path("sourceText").asText(null),
                        item.path("translationText").asText(null),
                        item.path("confidence").asDouble(0)
                ));
            }
            return results;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse correction response.", ex);
        }
    }

    private String extractJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return text;
        }
        return text.substring(start, end + 1);
    }

    private String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "http://localhost:11434/v1";
        }
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        if (result.endsWith("/v1")) {
            return result.substring(0, result.length() - 3);
        }
        return result;
    }

    private WebClient webClient(String baseUrl) {
        String normalized = stripTrailingSlash(baseUrl);
        WebClient local = webClient;
        if (local != null && normalized.equals(webClientBaseUrl)) {
            return local;
        }
        synchronized (this) {
            if (webClient == null || !normalized.equals(webClientBaseUrl)) {
                webClientBaseUrl = normalized;
                webClient = WebClient.builder().baseUrl(normalized).build();
            }
            return webClient;
        }
    }
}
