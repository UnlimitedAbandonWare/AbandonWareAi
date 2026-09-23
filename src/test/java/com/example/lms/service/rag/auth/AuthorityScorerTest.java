package com.example.lms.service.rag.auth;

import com.example.lms.domain.enums.RerankSourceCredibility;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthorityScorerTest {

    @Test
    void invalidUrlFallsBackToUnverifiedAndLeavesRedactedTraceBreadcrumb() {
        AuthorityScorer scorer = newScorer();

        TraceStore.clear();
        RerankSourceCredibility credibility =
                scorer.getSourceCredibility("http://[raw-authority-secret");

        assertEquals(RerankSourceCredibility.UNVERIFIED, credibility);
        assertTrue((Boolean) TraceStore.get("rag.authority.hostParse.failed"));
        assertTrue(TraceStore.get("rag.authority.hostParse.errorType") instanceof String);
        assertFalse(TraceStore.getAll().toString().contains("raw-authority-secret"));
    }

    private static AuthorityScorer newScorer() {
        return new AuthorityScorer(
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                1.0d,
                0.85d,
                0.80d,
                0.70d,
                0.55d,
                0.25d);
    }
}
