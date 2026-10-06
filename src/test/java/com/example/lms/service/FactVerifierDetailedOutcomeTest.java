package com.example.lms.service;

import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.domain.enums.SourceCredibility;
import com.example.lms.prompt.PromptBuilder;
import com.example.lms.service.rag.guard.EvidenceGate;
import com.example.lms.service.verification.ClaimVerifierService;
import com.example.lms.service.verification.FactStatusClassifier;
import com.example.lms.service.verification.FactVerificationStatus;
import com.example.lms.service.verification.SourceAnalyzerService;
import com.example.lms.search.TraceStore;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FactVerifierDetailedOutcomeTest {

    @ParameterizedTest
    @CsvSource({"PASS,rejected", "INSUFFICIENT,insufficient"})
    void unavailableClaimJudgeCannotPromoteFallbackToKnownNegative(
            String classifierStatus, String rejectedStatus) {
        TraceStore.clear();
        try {
            ClaimVerifierService claims = mock(ClaimVerifierService.class);
            when(claims.verifyClaims(anyString(), anyString(), anyString())).thenAnswer(invocation -> {
                TraceStore.put("claimVerifier.judge.disabledReason", "judge_call_failed");
                return new ClaimVerifierService.VerificationResult("draft", List.of(), true, false);
            });
            var result = service(claims, FactVerificationStatus.valueOf(classifierStatus))
                    .verifyDetailed("question", "supporting official context ".repeat(8),
                            "", "draft", "model", false);

            assertEquals("unknown", result.status(), rejectedStatus + " requires an observed judge verdict");
            assertFalse(result.outcomeKnown());
            assertFalse(result.acceptedForMemory());
        } finally { TraceStore.clear(); }
    }

    @Test
    void sourceCredibilityRejectionRemainsKnownWithoutJudgeCalls() {
        SourceAnalyzerService source = mock(SourceAnalyzerService.class);
        when(source.analyze(anyString(), anyString())).thenReturn(SourceCredibility.CONFLICTING);
        var result = service(acceptedClaimVerifier("draft"), FactVerificationStatus.PASS, source, "CONSISTENT")
                .verifyDetailed("ordinary question", "supporting official context ".repeat(8),
                        "", "draft", "model", false);

        assertEquals("rejected", result.status());
        assertTrue(result.outcomeKnown());
        assertFalse(result.acceptedForMemory());
    }

    @ParameterizedTest
    @CsvSource({"INSUFFICIENT,not_run", "CONSISTENT,INSUFFICIENT"})
    void sameRequestTraceDistinguishesMetaAndClassifierInsufficiency(String meta, String classifierVerdict) {
        TraceStore.clear();
        try {
            String context = "synthetic supporting context ".repeat(8);
            var result = service(acceptedClaimVerifier("draft"), FactVerificationStatus.INSUFFICIENT, meta)
                    .verifyDetailed("question", context, "", "draft", "model", false);

            assertEquals("insufficient", result.status());
            assertEquals(context.length(), TraceStore.get("factVerifier.input.evidenceChars"));
            assertEquals(0, TraceStore.get("factVerifier.input.memoryChars"));
            assertEquals(meta, TraceStore.get("factVerifier.meta.status"));
            assertEquals(classifierVerdict, TraceStore.get("factVerifier.classifier.status"));
            assertEquals("not_run".equals(classifierVerdict) ? "meta_check" : "fact_classification",
                    TraceStore.get("factVerifier.terminalStage"));
            assertEquals("insufficient", TraceStore.get("factVerifier.terminalReason"));
            assertFalse(TraceStore.getAll().toString().contains(context));
        } finally { TraceStore.clear(); }
    }

    @Test
    void absentEvidenceCannotBecomeKnownNegativeOrDiscardGeneralDraft() {
        String draft = "Water expands as hydrogen bonds form an open crystal structure.";
        SequentialModel judge = new SequentialModel(List.of(
                "[\"Water expands when it freezes.\"]", "[false]"));
        ClaimVerifierService claims = new ClaimVerifierService(judge, null, null, null);
        FactVerifierService service = service(claims, FactVerificationStatus.INSUFFICIENT);

        var result = service.verifyDetailed("Why does water expand when it freezes?",
                "", "", draft, "model", false);

        assertEquals(draft, result.answer());
        assertEquals("unknown", result.status());
        assertFalse(result.outcomeKnown());
        assertFalse(result.acceptedForMemory());
        assertFalse(result.verificationUnavailable());
        assertEquals(0, judge.calls.get(), "An empty context cannot establish support or contradiction");
    }

    @Test
    void shortPresentEvidenceKeepsExistingInsufficientVerdict() {
        ClaimVerifierService claims = mock(ClaimVerifierService.class);
        when(claims.verifyClaims(anyString(), anyString(), anyString()))
                .thenReturn(new ClaimVerifierService.VerificationResult("filtered answer", List.of("unsupported"), true, false));
        var result = service(claims, FactVerificationStatus.INSUFFICIENT).verifyDetailed(
                "question", "Short contrary evidence.", "", "draft", "model", false);

        assertEquals("filtered answer", result.answer());
        assertEquals("insufficient", result.status());
        assertTrue(result.outcomeKnown());
        assertFalse(result.acceptedForMemory());
        verify(claims).verifyClaims("", "draft", "model");
    }

    @Test
    void acceptedMemoryOutcomeRequiresPositiveClaimVerification() {
        ClaimVerifierService claimVerifier = mock(ClaimVerifierService.class);
        when(claimVerifier.verifyClaims(anyString(), anyString(), anyString()))
                .thenReturn(new ClaimVerifierService.VerificationResult(
                        "verified answer", List.of(), true, true));
        FactVerifierService service = service(claimVerifier, FactVerificationStatus.PASS);

        FactVerifierService.DetailedVerificationResult result = service.verifyDetailed(
                "question",
                "supporting official context ".repeat(8),
                "",
                "verified answer",
                "model",
                false);

        assertEquals("verified answer", result.answer());
        assertEquals("pass", result.status());
        assertTrue(result.outcomeKnown());
        assertTrue(result.acceptedForMemory());
    }

    @Test
    void claimJudgeFailSoftCannotBecomeAcceptedMemoryOutcome() {
        ClaimVerifierService claimVerifier = mock(ClaimVerifierService.class);
        when(claimVerifier.verifyClaims(anyString(), anyString(), anyString()))
                .thenReturn(new ClaimVerifierService.VerificationResult(
                        "unchanged draft", List.of(), false));
        FactVerifierService service = service(claimVerifier, FactVerificationStatus.PASS);

        FactVerifierService.DetailedVerificationResult result = service.verifyDetailed(
                "question",
                "supporting official context ".repeat(8),
                "",
                "unchanged draft",
                "model",
                false);

        assertEquals("unchanged draft", result.answer());
        assertEquals("unknown", result.status());
        assertFalse(result.outcomeKnown());
        assertFalse(result.acceptedForMemory());
    }

    @Test
    void noResultContextIsExplicitlyInsufficientForMemory() {
        ClaimVerifierService claimVerifier = mock(ClaimVerifierService.class);
        FactVerifierService service = service(claimVerifier, FactVerificationStatus.PASS);

        FactVerifierService.DetailedVerificationResult result = service.verifyDetailed(
                "question",
                "[검색 결과 없음] " + "context ".repeat(12),
                "",
                "draft",
                "model",
                false);

        assertEquals("draft", result.answer());
        assertEquals("insufficient", result.status());
        assertTrue(result.outcomeKnown());
        assertFalse(result.acceptedForMemory());
    }

    @Test
    void malformedNonblankMetaVerdictIsUnknownAndCannotEnterMemory() {
        ClaimVerifierService claimVerifier = acceptedClaimVerifier("draft");
        FactVerifierService service = service(
                claimVerifier,
                FactVerificationStatus.PASS,
                "not-a-meta-verdict");

        FactVerifierService.DetailedVerificationResult result = service.verifyDetailed(
                "question",
                "supporting official context ".repeat(8),
                "",
                "draft",
                "model",
                false);

        assertEquals("draft", result.answer());
        assertEquals("unknown", result.status());
        assertFalse(result.outcomeKnown());
        assertFalse(result.acceptedForMemory());
    }

    @Test
    void contradictoryMultilineMetaVerdictIsUnknownAndCannotEnterMemory() {
        for (String malformed : List.of(
                "CONSISTENT\nMISMATCH",
                "CONSISTENT\nSTATUS: INSUFFICIENT",
                "CONSISTENT | MISMATCH",
                "CONSISTENT: STATUS: INSUFFICIENT")) {
            ClaimVerifierService claimVerifier = acceptedClaimVerifier("draft");
            FactVerifierService service = service(
                    claimVerifier,
                    FactVerificationStatus.PASS,
                    malformed);

            FactVerifierService.DetailedVerificationResult result = service.verifyDetailed(
                    "question",
                    "supporting official context ".repeat(8),
                    "",
                    "draft",
                    "model",
                    false);

            assertEquals("unknown", result.status(), malformed);
            assertFalse(result.outcomeKnown(), malformed);
            assertFalse(result.acceptedForMemory(), malformed);
        }
    }

    @Test
    void ordinaryInlineMetaReasonRemainsAValidKnownVerdict() {
        ClaimVerifierService claimVerifier = acceptedClaimVerifier("draft");
        FactVerifierService service = service(
                claimVerifier,
                FactVerificationStatus.PASS,
                "CONSISTENT | ordinary supporting reason");

        FactVerifierService.DetailedVerificationResult result = service.verifyDetailed(
                "question",
                "supporting official context ".repeat(8),
                "",
                "draft",
                "model",
                false);

        assertEquals("pass", result.status());
        assertTrue(result.outcomeKnown());
        assertTrue(result.acceptedForMemory());
    }

    @Test
    void explicitMetaInsufficientIsKnownButRejectedForMemory() {
        ClaimVerifierService claimVerifier = acceptedClaimVerifier("draft");
        FactVerifierService service = service(
                claimVerifier,
                FactVerificationStatus.PASS,
                "INSUFFICIENT\nnot enough evidence");

        FactVerifierService.DetailedVerificationResult result = service.verifyDetailed(
                "question",
                "supporting official context ".repeat(8),
                "",
                "draft",
                "model",
                false);

        assertEquals("draft", result.answer());
        assertEquals("insufficient", result.status());
        assertTrue(result.outcomeKnown());
        assertFalse(result.acceptedForMemory());
    }

    @Test
    void malformedCorrectionEnvelopeIsUnknownAndCannotEnterMemory() {
        ClaimVerifierService claimVerifier = acceptedClaimVerifier("draft");
        FactVerifierService service = service(
                claimVerifier,
                FactVerificationStatus.CORRECTED,
                "CONSISTENT",
                "corrected prose without the required envelope");

        FactVerifierService.DetailedVerificationResult result = service.verifyDetailed(
                "question",
                "supporting official context ".repeat(8),
                "",
                "draft",
                "model",
                false);

        assertEquals("draft", result.answer());
        assertEquals("unknown", result.status());
        assertFalse(result.outcomeKnown());
        assertFalse(result.acceptedForMemory());
    }

    @Test
    void passCorrectionEnvelopeUsesPassTelemetry() {
        ClaimVerifierService claimVerifier = acceptedClaimVerifier("draft");
        FactVerifierService service = service(
                claimVerifier,
                FactVerificationStatus.CORRECTED,
                "CONSISTENT",
                "STATUS: PASS\nCONTENT:\ndraft");

        FactVerifierService.DetailedVerificationResult result = service.verifyDetailed(
                "question",
                "supporting official context ".repeat(8),
                "",
                "draft",
                "model",
                false);

        assertEquals("pass", result.status());
        assertTrue(result.outcomeKnown());
        assertTrue(result.acceptedForMemory());
    }

    @ParameterizedTest
    @CsvSource({"MISMATCH,rejected", "INSUFFICIENT,insufficient"})
    void earlierInfrastructureFailureDoesNotMaskLaterNegativeMetaVerdict(
            String metaVerdict, String expectedStatus) {
        ClaimVerifierService claimVerifier = acceptedClaimVerifier("draft");
        SourceAnalyzerService sourceAnalyzer = mock(SourceAnalyzerService.class);
        when(sourceAnalyzer.analyze(anyString(), anyString()))
                .thenThrow(new IllegalStateException("synthetic source analysis failure"));
        FactVerifierService service = service(
                claimVerifier,
                FactVerificationStatus.PASS,
                sourceAnalyzer,
                metaVerdict);

        FactVerifierService.DetailedVerificationResult result = service.verifyDetailed(
                "ordinary question",
                "supporting official context ".repeat(8),
                "",
                "draft",
                "model",
                false);

        assertEquals(expectedStatus, result.status());
        assertTrue(result.outcomeKnown());
        assertFalse(result.acceptedForMemory());
    }

    @Test
    void earlierInfrastructureFailureDoesNotMaskKnownNegativeClaimOutcome() {
        ClaimVerifierService claimVerifier = mock(ClaimVerifierService.class);
        when(claimVerifier.verifyClaims(anyString(), anyString(), anyString()))
                .thenReturn(new ClaimVerifierService.VerificationResult(
                        "draft", List.of(), true, false));
        SourceAnalyzerService sourceAnalyzer = mock(SourceAnalyzerService.class);
        when(sourceAnalyzer.analyze(anyString(), anyString()))
                .thenThrow(new IllegalStateException("synthetic source analysis failure"));
        FactVerifierService service = service(
                claimVerifier,
                FactVerificationStatus.PASS,
                sourceAnalyzer,
                "CONSISTENT");

        FactVerifierService.DetailedVerificationResult result = service.verifyDetailed(
                "ordinary question",
                "supporting official context ".repeat(8),
                "",
                "draft",
                "model",
                false);

        verify(claimVerifier).verifyClaims(anyString(), anyString(), anyString());
        assertEquals("rejected", result.status());
        assertTrue(result.outcomeKnown());
        assertFalse(result.acceptedForMemory());
    }

    @Test
    void earlierInfrastructureFailureStillPreventsLaterPositiveMemoryAcceptance() {
        ClaimVerifierService claimVerifier = acceptedClaimVerifier("draft");
        SourceAnalyzerService sourceAnalyzer = mock(SourceAnalyzerService.class);
        when(sourceAnalyzer.analyze(anyString(), anyString()))
                .thenThrow(new IllegalStateException("synthetic source analysis failure"));
        FactVerifierService service = service(
                claimVerifier,
                FactVerificationStatus.PASS,
                sourceAnalyzer,
                "CONSISTENT");

        FactVerifierService.DetailedVerificationResult result = service.verifyDetailed(
                "ordinary question",
                "supporting official context ".repeat(8),
                "",
                "draft",
                "model",
                false);

        verify(claimVerifier).verifyClaims(anyString(), anyString(), anyString());
        assertEquals("draft", result.answer());
        assertEquals("unknown", result.status());
        assertFalse(result.outcomeKnown());
        assertFalse(result.acceptedForMemory());
    }

    @Test
    void requestBudgetHardBoundsMetaCheckCall() {
        ClaimVerifierService claimVerifier = acceptedClaimVerifier("draft");
        SourceAnalyzerService sourceAnalyzer = mock(SourceAnalyzerService.class);
        when(sourceAnalyzer.analyze(anyString(), anyString())).thenReturn(SourceCredibility.OFFICIAL);
        FactStatusClassifier classifier = mock(FactStatusClassifier.class);
        when(classifier.classify(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(FactVerificationStatus.PASS);
        EvidenceGate evidenceGate = mock(EvidenceGate.class);
        when(evidenceGate.hasSufficientCoverage(anyString(), any(), any(), any(), anyBoolean()))
                .thenReturn(true);
        PromptBuilder promptBuilder = mock(PromptBuilder.class);
        when(promptBuilder.build(any())).thenReturn("prompt");
        FactVerifierService service = new FactVerifierService(
                new SlowModel(400, "CONSISTENT"),
                classifier,
                sourceAnalyzer,
                claimVerifier,
                evidenceGate,
                promptBuilder);
        TimeBudgetContext.set(new TimeBudget(80));
        TraceStore.clear();
        long startedAt = System.nanoTime();

        try {
            FactVerifierService.DetailedVerificationResult result = service.verifyDetailed(
                    "question",
                    "supporting official context ".repeat(8),
                    "",
                    "draft",
                    "model",
                    false);
            long elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS
                    .toMillis(System.nanoTime() - startedAt);

            assertTrue(elapsedMs < 300, "request budget must bound meta check; elapsedMs=" + elapsedMs);
            assertEquals("unknown", result.status());
            assertFalse(result.outcomeKnown());
            assertEquals("fact_verifier_judge", TraceStore.get("llm.call.timeout.stage"));
        } finally {
            TimeBudgetContext.clear();
            TraceStore.clear();
        }
    }

    private static ClaimVerifierService acceptedClaimVerifier(String answer) {
        ClaimVerifierService claimVerifier = mock(ClaimVerifierService.class);
        when(claimVerifier.verifyClaims(anyString(), anyString(), anyString()))
                .thenReturn(new ClaimVerifierService.VerificationResult(
                        answer,
                        List.of(),
                        true,
                        true));
        return claimVerifier;
    }

    private static FactVerifierService service(
            ClaimVerifierService claimVerifier,
            FactVerificationStatus status) {
        SourceAnalyzerService sourceAnalyzer = mock(SourceAnalyzerService.class);
        when(sourceAnalyzer.analyze(anyString(), anyString())).thenReturn(SourceCredibility.OFFICIAL);
        return service(claimVerifier, status, sourceAnalyzer, "CONSISTENT");
    }

    private static FactVerifierService service(
            ClaimVerifierService claimVerifier,
            FactVerificationStatus status,
            String... verifierResponses) {
        SourceAnalyzerService sourceAnalyzer = mock(SourceAnalyzerService.class);
        when(sourceAnalyzer.analyze(anyString(), anyString())).thenReturn(SourceCredibility.OFFICIAL);
        return service(claimVerifier, status, sourceAnalyzer, verifierResponses);
    }

    private static FactVerifierService service(
            ClaimVerifierService claimVerifier,
            FactVerificationStatus status,
            SourceAnalyzerService sourceAnalyzer,
            String... verifierResponses) {
        ChatModel verifierModel = new SequentialModel(List.of(verifierResponses));
        FactStatusClassifier classifier = mock(FactStatusClassifier.class);
        when(classifier.classify(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(status);
        EvidenceGate evidenceGate = mock(EvidenceGate.class);
        when(evidenceGate.hasSufficientCoverage(anyString(), any(), any(), any(), anyBoolean()))
                .thenReturn(true);
        PromptBuilder promptBuilder = mock(PromptBuilder.class);
        when(promptBuilder.build(any())).thenReturn("prompt");
        return new FactVerifierService(
                verifierModel,
                classifier,
                sourceAnalyzer,
                claimVerifier,
                evidenceGate,
                promptBuilder);
    }

    private static final class SequentialModel implements ChatModel {
        private final List<String> responses;
        private final AtomicInteger calls = new AtomicInteger();

        private SequentialModel(List<String> responses) {
            this.responses = responses;
        }

        @Override
        public ChatResponse chat(dev.langchain4j.data.message.ChatMessage... messages) {
            return nextResponse();
        }

        @Override
        public ChatResponse chat(List<dev.langchain4j.data.message.ChatMessage> messages) {
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
        public ChatResponse chat(List<dev.langchain4j.data.message.ChatMessage> messages) {
            return delayedResponse();
        }

        @Override
        public ChatResponse chat(dev.langchain4j.data.message.ChatMessage... messages) {
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
