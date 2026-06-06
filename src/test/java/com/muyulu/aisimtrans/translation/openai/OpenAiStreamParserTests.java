package com.muyulu.aisimtrans.translation.openai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class OpenAiStreamParserTests {
    private final OpenAiStreamParser parser = new OpenAiStreamParser(new ObjectMapper());

    @Test
    void parsesOpenAiCompatibleSseDeltas() {
        String chunk = """
                data: {"choices":[{"delta":{"content":"he"}}]}
                data: {"choices":[{"delta":{"content":"llo"}}]}
                data: [DONE]
                """;

        assertThat(parser.parseDeltas(chunk)).containsExactly("he", "llo");
    }

    @Test
    void parsesBareJsonDeltasWithoutShowingRawChunks() {
        String chunk = """
                {"choices":[{"delta":{"content":"","role":"assistant"},"index":0}]}
                {"choices":[{"delta":{"content":"他们","role":null},"index":0}]}
                {"choices":[{"delta":{"content":"憎","role":null},"index":0}]}
                {"choices":[{"delta":{"content":"恨","role":null},"index":0}]}
                {"choices":[{"delta":{"content":"。","role":null},"index":0}]}
                {"choices":[{"delta":{"content":"","role":null},"index":0,"finish_reason":"stop"}]}
                [DONE]
                """;

        assertThat(parser.parseDeltas(chunk)).containsExactly("", "他们", "憎", "恨", "。", "");
    }
}
