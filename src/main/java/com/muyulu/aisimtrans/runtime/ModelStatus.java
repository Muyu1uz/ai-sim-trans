package com.muyulu.aisimtrans.runtime;

public record ModelStatus(String status, String message) {
    public static ModelStatus ready(String message) {
        return new ModelStatus("ready", message);
    }

    public static ModelStatus missing(String message) {
        return new ModelStatus("missing", message);
    }

    public static ModelStatus loading(String message) {
        return new ModelStatus("loading", message);
    }

    public static ModelStatus error(String message) {
        return new ModelStatus("error", message);
    }
}
