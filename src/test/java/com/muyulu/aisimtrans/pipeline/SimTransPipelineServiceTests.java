package com.muyulu.aisimtrans.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.muyulu.aisimtrans.subtitle.SubtitleEvent;
import com.muyulu.aisimtrans.subtitle.SubtitleEventPublisher;
import com.muyulu.aisimtrans.subtitle.SubtitleEventType;
import com.muyulu.aisimtrans.translation.TranslationDelta;
import com.muyulu.aisimtrans.translation.TranslationProvider;
import com.muyulu.aisimtrans.translation.TranslationRequest;

class SimTransPipelineServiceTests {
    @Test
    void ignoresPartialAsrAndTranslatesFinalOnly() throws Exception {
        FakeAsrProvider asr = new FakeAsrProvider();
        FakeTranslationProvider translation = new FakeTranslationProvider();
        RecordingPublisher publisher = new RecordingPublisher();
        SimTransProperties properties = properties("qwen-asr-realtime");
        SimTransPipelineService service = new SimTransPipelineService(
                properties,
                new NoopCaptureProvider(),
                new AudioFrameQueue(properties),
                asr,
                translation,
                publisher
        );

        service.start();
        asr.emit(new AsrEvent(AsrEventType.PARTIAL, "s1", "hello", 1, "{}"));
        Thread.sleep(100);
        assertThat(translation.requests).isEmpty();

        asr.emit(new AsrEvent(AsrEventType.FINAL, "s1", "hello world", 2, "{}"));
        Thread.sleep(300);

        assertThat(translation.requests).hasSize(1);
        assertThat(translation.requests.getFirst().sourceText()).isEqualTo("hello world");
        assertThat(translation.requests.getFirst().context()).isEmpty();
        assertThat(publisher.events)
                .extracting(SubtitleEvent::type)
                .contains(SubtitleEventType.TRANSLATION_COMPLETED);
    }

    @Test
    void publishesLiveTranslateResultWithoutCallingTranslationProvider() throws Exception {
        FakeAsrProvider asr = new FakeAsrProvider();
        FakeTranslationProvider translation = new FakeTranslationProvider();
        RecordingPublisher publisher = new RecordingPublisher();
        SimTransProperties properties = properties("qwen3.5-livetranslate-flash-realtime");
        SimTransPipelineService service = new SimTransPipelineService(
                properties,
                new NoopCaptureProvider(),
                new AudioFrameQueue(properties),
                asr,
                translation,
                publisher
        );

        service.start();
        asr.emit(new AsrEvent(AsrEventType.TRANSLATION_RESULT, "s1", "你好世界", 1, "{}"));
        Thread.sleep(100);

        assertThat(translation.requests).isEmpty();
        assertThat(publisher.events)
                .filteredOn(event -> event.type() == SubtitleEventType.TRANSLATION_DELTA)
                .extracting(SubtitleEvent::translationText)
                .contains("你好世界");
    }

    @Test
    void publishesLiveTranslateTranscriptAsSourceText() throws Exception {
        FakeAsrProvider asr = new FakeAsrProvider();
        FakeTranslationProvider translation = new FakeTranslationProvider();
        RecordingPublisher publisher = new RecordingPublisher();
        SimTransProperties properties = properties("qwen3.5-livetranslate-flash-realtime");
        SimTransPipelineService service = new SimTransPipelineService(
                properties,
                new NoopCaptureProvider(),
                new AudioFrameQueue(properties),
                asr,
                translation,
                publisher
        );

        service.start();
        asr.emit(new AsrEvent(AsrEventType.TRANSCRIPT_RESULT, "s2", "hello world", 1, "{}"));
        asr.emit(new AsrEvent(AsrEventType.TRANSLATION_RESULT, "s2", "translated text", 2, "{}"));
        Thread.sleep(100);

        assertThat(translation.requests).isEmpty();
        assertThat(publisher.events)
                .filteredOn(event -> event.type() == SubtitleEventType.TRANSLATION_DELTA)
                .extracting(SubtitleEvent::sourceText)
                .contains("hello world");
        assertThat(publisher.events)
                .filteredOn(event -> event.type() == SubtitleEventType.TRANSLATION_DELTA)
                .extracting(SubtitleEvent::translationText)
                .contains("translated text");
        assertThat(publisher.events)
                .filteredOn(event -> event.type() == SubtitleEventType.TRANSLATION_DELTA)
                .extracting(SubtitleEvent::segmentId)
                .contains("live");
    }

    private SimTransProperties properties(String model) {
        return new SimTransProperties(
                new SimTransProperties.Audio("windows-wasapi", null, 16000, 1, 512, 100, "LiveTranslateAudio"),
                new SimTransProperties.Asr("dashscope", "", "", model, "auto", "server_vad", 500, 300, 0.5),
                new SimTransProperties.Translation("openai-compatible", "http://localhost:11434/v1", "", "model", "zh-CN", true, 0, 0.2, java.time.Duration.ofSeconds(45)),
                new SimTransProperties.Subtitle(2, true, 0.86, 28)
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
            deltaConsumer.accept(new TranslationDelta("你好世界", false));
            deltaConsumer.accept(new TranslationDelta("你好世界", true));
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
