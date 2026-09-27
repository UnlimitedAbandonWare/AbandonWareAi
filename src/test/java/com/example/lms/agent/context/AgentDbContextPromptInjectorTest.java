package com.example.lms.agent.context;

import com.example.lms.prompt.PromptContext;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentDbContextPromptInjectorTest {

    @BeforeEach
    void setUp() {
        TraceStore.clear();
    }

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void enrichBuilderAppendsRedactedDatabaseSnapshotSummary() {
        AgentDbContextProvider provider = mock(AgentDbContextProvider.class);
        AgentDbContextProvider.AgentDbSnapshot snapshot = new AgentDbContextProvider.AgentDbSnapshot();
        snapshot.memory = new AgentDbContextProvider.MemorySnapshot();
        snapshot.memory.statusCounts.put("ACTIVE", 12L);
        snapshot.memory.statusCounts.put("PENDING", 2L);
        snapshot.memory.statusCounts.put("QUARANTINED", 5L);
        snapshot.ledger = new AgentDbContextProvider.LedgerSnapshot();
        snapshot.ledger.hotspotDistribution.add(Map.of("hotspot", "webSearch", "count", 3L));
        snapshot.strategy = new AgentDbContextProvider.StrategySnapshot();
        snapshot.strategy.performances.add(Map.of(
                "strategyName", "ArtPlate",
                "sampleCount", 4L,
                "successRate", 0.75d,
                "averageReward", 0.7d));
        snapshot.subsystemPersistence.put("extremeZ", aliasRow("ExtremeZSystemHandler", 3));
        snapshot.subsystemPersistence.put("dppDiversityReranker", aliasRow("DppDiversityReranker", 1));
        snapshot.subsystemPersistence.put("retrievalOrderService", aliasRow("RetrievalOrderService", 1));
        snapshot.subsystemPersistence.put("localLlmProcessManager", aliasRow("LocalLlmProcessManager", 1));
        when(provider.snapshot()).thenReturn(snapshot);

        PromptContext.Builder builder = PromptContext.builder()
                .learningContextSummary("existing learner context");

        new AgentDbContextPromptInjector(provider).enrichBuilder(builder);

        String summary = builder.build().learningContextSummary();
        assertTrue(summary.contains("existing learner context"));
        assertTrue(summary.contains("db.memory active=12 pending=2 quarantined=5"));
        assertTrue(summary.contains("db.hotspots webSearch=3"));
        assertTrue(summary.contains("db.strategy ArtPlate successRate=0.75 samples=4 reward=0.70"));
        assertTrue(summary.contains("db.aliases ExtremeZSystemHandler=3/multi-package-alias-surface"));
        assertFalse(summary.contains("DppDiversityReranker="));
        assertFalse(summary.contains("RetrievalOrderService="));
        assertFalse(summary.contains("LocalLlmProcessManager="));
        assertFalse(summary.contains("content"));
        assertFalse(summary.contains("query="));
        assertEquals(Boolean.TRUE, TraceStore.get("agent.dbContext.prompt.injected"));
    }

    @Test
    void enrichBuilderRejectsNonFiniteNumericSnapshotValues() {
        AgentDbContextProvider provider = mock(AgentDbContextProvider.class);
        AgentDbContextProvider.AgentDbSnapshot snapshot = new AgentDbContextProvider.AgentDbSnapshot();
        snapshot.ledger = new AgentDbContextProvider.LedgerSnapshot();
        snapshot.ledger.hotspotDistribution.add(Map.of("hotspot", "webSearch", "count", Double.POSITIVE_INFINITY));
        snapshot.strategy = new AgentDbContextProvider.StrategySnapshot();
        snapshot.strategy.performances.add(Map.of(
                "strategyName", "ArtPlate",
                "sampleCount", Double.POSITIVE_INFINITY,
                "successRate", "Infinity",
                "averageReward", Double.POSITIVE_INFINITY));
        when(provider.snapshot()).thenReturn(snapshot);

        PromptContext.Builder builder = PromptContext.builder();

        new AgentDbContextPromptInjector(provider).enrichBuilder(builder);

        String summary = builder.build().learningContextSummary();
        assertTrue(summary.contains("db.hotspots webSearch=0"));
        assertTrue(summary.contains("db.strategy ArtPlate successRate=0.00 samples=0 reward=0.00"));
        assertFalse(summary.contains("9223372036854775807"));
        assertFalse(summary.contains("Infinity"));
    }

    @Test
    void enrichBuilderFailsSoftWithoutChangingExistingSummary() {
        AgentDbContextProvider provider = mock(AgentDbContextProvider.class);
        when(provider.snapshot()).thenThrow(new DataAccessResourceFailureException("db down api_key=redacted-test-token"));
        PromptContext.Builder builder = PromptContext.builder()
                .learningContextSummary("existing learner context");

        new AgentDbContextPromptInjector(provider).enrichBuilder(builder);

        assertEquals("existing learner context", builder.build().learningContextSummary());
        assertEquals(Boolean.TRUE, TraceStore.get("agent.dbContext.prompt.failSoft"));
        assertEquals("db_context_snapshot_unavailable", TraceStore.get("agent.dbContext.prompt.reason"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("DataAccessResourceFailureException"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("redacted-test-token"));
    }

    @Test
    void enrichBuilderPreservesSupplementaryCharactersAtSummaryLimit() {
        AgentDbContextProvider provider = mock(AgentDbContextProvider.class);
        AgentDbContextProvider.AgentDbSnapshot snapshot = new AgentDbContextProvider.AgentDbSnapshot();
        snapshot.memory = new AgentDbContextProvider.MemorySnapshot();
        snapshot.memory.statusCounts.put("ACTIVE", 1L);
        when(provider.snapshot()).thenReturn(snapshot);

        String emoji = "\uD83D\uDE00";
        String splitBoundary = enrichSummary(provider, "A".repeat(899) + emoji + "Z");
        String completePairBoundary = enrichSummary(provider, "A".repeat(898) + emoji + "Z");
        String bmpBoundary = enrichSummary(provider, "A".repeat(900) + "Z");
        String exactBoundary = enrichSummary(provider, "A".repeat(900));
        String shortSupplementary = enrichSummary(provider, "start" + emoji + "end");

        assertAll(
                () -> assertEquals(899, splitBoundary.length()),
                () -> assertFalse(Character.isHighSurrogate(
                        splitBoundary.charAt(splitBoundary.length() - 1))),
                () -> assertEquals(splitBoundary, new String(
                        splitBoundary.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)),
                () -> assertEquals("A".repeat(898) + emoji, completePairBoundary),
                () -> assertEquals("A".repeat(900), bmpBoundary),
                () -> assertEquals("A".repeat(900), exactBoundary),
                () -> assertTrue(shortSupplementary.startsWith("start" + emoji + "end\n")),
                () -> assertEquals(shortSupplementary, new String(
                        shortSupplementary.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)));
    }

    private static String enrichSummary(AgentDbContextProvider provider, String existing) {
        PromptContext.Builder builder = PromptContext.builder().learningContextSummary(existing);
        new AgentDbContextPromptInjector(provider).enrichBuilder(builder);
        return builder.build().learningContextSummary();
    }

    private static Map<String, Object> aliasRow(String component, int aliasCount) {
        return Map.of(
                "component", component,
                "aliasCount", aliasCount,
                "gapClass", aliasCount > 1 ? "multi-package-alias-surface" : "canonical-only");
    }
}
