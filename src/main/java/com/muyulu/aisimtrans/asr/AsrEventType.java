package com.muyulu.aisimtrans.asr;

public enum AsrEventType {
    SPEECH_STARTED,
    PARTIAL,
    FINAL,
    TRANSCRIPT_RESULT,
    TRANSLATION_RESULT,
    CORRECTION,
    SPEECH_ENDED,
    ERROR
}
