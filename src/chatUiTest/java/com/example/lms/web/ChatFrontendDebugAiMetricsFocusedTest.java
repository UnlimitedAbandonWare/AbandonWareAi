package com.example.lms.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatFrontendDebugAiMetricsFocusedTest {

    @Test
    void chatUiMatrixStatusIncludesHotChunkWithoutRawDebugFields() throws Exception {
        String chat = Files.readString(
                Path.of("main/resources/static/js/chat.js"),
                StandardCharsets.UTF_8);

        assertTrue(chat.contains("const virtualMatrixHotChunkIndex ="));
        assertTrue(chat.contains("const virtualMatrixHotChunkRiskScore ="));
        assertTrue(chat.contains("const matrixHotChunkDetail ="));
        assertTrue(chat.contains(
                "limit:${currentSampleLimit}/${previousSampleLimit}${matrixHotChunkDetail}"));
        assertFalse(chat.contains("debugAiMetrics.rawEvent"));
        assertFalse(chat.contains("debugAiMetrics.rawQuery"));
        assertFalse(chat.contains("debugAiMetrics.rawPayload"));
    }
}
