package com.example.lms.nova.burst;

import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.SelfAskPlanner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QueryBurstExpanderTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void koreanPrimaryMixedQueryLimitsEnglishSuffixFlood() {
        QueryBurstExpander expander = new QueryBurstExpander();
        String base = "Galaxy 트라이폴드 루머";

        List<String> variants = expander.expand(base, 3, 16);

        Set<String> englishSuffixes = Set.of(
                " official", " announcement", " release", " release date",
                " price", " specs", " review", " rumor", " vs");
        long englishSuffixCount = variants.stream()
                .filter(v -> v.startsWith(base + " "))
                .filter(v -> englishSuffixes.stream().anyMatch(v::endsWith))
                .count();
        assertTrue(englishSuffixCount <= 2, variants::toString);
        assertTrue(variants.stream().anyMatch(v -> v.startsWith(base + " ") && v.endsWith(" 공식")), variants::toString);
    }
    @Test
    void hangulQueryGetsCleanKoreanVariants() {
        QueryBurstExpander expander = new QueryBurstExpander();
        String base = "\uAC24\uB7ED\uC2DC \uD2B8\uB77C\uC774\uD3F4\uB4DC \uB8E8\uBA38";

        List<String> variants = expander.expand(base, 3, 8);

        assertTrue(variants.stream().anyMatch(v -> v.equals(base + " \uACF5\uC2DD")), variants::toString);
        assertTrue(variants.stream().anyMatch(v -> v.equals(base + " \uCD9C\uC2DC")), variants::toString);
        assertTrue(variants.stream().anyMatch(v -> v.equals("Galaxy trifold rumor")), variants::toString);
    }

    @Test
    void fallbackExpansionAddsConservativeEvidenceAndContradictionLanes() {
        QueryBurstExpander expander = new QueryBurstExpander();

        List<String> variants = expander.expand("RAG orchestration", 3, 8);

        assertTrue(variants.contains("RAG orchestration conservative official source"), variants::toString);
        assertTrue(variants.contains("RAG orchestration evidence first source verification"), variants::toString);
        assertTrue(variants.contains("RAG orchestration contradiction check counterexample"), variants::toString);
        assertEquals(List.of("conservative", "evidence-first", "contradiction-check"),
                TraceStore.get("extremeZ.burstExpand.laneProfiles"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void fallbackExpansionTracesRedactedVariantProfiles() {
        QueryBurstExpander expander = new QueryBurstExpander();
        String rawSeed = "ownerToken=debug-secret RAG orchestration";

        expander.expand(rawSeed, 3, 8);

        Object profilesObj = TraceStore.get("extremeZ.burstExpand.laneVariantProfiles");
        assertTrue(profilesObj instanceof List<?>);
        List<Map<String, Object>> profiles = (List<Map<String, Object>>) profilesObj;
        assertEquals(3, profiles.size());
        assertEquals("conservative", profiles.get(0).get("profile"));
        assertEquals("evidence-first", profiles.get(1).get("profile"));
        assertEquals("contradiction-check", profiles.get(2).get("profile"));
        assertTrue(profiles.stream().allMatch(row -> row.containsKey("queryHash12")));
        String trace = String.valueOf(TraceStore.getAll());
        assertTrue(trace.contains("queryHash12"), trace);
        assertTrue(!trace.contains("ownerToken"), trace);
        assertTrue(!trace.contains("debug-secret"), trace);
    }

    @Test
    void plannerBackedExtremeZExpansionDeduplicatesAndTracesCanonicalKeys() {
        SelfAskPlanner planner = mock(SelfAskPlanner.class);
        when(planner.plan("RAG evidence", 4))
                .thenReturn(List.of(" RAG official ", "RAG official", "", "RAG pdf"));
        QueryBurstExpander expander = new QueryBurstExpander(planner);

        List<String> variants = expander.expand(" RAG evidence ", 2, 4);

        assertEquals(List.of("RAG official", "RAG pdf"), variants);
        assertEquals(2, TraceStore.get("extremeZ.burstExpand.count"));
        assertEquals(2, TraceStore.get("extremeZ.burstExpand.min"));
        assertEquals(4, TraceStore.get("extremeZ.burstExpand.max"));
        assertEquals("", TraceStore.get("extremeZ.burstExpand.bypassReason"));
        verify(planner).plan("RAG evidence", 4);
    }
}
