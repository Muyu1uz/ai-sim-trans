package com.muyulu.aisimtrans.translation.openai;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import com.muyulu.aisimtrans.config.SimTransProperties;
import com.muyulu.aisimtrans.runtime.RuntimeConfig;
import com.muyulu.aisimtrans.service.RuntimeConfigService;
import com.muyulu.aisimtrans.translation.TranslationDelta;
import com.muyulu.aisimtrans.translation.TranslationMemory;
import com.muyulu.aisimtrans.translation.TranslationProvider;
import com.muyulu.aisimtrans.translation.TranslationRequest;

@Component
public class OpenAiCompatibleTranslationProvider implements TranslationProvider {
    private final SimTransProperties properties;
    private final RuntimeConfigService runtimeConfigService;
    private final OpenAiStreamParser streamParser;
    private volatile String webClientBaseUrl;
    private volatile WebClient webClient;

    public OpenAiCompatibleTranslationProvider(
            SimTransProperties properties,
            RuntimeConfigService runtimeConfigService,
            OpenAiStreamParser streamParser
    ) {
        this.properties = properties;
        this.runtimeConfigService = runtimeConfigService;
        this.streamParser = streamParser;
    }

    @Override
    public void translate(TranslationRequest request, Consumer<TranslationDelta> deltaConsumer) {
        StringBuilder fullText = new StringBuilder();
        RuntimeConfig config = runtimeConfigService.current();
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
                .timeout(properties.translation().timeout())
                .doOnNext(chunk -> {
                    List<String> deltas = streamParser.parseDeltas(chunk);
                    for (String delta : deltas) {
                        if (!delta.isEmpty()) {
                            fullText.append(delta);
                            deltaConsumer.accept(new TranslationDelta(delta, false));
                        }
                    }
                })
                .blockLast();
        deltaConsumer.accept(new TranslationDelta(fullText.toString(), true));
    }

    private Map<String, Object> requestBody(TranslationRequest request, RuntimeConfig config) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", config.translationModel());
        body.put("stream", properties.translation().stream());
        body.put("temperature", properties.translation().temperature());
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt(request)),
                Map.of("role", "user", "content", request.sourceText())
        ));
        return body;
    }

    private String systemPrompt(TranslationRequest request) {
        List<TranslationMemory.Entry> context = request.context();
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are a low-latency simultaneous interpretation assistant. ");
        prompt.append("Translate the incoming source text into ")
                .append(properties.translation().targetLanguage())
                .append(". Keep meaning faithful, concise, natural, and consistent with recent context. ");
        if (!request.finalText()) {
            prompt.append("This is an interim partial transcript; translate only the provided text and avoid adding unstated completion. ");
        }
        prompt.append("Return only the translated text.\n");
        String customPrompt = runtimeConfigService.current().translationPrompt();
        if (customPrompt != null && !customPrompt.isBlank()) {
            prompt.append("User translation instructions:\n");
            prompt.append(customPrompt.strip()).append('\n');
        }
        if (!context.isEmpty()) {
            prompt.append("Recent context:\n");
            for (TranslationMemory.Entry entry : context) {
                prompt.append("Source: ").append(entry.sourceText()).append('\n');
                prompt.append("Translation: ").append(entry.translationText()).append('\n');
            }
        }
        return prompt.toString();
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
