package com.muyulu.aisimtrans.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import com.muyulu.aisimtrans.asr.AsrEvent;
import com.muyulu.aisimtrans.asr.AsrEventType;
import com.muyulu.aisimtrans.asr.AsrProvider;
import com.muyulu.aisimtrans.audio.AudioCaptureProvider;
import com.muyulu.aisimtrans.audio.AudioChunkListener;
import com.muyulu.aisimtrans.audio.AudioFrameQueue;
import com.muyulu.aisimtrans.config.SimTransProperties;
import com.muyulu.aisimtrans.service.RuntimeConfigService;
import com.muyulu.aisimtrans.service.SimTransPipelineService;
import com.muyulu.aisimtrans.subtitle.SubtitleEvent;
import com.muyulu.aisimtrans.subtitle.SubtitleEventPublisher;
import com.muyulu.aisimtrans.subtitle.SubtitleEventType;
import com.muyulu.aisimtrans.translation.TranslationDelta;
import com.muyulu.aisimtrans.translation.TranslationProvider;
import com.muyulu.aisimtrans.translation.TranslationRequest;

class SimTransPipelineServiceTests {
    @Test
    void showsInterimSourceButTranslatesOnlyFinalToSameSubtitleSegment() throws Exception {
        FakeAsrProvider asr = new FakeAsrProvider();
        FakeTranslationProvider translation = new FakeTranslationProvider();
        RecordingPublisher publisher = new RecordingPublisher();
        SimTransProperties properties = properties("qwen-asr-realtime");
        RuntimeConfigService runtimeConfigService = new RuntimeConfigService(properties);
        SimTransPipelineService service = service(properties, asr, translation, publisher, runtimeConfigService);

        service.start();
        asr.emit(new AsrEvent(AsrEventType.PARTIAL, "s1", "hello", 1, "{}"));
        Thread.sleep(100);
        assertThat(translation.requests).isEmpty();

        asr.emit(new AsrEvent(AsrEventType.PARTIAL, "s1", "hello. next", 2, "{}"));
        Thread.sleep(100);
        assertThat(translation.requests).isEmpty();

        asr.emit(new AsrEvent(AsrEventType.FINAL, "s1", "hello. next sentence", 3, "{}"));
        Thread.sleep(300);

        assertThat(translation.requests).hasSize(1);
        assertThat(translation.requests.getFirst().sourceText()).isEqualTo("hello. next sentence");
        assertThat(translation.requests.getFirst().finalText()).isTrue();
        assertThat(translation.requests.getFirst().context()).isEmpty();
        assertThat(publisher.events)
                .extracting(SubtitleEvent::type)
                .contains(SubtitleEventType.TRANSLATION_COMPLETED);
        assertThat(publisher.events)
                .filteredOn(event -> event.type() == SubtitleEventType.TRANSLATION_COMPLETED)
                .extracting(SubtitleEvent::segmentId)
                .contains("s1");
    }

    @Test
    void publishesLiveTranslateResultWithoutCallingTranslationProvider() throws Exception {
        FakeAsrProvider asr = new FakeAsrProvider();
        FakeTranslationProvider translation = new FakeTranslationProvider();
        RecordingPublisher publisher = new RecordingPublisher();
        SimTransProperties properties = properties("qwen3.5-livetranslate-flash-realtime");
        RuntimeConfigService runtimeConfigService = liveTranslateConfig(properties);
        SimTransPipelineService service = service(properties, asr, translation, publisher, runtimeConfigService);

        service.start();
        asr.emit(new AsrEvent(AsrEventType.TRANSLATION_RESULT, "s1", "translated text", 1, "{}"));
        Thread.sleep(100);

        assertThat(translation.requests).isEmpty();
        assertThat(publisher.events)
                .filteredOn(event -> event.type() == SubtitleEventType.TRANSLATION_DELTA)
                .extracting(SubtitleEvent::translationText)
                .contains("translated text");
    }

    @Test
    void publishesLiveTranslateTranscriptAsSourceText() throws Exception {
        FakeAsrProvider asr = new FakeAsrProvider();
        FakeTranslationProvider translation = new FakeTranslationProvider();
        RecordingPublisher publisher = new RecordingPublisher();
        SimTransProperties properties = properties("qwen3.5-livetranslate-flash-realtime");
        RuntimeConfigService runtimeConfigService = liveTranslateConfig(properties);
        SimTransPipelineService service = service(properties, asr, translation, publisher, runtimeConfigService);

        service.start();
        asr.emit(new AsrEvent(AsrEventType.TRANSCRIPT_RESULT, "s2", "hello world", 1, "{}"));
        asr.emit(new AsrEvent(AsrEventType.TRANSLATION_RESULT, "s2", "translated text", 2, "{}"));
        Thread.sleep(100);

        assertThat(translation.requests).isEmpty();
        assertThat(publisher.events)
                .filteredOn(event -> event.type() == SubtitleEventType.SOURCE_DELTA)
                .extracting(SubtitleEvent::sourceText)
                .contains("hello world");
        assertThat(publisher.events)
                .filteredOn(event -> event.type() == SubtitleEventType.TRANSLATION_DELTA)
                .extracting(SubtitleEvent::sourceText)
                .contains("");
        assertThat(publisher.events)
                .filteredOn(event -> event.type() == SubtitleEventType.TRANSLATION_DELTA)
                .extracting(SubtitleEvent::translationText)
                .contains("translated text");
        assertThat(publisher.events)
                .filteredOn(event -> event.type() == SubtitleEventType.TRANSLATION_DELTA)
                .extracting(SubtitleEvent::segmentId)
                .contains("live");
    }

    @Test
    void keepsLiveTranslateTranscriptAndTranslationInOneSubtitleSlotWhenDashScopeUsesDifferentIds() throws Exception {
        FakeAsrProvider asr = new FakeAsrProvider();
        FakeTranslationProvider translation = new FakeTranslationProvider();
        RecordingPublisher publisher = new RecordingPublisher();
        SimTransProperties properties = properties("qwen3.5-livetranslate-flash-realtime");
        RuntimeConfigService runtimeConfigService = liveTranslateConfig(properties);
        SimTransPipelineService service = service(properties, asr, translation, publisher, runtimeConfigService);

        service.start();
        asr.emit(new AsrEvent(AsrEventType.TRANSCRIPT_RESULT, "s1", "first sentence", 1, "{}"));
        asr.emit(new AsrEvent(AsrEventType.TRANSLATION_RESULT, "different-translation-item", "translated first", 2, "{}"));
        Thread.sleep(100);

        assertThat(publisher.events)
                .filteredOn(event -> event.type() == SubtitleEventType.TRANSLATION_DELTA)
                .filteredOn(event -> "live".equals(event.segmentId()))
                .extracting(SubtitleEvent::sourceText, SubtitleEvent::translationText)
                .contains(org.assertj.core.groups.Tuple.tuple("", "translated first"));
    }

    @Test
    void doesNotLetChineseCloudCompletionOverwriteLiveTranslateSourceText() throws Exception {
        FakeAsrProvider asr = new FakeAsrProvider();
        FakeTranslationProvider translation = new FakeTranslationProvider();
        RecordingPublisher publisher = new RecordingPublisher();
        SimTransProperties properties = properties("qwen3.5-livetranslate-flash-realtime");
        RuntimeConfigService runtimeConfigService = liveTranslateConfig(properties);
        SimTransPipelineService service = service(properties, asr, translation, publisher, runtimeConfigService);
        String chinese = "\u7b2c\u4e00\u53e5\u8bdd";

        service.start();
        asr.emit(new AsrEvent(AsrEventType.TRANSCRIPT_RESULT, "source-item", "first sentence", 1, "{}"));
        asr.emit(new AsrEvent(AsrEventType.TRANSLATION_RESULT, "translation-item", chinese, 2, "{}"));
        asr.emit(new AsrEvent(AsrEventType.TRANSCRIPT_RESULT, "source-item", chinese, 3, "{}"));
        Thread.sleep(100);

        assertThat(publisher.events)
                .filteredOn(event -> event.type() == SubtitleEventType.SOURCE_DELTA)
                .extracting(SubtitleEvent::sourceText)
                .doesNotContain(chinese);
        assertThat(publisher.events)
                .filteredOn(event -> event.type() == SubtitleEventType.TRANSLATION_DELTA)
                .extracting(SubtitleEvent::sourceText, SubtitleEvent::translationText)
                .contains(org.assertj.core.groups.Tuple.tuple("", chinese));
    }

    @Test
    void marksDashScopeDoneTranslationAsCompletedSubtitleEvent() throws Exception {
        FakeAsrProvider asr = new FakeAsrProvider();
        FakeTranslationProvider translation = new FakeTranslationProvider();
        RecordingPublisher publisher = new RecordingPublisher();
        SimTransProperties properties = properties("qwen3.5-livetranslate-flash-realtime");
        RuntimeConfigService runtimeConfigService = liveTranslateConfig(properties);
        SimTransPipelineService service = service(properties, asr, translation, publisher, runtimeConfigService);
        String chinese = "\u7b2c\u4e00\u53e5\u8bdd";

        service.start();
        asr.emit(new AsrEvent(
                AsrEventType.TRANSLATION_RESULT,
                "translation-item",
                chinese,
                2,
                "{\"type\":\"response.text.done\"}"
        ));
        Thread.sleep(100);

        assertThat(publisher.events)
                .filteredOn(event -> event.type() == SubtitleEventType.TRANSLATION_COMPLETED)
                .extracting(SubtitleEvent::translationText)
                .contains(chinese);
    }

    @Test
    void doesNotCarryPreviousLiveTranslateSourceIntoNextTranslationAfterCompletion() throws Exception {
        FakeAsrProvider asr = new FakeAsrProvider();
        FakeTranslationProvider translation = new FakeTranslationProvider();
        RecordingPublisher publisher = new RecordingPublisher();
        SimTransProperties properties = properties("qwen3.5-livetranslate-flash-realtime");
        RuntimeConfigService runtimeConfigService = liveTranslateConfig(properties);
        SimTransPipelineService service = service(properties, asr, translation, publisher, runtimeConfigService);

        service.start();
        asr.emit(new AsrEvent(AsrEventType.TRANSCRIPT_RESULT, "source-item", "first sentence", 1, "{}"));
        asr.emit(new AsrEvent(
                AsrEventType.TRANSLATION_RESULT,
                "translation-item",
                "translated first",
                2,
                "{\"type\":\"response.text.done\"}"
        ));
        asr.emit(new AsrEvent(AsrEventType.TRANSLATION_RESULT, "next-translation-item", "translated second", 3, "{}"));
        Thread.sleep(100);

        assertThat(publisher.events)
                .filteredOn(event -> "translated second".equals(event.translationText()))
                .extracting(SubtitleEvent::sourceText)
                .contains("");
    }

    private RuntimeConfigService liveTranslateConfig(SimTransProperties properties) {
        RuntimeConfigService runtimeConfigService = new RuntimeConfigService(properties);
        runtimeConfigService.update(new com.muyulu.aisimtrans.runtime.RuntimeConfigUpdate(
                "dashscope-livetranslate", null, null, null, null, null, null, null, null, null, null
        ));
        return runtimeConfigService;
    }

    private SimTransProperties properties(String model) {
        return new SimTransProperties(
                "local-asr",
                new SimTransProperties.Audio("windows-wasapi", null, 16000, 1, 512, 100, "LiveTranslateAudio"),
                new SimTransProperties.Asr("dashscope", "", "", model, "auto", "server_vad", 500, 300, 0.5),
                new SimTransProperties.Vad("energy", 250, 700, 8000, 0.5, 0.012, 0.25, true, 2000, "models/vad/silero/silero_vad.onnx"),
                new SimTransProperties.LocalAsr("faster-whisper", "Systran/faster-whisper-large-v3", "models", "modelscope", "cuda", "float16", "py", 18765, Duration.ofSeconds(120), Duration.ofHours(2)),
                new SimTransProperties.Translation("openai-compatible", "http://localhost:11434/v1", "", "model", "zh-CN", "", true, 0, 0.2, Duration.ofSeconds(45)),
                new SimTransProperties.Subtitle(2, true, 0.86, 28)
        );
    }

    private SimTransPipelineService service(
            SimTransProperties properties,
            FakeAsrProvider asr,
            FakeTranslationProvider translation,
            RecordingPublisher publisher,
            RuntimeConfigService runtimeConfigService
    ) {
        return new SimTransPipelineService(
                properties,
                new NoopCaptureProvider(),
                new AudioFrameQueue(properties),
                asr,
                translation,
                publisher,
                runtimeConfigService
        );
    }

    private static final class FakeAsrProvider implements AsrProvider {
        private final AtomicReference<Consumer<AsrEvent>> consumer = new AtomicReference<>();
        private boolean running;

        @Override
        public void start(AudioFrameQueue audioQueue, Consumer<AsrEvent> eventConsumer) {
            consumer.set(eventConsumer);
            running = true;
        }

        @Override
        public void stop() {
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        void emit(AsrEvent event) {
            consumer.get().accept(event);
        }
    }

    private static final class FakeTranslationProvider implements TranslationProvider {
        private final List<TranslationRequest> requests = new ArrayList<>();

        @Override
        public void translate(TranslationRequest request, Consumer<TranslationDelta> deltaConsumer) {
            requests.add(request);
            deltaConsumer.accept(new TranslationDelta("fake translation", false));
            deltaConsumer.accept(new TranslationDelta("fake translation", true));
        }
    }

    private static final class RecordingPublisher implements SubtitleEventPublisher {
        private final List<SubtitleEvent> events = new ArrayList<>();

        @Override
        public void publish(SubtitleEvent event) {
            events.add(event);
        }
    }

    private static final class NoopCaptureProvider implements AudioCaptureProvider {
        private boolean running;

        @Override
        public void start(AudioChunkListener listener) {
            running = true;
        }

        @Override
        public void stop() {
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public List<String> listOutputDevices() {
            return List.of();
        }
    }
}
