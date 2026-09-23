package com.example.lms.service.verification;

import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.search.TraceStore;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClaimVerifierOutcomeValidationTest {

    @Test
    void malformedNonblankClaimExtractionIsUnknown() {
        ClaimVerifierService service = service("not-json");

        ClaimVerifierService.VerificationResult result =
                service.verifyClaims("supporting context", "A factual draft.", "model");

        assertEquals("A factual draft.", result.verifiedAnswer());
        assertFalse(result.outcomeKnown());
    }

    @Test
    void malformedClaimJudgmentIsUnknownInsteadOfFalseVerdicts() {
        ClaimVerifierService service = service("[\"A factual claim.\"]", "[truthy]");

        ClaimVerifierService.VerificationResult result =
                service.verifyClaims("A factual claim.", "A factual claim.", "model");

        assertEquals("A factual claim.", result.verifiedAnswer());
        assertFalse(result.outcomeKnown());
    }

    @Test
    void wrongLengthClaimJudgmentIsUnknown() {
        ClaimVerifierService service = service(
                "[\"Claim one.\",\"Claim two.\"]",
                "[true]");

        ClaimVerifierService.VerificationResult result =
                service.verifyClaims("Claim one. Claim two.", "Claim one. Claim two.", "model");

        assertEquals("Claim one. Claim two.", result.verifiedAnswer());
        assertFalse(result.outcomeKnown());
    }

    @Test
    void temporalVerifierFailureMakesOutcomeUnknown() {
        TemporalConsistencyVerifier temporalVerifier = mock(TemporalConsistencyVerifier.class);
        when(temporalVerifier.verify(any(String.class), anyList(), any(LocalDate.class)))
                .thenThrow(new IllegalStateException("private temporal failure"));
        ClaimVerifierService service = new ClaimVerifierService(
                new SequentialModel(List.of("[\"A factual claim.\"]", "[true]")),
                null,
                null,
                temporalVerifier);

        ClaimVerifierService.VerificationResult result =
                service.verifyClaims("A factual claim.", "A factual claim.", "model");

        assertEquals("A factual claim.", result.verifiedAnswer());
        assertFalse(result.outcomeKnown());
    }

    @Test
    void emptyClaimExtractionStillRunsTemporalGate() {
        TemporalConsistencyVerifier temporalVerifier = mock(TemporalConsistencyVerifier.class);
        when(temporalVerifier.verify(any(String.class), anyList(), any(LocalDate.class)))
                .thenThrow(new IllegalStateException("private temporal failure"));
        ClaimVerifierService service = new ClaimVerifierService(
                new SequentialModel(List.of("[]")),
                null,
                null,
                temporalVerifier);

        ClaimVerifierService.VerificationResult result =
                service.verifyClaims("supporting context", "Non-factual answer.", "model");

        assertFalse(result.outcomeKnown());
        assertFalse(result.acceptedForMemory());
        verify(temporalVerifier).verify(any(String.class), anyList(), any(LocalDate.class));
    }

    @Test
    void emptyClaimExtractionNeverBecomesAcceptedForMemory() {
        ClaimVerifierService service = service("[]");

        ClaimVerifierService.VerificationResult result =
                service.verifyClaims("supporting context", "A factual draft.", "model");

        assertEquals("A factual draft.", result.verifiedAnswer());
        assertFalse(result.outcomeKnown());
        assertFalse(result.acceptedForMemory());
    }

    @Test
    void threeArgumentResultConstructorNeverSynthesizesMemoryAcceptance() {
        ClaimVerifierService.VerificationResult result =
                new ClaimVerifierService.VerificationResult("answer", List.of(), true);

        assertFalse(result.acceptedForMemory());
    }

    @Test
    void requestBudgetHardBoundsClaimJudgeCall() {
        ClaimVerifierService service = new ClaimVerifierService(
                new SlowModel(400, "[]"),
                null,
                null,
                null);
        TimeBudgetContext.set(new TimeBudget(80));
        TraceStore.clear();
        long startedAt = System.nanoTime();

        try {
            ClaimVerifierService.VerificationResult result =
                    service.verifyClaims("supporting context", "A factual draft.", "model");
            long elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS
                    .toMillis(System.nanoTime() - startedAt);

            assertFalse(result.outcomeKnown());
            assertTrue(elapsedMs < 300, "request budget must bound claim judge; elapsedMs=" + elapsedMs);
            assertEquals("claim_verifier_judge", TraceStore.get("llm.call.timeout.stage"));
        } finally {
            TimeBudgetContext.clear();
            TraceStore.clear();
        }
    }

    private static ClaimVerifierService service(String... responses) {
        return new ClaimVerifierService(
                new SequentialModel(List.of(responses)),
                null,
                null,
                null);
    }

    private static final class SequentialModel implements ChatModel {
        private final List<String> responses;
        private final AtomicInteger calls = new AtomicInteger();

        private SequentialModel(List<String> responses) {
            this.responses = responses;
        }

        @Override
        public ChatResponse chat(ChatMessage... messages) {
            return nextResponse();
        }

        @Override
        public ChatResponse chat(List<ChatMessage> messages) {
            return nextResponse();
        }

        private ChatResponse nextResponse() {
            int index = Math.min(calls.getAndIncrement(), responses.size() - 1);
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(responses.get(index)))
                    .build();
        }
    }

    private record SlowModel(long delayMs, String response) implements ChatModel {
        @Override
        public ChatResponse chat(List<ChatMessage> messages) {
            return delayedResponse();
        }

        @Override
        public ChatResponse chat(ChatMessage... messages) {
            return delayedResponse();
        }

        private ChatResponse delayedResponse() {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(response))
                    .build();
        }
    }
}
