package com.muyulu.aisimtrans.service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.muyulu.aisimtrans.asr.AsrEvent;
import com.muyulu.aisimtrans.asr.AsrEventType;
import com.muyulu.aisimtrans.asr.AsrProvider;
import com.muyulu.aisimtrans.audio.AudioCaptureProvider;
import com.muyulu.aisimtrans.audio.AudioFrameQueue;
import com.muyulu.aisimtrans.config.SimTransProperties;
import com.muyulu.aisimtrans.pipeline.PipelineStatus;
import com.muyulu.aisimtrans.subtitle.SubtitleEvent;
import com.muyulu.aisimtrans.subtitle.SubtitleEventPublisher;
import com.muyulu.aisimtrans.subtitle.SubtitleEventType;
import com.muyulu.aisimtrans.translation.TranslationDelta;
import com.muyulu.aisimtrans.translation.TranslationProvider;
import com.muyulu.aisimtrans.translation.TranslationRequest;

@Service
public class SimTransPipelineService {
    private static final Logger log = LoggerFactory.getLogger(SimTransPipelineService.class);

    private final SimTransProperties properties;
    private final AudioCaptureProvider audioCaptureProvider;
    private final AudioFrameQueue audioQueue;
    private final AsrProvider asrProvider;
    private final TranslationProvider translationProvider;
    private final SubtitleEventPublisher subtitlePublisher;
    private final RuntimeConfigService runtimeConfigService;
    private final ExecutorService translationExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean running = new AtomicBoolean();
    private final Map<String, SegmentState> segments = new ConcurrentHashMap<>();
    private final SegmentState liveTranslateState = new SegmentState("live");
    private volatile boolean liveTranslationCompleted;

    public SimTransPipelineService(
            SimTransProperties properties,
            AudioCaptureProvider audioCaptureProvider,
            AudioFrameQueue audioQueue,
            AsrProvider asrProvider,
            TranslationProvider translationProvider,
            SubtitleEventPublisher subtitlePublisher,
            RuntimeConfigService runtimeConfigService
    ) {
        this.properties = properties;
        this.audioCaptureProvider = audioCaptureProvider;
        this.audioQueue = audioQueue;
        this.asrProvider = asrProvider;
        this.translationProvider = translationProvider;
        this.subtitlePublisher = subtitlePublisher;
        this.runtimeConfigService = runtimeConfigService;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            log.info("流水线已在运行，忽略重复启动");
            return;
        }
        try {
            log.info("开始启动流水线，模式={}", runtimeConfigService.current().mode());
            audioQueue.clear();
            audioQueue.resetStats();
            resetLiveTranslateState();
            log.info("开始启动本地音频采集");
            audioCaptureProvider.start(audioQueue::offerLatest);
            log.info("本地音频采集启动成功");
            log.info("开始启动 ASR");
            asrProvider.start(audioQueue, this::handleAsrEvent);
            log.info("ASR 启动成功");
            subtitlePublisher.publish(SubtitleEvent.status("pipeline started"));
            log.info("流水线启动成功");
        } catch (RuntimeException ex) {
            log.error("流水线启动失败：{}", ex.getMessage(), ex);
            stop();
            subtitlePublisher.publish(SubtitleEvent.error(ex.getMessage()));
            throw ex;
        }
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            log.info("流水线未运行，忽略停止请求");
            return;
        }
        log.info("开始停止流水线");
        asrProvider.stop();
        audioCaptureProvider.stop();
        audioQueue.clear();
        resetLiveTranslateState();
        subtitlePublisher.publish(SubtitleEvent.status("pipeline stopped"));
        log.info("流水线已停止");
    }

    public PipelineStatus status() {
        return new PipelineStatus(
                running.get(),
                audioCaptureProvider.isRunning(),
                asrProvider.isRunning(),
                audioQueue.size(),
                audioQueue.droppedChunks(),
                audioCaptureProvider.lastRms(),
                audioCaptureProvider.lastAudioAtMillis(),
                audioCaptureProvider.audioCallbackCount()
        );
    }

    private void handleAsrEvent(AsrEvent event) {
        if (event.type() == AsrEventType.ERROR) {
            log.error("收到 ASR 错误事件：{}", event.text());
            subtitlePublisher.publish(SubtitleEvent.error(event.text()));
            return;
        }
        if (event.type() == AsrEventType.SPEECH_STARTED) {
            SegmentState state = stateFor(event);
            subtitlePublisher.publish(new SubtitleEvent(
                    SubtitleEventType.CREATED,
                    state.segmentId,
                    "",
                    "",
                    null,
                    System.currentTimeMillis(),
                    null
            ));
            return;
        }
        if (event.type() == AsrEventType.SPEECH_ENDED) {
            return;
        }
        if (event.text() == null || event.text().isBlank()) {
            return;
        }
        if (event.type() == AsrEventType.TRANSCRIPT_RESULT) {
            log.info("收到转写结果，segmentId={}，文本长度={}", event.segmentId(), event.text().length());
            publishTranscriptEvent(event);
            return;
        }
        if (event.type() == AsrEventType.TRANSLATION_RESULT) {
            log.info("收到云端翻译结果，segmentId={}，文本长度={}", event.segmentId(), event.text().length());
            publishTranslatedEvent(event);
            return;
        }

        SegmentState state = stateFor(event);
        state.sourceText = event.text();
        SubtitleEventType sourceType = event.type() == AsrEventType.CORRECTION
                ? SubtitleEventType.CORRECTED
                : SubtitleEventType.SOURCE_DELTA;
        subtitlePublisher.publish(new SubtitleEvent(
                sourceType,
                state.segmentId,
                state.sourceText,
                state.translationText,
                event.text(),
                System.currentTimeMillis(),
                null
        ));

        if (isLiveTranslateModel()) {
            return;
        }
        if (event.type() == AsrEventType.PARTIAL) {
            return;
        }
        String translationSource = event.text();
        if (translationSource == null || translationSource.isBlank()) {
            return;
        }
        log.info("开始提交翻译任务，segmentId={}，源文本长度={}", state.segmentId, translationSource.length());
        long version = state.nextTranslationVersion();
        translationExecutor.submit(() -> translateSegment(state.segmentId, translationSource, event.type(), true, version));
    }

    private void publishTranscriptEvent(AsrEvent event) {
        SegmentState state = stateFor(event);
        if (isLiveTranslateModel() && isMostlyCjk(event.text()) && !isMostlyCjk(state.sourceText)) {
            state.translationText = event.text();
            subtitlePublisher.publish(new SubtitleEvent(
                    subtitleTypeForCloudTranslation(event),
                    state.segmentId,
                    state.sourceText,
                    state.translationText,
                    event.text(),
                    System.currentTimeMillis(),
                    null
            ));
            return;
        }
        state.sourceText = event.text();
        if (isLiveTranslateModel()) {
            liveTranslationCompleted = false;
        }
        subtitlePublisher.publish(new SubtitleEvent(
                SubtitleEventType.SOURCE_DELTA,
                state.segmentId,
                state.sourceText,
                state.translationText,
                event.text(),
                System.currentTimeMillis(),
                null
        ));
    }

    private void publishTranslatedEvent(AsrEvent event) {
        SegmentState state = stateFor(event);
        if (isLiveTranslateModel() && isMostlyCjk(state.sourceText)) {
            state.sourceText = "";
        }
        state.translationText = event.text();
        SubtitleEventType subtitleType = subtitleTypeForCloudTranslation(event);
        String sourceText = isLiveTranslateModel() ? "" : state.sourceText;
        subtitlePublisher.publish(new SubtitleEvent(
                subtitleType,
                state.segmentId,
                sourceText,
                state.translationText,
                event.text(),
                System.currentTimeMillis(),
                null
        ));
        if (subtitleType == SubtitleEventType.TRANSLATION_COMPLETED) {
            liveTranslationCompleted = true;
        }
    }

    private void translateSegment(String segmentId, String sourceText, AsrEventType asrEventType, boolean finalText, long version) {
        SegmentState state = segments.computeIfAbsent(segmentId, SegmentState::new);
        if (finalText) {
            state.translationText = "";
        }
        TranslationRequest request = TranslationRequest.finalOnly(segmentId, sourceText);
        StringBuilder translated = new StringBuilder();
        try {
            translationProvider.translate(request, delta -> handleTranslationDelta(state, sourceText, asrEventType, finalText, version, translated, delta));
            log.info("翻译任务完成，segmentId={}，源文本长度={}", segmentId, sourceText.length());
        } catch (RuntimeException ex) {
            log.error("翻译任务失败，segmentId={}，错误={}", segmentId, ex.getMessage(), ex);
            subtitlePublisher.publish(SubtitleEvent.error("Translation failed: " + ex.getMessage()));
        }
    }

    private void handleTranslationDelta(
            SegmentState state,
            String sourceText,
            AsrEventType asrEventType,
            boolean finalText,
            long version,
            StringBuilder translated,
            TranslationDelta delta
    ) {
        if (!state.isCurrentTranslation(version)) {
            return;
        }
        if (delta.done()) {
            String completed = delta.text().isBlank() ? translated.toString() : delta.text();
            state.translationText = completed;
            subtitlePublisher.publish(new SubtitleEvent(
                    finalText ? SubtitleEventType.TRANSLATION_COMPLETED : SubtitleEventType.TRANSLATION_DELTA,
                    state.segmentId,
                    sourceText,
                    completed,
                    null,
                    System.currentTimeMillis(),
                    null
            ));
            return;
        }
        translated.append(delta.text());
        state.translationText = translated.toString();
        subtitlePublisher.publish(new SubtitleEvent(
                finalText && asrEventType == AsrEventType.CORRECTION ? SubtitleEventType.CORRECTED : SubtitleEventType.TRANSLATION_DELTA,
                state.segmentId,
                sourceText,
                state.translationText,
                delta.text(),
                System.currentTimeMillis(),
                null
        ));
    }

    private SegmentState stateFor(AsrEvent event) {
        if (isLiveTranslateModel()
                && (event.type() == AsrEventType.TRANSCRIPT_RESULT || event.type() == AsrEventType.TRANSLATION_RESULT)) {
            return liveTranslateState;
        }
        String id = event.segmentId() == null || event.segmentId().isBlank()
                ? UUID.randomUUID().toString()
                : event.segmentId();
        return segments.computeIfAbsent(id, SegmentState::new);
    }

    private boolean isLiveTranslateModel() {
        return RuntimeConfigService.MODE_DASHSCOPE_LIVETRANSLATE.equals(runtimeConfigService.current().mode());
    }

    private void resetLiveTranslateState() {
        liveTranslateState.sourceText = "";
        liveTranslateState.translationText = "";
        liveTranslationCompleted = false;
    }

    private SubtitleEventType subtitleTypeForCloudTranslation(AsrEvent event) {
        if (!isLiveTranslateModel() || event.rawEvent() == null) {
            return SubtitleEventType.TRANSLATION_DELTA;
        }
        String rawEvent = event.rawEvent().toLowerCase();
        return rawEvent.contains(".done")
                || rawEvent.contains("completed")
                || rawEvent.contains("\"is_final\":true")
                ? SubtitleEventType.TRANSLATION_COMPLETED
                : SubtitleEventType.TRANSLATION_DELTA;
    }

    private boolean isMostlyCjk(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        int signalChars = 0;
        int cjkChars = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isWhitespace(ch) || Character.isDigit(ch) || isPunctuation(ch)) {
                continue;
            }
            signalChars++;
            Character.UnicodeScript script = Character.UnicodeScript.of(ch);
            if (script == Character.UnicodeScript.HAN) {
                cjkChars++;
            }
        }
        return signalChars > 0 && cjkChars * 100 / signalChars >= 45;
    }

    private boolean isPunctuation(char ch) {
        int type = Character.getType(ch);
        return type == Character.CONNECTOR_PUNCTUATION
                || type == Character.DASH_PUNCTUATION
                || type == Character.START_PUNCTUATION
                || type == Character.END_PUNCTUATION
                || type == Character.INITIAL_QUOTE_PUNCTUATION
                || type == Character.FINAL_QUOTE_PUNCTUATION
                || type == Character.OTHER_PUNCTUATION;
    }

    private static final class SegmentState {
        private final String segmentId;
        private final AtomicLong translationVersion = new AtomicLong();
        private volatile String sourceText = "";
        private volatile String translationText = "";

        private SegmentState(String segmentId) {
            this.segmentId = segmentId;
        }

        private long nextTranslationVersion() {
            return translationVersion.incrementAndGet();
        }

        private boolean isCurrentTranslation(long version) {
            return translationVersion.get() == version;
        }

        private String completedPrefixBeforeTrailingFragment(String text) {
            String trimmed = text == null ? "" : text.trim();
            int lastBoundary = -1;
            for (int i = 0; i < trimmed.length(); i++) {
                if (isSentenceBoundary(trimmed.charAt(i))
                        && i < trimmed.length() - 1
                        && !trimmed.substring(i + 1).trim().isBlank()) {
                    lastBoundary = i;
                }
            }
            return lastBoundary < 0 ? "" : trimmed.substring(0, lastBoundary + 1).trim();
        }

        private boolean isSentenceBoundary(char value) {
            return value == '.' || value == '?' || value == '!'
                    || value == '。' || value == '？' || value == '！';
        }
    }
}
