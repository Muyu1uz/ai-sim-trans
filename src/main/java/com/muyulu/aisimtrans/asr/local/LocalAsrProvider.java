package com.muyulu.aisimtrans.asr.local;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.muyulu.aisimtrans.asr.AsrException;
import com.muyulu.aisimtrans.asr.AsrEvent;
import com.muyulu.aisimtrans.asr.AsrEventType;
import com.muyulu.aisimtrans.asr.AsrProvider;
import com.muyulu.aisimtrans.audio.AudioChunk;
import com.muyulu.aisimtrans.audio.AudioFrameQueue;
import com.muyulu.aisimtrans.config.SimTransProperties;
import com.muyulu.aisimtrans.runtime.ModelStatus;
import com.muyulu.aisimtrans.runtime.RuntimeConfig;
import com.muyulu.aisimtrans.runtime.RuntimeConfigService;
import com.muyulu.aisimtrans.vad.VadDetector;
import com.muyulu.aisimtrans.vad.VadDetectorFactory;

@Component
@Qualifier("localAsrProvider")
public class LocalAsrProvider implements AsrProvider {
    private static final Logger log = LoggerFactory.getLogger(LocalAsrProvider.class);
    private static final int SILENCE_PADDING_MS = 120;
    private static final int MIN_INTERIM_AUDIO_MS = 1200;
    private static final int MAX_INTERIM_AUDIO_MS = 4000;

    private final SimTransProperties properties;
    private final RuntimeConfigService runtimeConfigService;
    private final LocalAsrModelManager modelManager;
    private final VadDetectorFactory vadDetectorFactory;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("local-asr-", 0).factory());
    private final ExecutorService interimExecutor = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("local-asr-interim-", 0).factory());
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean interimRunning = new AtomicBoolean();
    private volatile Future<?> task;

    public LocalAsrProvider(
            SimTransProperties properties,
            RuntimeConfigService runtimeConfigService,
            LocalAsrModelManager modelManager,
            VadDetectorFactory vadDetectorFactory,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.runtimeConfigService = runtimeConfigService;
        this.modelManager = modelManager;
        this.vadDetectorFactory = vadDetectorFactory;
        this.objectMapper = objectMapper;
    }

    @Override
    public void start(AudioFrameQueue audioQueue, Consumer<AsrEvent> eventConsumer) {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            ModelStatus status = modelManager.ensureLoaded();
            if (!"ready".equals(status.status())) {
                throw new AsrException("Local ASR model is not ready: " + status.message());
            }
            RuntimeConfig config = runtimeConfigService.current();
            log.info("Starting local VAD/ASR, vad={}, engine={}, model={}", config.vadProvider(), config.asrEngine(), config.asrModelId());
            VadDetector vad = vadDetectorFactory.create(config.vadProvider());
            task = executor.submit(() -> processAudio(audioQueue, vad, eventConsumer));
        } catch (RuntimeException ex) {
            running.set(false);
            throw ex;
        }
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        Future<?> local = task;
        if (local != null) {
            local.cancel(true);
        }
        log.info("Local VAD/ASR stopped");
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    private void processAudio(AudioFrameQueue audioQueue, VadDetector vad, Consumer<AsrEvent> eventConsumer) {
        ByteArrayOutputStream segment = new ByteArrayOutputStream();
        int speechMs = 0;
        int silenceMs = 0;
        int lastInterimMs = 0;
        String segmentId = UUID.randomUUID().toString();
        boolean inSpeech = false;
        try {
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    AudioChunk chunk = audioQueue.poll(200);
                    if (chunk == null) {
                        continue;
                    }
                    int chunkMs = chunkMillis(chunk);
                    boolean speech = vad.isSpeech(chunk);
                    if (speech) {
                        if (!inSpeech) {
                            inSpeech = true;
                            segmentId = UUID.randomUUID().toString();
                            speechMs = 0;
                            silenceMs = 0;
                            lastInterimMs = 0;
                            eventConsumer.accept(new AsrEvent(AsrEventType.SPEECH_STARTED, segmentId, "", System.currentTimeMillis(), null));
                        }
                        speechMs += chunkMs;
                        silenceMs = 0;
                        segment.write(chunk.pcm16le());
                        if (shouldTranscribeInterim(speechMs, lastInterimMs, segment.size())) {
                            lastInterimMs = speechMs;
                            transcribeInterim(segmentId, interimWindow(segment.toByteArray()), eventConsumer);
                        }
                        if (properties.vad().maxSpeechMs() > 0 && speechMs >= properties.vad().maxSpeechMs()) {
                            finishSegment(segmentId, segment, eventConsumer);
                            inSpeech = false;
                        }
                        continue;
                    }
                    if (inSpeech) {
                        silenceMs += chunkMs;
                        if (silenceMs <= SILENCE_PADDING_MS) {
                            segment.write(chunk.pcm16le());
                        }
                        if (speechMs >= properties.vad().minSpeechMs() && silenceMs >= silenceThresholdMs(speechMs)) {
                            finishSegment(segmentId, segment, eventConsumer);
                            inSpeech = false;
                        }
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException | IOException ex) {
                    log.error("Local ASR processing failed: {}", ex.getMessage(), ex);
                    eventConsumer.accept(AsrEvent.error("Local ASR failed: " + ex.getMessage()));
                }
            }
        } finally {
            closeVad(vad);
        }
    }

    private void finishSegment(String segmentId, ByteArrayOutputStream segment, Consumer<AsrEvent> eventConsumer) {
        transcribeFinal(segmentId, segment.toByteArray(), eventConsumer);
        eventConsumer.accept(new AsrEvent(AsrEventType.SPEECH_ENDED, segmentId, "", System.currentTimeMillis(), null));
        segment.reset();
    }

    private boolean shouldTranscribeInterim(int speechMs, int lastInterimMs, int segmentBytes) {
        return properties.vad().incrementalAsr()
                && speechMs >= MIN_INTERIM_AUDIO_MS
                && speechMs - lastInterimMs >= Math.max(800, properties.vad().interimIntervalMs())
                && segmentBytes > 0;
    }

    private int silenceThresholdMs(int speechMs) {
        int base = properties.vad().minSilenceMs();
        if (speechMs < 3000) {
            return Math.min(500, Math.max(300, base + 100));
        }
        if (speechMs < 6000) {
            return Math.min(500, Math.max(300, base));
        }
        return Math.min(500, Math.max(300, base - 100));
    }

    private byte[] interimWindow(byte[] pcm16le) {
        int bytesPerSecond = properties.audio().sampleRate() * properties.audio().channels() * Short.BYTES;
        int maxBytes = bytesPerSecond * MAX_INTERIM_AUDIO_MS / 1000;
        if (pcm16le.length <= maxBytes) {
            return pcm16le;
        }
        byte[] window = new byte[maxBytes];
        System.arraycopy(pcm16le, pcm16le.length - maxBytes, window, 0, maxBytes);
        return window;
    }

    private void closeVad(VadDetector vad) {
        if (vad instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ex) {
                log.warn("Failed to close VAD detector: {}", ex.getMessage());
            }
        }
    }

    private void transcribeInterim(String segmentId, byte[] pcm16le, Consumer<AsrEvent> eventConsumer) {
        if (!interimRunning.compareAndSet(false, true)) {
            return;
        }
        interimExecutor.submit(() -> {
            try {
                String text = transcribeText(segmentId, pcm16le);
                if (text != null && !text.isBlank()) {
                    eventConsumer.accept(new AsrEvent(AsrEventType.PARTIAL, segmentId, text, System.currentTimeMillis(), null));
                }
            } finally {
                interimRunning.set(false);
            }
        });
    }

    private void transcribeFinal(String segmentId, byte[] pcm16le, Consumer<AsrEvent> eventConsumer) {
        String text = transcribeText(segmentId, pcm16le);
        if (text != null && !text.isBlank()) {
            eventConsumer.accept(new AsrEvent(AsrEventType.FINAL, segmentId, text, System.currentTimeMillis(), null));
        }
    }

    private String transcribeText(String segmentId, byte[] pcm16le) {
        if (pcm16le.length == 0) {
            return "";
        }
        log.info("Starting local ASR transcription, segmentId={}, audioBytes={}", segmentId, pcm16le.length);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> error = new AtomicReference<>();
        AtomicReference<String> result = new AtomicReference<>("");
        HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(serviceUri("/ws/asr"), new Listener(segmentId, done, error, result))
                .thenAccept(socket -> socket.sendText(transcribeMessage(segmentId, pcm16le), true)
                        .thenCompose(ignored -> socket.sendText("{\"type\":\"end\"}", true)))
                .join();
        try {
            if (!done.await(properties.localAsr().startupTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("local ASR transcription timed out");
            }
            if (error.get() != null) {
                throw new IllegalStateException(error.get());
            }
            return result.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return "";
        }
    }

    private String transcribeMessage(String segmentId, byte[] pcm16le) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", "audio");
        node.put("segment_id", segmentId);
        node.put("sample_rate", properties.audio().sampleRate());
        node.put("channels", properties.audio().channels());
        node.put("audio", Base64.getEncoder().encodeToString(pcm16le));
        return node.toString();
    }

    private URI serviceUri(String path) {
        return URI.create("ws://127.0.0.1:" + properties.localAsr().servicePort() + path);
    }

    private int chunkMillis(AudioChunk chunk) {
        return (int) Math.max(1, Math.round(chunk.sampleCount() * 1000.0 / chunk.sampleRate()));
    }

    private final class Listener implements WebSocket.Listener {
        private final String segmentId;
        private final CountDownLatch done;
        private final AtomicReference<String> error;
        private final AtomicReference<String> result;
        private final StringBuilder buffer = new StringBuilder();

        private Listener(String segmentId, CountDownLatch done, AtomicReference<String> error, AtomicReference<String> result) {
            this.segmentId = segmentId;
            this.done = done;
            this.error = error;
            this.result = result;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                handle(buffer.toString());
                buffer.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            done.countDown();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            this.error.compareAndSet(null, error.getMessage());
            done.countDown();
        }

        private void handle(String payload) {
            try {
                JsonNode root = objectMapper.readTree(payload);
                String type = root.path("type").asText();
                String text = root.path("text").asText("");
                if ("final".equals(type) || "partial".equals(type)) {
                    result.set(text);
                    done.countDown();
                } else if ("error".equals(type)) {
                    error.compareAndSet(null, root.path("message").asText("local ASR error"));
                    done.countDown();
                }
            } catch (Exception ex) {
                error.compareAndSet(null, "Failed to parse local ASR event: " + ex.getMessage());
                done.countDown();
            }
        }
    }
}
