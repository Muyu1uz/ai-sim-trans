package com.muyulu.aisimtrans.pipeline;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.stereotype.Service;

import com.muyulu.aisimtrans.asr.AsrEvent;
import com.muyulu.aisimtrans.asr.AsrEventType;
import com.muyulu.aisimtrans.asr.AsrProvider;
import com.muyulu.aisimtrans.audio.AudioCaptureProvider;
import com.muyulu.aisimtrans.audio.AudioFrameQueue;
import com.muyulu.aisimtrans.config.SimTransProperties;
import com.muyulu.aisimtrans.subtitle.SubtitleEvent;
import com.muyulu.aisimtrans.subtitle.SubtitleEventPublisher;
import com.muyulu.aisimtrans.subtitle.SubtitleEventType;
import com.muyulu.aisimtrans.translation.TranslationDelta;
import com.muyulu.aisimtrans.translation.TranslationProvider;
import com.muyulu.aisimtrans.translation.TranslationRequest;

@Service
public class SimTransPipelineService {
    private final SimTransProperties properties;
    private final AudioCaptureProvider audioCaptureProvider;
    private final AudioFrameQueue audioQueue;
    private final AsrProvider asrProvider;
    private final TranslationProvider translationProvider;
    private final SubtitleEventPublisher subtitlePublisher;
    private final ExecutorService translationExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean running = new AtomicBoolean();
    private final Map<String, SegmentState> segments = new ConcurrentHashMap<>();
    private final SegmentState liveTranslateState = new SegmentState("live");

    public SimTransPipelineService(
            SimTransProperties properties,
            AudioCaptureProvider audioCaptureProvider,
            AudioFrameQueue audioQueue,
            AsrProvider asrProvider,
            TranslationProvider translationProvider,
            SubtitleEventPublisher subtitlePublisher
    ) {
        this.properties = properties;
        this.audioCaptureProvider = audioCaptureProvider;
        this.audioQueue = audioQueue;
        this.asrProvider = asrProvider;
        this.translationProvider = translationProvider;
        this.subtitlePublisher = subtitlePublisher;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            audioQueue.clear();
            audioCaptureProvider.start(audioQueue::offerLatest);
            asrProvider.start(audioQueue, this::handleAsrEvent);
            subtitlePublisher.publish(SubtitleEvent.status("pipeline started"));
        } catch (RuntimeException ex) {
            stop();
            subtitlePublisher.publish(SubtitleEvent.error(ex.getMessage()));
            throw ex;
        }
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        asrProvider.stop();
        audioCaptureProvider.stop();
        audioQueue.clear();
        subtitlePublisher.publish(SubtitleEvent.status("pipeline stopped"));
    }

    public PipelineStatus status() {
        return new PipelineStatus(
                running.get(),
                audioCaptureProvider.isRunning(),
                asrProvider.isRunning(),
                audioQueue.size(),
                audioQueue.droppedChunks()
        );
    }

    private void handleAsrEvent(AsrEvent event) {
        if (event.type() == AsrEventType.ERROR) {
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
        if (event.type() == AsrEventType.PARTIAL || event.type() == AsrEventType.SPEECH_ENDED) {
            return;
        }
        if (event.text() == null || event.text().isBlank()) {
            return;
        }
        if (event.type() == AsrEventType.TRANSCRIPT_RESULT) {
            publishTranscriptEvent(event);
            return;
        }
        if (event.type() == AsrEventType.TRANSLATION_RESULT) {
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
        translationExecutor.submit(() -> translateSegment(state.segmentId, event.text(), event.type()));
    }

    private void publishTranscriptEvent(AsrEvent event) {
        SegmentState state = stateFor(event);
        state.sourceText = event.text();
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
        state.translationText = event.text();
        subtitlePublisher.publish(new SubtitleEvent(
                SubtitleEventType.TRANSLATION_DELTA,
                state.segmentId,
                state.sourceText,
                state.translationText,
                event.text(),
                System.currentTimeMillis(),
                null
        ));
    }

    private void translateSegment(String segmentId, String sourceText, AsrEventType asrEventType) {
        SegmentState state = segments.computeIfAbsent(segmentId, SegmentState::new);
        state.translationText = "";
        TranslationRequest request = TranslationRequest.finalOnly(segmentId, sourceText);
        StringBuilder translated = new StringBuilder();
        try {
            translationProvider.translate(request, delta -> handleTranslationDelta(state, sourceText, asrEventType, translated, delta));
        } catch (RuntimeException ex) {
            subtitlePublisher.publish(SubtitleEvent.error("Translation failed: " + ex.getMessage()));
        }
    }

    private void handleTranslationDelta(
            SegmentState state,
            String sourceText,
            AsrEventType asrEventType,
            StringBuilder translated,
            TranslationDelta delta
    ) {
        if (delta.done()) {
            String completed = delta.text().isBlank() ? translated.toString() : delta.text();
            state.translationText = completed;
            subtitlePublisher.publish(new SubtitleEvent(
                    SubtitleEventType.TRANSLATION_COMPLETED,
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
                asrEventType == AsrEventType.CORRECTION ? SubtitleEventType.CORRECTED : SubtitleEventType.TRANSLATION_DELTA,
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
        String model = properties.asr().model();
        return model != null && model.toLowerCase().contains("livetranslate");
    }

    private static final class SegmentState {
        private final String segmentId;
        private volatile String sourceText = "";
        private volatile String translationText = "";

        private SegmentState(String segmentId) {
            this.segmentId = segmentId;
        }
    }
}
