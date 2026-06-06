package com.muyulu.aisimtrans.translation;

import java.util.Collections;
import java.util.List;

public record TranslationRequest(
        String segmentId,
        String sourceText,
        boolean finalText,
        List<TranslationMemory.Entry> context
) {
    public static TranslationRequest finalOnly(String segmentId, String sourceText) {
        return new TranslationRequest(segmentId, sourceText, true, Collections.emptyList());
    }

    public static TranslationRequest interim(String segmentId, String sourceText) {
        return new TranslationRequest(segmentId, sourceText, false, Collections.emptyList());
    }
}
