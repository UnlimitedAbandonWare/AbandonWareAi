package com.example.lms.service.rag.pre;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CognitiveStateExtractorPromptBoundaryTest {

    @Test
    void cognitiveExtractorStaysDeterministicAndDoesNotCallChatModel() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/pre/CognitiveStateExtractor.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("String prompt ="));
        assertFalse(source.contains("UserMessage.from(prompt)"));
        assertFalse(source.contains("ChatModel"));
        assertFalse(source.contains("UserMessage.from("));
        assertTrue(source.contains("abstractionLevel(normalized)"));
        assertTrue(source.contains("executionMode(normalized)"));
    }
}
