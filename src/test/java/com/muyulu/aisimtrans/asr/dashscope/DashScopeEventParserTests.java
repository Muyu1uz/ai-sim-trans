package com.muyulu.aisimtrans.asr.dashscope;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muyulu.aisimtrans.asr.AsrEventType;

class DashScopeEventParserTests {
    private final DashScopeEventParser parser = new DashScopeEventParser(new ObjectMapper());

    @Test
    void parsesPartialTranscriptEvent() {
        var event = parser.parse("""
                {"type":"conversation.item.input_audio_transcription.delta","segment_id":"s1","delta":{"text":"hello"}}
                """).orElseThrow();

        assertThat(event.type()).isEqualTo(AsrEventType.PARTIAL);
        assertThat(event.segmentId()).isEqualTo("s1");
        assertThat(event.text()).isEqualTo("hello");
    }

    @Test
    void parsesFinalTranscriptEvent() {
        var event = parser.parse("""
                {"event":"transcript.completed","id":"s2","text":"hello world"}
                """).orElseThrow();

        assertThat(event.type()).isEqualTo(AsrEventType.FINAL);
        assertThat(event.segmentId()).isEqualTo("s2");
        assertThat(event.text()).isEqualTo("hello world");
    }

    @Test
    void parsesLiveTranslateEvent() {
        var event = parser.parse("""
                {"type":"response.translation_text.delta","item_id":"s3","delta":{"translation":"浣犲ソ涓栫晫"}}
                """).orElseThrow();

        assertThat(event.type()).isEqualTo(AsrEventType.TRANSLATION_RESULT);
        assertThat(event.segmentId()).isEqualTo("s3");
        assertThat(event.text()).isEqualTo("浣犲ソ涓栫晫");
    }

    @Test
    void prefersItemIdOverEventIdForLiveTranslateEvents() {
        var event = parser.parse("""
                {"type":"response.translation_text.delta","id":"event_1","item_id":"s3","delta":{"translation":"translated"}}
                """).orElseThrow();

        assertThat(event.type()).isEqualTo(AsrEventType.TRANSLATION_RESULT);
        assertThat(event.segmentId()).isEqualTo("s3");
    }

    @Test
    void parsesLiveTranslateTextDeltaAsTranslation() {
        var event = parser.parse("""
                {"type":"response.translation_text.delta","item_id":"s3","delta":{"text":"translated"}}
                """).orElseThrow();

        assertThat(event.type()).isEqualTo(AsrEventType.TRANSLATION_RESULT);
        assertThat(event.segmentId()).isEqualTo("s3");
        assertThat(event.text()).isEqualTo("translated");
    }

    @Test
    void parsesLiveTranslateResponseTextDoneEvent() {
        var event = parser.parse("""
                {"type":"response.text.done","item_id":"s4","text":"浣犲ソ涓栫晫"}
                """).orElseThrow();

        assertThat(event.type()).isEqualTo(AsrEventType.TRANSLATION_RESULT);
        assertThat(event.segmentId()).isEqualTo("s4");
        assertThat(event.text()).isEqualTo("浣犲ソ涓栫晫");
    }

    @Test
    void parsesLiveTranslateIncrementalTextEvent() {
        var event = parser.parse("""
                {"type":"response.text.text","item_id":"s5","text":"浣犲ソ","stash":"涓栫晫"}
                """).orElseThrow();

        assertThat(event.type()).isEqualTo(AsrEventType.TRANSLATION_RESULT);
        assertThat(event.segmentId()).isEqualTo("s5");
        assertThat(event.text()).isEqualTo("浣犲ソ涓栫晫");
    }
    @Test
    void parsesLiveTranslateTranscriptEvent() {
        var event = parser.parse("""
                {"type":"response.audio_transcript.text","item_id":"s6","text":"hello","stash":" world"}
                """).orElseThrow();

        assertThat(event.type()).isEqualTo(AsrEventType.TRANSCRIPT_RESULT);
        assertThat(event.segmentId()).isEqualTo("s6");
        assertThat(event.text()).isEqualTo("hello world");
    }
    @Test
    void parsesConversationInputAudioTranscriptionEvent() {
        var event = parser.parse("""
                {"type":"conversation.item.input_audio_transcription.completed","item_id":"s7","transcript":"hello world"}
                """).orElseThrow();

        assertThat(event.type()).isEqualTo(AsrEventType.TRANSCRIPT_RESULT);
        assertThat(event.segmentId()).isEqualTo("s7");
        assertThat(event.text()).isEqualTo("hello world");
    }
}
