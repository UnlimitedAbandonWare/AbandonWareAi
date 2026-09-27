package com.example.lms.gptsearch.decision;

import com.example.lms.gptsearch.dto.SearchMode;
import com.example.lms.gptsearch.web.ProviderId;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchDecisionServiceTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void unknownProviderIdsAreSkipped() {
        SearchDecision decision = new SearchDecisionService()
                .decide("RAG?", SearchMode.FORCE_LIGHT, List.of("NAVER", "unknown-ownerToken-secret"), 3);

        assertEquals(List.of(ProviderId.NAVER), decision.providers());
        assertEquals(Boolean.TRUE, TraceStore.get("search.decision.suppressed.searchDecision.providerId"));
        assertEquals(1L, TraceStore.get("search.decision.suppressed.count"));
        assertEquals(1L, TraceStore.get("search.decision.suppressed.searchDecision.providerId.count"));
        assertEquals("searchDecision.providerId", TraceStore.get("search.decision.suppressed.stage"));
        assertEquals("IllegalArgumentException", TraceStore.get("search.decision.suppressed.errorType"));
        assertEquals("IllegalArgumentException",
                TraceStore.get("search.decision.suppressed.searchDecision.providerId.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("unknown-ownerToken-secret"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("ownerToken"));
    }

    @Test
    void unknownProviderFallbackLeavesTraceBreadcrumb() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/gptsearch/decision/SearchDecisionService.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("traceSuppressed(\"searchDecision.providerId\", ignore);"));
        assertTrue(source.contains("TraceStore.put(\"search.decision.suppressed.\" + safeStage, true);"));
        assertTrue(source.contains("TraceStore.inc(\"search.decision.suppressed.count\")"));
        assertTrue(source.contains("TraceStore.inc(\"search.decision.suppressed.\" + safeStage + \".count\")"));
    }

    @Test
    void autoModeDeepSearchesExplicitKoreanWebFactCheckIntent() {
        SearchDecision decision = new SearchDecisionService().decide(
                "모르는 내용은 웹에서 찾아 사실관계를 교차 검증해줘.",
                com.example.lms.gptsearch.dto.SearchMode.AUTO,
                null,
                5);

        assertTrue(decision.shouldSearch());
        assertEquals(SearchDecision.Depth.DEEP, decision.depth());
        assertEquals("Explicit web fact-check intent triggers deep search", decision.reason());
    }

    @Test
    void autoModeSkipsIncidentalWebAndRagMention() {
        SearchDecision decision = new SearchDecisionService().decide(
                "웹과 RAG 상태를 로컬에서 설명해줘.",
                com.example.lms.gptsearch.dto.SearchMode.AUTO,
                null,
                5);

        assertFalse(decision.shouldSearch());
    }
}
