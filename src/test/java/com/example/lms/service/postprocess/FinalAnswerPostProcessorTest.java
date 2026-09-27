package com.example.lms.service.postprocess;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FinalAnswerPostProcessorTest {

    private final FinalAnswerPostProcessor postProcessor =
            new FinalAnswerPostProcessor(new OutputSanitizer());

    @Test
    void unchangedAnswerIsPreservedExactly() {
        String answer = "A deterministic final answer.";

        FinalAnswerPostProcessor.Result result = postProcessor.process(
                new FinalAnswerPostProcessor.Request(answer));

        assertEquals(answer, result.content());
        assertFalse(result.changed());
        assertEquals("none", result.reasonCode());
        assertEquals(0L, result.removedChars());
    }

    @Test
    void diagnosticTailIsSanitizedWithLowCardinalityEvidence() {
        FinalAnswerPostProcessor.Result result = postProcessor.process(
                new FinalAnswerPostProcessor.Request(
                        "answer\nTRACE_JSON {\"ownerToken\":\"private\"}"));

        assertEquals("answer", result.content());
        assertTrue(result.changed());
        assertEquals("diagnostics_removed", result.reasonCode());
        assertEquals("TRACE_JSON", result.marker());
        assertTrue(result.removedChars() > 0L);
        assertNotNull(result.removedHash());
        assertFalse(result.toString().contains("ownerToken"));
    }

    @Test
    void blankAndMarkerOnlyAnswersUseDeterministicFallbacks() {
        FinalAnswerPostProcessor.Result blank = postProcessor.process(
                new FinalAnswerPostProcessor.Request("  "));
        FinalAnswerPostProcessor.Result markerOnly = postProcessor.process(
                new FinalAnswerPostProcessor.Request("<!-- NOVA_TRACE_INJECTED -->"));

        assertEquals("blank_content", blank.reasonCode());
        assertEquals("diagnostics_removed_empty", markerOnly.reasonCode());
        assertTrue(blank.changed());
        assertTrue(markerOnly.changed());
    }

    @Test
    void immutableRequestProducesEqualResultsAcrossRepeatedRuns() {
        FinalAnswerPostProcessor.Request request =
                new FinalAnswerPostProcessor.Request("answer\nTRACE_HTML {private}");

        assertEquals(postProcessor.process(request), postProcessor.process(request));
        assertThrows(NullPointerException.class, () -> postProcessor.process(null));
    }

    @Test
    void cleanStrictAnswerAllowsMemoryOnlyWhenWritingIsEnabled() {
        FinalAnswerPostProcessor.Result allowed = postProcessor.process(
                verifiedRequest("verified answer", true, false, false, false, false));
        FinalAnswerPostProcessor.Result disabled = postProcessor.process(
                verifiedRequest("verified answer", false, false, false, false, false));

        assertTrue(allowed.memorySaveAllowed());
        assertEquals("verified answer", allowed.memoryContent());
        assertEquals("none", allowed.memoryDenyReason());
        assertFalse(disabled.memorySaveAllowed());
        assertEquals(null, disabled.memoryContent());
        assertEquals("write_disabled", disabled.memoryDenyReason());
    }

    @Test
    void displayAppendixDoesNotEnterSemanticMemoryPayload() {
        String semantic = "verified semantic answer";
        String display = semantic + "\n\n---\n### Sources\n- [W1] Public source";

        FinalAnswerPostProcessor.Result result = postProcessor.process(
                new FinalAnswerPostProcessor.Request(
                        display,
                        semantic,
                        true,
                        true,
                        true,
                        false,
                        false,
                        false,
                        false));

        assertEquals(display, result.content());
        assertTrue(result.memorySaveAllowed());
        assertEquals(semantic, result.memoryContent());
        assertFalse(result.memoryContent().contains("### Sources"));
    }

    @Test
    void unknownOrSkippedFinalVerificationCannotBecomeMemory() {
        assertMemoryDenied(
                new FinalAnswerPostProcessor.Request(
                        "answer",
                        "answer",
                        false,
                        false,
                        true,
                        false,
                        false,
                        false,
                        false),
                "verification_outcome_unknown");
    }

    @Test
    void knownRejectedFinalVerificationCannotBecomeMemory() {
        assertMemoryDenied(
                new FinalAnswerPostProcessor.Request(
                        "answer",
                        "answer",
                        true,
                        false,
                        true,
                        false,
                        false,
                        false,
                        false),
                "verification_not_accepted");
    }

    @Test
    void convenienceConstructorsNeverSynthesizePositiveVerification() {
        assertMemoryDenied(
                new FinalAnswerPostProcessor.Request("answer", true, false, false, false),
                "verification_outcome_unknown");
        assertMemoryDenied(
                new FinalAnswerPostProcessor.Request("answer", true, false, false, false, false),
                "verification_outcome_unknown");
    }

    @Test
    void creativeFallbackAndWeakResultsAreDeniedWithStableReasons() {
        assertMemoryDenied(
                verifiedRequest("answer", true, true, false, false, false),
                "memory_policy_denied");
        assertMemoryDenied(
                verifiedRequest("answer", true, false, true, false, false),
                "creative_result");
        assertMemoryDenied(
                verifiedRequest("answer", true, false, false, true, false),
                "fallback_result");
        assertMemoryDenied(
                verifiedRequest("answer", true, false, false, false, true),
                "weak_result");
    }

    @Test
    void sanitizerFallbackCannotBecomeMemory() {
        assertMemoryDenied(
                verifiedRequest("  ", true, false, false, false, false),
                "sanitizer_fallback");
        assertMemoryDenied(
                verifiedRequest("<!-- NOVA_TRACE_INJECTED -->", true, false, false, false, false),
                "sanitizer_fallback");
    }

    private static FinalAnswerPostProcessor.Request verifiedRequest(
            String candidate,
            boolean memoryWriteEnabled,
            boolean memoryDeniedByPolicy,
            boolean creativeApplied,
            boolean fallbackApplied,
            boolean weakResult) {
        return new FinalAnswerPostProcessor.Request(
                candidate,
                candidate,
                true,
                true,
                memoryWriteEnabled,
                memoryDeniedByPolicy,
                creativeApplied,
                fallbackApplied,
                weakResult);
    }

    private void assertMemoryDenied(FinalAnswerPostProcessor.Request request, String reason) {
        FinalAnswerPostProcessor.Result result = postProcessor.process(request);
        assertFalse(result.memorySaveAllowed());
        assertEquals(null, result.memoryContent());
        assertEquals(reason, result.memoryDenyReason());
    }
}
