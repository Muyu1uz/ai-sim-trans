package com.muyulu.aisimtrans.subtitle;

public record SubtitleEvent(
        SubtitleEventType type,
        String segmentId,
        String sourceText,
        String translationText,
        String delta,
        long timestampMillis,
        String message
) {
    public static SubtitleEvent status(String message) {
        return new SubtitleEvent(SubtitleEventType.STATUS, null, null, null, null, System.currentTimeMillis(), message);
    }

    public static SubtitleEvent error(String message) {
        return new SubtitleEvent(SubtitleEventType.ERROR, null, null, null, null, System.currentTimeMillis(), message);
    }
}
