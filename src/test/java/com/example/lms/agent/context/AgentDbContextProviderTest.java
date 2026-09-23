package com.example.lms.agent.context;

import com.example.lms.entity.RagOpsLedgerEntry;
import com.example.lms.entity.TranslationMemory;
import com.example.lms.repository.RagOpsLedgerRepository;
import com.example.lms.repository.TranslationMemoryRepository;
import com.example.lms.strategy.StrategyPerformance;
import com.example.lms.strategy.StrategyPerformanceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentDbContextProviderTest {

    @Test
    void snapshotUsesDatabaseRowsWithoutExposingRawMemoryText() {
        TranslationMemoryRepository memoryRepo = mock(TranslationMemoryRepository.class);
        RagOpsLedgerRepository ledgerRepo = mock(RagOpsLedgerRepository.class);
        StrategyPerformanceRepository strategyRepo = mock(StrategyPerformanceRepository.class);
        AgentDbContextProperties properties = new AgentDbContextProperties();
        properties.setMaxLedgerFailures(20);
        properties.setMaxMemoryTopN(10);

        when(memoryRepo.countByStatus(TranslationMemory.MemoryStatus.ACTIVE)).thenReturn(2L);
        when(memoryRepo.countByStatus(TranslationMemory.MemoryStatus.PENDING)).thenReturn(1L);
        when(memoryRepo.findTop10ByEnergyNotNullOrderByEnergyAsc()).thenReturn(List.of(memoryRow()));
        when(ledgerRepo.findTop20ByDecisionOrderByCreatedAtDesc("FAIL")).thenReturn(List.of(ledgerRow()));
        RagOpsLedgerRepository.HotspotCountRow hotspotCount = hotspotRow("webSearch.naver", 3L);
        when(ledgerRepo.countHotspotDistribution(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(hotspotCount)));
        when(strategyRepo.findAll()).thenReturn(List.of(strategyRow()));

        AgentDbContextProvider provider = new AgentDbContextProvider(memoryRepo, ledgerRepo, strategyRepo, properties);

        AgentDbContextProvider.AgentDbSnapshot snapshot = provider.snapshot();

        assertNotNull(snapshot.capturedAt);
        assertEquals(2L, snapshot.memory.statusCounts.get("ACTIVE"));
        assertEquals(1L, snapshot.memory.statusCounts.get("PENDING"));

        Map<String, Object> memory = snapshot.memory.topLowEnergy.get(0);
        assertEquals("hash-only-source", memory.get("contentHash"));
        assertEquals(14, memory.get("queryLength"));
        assertEquals(0.12, memory.get("energy"));
        assertFalse(memory.containsKey("content"));
        assertFalse(memory.containsKey("corrected"));
        assertFalse(memory.containsKey("query"));
        assertFalse(memory.containsValue("raw memory content"));
        assertFalse(memory.containsValue("raw user query"));

        Map<String, Object> failure = snapshot.ledger.recentFailures.get(0);
        assertEquals("query-hash-only", failure.get("queryHash"));
        assertEquals("timeout", failure.get("failureClass"));
        assertFalse(failure.containsKey("query"));
        assertFalse(failure.containsKey("sourceCountsJson"));
        assertFalse(failure.containsKey("qualityJson"));

        Map<String, Object> hotspot = snapshot.ledger.hotspotDistribution.get(0);
        assertEquals("webSearch.naver", hotspot.get("hotspot"));
        assertEquals(3L, hotspot.get("count"));

        Map<String, Object> strategy = snapshot.strategy.performances.get(0);
        assertEquals("ArtPlate", strategy.get("strategyName"));
        assertEquals(4L, strategy.get("sampleCount"));
        assertEquals(0.75d, strategy.get("successRate"));
        assertEquals(0.7d, strategy.get("averageReward"));

        verify(memoryRepo).findTop10ByEnergyNotNullOrderByEnergyAsc();
        verify(ledgerRepo).findTop20ByDecisionOrderByCreatedAtDesc("FAIL");
        verify(ledgerRepo).countHotspotDistribution(any(Pageable.class));
    }

    @Test
    void snapshotSanitizesPublicDbLabelFields() {
        TranslationMemoryRepository memoryRepo = mock(TranslationMemoryRepository.class);
        RagOpsLedgerRepository ledgerRepo = mock(RagOpsLedgerRepository.class);
        StrategyPerformanceRepository strategyRepo = mock(StrategyPerformanceRepository.class);
        AgentDbContextProperties properties = new AgentDbContextProperties();
        properties.setMaxLedgerFailures(20);
        properties.setMaxMemoryTopN(10);

        TranslationMemory memory = memoryRow();
        ReflectionTestUtils.setField(memory, "sourceHash", "sourceHash ownerToken=memory-secret");
        ReflectionTestUtils.setField(memory, "sourceTag", "source ownerToken=memory-secret");
        ReflectionTestUtils.setField(memory, "modelId", "model api_key=memory-secret");
        when(memoryRepo.findTop10ByEnergyNotNullOrderByEnergyAsc()).thenReturn(List.of(memory));
        RagOpsLedgerEntry ledger = ledgerRow("plan ownerToken=ledger-secret");
        ledger.setQueryHash("queryHash ownerToken=ledger-secret");
        when(ledgerRepo.findTop20ByDecisionOrderByCreatedAtDesc("FAIL"))
                .thenReturn(List.of(ledger));
        RagOpsLedgerRepository.HotspotCountRow hotspotCount =
                hotspotRow("hotspot ownerToken=hotspot-secret", 1L);
        when(ledgerRepo.countHotspotDistribution(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(hotspotCount)));
        when(strategyRepo.findAll())
                .thenReturn(List.of(strategyRow("strategy ownerToken=strategy-secret")));

        AgentDbContextProvider provider = new AgentDbContextProvider(memoryRepo, ledgerRepo, strategyRepo, properties);

        AgentDbContextProvider.AgentDbSnapshot snapshot = provider.snapshot();
        String rendered = String.valueOf(snapshot.memory.topLowEnergy)
                + snapshot.ledger.recentFailures
                + snapshot.ledger.hotspotDistribution
                + snapshot.strategy.performances;

        assertFalse(rendered.contains("ownerToken"), rendered);
        assertFalse(rendered.contains("api_key"), rendered);
        assertFalse(rendered.contains("memory-secret"), rendered);
        assertFalse(rendered.contains("ledger-secret"), rendered);
        assertFalse(rendered.contains("hotspot-secret"), rendered);
        assertFalse(rendered.contains("strategy-secret"), rendered);
    }

    @Test
    void snapshotReusesShortTtlCacheForRepeatedAgentReads() {
        TranslationMemoryRepository memoryRepo = mock(TranslationMemoryRepository.class);
        RagOpsLedgerRepository ledgerRepo = mock(RagOpsLedgerRepository.class);
        StrategyPerformanceRepository strategyRepo = mock(StrategyPerformanceRepository.class);
        AgentDbContextProperties properties = new AgentDbContextProperties();
        properties.setSnapshotCacheTtlMillis(30_000L);

        when(memoryRepo.countByStatus(TranslationMemory.MemoryStatus.ACTIVE)).thenReturn(2L);
        when(memoryRepo.countByStatus(TranslationMemory.MemoryStatus.PENDING)).thenReturn(1L);
        when(memoryRepo.findTop10ByEnergyNotNullOrderByEnergyAsc()).thenReturn(List.of(memoryRow()));
        when(ledgerRepo.findTop20ByDecisionOrderByCreatedAtDesc("FAIL")).thenReturn(List.of(ledgerRow()));
        RagOpsLedgerRepository.HotspotCountRow hotspotCount = hotspotRow("webSearch.naver", 3L);
        when(ledgerRepo.countHotspotDistribution(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(hotspotCount)));
        when(strategyRepo.findAll()).thenReturn(List.of(strategyRow()));

        AgentDbContextProvider provider = new AgentDbContextProvider(memoryRepo, ledgerRepo, strategyRepo, properties);

        AgentDbContextProvider.AgentDbSnapshot first = provider.snapshot();
        AgentDbContextProvider.AgentDbSnapshot second = provider.snapshot();

        assertTrue(first == second);
        verify(memoryRepo, times(1)).findTop10ByEnergyNotNullOrderByEnergyAsc();
        verify(ledgerRepo, times(1)).findTop20ByDecisionOrderByCreatedAtDesc("FAIL");
        verify(ledgerRepo, times(1)).countHotspotDistribution(any(Pageable.class));
        verify(strategyRepo, times(1)).findAll();
    }

    @Test
    void snapshotReportsCfvmSnapshotBackedProcessBufferForAgentPlanning() {
        TranslationMemoryRepository memoryRepo = mock(TranslationMemoryRepository.class);
        RagOpsLedgerRepository ledgerRepo = mock(RagOpsLedgerRepository.class);
        StrategyPerformanceRepository strategyRepo = mock(StrategyPerformanceRepository.class);
        AgentDbContextProperties properties = new AgentDbContextProperties();

        when(memoryRepo.findTop10ByEnergyNotNullOrderByEnergyAsc()).thenReturn(List.of());
        when(ledgerRepo.findTop20ByDecisionOrderByCreatedAtDesc("FAIL")).thenReturn(List.of());
        when(ledgerRepo.countHotspotDistribution(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(strategyRepo.findAll()).thenReturn(List.of());

        AgentDbContextProvider provider = new AgentDbContextProvider(memoryRepo, ledgerRepo, strategyRepo, properties);

        AgentDbContextProvider.AgentDbSnapshot snapshot = provider.snapshot();

        assertNotNull(snapshot.subsystemPersistence);
        Map<String, Object> cfvm = snapshot.subsystemPersistence.get("cfvmRawMatrixBuffer");
        assertEquals("process_local_ring_buffer_with_snapshot_restore", cfvm.get("storageMode"));
        assertEquals(Boolean.FALSE, cfvm.get("durable"));
        assertEquals(Boolean.TRUE, cfvm.get("repositoryBacked"));
        assertEquals(Boolean.TRUE, cfvm.get("durableCheckpoint"));
        assertEquals("snapshot-backed-process-buffer", cfvm.get("gapClass"));
        assertEquals("com.example.lms.cfvm.CfvmSnapshotService", cfvm.get("checkpointServiceFqcn"));

        Map<String, Object> traceStore = snapshot.subsystemPersistence.get("traceStore");
        assertEquals("thread_local_request_trace_with_ndjson_snapshot", traceStore.get("storageMode"));
        assertEquals(Boolean.FALSE, traceStore.get("durable"));
        assertEquals(Boolean.FALSE, traceStore.get("repositoryBacked"));
        assertEquals(Boolean.TRUE, traceStore.get("durableCheckpoint"));
        assertEquals("NDJSON", traceStore.get("persistenceType"));
        assertEquals("ndjson-backed-request-snapshot", traceStore.get("gapClass"));
        assertEquals("com.example.lms.trace.TraceSnapshotExporter", traceStore.get("checkpointExporterFqcn"));
        assertEquals("main/java/com/example/lms/trace/TraceSnapshotExporter.java",
                traceStore.get("checkpointExporterPath"));

        Map<String, Object> artPlate = snapshot.subsystemPersistence.get("artPlateEvolver");
        assertEquals("repository_backed_evolution_log", artPlate.get("storageMode"));
        assertEquals(Boolean.TRUE, artPlate.get("repositoryBacked"));
        assertEquals(Boolean.TRUE, artPlate.get("durableCheckpoint"));
        assertEquals("db-backed-evolution-log", artPlate.get("gapClass"));
        assertEquals("com.example.lms.artplate.ArtPlateEvolutionLogRepository",
                artPlate.get("checkpointRepositoryFqcn"));

        Map<String, Object> extremeZ = snapshot.subsystemPersistence.get("extremeZ");
        assertEquals("main/java/com/example/lms/service/rag/burst/ExtremeZSystemHandler.java",
                extremeZ.get("canonicalPath"));
        assertEquals("com.example.lms.service.rag.burst.ExtremeZSystemHandler",
                extremeZ.get("canonicalFqcn"));
        assertEquals("canonical-only", extremeZ.get("gapClass"));
        assertEquals(1, extremeZ.get("aliasCount"));

        Map<String, Object> dpp = snapshot.subsystemPersistence.get("dppDiversityReranker");
        assertEquals("main/java/com/example/lms/service/rag/rerank/DppDiversityReranker.java",
                dpp.get("canonicalPath"));
        assertEquals("com.example.lms.service.rag.rerank.DppDiversityReranker",
                dpp.get("canonicalFqcn"));
        assertEquals("canonical-only", dpp.get("gapClass"));
        assertEquals(1, dpp.get("aliasCount"));

        Map<String, Object> retrievalOrder = snapshot.subsystemPersistence.get("retrievalOrderService");
        assertEquals("main/java/com/example/lms/strategy/RetrievalOrderService.java",
                retrievalOrder.get("canonicalPath"));
        assertEquals("com.example.lms.strategy.RetrievalOrderService",
                retrievalOrder.get("canonicalFqcn"));
        assertEquals("canonical-only", retrievalOrder.get("gapClass"));
        assertEquals(1, retrievalOrder.get("aliasCount"));

        Map<String, Object> localLlm = snapshot.subsystemPersistence.get("localLlmProcessManager");
        assertEquals("main/java/com/example/lms/config/LocalLlmProcessManager.java",
                localLlm.get("canonicalPath"));
        assertEquals("com.example.lms.config.LocalLlmProcessManager",
                localLlm.get("canonicalFqcn"));
        assertEquals("canonical-only", localLlm.get("gapClass"));
        assertEquals(1, localLlm.get("aliasCount"));
    }

    private static TranslationMemory memoryRow() {
        TranslationMemory row = new TranslationMemory();
        ReflectionTestUtils.setField(row, "id", 7L);
        ReflectionTestUtils.setField(row, "sourceHash", "hash-only-source");
        ReflectionTestUtils.setField(row, "query", "raw user query");
        ReflectionTestUtils.setField(row, "content", "raw memory content");
        ReflectionTestUtils.setField(row, "corrected", "raw corrected content");
        ReflectionTestUtils.setField(row, "status", TranslationMemory.MemoryStatus.ACTIVE);
        ReflectionTestUtils.setField(row, "energy", 0.12d);
        ReflectionTestUtils.setField(row, "temperature", 0.9d);
        ReflectionTestUtils.setField(row, "qValue", 0.44d);
        ReflectionTestUtils.setField(row, "hitCount", 5);
        ReflectionTestUtils.setField(row, "confidenceScore", 0.83d);
        ReflectionTestUtils.setField(row, "sourceTag", "ASSISTANT");
        ReflectionTestUtils.setField(row, "modelId", "local-model");
        ReflectionTestUtils.setField(row, "createdAt", LocalDateTime.parse("2026-06-01T10:15:30"));
        ReflectionTestUtils.setField(row, "lastUsedAt", LocalDateTime.parse("2026-06-02T10:15:30"));
        return row;
    }

    private static RagOpsLedgerEntry ledgerRow() {
        return ledgerRow("plan-a");
    }

    private static RagOpsLedgerEntry ledgerRow(String planId) {
        RagOpsLedgerEntry row = new RagOpsLedgerEntry();
        row.setRunId("run-1");
        row.setEntryType("RAG_RUN");
        row.setStrategyName("ArtPlate");
        row.setDecision("FAIL");
        row.setFailureClass("timeout");
        row.setHotspot("webSearch.naver");
        row.setLatencyMs(1234L);
        row.setPlanId(planId);
        row.setResourceTier("local");
        row.setQueryHash("query-hash-only");
        row.setQueryLength(15);
        row.setCreatedAt(LocalDateTime.parse("2026-06-03T10:15:30"));
        row.setSourceCountsJson("{\"raw\":\"not exported\"}");
        row.setQualityJson("{\"raw\":\"not exported\"}");
        return row;
    }

    private static RagOpsLedgerRepository.HotspotCountRow hotspotRow(String hotspot, long count) {
        RagOpsLedgerRepository.HotspotCountRow row = mock(RagOpsLedgerRepository.HotspotCountRow.class);
        when(row.getHotspot()).thenReturn(hotspot);
        when(row.getCnt()).thenReturn(count);
        return row;
    }

    private static StrategyPerformance strategyRow() {
        return strategyRow("ArtPlate");
    }

    private static StrategyPerformance strategyRow(String strategyName) {
        StrategyPerformance row = new StrategyPerformance();
        row.setStrategyName(strategyName);
        row.setQueryCategory("default");
        row.setSuccessCount(3L);
        row.setFailureCount(1L);
        row.setAverageReward(0.7d);
        return row;
    }
}
