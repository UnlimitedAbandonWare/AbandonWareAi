package com.example.lms.llm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.example.lms.llm.OpenAiSamplingContract.Action.*;
import static org.junit.jupiter.api.Assertions.*;

class OpenAiSamplingContractTest {
    private static final String OFFICIAL = "https://api.openai.com/v1";

    @Test
    void exactInitialIdsOmitEachKeyForEveryEffort() {
        for (String model : List.of("gpt-5", "gpt-5-mini", "gpt-5-nano")) {
            for (String effort : new String[]{null, "minimal", "low", "medium", "high", "none"}) {
                var policy = OpenAiSamplingContract.resolve(OFFICIAL, model, effort);
                assertEquals(OMIT, policy.temperature());
                assertEquals(OMIT, policy.topP());
            }
        }
    }

    @Test
    void supportedExactIdsUseDocumentedNoneDefault() {
        for (String model : List.of("gpt-5.1", "gpt-5.2")) {
            for (String effort : new String[]{null, "none", "low", "medium", "high", "xhigh"}) {
                var action = effort == null || "none".equals(effort) ? SEND : OMIT;
                var policy = OpenAiSamplingContract.resolve(OFFICIAL, model, effort);
                assertEquals(action, policy.temperature());
                assertEquals(action, policy.topP());
            }
        }
    }

    @Test
    void unverifiedIdsRemainLegacyWithoutPrefixOrCaseInference() {
        for (String model : new String[]{null, "gpt-5.5", "gpt-5.6", "gpt-5-chat-latest",
                "gpt-5-codex", "gpt-5-pro", "gpt-5-2025-08-07", "GPT-5", "gpt-5.1-chat-latest",
                "gpt-5.2-pro", "gpt-4o", "gemma4:26b"}) {
            var policy = OpenAiSamplingContract.resolve(OFFICIAL, model, "none");
            assertEquals(LEGACY, policy.temperature());
            assertEquals(LEGACY, policy.topP());
        }
    }

    @Test
    void customEndpointsRemainLegacyAndOfficialHostComparisonIsStructural() {
        for (String base : new String[]{null, "invalid endpoint", "http://api.openai.com/v1",
                "http://127.0.0.1:18180/v1", "https://gateway.example/v1", "https://api.openai.com.evil/v1",
                "https://api.openai.com@proxy.example/v1", "https://api.openai.com:8443/v1",
                "https://api.openai.com/v1/proxy", "https://api.openai.com/v1?proxy=true",
                "https://api.openai.com/v1#proxy"}) {
            assertFalse(OpenAiSamplingContract.isOfficialEndpoint(base));
            assertEquals(LEGACY, OpenAiSamplingContract.resolve(base, "gpt-5", null).temperature());
        }
        assertTrue(OpenAiSamplingContract.isOfficialEndpoint("https://api.openai.com/v1/"));
        assertTrue(OpenAiSamplingContract.isOfficialEndpoint("https://API.OPENAI.COM:443/v1"));
    }
}
