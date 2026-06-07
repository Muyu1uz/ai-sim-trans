package com.muyulu.aisimtrans.service;

import java.util.Map;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
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
import com.muyulu.aisimtrans.correction.CorrectionRequest;
import com.muyulu.aisimtrans.correction.CorrectionResult;
import com.muyulu.aisimtrans.correction.CorrectionReviewer;
import com.muyulu.aisimtrans.correction.CorrectionSegment;
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
    private final CorrectionReviewer correctionReviewer;
    private final SubtitleEventPublisher subtitlePublisher;
    private final RuntimeConfigService runtimeConfigService;
    private final ExecutorService translationExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final ExecutorService correctionExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean correctionReviewRunning = new AtomicBoolean();
    private final AtomicLong asrEventCount = new AtomicLong();
    private final AtomicLong speechStartedCount = new AtomicLong();
    private final AtomicLong finalTranscriptCount = new AtomicLong();
    private final AtomicLong translationSubmittedCount = new AtomicLong();
    private final AtomicLong translationCompletedCount = new AtomicLong();
    private final Map<String, SegmentState> segments = new ConcurrentHashMap<>();
    private final Deque<String> completedSegmentIds = new ArrayDeque<>();
    private final AtomicLong liveSegmentCounter = new AtomicLong();
    private volatile SegmentState liveTranslateState = new SegmentState("live-0");
    private volatile boolean liveTranslationCompleted;
    private volatile String lastAsrEvent = "";
    private volatile String lastAsrText = "";
    private volatile String lastTranslationText = "";
    private volatile String lastError = "";

    public SimTransPipelineService(
            SimTransProperties properties,
            AudioCaptureProvider audioCaptureProvider,
            AudioFrameQueue audioQueue,
            AsrProvider asrProvider,
            TranslationProvider translationProvider,
            CorrectionReviewer correctionReviewer,
            SubtitleEventPublisher subtitlePublisher,
            RuntimeConfigService runtimeConfigService
    ) {
        this.properties = properties;
        this.audioCaptureProvider = audioCaptureProvider;
        this.audioQueue = audioQueue;
        this.asrProvider = asrProvider;
        this.translationProvider = translationProvider;
        this.correctionReviewer = correctionReviewer;
        this.subtitlePublisher = subtitlePublisher;
        this.runtimeConfigService = runtimeConfigService;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            log.info("Pipeline is already running, ignoring duplicate start");
            return;
        }
        try {
            log.info("Starting pipeline, mode={}", runtimeConfigService.current().mode());
            audioQueue.clear();
            audioQueue.resetStats();
            segments.clear();
            resetLiveTranslateState();
            audioCaptureProvider.start(audioQueue::offerLatest);
            asrProvider.start(audioQueue, this::handleAsrEvent);
            subtitlePublisher.publish(SubtitleEvent.status("pipeline started"));
            log.info("Pipeline started");
        } catch (RuntimeException ex) {
            log.error("Pipeline start failed: {}", ex.getMessage(), ex);
            stop();
            subtitlePublisher.publish(SubtitleEvent.error(ex.getMessage()));
            throw ex;
        }
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            log.info("Pipeline is not running, ignoring stop");
            return;
        }
        log.info("Stopping pipeline");
        asrProvider.stop();
        audioCaptureProvider.stop();
        audioQueue.clear();
        segments.clear();
        resetLiveTranslateState();
        subtitlePublisher.publish(SubtitleEvent.status("pipeline stopped"));
        log.info("Pipeline stopped");
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
                audioCaptureProvider.audioCallbackCount(),
                asrEventCount.get(),
                speechStartedCount.get(),
                finalTranscriptCount.get(),
                translationSubmittedCount.get(),
                translationCompletedCount.get(),
                lastAsrEvent,
                lastAsrText,
                lastTranslationText,
                lastError
        );
    }

    private void handleAsrEvent(AsrEvent event) {
        asrEventCount.incrementAndGet();
        lastAsrEvent = event.type().name();
        if (event.text() != null && !event.text().isBlank()) {
            lastAsrText = event.text();
        }
        if (event.type() == AsrEventType.ERROR) {
            log.error("Received ASR error event: {}", event.text());
            lastError = event.text();
            subtitlePublisher.publish(SubtitleEvent.error(event.text()));
            return;
        }
        if (event.type() == AsrEventType.SPEECH_STARTED) {
            speechStartedCount.incrementAndGet();
            SegmentState state = stateFor(event);
            subtitlePublisher.publish(SubtitleEvent.of(
                    SubtitleEventType.CREATED,
                    state.segmentId,
                    "",
                    "",
                    null,
                    state.revision()
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
            log.info("Received transcript result, segmentId={}, textLength={}", event.segmentId(), event.text().length());
            publishTranscriptEvent(event);
            return;
        }
        if (event.type() == AsrEventType.TRANSLATION_RESULT) {
            log.info("Received cloud translation result, segmentId={}, textLength={}", event.segmentId(), event.text().length());
            publishTranslatedEvent(event);
            return;
        }

        SegmentState state = stateFor(event);
        state.sourceText = event.text();
        if (event.type() == AsrEventType.FINAL) {
            finalTranscriptCount.incrementAndGet();
        }
        SubtitleEventType sourceType = event.type() == AsrEventType.CORRECTION
                ? SubtitleEventType.CORRECTED
                : SubtitleEventType.SOURCE_DELTA;
        subtitlePublisher.publish(SubtitleEvent.of(
                sourceType,
                state.segmentId,
                state.sourceText,
                state.translationText,
                event.text(),
                state.revision()
        ));

        if (isLiveTranslateModel() || event.type() == AsrEventType.PARTIAL) {
            return;
        }
        String translationSource = event.text();
        if (translationSource == null || translationSource.isBlank()) {
            return;
        }
        log.info("Submitting translation, segmentId={}, sourceLength={}", state.segmentId, translationSource.length());
        translationSubmittedCount.incrementAndGet();
        long version = state.nextTranslationVersion();
        translationExecutor.submit(() -> translateSegment(state.segmentId, translationSource, event.type(), true, version));
    }

    private void publishTranscriptEvent(AsrEvent event) {
        SegmentState state = stateFor(event);
        if (isLiveTranslateModel() && isMostlyCjk(event.text())) {
            return;
        }
        state.sourceText = event.text();
        if (isLiveTranslateModel()) {
            state.translationText = "";
            liveTranslationCompleted = false;
        }
        subtitlePublisher.publish(SubtitleEvent.of(
                SubtitleEventType.SOURCE_DELTA,
                state.segmentId,
                state.sourceText,
                state.translationText,
                event.text(),
                state.revision()
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
        subtitlePublisher.publish(SubtitleEvent.of(
                subtitleType,
                state.segmentId,
                sourceText,
                state.translationText,
                event.text(),
                state.revision()
        ));
        if (subtitleType == SubtitleEventType.TRANSLATION_COMPLETED) {
            liveTranslationCompleted = true;
            markCompleted(state.segmentId);
            scheduleCorrectionReview();
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
            log.info("Translation task completed, segmentId={}, sourceLength={}", segmentId, sourceText.length());
        } catch (RuntimeException ex) {
            log.error("Translation task failed, segmentId={}, error={}", segmentId, ex.getMessage(), ex);
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
            translationCompletedCount.incrementAndGet();
            lastTranslationText = completed;
            subtitlePublisher.publish(SubtitleEvent.of(
                    finalText ? SubtitleEventType.TRANSLATION_COMPLETED : SubtitleEventType.TRANSLATION_DELTA,
                    state.segmentId,
                    sourceText,
                    completed,
                    null,
                    state.revision()
            ));
            if (finalText) {
                markCompleted(state.segmentId);
                scheduleCorrectionReview();
            }
            return;
        }
        translated.append(delta.text());
        state.translationText = translated.toString();
        subtitlePublisher.publish(SubtitleEvent.of(
                finalText && asrEventType == AsrEventType.CORRECTION ? SubtitleEventType.CORRECTED : SubtitleEventType.TRANSLATION_DELTA,
                state.segmentId,
                sourceText,
                state.translationText,
                delta.text(),
                state.revision()
        ));
    }

    private void markCompleted(String segmentId) {
        synchronized (completedSegmentIds) {
            completedSegmentIds.remove(segmentId);
            completedSegmentIds.addLast(segmentId);
            int maxSegments = Math.max(1, properties.correction().reviewWindowSegments() * 2);
            while (completedSegmentIds.size() > maxSegments) {
                completedSegmentIds.removeFirst();
            }
        }
    }

    private void scheduleCorrectionReview() {
        if (!properties.correction().enabled()) {
            return;
        }
        if (!correctionReviewRunning.compareAndSet(false, true)) {
            return;
        }
        correctionExecutor.submit(() -> {
            try {
                int debounceMs = Math.max(0, properties.correction().debounceMs());
                if (debounceMs > 0) {
                    Thread.sleep(debounceMs);
                }
                reviewRecentSegments();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException ex) {
                log.warn("Correction review failed: {}", ex.getMessage(), ex);
            } finally {
                correctionReviewRunning.set(false);
            }
        });
    }

    private void reviewRecentSegments() {
        List<CorrectionSegment> snapshot = correctionSnapshot();
        if (snapshot.size() < Math.max(1, properties.correction().minCompletedSegments())) {
            return;
        }
        CorrectionRequest request = new CorrectionRequest(snapshot, properties.translation().targetLanguage());
        List<CorrectionResult> results = correctionReviewer.review(request);
        for (CorrectionResult result : results) {
            applyCorrection(result);
        }
    }

    private List<CorrectionSegment> correctionSnapshot() {
        List<String> ids;
        synchronized (completedSegmentIds) {
            ids = new ArrayList<>(completedSegmentIds);
        }
        int window = Math.max(1, properties.correction().reviewWindowSegments());
        int from = Math.max(0, ids.size() - window);
        List<CorrectionSegment> snapshot = new ArrayList<>();
        for (String id : ids.subList(from, ids.size())) {
            SegmentState state = segments.get(id);
            if (state == null) {
                continue;
            }
            String sourceText = state.sourceText;
            String translationText = state.translationText;
            if ((sourceText == null || sourceText.isBlank()) && (translationText == null || translationText.isBlank())) {
                continue;
            }
            snapshot.add(new CorrectionSegment(id, sourceText, translationText, state.revision()));
        }
        return snapshot;
    }

    private void applyCorrection(CorrectionResult result) {
        if (result.confidence() < properties.correction().confidenceThreshold()) {
            return;
        }
        SegmentState state = segments.get(result.segmentId());
        if (state == null || !state.canCorrect(properties.correction().maxCorrectionsPerSegment())) {
            return;
        }
        String sourceText = valueOrCurrent(result.sourceText(), state.sourceText);
        String translationText = valueOrCurrent(result.translationText(), state.translationText);
        if (sameText(sourceText, state.sourceText) && sameText(translationText, state.translationText)) {
            return;
        }
        long revision = state.applyCorrection(sourceText, translationText);
        lastTranslationText = translationText;
        subtitlePublisher.publish(SubtitleEvent.of(
                SubtitleEventType.CORRECTED,
                state.segmentId,
                state.sourceText,
                state.translationText,
                null,
                revision
        ));
    }

    private String valueOrCurrent(String value, String current) {
        return value == null ? current : value.trim();
    }

    private boolean sameText(String left, String right) {
        return (left == null ? "" : left).equals(right == null ? "" : right);
    }

    private SegmentState stateFor(AsrEvent event) {
        if (isLiveTranslateModel()
                && (event.type() == AsrEventType.TRANSCRIPT_RESULT || event.type() == AsrEventType.TRANSLATION_RESULT)) {
            if (liveTranslationCompleted && !isSameLiveTranslateEcho(event.text())) {
                liveTranslateState = new SegmentState("live-" + liveSegmentCounter.incrementAndGet());
                liveTranslationCompleted = false;
            }
            segments.putIfAbsent(liveTranslateState.segmentId, liveTranslateState);
            return liveTranslateState;
        }
        String id = event.segmentId() == null || event.segmentId().isBlank()
                ? UUID.randomUUID().toString()
                : event.segmentId();
        return segments.computeIfAbsent(id, SegmentState::new);
    }

    private boolean isSameLiveTranslateEcho(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        return text.equals(liveTranslateState.sourceText) || text.equals(liveTranslateState.translationText);
    }

    private boolean isLiveTranslateModel() {
        return RuntimeConfigService.MODE_DASHSCOPE_LIVETRANSLATE.equals(runtimeConfigService.current().mode());
    }

    private void resetLiveTranslateState() {
        liveSegmentCounter.set(0);
        liveTranslateState = new SegmentState("live-0");
        segments.put(liveTranslateState.segmentId, liveTranslateState);
        liveTranslationCompleted = false;
        synchronized (completedSegmentIds) {
            completedSegmentIds.clear();
        }
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
        private final AtomicLong revision = new AtomicLong();
        private volatile String sourceText = "";
        private volatile String translationText = "";
        private volatile int correctionCount;

        private SegmentState(String segmentId) {
            this.segmentId = segmentId;
        }

        private long nextTranslationVersion() {
            return translationVersion.incrementAndGet();
        }

        private boolean isCurrentTranslation(long version) {
            return translationVersion.get() == version;
        }

        private long revision() {
            return revision.get();
        }

        private boolean canCorrect(int maxCorrections) {
            return maxCorrections <= 0 || correctionCount < maxCorrections;
        }

        private synchronized long applyCorrection(String sourceText, String translationText) {
            this.sourceText = sourceText == null ? "" : sourceText;
            this.translationText = translationText == null ? "" : translationText;
            correctionCount++;
            translationVersion.incrementAndGet();
            return revision.incrementAndGet();
        }
    }
}
