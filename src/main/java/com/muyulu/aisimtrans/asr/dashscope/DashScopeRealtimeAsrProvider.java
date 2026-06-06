package com.muyulu.aisimtrans.asr.dashscope;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Qualifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.muyulu.aisimtrans.asr.AsrProvider;
import com.muyulu.aisimtrans.asr.AsrEvent;
import com.muyulu.aisimtrans.asr.AsrException;
import com.muyulu.aisimtrans.audio.AudioChunk;
import com.muyulu.aisimtrans.audio.AudioFrameQueue;
import com.muyulu.aisimtrans.config.SimTransProperties;

@Component
@Qualifier("dashScopeAsrProvider")
public class DashScopeRealtimeAsrProvider implements AsrProvider {
    private static final Logger log = LoggerFactory.getLogger(DashScopeRealtimeAsrProvider.class);

    private final SimTransProperties properties;
    private final ObjectMapper objectMapper;
    private final DashScopeEventParser eventParser;
    private final ExecutorService senderExecutor = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("asr-audio-sender-", 0).factory());
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile WebSocket webSocket;
    private volatile Future<?> senderTask;

    public DashScopeRealtimeAsrProvider(
            SimTransProperties properties,
            ObjectMapper objectMapper,
            DashScopeEventParser eventParser
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.eventParser = eventParser;
    }

    @Override
    public void start(AudioFrameQueue audioQueue, Consumer<AsrEvent> eventConsumer) {
        if (!running.compareAndSet(false, true)) {
            log.info("DashScope ASR 已在运行，忽略重复启动");
            return;
        }
        if (properties.asr().apiKey() == null || properties.asr().apiKey().isBlank()) {
            running.set(false);
            throw new AsrException("DASHSCOPE_API_KEY is required for DashScope ASR.");
        }
        try {
            log.info("开始启动 DashScope realtime ASR，模型={}，地址={}", properties.asr().model(), properties.asr().baseUrl());
            CountDownLatch sessionReady = new CountDownLatch(1);
            AtomicReference<String> startupError = new AtomicReference<>();
            webSocket = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build()
                    .newWebSocketBuilder()
                    .header("Authorization", "Bearer " + properties.asr().apiKey())
                    .header("OpenAI-Beta", "realtime=v1")
                    .buildAsync(asrWebSocketUri(), new Listener(eventConsumer, sessionReady, startupError))
                    .join();
            webSocket.sendText(sessionUpdateJson(), true).join();
            log.info("DashScope session.update 已发送");
            if (!sessionReady.await(5, TimeUnit.SECONDS)) {
                throw new AsrException("DashScope ASR session.update timed out.");
            }
            if (startupError.get() != null) {
                throw new AsrException("DashScope ASR session.update failed: " + startupError.get());
            }
            senderTask = senderExecutor.submit(() -> sendAudioLoop(audioQueue, eventConsumer));
            log.info("DashScope realtime ASR 启动成功");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            running.set(false);
            log.error("启动 DashScope ASR 时被中断", ex);
            throw new AsrException("Interrupted while starting DashScope ASR WebSocket.", ex);
        } catch (AsrException ex) {
            running.set(false);
            log.error("启动 DashScope ASR 失败：{}", ex.getMessage(), ex);
            throw ex;
        } catch (RuntimeException ex) {
            running.set(false);
            log.error("启动 DashScope ASR 失败：{}", ex.getMessage(), ex);
            throw new AsrException(startFailureMessage(ex), ex);
        }
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        log.info("开始停止 DashScope realtime ASR");
        Future<?> task = senderTask;
        if (task != null) {
            task.cancel(true);
        }
        WebSocket socket = webSocket;
        if (socket != null) {
            socket.sendText("{\"type\":\"session.finish\"}", true)
                    .thenRun(() -> socket.sendClose(WebSocket.NORMAL_CLOSURE, "stop"));
        }
        log.info("DashScope realtime ASR 停止请求已发送");
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    private void sendAudioLoop(AudioFrameQueue audioQueue, Consumer<AsrEvent> eventConsumer) {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                AudioChunk chunk = audioQueue.poll(200);
                if (chunk != null && webSocket != null) {
                    webSocket.sendText(audioAppendJson(chunk), true).join();
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException ex) {
                if (running.get()) {
                    eventConsumer.accept(AsrEvent.error("ASR audio send failed: " + ex.getMessage()));
                }
                running.set(false);
            }
        }
    }

    private String sessionUpdateJson() {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("event_id", eventId());
        root.put("type", "session.update");
        ObjectNode session = root.putObject("session");
        session.putArray("modalities").add("text");
        session.put("input_audio_format", "pcm");
        session.put("sample_rate", properties.audio().sampleRate());
        ObjectNode transcription = session.putObject("input_audio_transcription");
        transcription.put("model", "qwen3-asr-flash-realtime");
        if (properties.asr().language() != null
                && !properties.asr().language().isBlank()
                && !"auto".equalsIgnoreCase(properties.asr().language())) {
            transcription.put("language", liveTranslateLanguage(properties.asr().language()));
        }
        ObjectNode translation = session.putObject("translation");
        translation.put("language", liveTranslateLanguage(properties.translation().targetLanguage()));
        ObjectNode turnDetection = session.putObject("turn_detection");
        turnDetection.put("type", properties.asr().turnDetection());
        if (properties.asr().silenceDurationMs() > 0) {
            turnDetection.put("silence_duration_ms", properties.asr().silenceDurationMs());
        }
        if (properties.asr().prefixPaddingMs() > 0) {
            turnDetection.put("prefix_padding_ms", properties.asr().prefixPaddingMs());
        }
        if (properties.asr().threshold() > 0) {
            turnDetection.put("threshold", properties.asr().threshold());
        }
        return root.toString();
    }

    private URI asrWebSocketUri() {
        String baseUrl = properties.asr().baseUrl();
        String separator = baseUrl.contains("?") ? "&" : "?";
        String model = URLEncoder.encode(properties.asr().model(), StandardCharsets.UTF_8);
        return URI.create(baseUrl + separator + "model=" + model);
    }

    private String audioAppendJson(AudioChunk chunk) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("event_id", eventId());
        root.put("type", "input_audio_buffer.append");
        root.put("audio", Base64.getEncoder().encodeToString(chunk.pcm16le()));
        return root.toString();
    }

    private String liveTranslateLanguage(String language) {
        if (language == null || language.isBlank()) {
            return "zh";
        }
        return switch (language.toLowerCase()) {
            case "zh-cn", "zh_hans", "zh-hans", "chinese", "中文" -> "zh";
            case "en-us", "en-gb", "english" -> "en";
            case "ja-jp", "japanese" -> "ja";
            case "ko-kr", "korean" -> "ko";
            default -> language;
        };
    }

    private String eventId() {
        return "event_" + UUID.randomUUID().toString().replace("-", "");
    }

    private String startFailureMessage(RuntimeException ex) {
        Throwable cause = ex instanceof CompletionException && ex.getCause() != null ? ex.getCause() : ex;
        String message = cause.getMessage();
        if (message != null && message.contains("401")) {
            return "Failed to start DashScope ASR WebSocket: unauthorized. Check DASHSCOPE_API_KEY and make sure the key matches the DashScope endpoint region.";
        }
        return "Failed to start DashScope ASR WebSocket.";
    }

    private final class Listener implements WebSocket.Listener {
        private final Consumer<AsrEvent> eventConsumer;
        private final CountDownLatch sessionReady;
        private final AtomicReference<String> startupError;
        private final StringBuilder textBuffer = new StringBuilder();

        private Listener(
                Consumer<AsrEvent> eventConsumer,
                CountDownLatch sessionReady,
                AtomicReference<String> startupError
        ) {
            this.eventConsumer = eventConsumer;
            this.sessionReady = sessionReady;
            this.startupError = startupError;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            log.info("DashScope WebSocket 已连接");
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            textBuffer.append(data);
            if (last) {
                String payload = textBuffer.toString();
                textBuffer.setLength(0);
                handleStartupEvent(payload);
                eventParser.parse(payload).ifPresent(eventConsumer);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            running.set(false);
            startupError.compareAndSet(null, "WebSocket closed before session was ready: " + statusCode + " " + reason);
            sessionReady.countDown();
            eventConsumer.accept(AsrEvent.error("ASR WebSocket closed: " + statusCode + " " + reason));
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            running.set(false);
            startupError.compareAndSet(null, "WebSocket error before session was ready: " + error.getMessage());
            sessionReady.countDown();
            eventConsumer.accept(AsrEvent.error("ASR WebSocket error: " + error.getMessage()));
        }

        private void handleStartupEvent(String payload) {
            try {
                String type = objectMapper.readTree(payload).path("type").asText();
                if ("session.updated".equals(type)) {
                    log.info("DashScope session.updated 已收到");
                    sessionReady.countDown();
                }
                if ("error".equals(type)) {
                    log.error("DashScope 启动阶段返回错误：{}", payload);
                    startupError.compareAndSet(null, payload);
                    sessionReady.countDown();
                }
            } catch (Exception ex) {
                startupError.compareAndSet(null, "Failed to parse startup event: " + ex.getMessage());
                sessionReady.countDown();
            }
        }
    }
}
