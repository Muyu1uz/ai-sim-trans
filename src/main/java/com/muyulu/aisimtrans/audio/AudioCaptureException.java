package com.muyulu.aisimtrans.audio;

public class AudioCaptureException extends RuntimeException {
    public AudioCaptureException(String message) {
        super(message);
    }

    public AudioCaptureException(String message, Throwable cause) {
        super(message, cause);
    }
}
