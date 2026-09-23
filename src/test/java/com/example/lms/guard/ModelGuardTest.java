package com.example.lms.guard;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModelGuardTest {

    @Test
    void placeholderLocalKeyDoesNotSatisfyOpenAiCompatibleGuard() {
        assertThrows(IllegalStateException.class,
                () -> ModelGuard.assertConfigured("openai-compatible", "sk-local", "gemma3:4b"));
    }

    @Test
    void ollamaSentinelStillSatisfiesLocalOpenAiCompatibleGuard() {
        assertDoesNotThrow(
                () -> ModelGuard.assertConfigured("openai-compatible", "ollama", "gemma3:4b"));
    }
}
