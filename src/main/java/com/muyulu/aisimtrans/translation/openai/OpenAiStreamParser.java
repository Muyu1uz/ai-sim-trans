package com.muyulu.aisimtrans.translation.openai;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class OpenAiStreamParser {
    private final ObjectMapper objectMapper;

    public OpenAiStreamParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<String> parseDeltas(String chunk) {
        List<String> deltas = new ArrayList<>();
        for (String line : chunk.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || "[DONE]".equals(trimmed)) {
                continue;
            }
            String data = trimmed.startsWith("data:")
                    ? trimmed.substring("data:".length()).trim()
                    : trimmed;
            if ("[DONE]".equals(data)) {
                continue;
            }
            parseDelta(data).ifPresent(deltas::add);
        }
        return deltas;
    }

    private java.util.Optional<String> parseDelta(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                return java.util.Optional.empty();
            }
            JsonNode delta = choices.get(0).path("delta").path("content");
            if (delta.isTextual()) {
                return java.util.Optional.of(delta.asText());
            }
            JsonNode message = choices.get(0).path("message").path("content");
            if (message.isTextual()) {
                return java.util.Optional.of(message.asText());
            }
            return java.util.Optional.empty();
        } catch (Exception ex) {
            return java.util.Optional.empty();
        }
    }
}
