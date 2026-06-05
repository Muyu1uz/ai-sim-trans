package com.muyulu.aisimtrans.asr.dashscope;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.muyulu.aisimtrans.asr.AsrEvent;
import com.muyulu.aisimtrans.asr.AsrEventType;

@Component
public class DashScopeEventParser {
    private final ObjectMapper objectMapper;

    public DashScopeEventParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Optional<AsrEvent> parse(String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            String eventName = text(root, "event")
                    .or(() -> text(root, "type"))
                    .orElse("");
            String normalized = eventName.toLowerCase(Locale.ROOT);
            String segmentId = text(root, "segment_id")
                    .or(() -> text(root, "sentence_id"))
                    .or(() -> text(root, "id"))
                    .or(() -> text(root, "item_id"))
                    .orElseGet(() -> UUID.nameUUIDFromBytes(payload.getBytes()).toString());
            String translationText = findTranslationText(root).orElse("");
            String text = translationText.isBlank()
                    ? findEventText(normalized, root).orElse("")
                    : translationText;

            AsrEventType type = translationText.isBlank()
                    ? classify(normalized, root, text)
                    : AsrEventType.TRANSLATION_RESULT;
            if (type == null) {
                return Optional.empty();
            }
            return Optional.of(new AsrEvent(type, segmentId, text, System.currentTimeMillis(), payload));
        } catch (Exception ex) {
            return Optional.of(AsrEvent.error("Failed to parse ASR event: " + ex.getMessage()));
        }
    }

    private AsrEventType classify(String eventName, JsonNode root, String text) {
        if (eventName.equals("response.text.done")
                || eventName.equals("response.text.text")) {
            return text.isBlank() ? null : AsrEventType.TRANSLATION_RESULT;
        }
        if (eventName.equals("response.audio_transcript.text")
                || eventName.equals("response.audio_transcript.done")
                || eventName.equals("conversation.item.input_audio_transcription.text")
                || eventName.equals("conversation.item.input_audio_transcription.completed")) {
            return text.isBlank() ? null : AsrEventType.TRANSCRIPT_RESULT;
        }
        if (eventName.contains("speech_started") || eventName.contains("speech.start")) {
            return AsrEventType.SPEECH_STARTED;
        }
        if (eventName.contains("speech_ended") || eventName.contains("speech.end")) {
            return AsrEventType.SPEECH_ENDED;
        }
        if (eventName.contains("error")) {
            return AsrEventType.ERROR;
        }
        if (eventName.contains("correction") || eventName.contains("corrected")) {
            return AsrEventType.CORRECTION;
        }
        if (eventName.contains("completed") || eventName.contains("final") || booleanValue(root, "is_final")) {
            return text.isBlank() ? null : AsrEventType.FINAL;
        }
        if (eventName.contains("delta") || eventName.contains("partial") || eventName.contains("transcript")) {
            return text.isBlank() ? null : AsrEventType.PARTIAL;
        }
        return text.isBlank() ? null : AsrEventType.PARTIAL;
    }

    private Optional<String> findEventText(String eventName, JsonNode root) {
        if (eventName.equals("response.text.text") || eventName.equals("response.audio_transcript.text")) {
            return combinedTextAndStash(root);
        }
        return findText(root);
    }

    private Optional<String> combinedTextAndStash(JsonNode root) {
        String text = text(root, "text").orElse("");
        String stash = text(root, "stash").orElse("");
        String combined = text + stash;
        return combined.isBlank() ? Optional.empty() : Optional.of(combined);
    }

    private Optional<String> findTranslationText(JsonNode node) {
        for (String field : new String[]{"translation", "translated_text", "translation_text", "translatedText"}) {
            Optional<String> value = text(node, field);
            if (value.isPresent()) {
                return value;
            }
        }
        for (String field : new String[]{"translation", "translation_result", "translated"}) {
            JsonNode child = node.get(field);
            if (child != null && child.isObject()) {
                Optional<String> value = findText(child);
                if (value.isPresent()) {
                    return value;
                }
            }
        }
        for (JsonNode child : node) {
            Optional<String> value = findTranslationText(child);
            if (value.isPresent()) {
                return value;
            }
        }
        return Optional.empty();
    }

    private Optional<String> findText(JsonNode node) {
        for (String field : new String[]{"text", "transcript", "sentence", "result", "content", "delta"}) {
            Optional<String> value = text(node, field);
            if (value.isPresent()) {
                return value;
            }
        }
        for (JsonNode child : node) {
            Optional<String> value = findText(child);
            if (value.isPresent()) {
                return value;
            }
        }
        return Optional.empty();
    }

    private Optional<String> text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        String text = value.asText();
        return text == null || text.isBlank() ? Optional.empty() : Optional.of(text);
    }

    private boolean booleanValue(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.asBoolean(false);
    }
}
