package com.example.lms.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RequestedModelTimeoutPolicyTest {
    @Test
    void acceptedExecutionUsesPositiveTransportPolicyWithoutTheLogicalRunCap() {
        try (var scope = com.example.lms.service.chat.ChatRunExecutionContext.bindAcceptedTask()) {
            assertEquals(900, RequestedModelTimeoutPolicy.timeoutSeconds("gpt-5.6-sol", "gpt-5.6-sol", 12, 900, 600));
            assertEquals(3, RequestedModelTimeoutPolicy.timeoutSeconds("gpt-5.6-sol", "gpt-5.6-sol", 12, 3, 600));
            assertEquals(12, RequestedModelTimeoutPolicy.timeoutSeconds("gpt-5.6-sol", "gpt-5.6-sol", 12, 0, 600));
            assertEquals(12, RequestedModelTimeoutPolicy.timeoutSeconds("", "qwen3-embedding:4b", 12, 900, 600));
        }
    }

    @Test
    void resolvedChatModelHonorsRequestedTimeoutUpToRunCap() {
        assertEquals(75, RequestedModelTimeoutPolicy.timeoutSeconds("", "gemma4:26b", 12, 75, 600));
    }

    @Test
    void blankRequestedModelWithNonChatResolvedModelKeepsBaseTimeout() {
        assertEquals(12, RequestedModelTimeoutPolicy.timeoutSeconds("", "qwen3-embedding:4b", 12, 75, 600));
    }

    @Test
    void selectedLocalModelUsesRequestedTimeoutUpToRunCap() {
        assertEquals(75, RequestedModelTimeoutPolicy.timeoutSeconds("qwen3:30b", "qwen3:30b", 12, 75, 600));
        assertEquals(75, RequestedModelTimeoutPolicy.timeoutSeconds("qwen3:8b", "qwen3:8b", 12, 75, 600));
    }

    @Test
    void missingRequestedTimeoutPreservesTheShorterStageBudget() {
        assertEquals(12, RequestedModelTimeoutPolicy.timeoutSeconds("gemma4:26b", "gemma4:26b", 12, 0, 600));
    }

    @Test
    void chatRunCapBoundsRequestedTimeoutAndExplicitShorterWins() {
        assertEquals(600, RequestedModelTimeoutPolicy.timeoutSeconds("gemma3:27b", "gemma3:27b", 90, 600, 600));
        assertEquals(600, RequestedModelTimeoutPolicy.timeoutSeconds("gemma3:27b", "gemma3:27b", 90, 900, 600));
        assertEquals(3, RequestedModelTimeoutPolicy.timeoutSeconds("gemma3:27b", "gemma3:27b", 12, 3, 600));
    }

    @Test
    void remoteLookingModelFollowsTheSameRunCap() {
        assertEquals(600, RequestedModelTimeoutPolicy.timeoutSeconds("gpt-5.6-sol", "gpt-5.6-sol", 12, 900, 600));
    }

    @Test
    void nonPositiveRunCapFallsBackToTheDefaultCap() {
        assertEquals(75, RequestedModelTimeoutPolicy.timeoutSeconds("qwen3:8b", "qwen3:8b", 12, 75, 0));
    }
}
