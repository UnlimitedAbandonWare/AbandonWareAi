package com.example.lms.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatWorkflowEmptyAnswerGuardFallbackTest {

    @Test
    void finalNoEvidenceFallbackIncludesEvidenceNeededForOfficialChangelogPrompt() {
        String query = "RAG web-search verification: answer only from official OpenAI and Supabase "
                + "docs/changelog evidence; if official evidence is missing say evidence_needed.";

        String answer = ChatWorkflow.emptyAnswerNoEvidenceFallback(query);

        assertTrue(answer.contains("evidence_needed"), answer);
        assertTrue(answer.contains("official/changelog"), answer);
    }

    @Test
    void finalNoEvidenceFallbackDoesNotInventEvidenceNeededForGeneralPrompt() {
        String answer = ChatWorkflow.emptyAnswerNoEvidenceFallback("short local chat fallback check");

        assertFalse(answer.contains("evidence_needed"), answer);
    }
}
