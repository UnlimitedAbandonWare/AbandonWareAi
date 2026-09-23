package com.example.lms.service;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ChatWorkflowSelfAskRewriteFallbackTest {

    private static final String SYNTHETIC_REWRITE_REQUEST =
            "If the request is ambiguous, use Self-Ask to identify missing information "
                    + "and return A/B/C query rewrites.";

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void noEvidenceFallbackPreservesExplicitSelfAskRewriteContract() {
        assertStructuredRewrite(NoEvidenceChatFallback.compose(SYNTHETIC_REWRITE_REQUEST));
        assertEquals("self_ask_query_rewrite", TraceStore.get("chat.llmFallback.mode"));
    }

    @Test
    void emptyAnswerFallbackPreservesExplicitSelfAskRewriteContract() {
        assertStructuredRewrite(ChatWorkflow.emptyAnswerNoEvidenceFallback(SYNTHETIC_REWRITE_REQUEST));
    }

    @Test
    void officialEvidenceIntentKeepsEvidenceNeededPrecedence() {
        String answer = NoEvidenceChatFallback.compose(
                SYNTHETIC_REWRITE_REQUEST + " Use only official docs; otherwise say evidence_needed.");

        assertTrue(answer.contains("evidence_needed"), answer);
        assertFalse(answer.contains("A."), answer);
    }

    @Test
    void negatedSelfAskDoesNotTriggerStructuredRewrite() {
        String answer = NoEvidenceChatFallback.compose(
                "Do not use Self-Ask rewrite; compare the A/B/C labels only.");

        assertFalse(answer.contains("Self-Ask:"), answer);
        assertFalse(answer.contains("A. "), answer);
    }

    private static void assertStructuredRewrite(String answer) {
        assertTrue(answer.contains("Self-Ask"), answer);
        assertTrue(answer.contains("A."), answer);
        assertTrue(answer.contains("B."), answer);
        assertTrue(answer.contains("C."), answer);
    }
}
