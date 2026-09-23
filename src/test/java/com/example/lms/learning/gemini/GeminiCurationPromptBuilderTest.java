package com.example.lms.learning.gemini;

import com.example.lms.prompt.PromptContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeminiCurationPromptBuilderTest {

    @Test
    void rendersUsefulContextWithoutRawSecrets() {
        String rawKey = "sk-" + "abcdefghijklmnopqrstuvwxyz" + "123456";
        PromptContext context = PromptContext.builder()
                .userQuery("summarize curation " + rawKey)
                .subject("rag")
                .domain("TECH")
                .memory("memory " + rawKey)
                .build();

        String prompt = new GeminiCurationPromptBuilder().build(context);

        assertTrue(prompt.contains("summarize curation"));
        assertTrue(prompt.contains("subject: rag"));
        assertFalse(prompt.contains(rawKey));
        assertFalse(prompt.contains("PromptContext@"));
    }
}
