package com.muyulu.aisimtrans.correction;

public record CorrectionResult(
        String segmentId,
        String sourceText,
        String translationText,
        double confidence
) {
}
