package com.example.lms.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RequestedModelTimeoutPolicyTest {

    @Test
    void blankRequestedModelUsesResolvedDefaultModelTimeoutFloor() {
        assertEquals(75, RequestedModelTimeoutPolicy.timeoutSeconds("", "gemma4:26b", 12, 75));
    }

    @Test
    void blankRequestedModelWithNonChatResolvedModelKeepsBaseTimeout() {
        assertEquals(12, RequestedModelTimeoutPolicy.timeoutSeconds("", "qwen3-embedding:4b", 12, 75));
    }

    @Test
    void selectedStrongLocalModelUsesRequestedTimeoutFloor() {
        assertEquals(75, RequestedModelTimeoutPolicy.timeoutSeconds("qwen3:30b", "qwen3:30b", 12, 75));
        assertEquals(75, RequestedModelTimeoutPolicy.timeoutSeconds("qwen3:8b", "qwen3:8b", 12, 75));
    }

    @Test
    void missingRequestedTimeoutUsesSlowDefaultModelFloor() {
        assertEquals(180, RequestedModelTimeoutPolicy.timeoutSeconds("gemma4:26b", "gemma4:26b", 12, 0));
    }

    @Test
    void requestedTimeoutNeverShortensExistingBaseTimeout() {
        assertEquals(90, RequestedModelTimeoutPolicy.timeoutSeconds("gemma3:27b", "gemma3:27b", 90, 75));
    }
}
