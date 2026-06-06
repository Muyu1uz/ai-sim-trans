package com.muyulu.aisimtrans.runtime;

public record RuntimeConfig(
        String mode,
        String audioDeviceName,
        String vadProvider,
        String asrEngine,
        String asrModelId,
        String asrDevice,
        String asrComputeType,
        String translationBaseUrl,
        String translationApiKey,
        String translationModel
) {
}
