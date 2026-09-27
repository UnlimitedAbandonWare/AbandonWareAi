package com.example.lms.service.llm;

import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.infra.resilience.NightmareBreakerProperties;
import com.example.lms.infra.resilience.NightmareKeys;
import com.example.lms.service.rag.energy.ContradictionScorer;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmSemanticCallPermitOwnershipTest {

    @Test
    void completeWithPermitPropagatesRawFailureWithoutCompletingCallerPermit() {
        NightmareBreakerProperties properties = new NightmareBreakerProperties();
        properties.setEnabled(true);
        properties.setFailureThreshold(1);
        NightmareBreaker breaker = new NightmareBreaker(properties);
        String key = "test:llm:permit-owner";
        NightmareBreaker.CallPermit permit = breaker.acquire(key, "llm-call");
        IllegalStateException modelFailure = new IllegalStateException("model-call-failed");
        LlmClient client = new LangChain4jLlmClient(new ThrowingChatModel(modelFailure));

        IllegalStateException propagated = assertThrows(
                IllegalStateException.class,
                () -> client.completeWithPermit(permit, "llm-call", "bounded prompt"));

        assertSame(modelFailure, propagated);
        NightmareBreaker.StateView beforeCallerTerminal = breaker.inspect(key);
        assertFalse(beforeCallerTerminal.open);
        assertEquals(0, beforeCallerTerminal.consecutiveFailures);

        permit.completeFailure(NightmareBreaker.FailureKind.UNKNOWN, modelFailure, "llm-call");

        assertTrue(breaker.isOpen(key),
                "the caller must retain the only effective terminal decision after the raw model call fails");
    }

    @Test
    void nonNumericContradictionResultIsSilentFailureAndNeverBreakerSuccess() {
        NightmareBreakerProperties properties = new NightmareBreakerProperties();
        properties.setEnabled(true);
        properties.setTripOnSilentFailure(true);
        properties.setSilentFailureThreshold(1);
        NightmareBreaker breaker = new NightmareBreaker(properties);
        ContradictionScorer scorer = new ContradictionScorer();
        ReflectionTestUtils.setField(scorer, "useLlm", true);
        ReflectionTestUtils.setField(scorer, "chatModel", new FixedChatModel("not-a-score"));
        ReflectionTestUtils.setField(scorer, "nightmareBreaker", breaker);

        double fallback = scorer.score("feature is enabled", "feature is disabled");

        NightmareBreaker.StateView state = breaker.inspect(NightmareKeys.RAG_CONTRADICTION_SCORE);
        assertTrue(fallback >= 0.0d && fallback <= 1.0d);
        assertTrue(state.open, "a nonnumeric semantic result must not relax the breaker as success");
        assertEquals(1, state.consecutiveSilentFailures);
        assertEquals(0, state.consecutiveSuccesses);
        assertEquals(NightmareBreaker.FailureKind.EMPTY_RESPONSE, state.lastKind);
    }

    private record FixedChatModel(String response) implements ChatModel {
        @Override
        public ChatResponse chat(List<ChatMessage> messages) {
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(response))
                    .build();
        }
    }

    private record ThrowingChatModel(RuntimeException failure) implements ChatModel {
        @Override
        public ChatResponse chat(List<ChatMessage> messages) {
            throw failure;
        }
    }
}
