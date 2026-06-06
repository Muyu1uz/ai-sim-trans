package com.muyulu.aisimtrans.translation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.springframework.stereotype.Component;

import com.muyulu.aisimtrans.config.SimTransProperties;

@Component
public class TranslationMemory {
    private final int maxEntries;
    private final Deque<Entry> entries = new ArrayDeque<>();

    public TranslationMemory(SimTransProperties properties) {
        this.maxEntries = Math.max(0, properties.translation().contextSegments());
    }

    public synchronized List<Entry> snapshot() {
        return new ArrayList<>(entries);
    }

    public synchronized void remember(String sourceText, String translationText) {
        if (maxEntries == 0 || sourceText == null || sourceText.isBlank()) {
            return;
        }
        entries.addLast(new Entry(sourceText, translationText == null ? "" : translationText));
        while (entries.size() > maxEntries) {
            entries.removeFirst();
        }
    }

    public record Entry(String sourceText, String translationText) {
    }
}
