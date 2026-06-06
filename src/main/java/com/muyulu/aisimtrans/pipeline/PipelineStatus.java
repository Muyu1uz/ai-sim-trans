package com.muyulu.aisimtrans.pipeline;

public record PipelineStatus(
        boolean running,
        boolean captureRunning,
        boolean asrRunning,
        int queuedAudioChunks,
        long droppedAudioChunks,
        double lastAudioRms,
        long lastAudioAtMillis,
        long audioCallbackCount
) {
}
