package com.muyulu.aisimtrans.correction;

public record CorrectionSegment(
        String segmentId,
        String sourceText,
        String translationText,
        long revision
) {
}
