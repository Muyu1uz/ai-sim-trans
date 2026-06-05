package com.muyulu.aisimtrans.asr.dashscope;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.muyulu.aisimtrans.audio.AudioChunk;
import com.muyulu.aisimtrans.config.SimTransProperties;

class DashScopeRealtimeAsrProviderTests {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void buildsDashScopeRealtimeSessionUpdateAndAudioAppendEvents() throws Exception {
        DashScopeRealtimeAsrProvider provider = provider();

        JsonNode session = objectMapper.readTree((String) invoke(provider, "sessionUpdateJson"));
        assertThat(session.get("type").asText()).isEqualTo("session.update");
        assertThat(session.at("/session/input_audio_format").asText()).isEqualTo("pcm");
        assertThat(session.at("/session/sample_rate").asInt()).isEqualTo(16000);
        assertThat(session.at("/session/input_audio_transcription/model").asText()).isEqualTo("qwen3-asr-flash-realtime");
        assertThat(session.at("/session/input_audio_transcription/language").isMissingNode()).isTrue();
        assertThat(session.at("/session/translation/language").asText()).isEqualTo("zh");
        assertThat(session.at("/session/turn_detection/type").asText()).isEqualTo("server_vad");
        assertThat(session.at("/session/turn_detection/silence_duration_ms").asInt()).isEqualTo(500);
        assertThat(session.at("/session/turn_detection/prefix_padding_ms").asInt()).isEqualTo(300);
        assertThat(session.at("/session/turn_detection/threshold").asDouble()).isEqualTo(0.5);

        AudioChunk chunk = AudioChunk.fromPcm16(new short[]{1, 2}, 16000, 1, 1, 1);
        JsonNode audio = objectMapper.readTree((String) invoke(provider, "audioAppendJson", AudioChunk.class, chunk));
        assertThat(audio.get("type").asText()).isEqualTo("input_audio_buffer.append");
        assertThat(audio.get("audio").asText()).isEqualTo("AQACAA==");
    }

    @Test
    void appendsModelQueryParameterToRealtimeWebSocketUri() throws Exception {
        DashScopeRealtimeAsrProvider provider = provider();

        Object uri = invoke(provider, "asrWebSocketUri");

        assertThat(uri.toString()).isEqualTo("wss://dashscope.aliyuncs.com/api-ws/v1/realtime?model=qwen-asr-realtime");
    }

    @Test
    void sendsExplicitLanguageWhenConfigured() throws Exception {
        DashScopeRealtimeAsrProvider provider = provider("zh");

        JsonNode session = objectMapper.readTree((String) invoke(provider, "sessionUpdateJson"));

        assertThat(session.at("/session/input_audio_transcription/language").asText()).isEqualTo("zh");
    }

    private DashScopeRealtimeAsrProvider provider() {
        return provider("auto");
    }

    private DashScopeRealtimeAsrProvider provider(String language) {
        SimTransProperties properties = new SimTransProperties(
                new SimTransProperties.Audio("windows-wasapi", null, 16000, 1, 512, 100, "LiveTranslateAudio"),
                new SimTransProperties.Asr("dashscope", "wss://dashscope.aliyuncs.com/api-ws/v1/realtime", "test-key", "qwen-asr-realtime", language, "server_vad", 500, 300, 0.5),
                new SimTransProperties.Translation("openai-compatible", "http://localhost:11434/v1", "", "model", "zh-CN", true, 0, 0.2, Duration.ofSeconds(45)),
                new SimTransProperties.Subtitle(2, true, 0.86, 28)
        );
        return new DashScopeRealtimeAsrProvider(properties, objectMapper, new DashScopeEventParser(objectMapper));
    }

    private Object invoke(Object target, String methodName) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName);
        method.setAccessible(true);
        return method.invoke(target);
    }

    private Object invoke(Object target, String methodName, Class<?> parameterType, Object argument) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName, parameterType);
        method.setAccessible(true);
        return method.invoke(target, argument);
    }
}
