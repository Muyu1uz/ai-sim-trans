package com.muyulu.aisimtrans.asr;

public record AsrEvent(
        AsrEventType type,
        String segmentId,
        String text,
        long eventTimeMillis,
        String rawEvent
) {
    public static AsrEvent error(String message) {
        return new AsrEvent(AsrEventType.ERROR, null, message, System.currentTimeMillis(), null);
    }
}
