package com.example.lms.transform;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryTransformerSubQueryFallbackTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void threeAxisFallbackPrunesSupabaseKeyShapedTokens() {
        String rawKey = "sb_secret_" + "qtxfallback";

        List<String> fallback = QueryTransformerSubQueryFallback.threeAxisFallback(
                "GraphRAG debug " + rawKey,
                "unit",
                3);

        assertFalse(fallback.isEmpty());
        assertTrue(fallback.stream().noneMatch(query -> query.contains(rawKey)), String.valueOf(fallback));
    }

    @Test
    void threeAxisFallbackAddsSuperTokenBranchesWithoutLeakingRawSuppressedText() {
        List<String> fallback = QueryTransformerSubQueryFallback.threeAxisFallback(
                "GraphRAG ops console query rewrite ownerToken=hidden-value",
                "unit",
                3);

        assertEquals(3, fallback.size());
        assertTrue(fallback.get(0).contains("core-focus"), String.valueOf(fallback));
        assertTrue(fallback.get(1).contains("alias-map"), String.valueOf(fallback));
        assertTrue(fallback.get(2).contains("failure-path"), String.valueOf(fallback));
        assertTrue(fallback.stream().noneMatch(query -> query.contains("ownerToken")), String.valueOf(fallback));

        assertEquals(Boolean.TRUE, TraceStore.get("queryTransformer.subQueries.superTokens.enabled"));
        assertEquals(3, TraceStore.get("queryTransformer.subQueries.superTokens.branchCount"));
        assertEquals(3, TraceStore.get("queryTransformer.subQueries.superTokens.tokenCount"));
        assertEquals(3, TraceStore.get("queryTransformer.subQueries.superTokens.subModelCount"));
        assertEquals(List.of("definition-model", "alias-model", "relation-model"),
                TraceStore.get("queryTransformer.subQueries.superTokens.subModelIds"));
        assertEquals(3, TraceStore.get("queryTransformer.subQueries.superTokens.subModelAssignmentCount"));
        assertEquals(3, TraceStore.get("queryTransformer.subQueries.superTokens.branchTitleCount"));
        assertEquals(3, TraceStore.get("queryTransformer.subQueries.superTokens.branchTitleHashCount"));
        assertEquals(3, TraceStore.get("queryTransformer.subQueries.superTokens.branchTitleMetadataCount"));
        assertEquals(5, TraceStore.get("queryTransformer.subQueries.superTokens.titleTokenCount"));
        assertEquals(3, TraceStore.get("queryTransformer.subQueries.superTokens.axisCount"));
        assertTrue(TraceStore.get("queryTransformer.subQueries.superTokens.branchTitleHashes") instanceof List<?> hashes
                && hashes.size() == 3
                && hashes.stream().allMatch(hash -> String.valueOf(hash).matches("[a-f0-9]{12}")));
        assertTrue(TraceStore.get("queryTransformer.subQueries.superTokens.branchTitleLengths") instanceof List<?> lengths
                && lengths.size() == 3
                && lengths.stream().allMatch(length -> length instanceof Integer n && n > 0));
        assertTrue(TraceStore.get("queryTransformer.subQueries.superTokens.branchTitleTermCounts") instanceof List<?> termCounts
                && termCounts.size() == 3
                && termCounts.stream().allMatch(count -> Integer.valueOf(6).equals(count)));
        assertEquals(3, TraceStore.get("queryTransformer.subQueries.superTokens.branchQueryMetadataCount"));
        assertTrue(TraceStore.get("queryTransformer.subQueries.superTokens.branchQueryHashes") instanceof List<?> queryHashes
                && queryHashes.size() == 3
                && queryHashes.stream().allMatch(hash -> String.valueOf(hash).matches("[a-f0-9]{12}")));
        assertTrue(TraceStore.get("queryTransformer.subQueries.superTokens.branchQueryLengths") instanceof List<?> queryLengths
                && queryLengths.size() == 3
                && queryLengths.stream().allMatch(length -> length instanceof Integer n && n > 0));
        assertTrue(TraceStore.get("queryTransformer.subQueries.superTokens.branchQueryTermCounts") instanceof List<?> queryTermCounts
                && queryTermCounts.size() == 3
                && queryTermCounts.stream().allMatch(count -> count instanceof Integer n && n >= 8));
        assertEquals(Boolean.TRUE, TraceStore.get("queryTransformer.subQueries.superTokens.branchQueryCoverageComplete"));
        assertEquals(Boolean.TRUE, TraceStore.get("queryTransformer.subQueries.superTokens.branchTitleCoverageComplete"));
        assertEquals(Boolean.TRUE, TraceStore.get("queryTransformer.subQueries.superTokens.coverageComplete"));
        assertEquals(Boolean.TRUE, TraceStore.get("queryTransformer.subQueries.superTokens.titlePresent"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("ownerToken=hidden-value"));
    }

    @Test
    void parsedSubQueriesPadMissingBranchesWithSafeSuperTokenFallback() {
        List<String> refined = QueryTransformerSubQueryFallback.refineParsedSubQueries(
                List.of("definition-only lane ownerToken=hidden-value"),
                "GraphRAG ops console query rewrite",
                "llm-response",
                3);

        assertEquals(3, refined.size());
        assertEquals("definition-only lane", refined.get(0));
        assertTrue(refined.stream().anyMatch(query -> query.contains("alias-map")), String.valueOf(refined));
        assertTrue(refined.stream().anyMatch(query -> query.contains("failure-path")), String.valueOf(refined));
        assertEquals(2, TraceStore.get("queryTransformer.subQueries.refined.paddedCount"));
        assertEquals(Boolean.TRUE, TraceStore.get("queryTransformer.subQueries.convergence.targetReached"));
        assertEquals(2, TraceStore.get("queryTransformer.subQueries.convergence.rounds"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("ownerToken=hidden-value"));
    }

    @Test
    void parsedSubQueriesTreatSpaceSeparatedSuperTokenAsCoveredAxisAndTraceCoverage() {
        List<String> refined = QueryTransformerSubQueryFallback.refineParsedSubQueries(
                List.of("core focus lane"),
                "GraphRAG ops console query rewrite",
                "llm-response",
                3);

        assertEquals(3, refined.size());
        assertEquals("core focus lane", refined.get(0));
        assertFalse(refined.stream().anyMatch(query -> query.contains("core-focus")), String.valueOf(refined));
        assertTrue(refined.stream().anyMatch(query -> query.contains("alias-map")), String.valueOf(refined));
        assertTrue(refined.stream().anyMatch(query -> query.contains("failure-path")), String.valueOf(refined));
        assertEquals(3, TraceStore.get("queryTransformer.subQueries.coverage.axisCount"));
        assertEquals(3, TraceStore.get("queryTransformer.subQueries.coverage.coveredAxisCount"));
        assertEquals(0, TraceStore.get("queryTransformer.subQueries.coverage.missingAxisCount"));
        assertEquals(Boolean.TRUE, TraceStore.get("queryTransformer.subQueries.coverage.complete"));
        assertEquals(List.of("definition", "alias", "relation"),
                TraceStore.get("queryTransformer.subQueries.coverage.coveredAxes"));
    }

    @Test
    void threeAxisFallbackPromotesRequestedQueryRewriteProfileWithoutProviderTrace() {
        TraceStore.put("web.query.rewrite.requestedTemperatureProfile", "balanced");
        TraceStore.put("web.query.rewrite.requestedValidationTemperature", 0.15d);
        TraceStore.put("web.query.rewrite.requestedExplorationTemperature", 0.7d);
        TraceStore.put("web.query.rewrite.requestedExplorationRate", 0.35d);

        QueryTransformerSubQueryFallback.threeAxisFallback(
                "RAG query rewrite conservative validation creative exploration",
                "diagnostic-smoke",
                3);

        assertEquals("balanced", TraceStore.get("web.query.rewrite.temperatureProfile"));
        assertEquals(0.15d, (Double) TraceStore.get("web.query.rewrite.validationTemperature"), 1.0e-9);
        assertEquals(0.7d, (Double) TraceStore.get("web.query.rewrite.explorationTemperature"), 1.0e-9);
        assertEquals(0.35d, (Double) TraceStore.get("web.query.rewrite.explorationRate"), 1.0e-9);
        assertEquals(1, TraceStore.get("web.query.rewrite.verificationLaneCount"));
        assertEquals(2, TraceStore.get("web.query.rewrite.explorationLaneCount"));
        assertEquals("verification:1 exploration:2 profile:balanced", TraceStore.get("web.query.rewrite.laneSummary"));
        assertEquals(List.of("verification", "exploration", "exploration"), TraceStore.get("web.query.rewrite.laneLabels"));
    }

    @Test
    void threeAxisFallbackUsesBalancedQueryRewriteProfileWhenRequestHintIsThreadLocalMissing() {
        QueryTransformerSubQueryFallback.threeAxisFallback(
                "RAG query rewrite without controller thread local hint",
                "diagnostic-smoke",
                3);

        assertEquals("balanced", TraceStore.get("web.query.rewrite.temperatureProfile"));
        assertEquals(0.15d, (Double) TraceStore.get("web.query.rewrite.validationTemperature"), 1.0e-9);
        assertEquals(0.55d, (Double) TraceStore.get("web.query.rewrite.explorationTemperature"), 1.0e-9);
        assertEquals(0.35d, (Double) TraceStore.get("web.query.rewrite.explorationRate"), 1.0e-9);
        assertEquals(1, TraceStore.get("web.query.rewrite.verificationLaneCount"));
        assertEquals(2, TraceStore.get("web.query.rewrite.explorationLaneCount"));
    }
}
