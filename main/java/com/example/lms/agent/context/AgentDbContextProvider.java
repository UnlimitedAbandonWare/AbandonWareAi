package com.example.lms.agent.context;

import com.example.lms.entity.RagOpsLedgerEntry;
import com.example.lms.entity.TranslationMemory;
import com.example.lms.repository.RagOpsLedgerRepository;
import com.example.lms.repository.TranslationMemoryRepository;
import com.example.lms.strategy.StrategyPerformance;
import com.example.lms.strategy.StrategyPerformanceRepository;
import com.example.lms.trace.SafeRedactor;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ClassUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AgentDbContextProvider {

    private static final String EXTREMEZ_CANONICAL_FQCN =
            "com.example.lms.service.rag.burst.ExtremeZSystemHandler";
    private static final String EXTREMEZ_CANONICAL_PATH =
            "main/java/com/example/lms/service/rag/burst/ExtremeZSystemHandler.java";
    private static final String DPP_CANONICAL_FQCN =
            "com.example.lms.service.rag.rerank.DppDiversityReranker";
    private static final String DPP_CANONICAL_PATH =
            "main/java/com/example/lms/service/rag/rerank/DppDiversityReranker.java";
    private static final String RETRIEVAL_ORDER_CANONICAL_FQCN =
            "com.example.lms.strategy.RetrievalOrderService";
    private static final String RETRIEVAL_ORDER_CANONICAL_PATH =
            "main/java/com/example/lms/strategy/RetrievalOrderService.java";
    private static final String LOCAL_LLM_CANONICAL_FQCN =
            "com.example.lms.config.LocalLlmProcessManager";
    private static final String LOCAL_LLM_CANONICAL_PATH =
            "main/java/com/example/lms/config/LocalLlmProcessManager.java";
    private static final String CFVM_SNAPSHOT_ENTITY_FQCN = "com.example.lms.cfvm.CfvmSnapshot";
    private static final String CFVM_SNAPSHOT_REPOSITORY_FQCN = "com.example.lms.cfvm.CfvmSnapshotRepository";
    private static final String CFVM_SNAPSHOT_SERVICE_FQCN = "com.example.lms.cfvm.CfvmSnapshotService";
    private static final String CFVM_SNAPSHOT_SERVICE_PATH =
            "main/java/com/example/lms/cfvm/CfvmSnapshotService.java";
    private static final String ART_PLATE_EVOLUTION_LOG_FQCN =
            "com.example.lms.artplate.ArtPlateEvolutionLog";
    private static final String ART_PLATE_EVOLUTION_LOG_REPOSITORY_FQCN =
            "com.example.lms.artplate.ArtPlateEvolutionLogRepository";
    private static final String ART_PLATE_EVOLVER_FQCN =
            "com.example.lms.artplate.ArtPlateEvolver";
    private static final String ART_PLATE_EVOLVER_PATH =
            "main/java/com/example/lms/artplate/ArtPlateEvolver.java";
    private static final String TRACE_SNAPSHOT_EXPORTER_FQCN =
            "com.example.lms.trace.TraceSnapshotExporter";
    private static final String TRACE_SNAPSHOT_EXPORTER_PATH =
            "main/java/com/example/lms/trace/TraceSnapshotExporter.java";
    private static final List<String> EXTREMEZ_COMPATIBILITY_ALIASES = List.of();
    private static final List<String> DPP_COMPATIBILITY_ALIASES = List.of();
    private static final List<String> RETRIEVAL_ORDER_COMPATIBILITY_ALIASES = List.of();
    private static final List<String> LOCAL_LLM_COMPATIBILITY_ALIASES = List.of();

    private final TranslationMemoryRepository memoryRepo;
    private final RagOpsLedgerRepository ledgerRepo;
    private final StrategyPerformanceRepository strategyRepo;
    private final AgentDbContextProperties properties;
    private volatile AgentDbSnapshot cachedSnapshot;
    private volatile long cachedSnapshotAtMillis;

    public AgentDbContextProvider(TranslationMemoryRepository memoryRepo,
                                  RagOpsLedgerRepository ledgerRepo,
                                  StrategyPerformanceRepository strategyRepo,
                                  AgentDbContextProperties properties) {
        this.memoryRepo = memoryRepo;
        this.ledgerRepo = ledgerRepo;
        this.strategyRepo = strategyRepo;
        this.properties = properties;
    }

    @Transactional(readOnly = true, timeoutString = "${agent.db-context.query-timeout-seconds:2}")
    public AgentDbSnapshot snapshot() {
        long ttlMillis = Math.max(0L, properties.getSnapshotCacheTtlMillis());
        long now = System.currentTimeMillis();
        AgentDbSnapshot cached = cachedSnapshot;
        if (ttlMillis > 0L && cached != null && now - cachedSnapshotAtMillis < ttlMillis) {
            return cached;
        }
        AgentDbSnapshot snap = new AgentDbSnapshot();
        snap.capturedAt = LocalDateTime.now().toString();
        snap.memory = memorySnapshot();
        snap.ledger = ledgerSnapshot();
        snap.strategy = strategySnapshot();
        snap.subsystemPersistence = subsystemPersistenceSnapshot();
        if (ttlMillis > 0L) {
            cachedSnapshot = snap;
            cachedSnapshotAtMillis = now;
        }
        return snap;
    }

    @Transactional(readOnly = true, timeoutString = "${agent.db-context.query-timeout-seconds:2}")
    public MemorySnapshot memorySnapshot() {
        MemorySnapshot snapshot = new MemorySnapshot();
        for (TranslationMemory.MemoryStatus status : TranslationMemory.MemoryStatus.values()) {
            snapshot.statusCounts.put(status.name(), memoryRepo.countByStatus(status));
        }
        int limit = clamp(properties.getMaxMemoryTopN(), 1, 10);
        snapshot.topLowEnergy = memoryRepo.findTop10ByEnergyNotNullOrderByEnergyAsc()
                .stream()
                .limit(limit)
                .map(AgentDbContextProvider::toMemorySummary)
                .toList();
        return snapshot;
    }

    @Transactional(readOnly = true, timeoutString = "${agent.db-context.query-timeout-seconds:2}")
    public LedgerSnapshot ledgerSnapshot() {
        LedgerSnapshot snapshot = new LedgerSnapshot();
        int failureLimit = clamp(properties.getMaxLedgerFailures(), 1, 20);
        snapshot.recentFailures = ledgerRepo.findTop20ByDecisionOrderByCreatedAtDesc("FAIL")
                .stream()
                .limit(failureLimit)
                .map(AgentDbContextProvider::toLedgerSummary)
                .toList();
        snapshot.hotspotDistribution = ledgerRepo.countHotspotDistribution(PageRequest.of(0, 200))
                .getContent()
                .stream()
                .map(AgentDbContextProvider::toHotspotSummary)
                .toList();
        return snapshot;
    }

    @Transactional(readOnly = true, timeoutString = "${agent.db-context.query-timeout-seconds:2}")
    public StrategySnapshot strategySnapshot() {
        StrategySnapshot snapshot = new StrategySnapshot();
        snapshot.performances = strategyRepo.findAll()
                .stream()
                .map(AgentDbContextProvider::toStrategySummary)
                .toList();
        return snapshot;
    }

    private static Map<String, Object> toMemorySummary(TranslationMemory memory) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", memory.getId());
        map.put("status", memory.getStatus() != null ? memory.getStatus().name() : null);
        map.put("contentHash", safeLabel(memory.getSourceHash()));
        map.put("energy", memory.getEnergy());
        map.put("temperature", memory.getTemperature());
        map.put("qValue", memory.getQValue());
        map.put("hitCount", memory.getHitCount());
        map.put("confidenceScore", memory.getConfidenceScore());
        map.put("sourceTag", safeLabel(memory.getSourceTag()));
        map.put("queryLength", memory.getQuery() != null ? memory.getQuery().length() : 0);
        map.put("modelId", safeLabel(memory.getModelId()));
        map.put("createdAt", memory.getCreatedAt() != null ? memory.getCreatedAt().toString() : null);
        map.put("lastUsedAt", memory.getLastUsedAt() != null ? memory.getLastUsedAt().toString() : null);
        return map;
    }

    private static Map<String, Object> toLedgerSummary(RagOpsLedgerEntry entry) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("runId", safeLabel(entry.getRunId()));
        map.put("entryType", safeLabel(entry.getEntryType()));
        map.put("strategyName", safeLabel(entry.getStrategyName()));
        map.put("decision", safeLabel(entry.getDecision()));
        map.put("failureClass", safeLabel(entry.getFailureClass()));
        map.put("hotspot", safeLabel(entry.getHotspot()));
        map.put("latencyMs", entry.getLatencyMs());
        map.put("planId", safeLabel(entry.getPlanId()));
        map.put("resourceTier", safeLabel(entry.getResourceTier()));
        map.put("createdAt", entry.getCreatedAt() != null ? entry.getCreatedAt().toString() : null);
        map.put("queryHash", safeLabel(entry.getQueryHash()));
        map.put("queryLength", entry.getQueryLength());
        return map;
    }

    private static Map<String, Object> toHotspotSummary(RagOpsLedgerRepository.HotspotCountRow row) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("hotspot", safeLabel(row.getHotspot()));
        map.put("count", row.getCnt());
        return map;
    }

    private static Map<String, Object> toStrategySummary(StrategyPerformance performance) {
        Map<String, Object> map = new LinkedHashMap<>();
        long success = performance.getSuccessCount();
        long failure = performance.getFailureCount();
        long sampleCount = success + failure;
        map.put("strategyName", safeLabel(performance.getStrategyName()));
        map.put("queryCategory", safeLabel(performance.getQueryCategory()));
        map.put("successCount", success);
        map.put("failureCount", failure);
        map.put("sampleCount", sampleCount);
        map.put("successRate", sampleCount == 0 ? 0.0d : (double) success / sampleCount);
        map.put("averageReward", performance.getAverageReward());
        map.put("updatedAt", performance.getUpdatedAt() != null ? performance.getUpdatedAt().toString() : null);
        return map;
    }

    private static Map<String, Map<String, Object>> subsystemPersistenceSnapshot() {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        out.put("cfvmRawMatrixBuffer", cfvmRawMatrixBufferPersistence());
        out.put("traceStore", traceStorePersistence());
        out.put("artPlateEvolver", artPlateEvolverPersistence());
        out.put("extremeZ", extremeZAliasSurface());
        out.put("dppDiversityReranker", dppAliasSurface());
        out.put("retrievalOrderService", retrievalOrderAliasSurface());
        out.put("localLlmProcessManager", localLlmAliasSurface());
        return out;
    }

    private static Map<String, Object> cfvmRawMatrixBufferPersistence() {
        boolean snapshotEntityPresent = classPresent(CFVM_SNAPSHOT_ENTITY_FQCN);
        boolean snapshotRepositoryPresent = classPresent(CFVM_SNAPSHOT_REPOSITORY_FQCN);
        boolean snapshotServicePresent = classPresent(CFVM_SNAPSHOT_SERVICE_FQCN);
        boolean durableCheckpoint = snapshotEntityPresent && snapshotRepositoryPresent && snapshotServicePresent;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("component", "RawMatrixBuffer");
        row.put("sourceRootKind", "active-main-java");
        row.put("storageMode", durableCheckpoint
                ? "process_local_ring_buffer_with_snapshot_restore"
                : "process_local_ring_buffer");
        row.put("durable", false);
        row.put("repositoryBacked", snapshotRepositoryPresent);
        row.put("durableCheckpoint", durableCheckpoint);
        row.put("checkpointEntityPresent", snapshotEntityPresent);
        row.put("checkpointRepositoryPresent", snapshotRepositoryPresent);
        row.put("checkpointServicePresent", snapshotServicePresent);
        row.put("checkpointServiceFqcn", CFVM_SNAPSHOT_SERVICE_FQCN);
        row.put("checkpointServicePath", CFVM_SNAPSHOT_SERVICE_PATH);
        row.put("gapClass", durableCheckpoint
                ? "snapshot-backed-process-buffer"
                : "volatile-process-memory");
        row.put("handoff", "CfvmFailureRecorder");
        return row;
    }

    private static Map<String, Object> traceStorePersistence() {
        boolean exporterPresent = classPresent(TRACE_SNAPSHOT_EXPORTER_FQCN);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("component", "TraceStore");
        row.put("sourceRootKind", "active-main-java");
        row.put("storageMode", exporterPresent
                ? "thread_local_request_trace_with_ndjson_snapshot"
                : "thread_local_request_trace");
        row.put("durable", false);
        row.put("repositoryBacked", false);
        row.put("durableCheckpoint", exporterPresent);
        row.put("persistenceType", exporterPresent ? "NDJSON" : "none");
        row.put("checkpointExporterPresent", exporterPresent);
        row.put("checkpointExporterFqcn", TRACE_SNAPSHOT_EXPORTER_FQCN);
        row.put("checkpointExporterPath", TRACE_SNAPSHOT_EXPORTER_PATH);
        row.put("gapClass", exporterPresent
                ? "ndjson-backed-request-snapshot"
                : "request-local-non-durable-trace");
        return row;
    }

    private static Map<String, Object> artPlateEvolverPersistence() {
        boolean logEntityPresent = classPresent(ART_PLATE_EVOLUTION_LOG_FQCN);
        boolean repositoryPresent = classPresent(ART_PLATE_EVOLUTION_LOG_REPOSITORY_FQCN);
        boolean evolverPresent = classPresent(ART_PLATE_EVOLVER_FQCN);
        boolean checkpoint = logEntityPresent && repositoryPresent && evolverPresent;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("component", "ArtPlateEvolver");
        row.put("sourceRootKind", "active-main-java");
        row.put("storageMode", checkpoint ? "repository_backed_evolution_log" : "process_local_evolver");
        row.put("durable", checkpoint);
        row.put("repositoryBacked", repositoryPresent);
        row.put("durableCheckpoint", checkpoint);
        row.put("checkpointEntityPresent", logEntityPresent);
        row.put("checkpointRepositoryPresent", repositoryPresent);
        row.put("checkpointServicePresent", evolverPresent);
        row.put("checkpointRepositoryFqcn", ART_PLATE_EVOLUTION_LOG_REPOSITORY_FQCN);
        row.put("checkpointServiceFqcn", ART_PLATE_EVOLVER_FQCN);
        row.put("checkpointServicePath", ART_PLATE_EVOLVER_PATH);
        row.put("gapClass", checkpoint ? "db-backed-evolution-log" : "evidence_needed");
        return row;
    }

    private static Map<String, Object> extremeZAliasSurface() {
        return aliasSurface(
                "ExtremeZSystemHandler",
                EXTREMEZ_CANONICAL_PATH,
                EXTREMEZ_CANONICAL_FQCN,
                EXTREMEZ_COMPATIBILITY_ALIASES);
    }

    private static Map<String, Object> dppAliasSurface() {
        return aliasSurface(
                "DppDiversityReranker",
                DPP_CANONICAL_PATH,
                DPP_CANONICAL_FQCN,
                DPP_COMPATIBILITY_ALIASES);
    }

    private static Map<String, Object> retrievalOrderAliasSurface() {
        return aliasSurface(
                "RetrievalOrderService",
                RETRIEVAL_ORDER_CANONICAL_PATH,
                RETRIEVAL_ORDER_CANONICAL_FQCN,
                RETRIEVAL_ORDER_COMPATIBILITY_ALIASES);
    }

    private static Map<String, Object> localLlmAliasSurface() {
        return aliasSurface(
                "LocalLlmProcessManager",
                LOCAL_LLM_CANONICAL_PATH,
                LOCAL_LLM_CANONICAL_FQCN,
                LOCAL_LLM_COMPATIBILITY_ALIASES);
    }

    private static Map<String, Object> aliasSurface(
            String component,
            String canonicalPath,
            String canonicalFqcn,
            List<String> compatibilityAliases) {
        List<String> presentAliases = compatibilityAliases.stream()
                .filter(AgentDbContextProvider::classPresent)
                .toList();
        boolean canonicalPresent = classPresent(canonicalFqcn);
        int aliasCount = (canonicalPresent ? 1 : 0) + presentAliases.size();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("component", component);
        row.put("sourceRootKind", "active-main-java");
        row.put("canonicalPath", canonicalPath);
        row.put("canonicalFqcn", canonicalFqcn);
        row.put("canonicalPresent", canonicalPresent);
        row.put("compatibilityAliases", presentAliases);
        row.put("aliasCount", aliasCount);
        row.put("gapClass", aliasCount > 1 ? "multi-package-alias-surface" : "canonical-only");
        return row;
    }

    private static boolean classPresent(String fqcn) {
        return ClassUtils.isPresent(fqcn, AgentDbContextProvider.class.getClassLoader());
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String safeLabel(Object value) {
        if (value == null) {
            return null;
        }
        return SafeRedactor.traceLabelOrFallback(value, "unknown");
    }

    public static class AgentDbSnapshot {
        public String capturedAt;
        public MemorySnapshot memory;
        public LedgerSnapshot ledger;
        public StrategySnapshot strategy;
        public Map<String, Map<String, Object>> subsystemPersistence = new LinkedHashMap<>();
    }

    public static class MemorySnapshot {
        public Map<String, Long> statusCounts = new LinkedHashMap<>();
        public List<Map<String, Object>> topLowEnergy = new ArrayList<>();
    }

    public static class LedgerSnapshot {
        public List<Map<String, Object>> recentFailures = new ArrayList<>();
        public List<Map<String, Object>> hotspotDistribution = new ArrayList<>();
    }

    public static class StrategySnapshot {
        public List<Map<String, Object>> performances = new ArrayList<>();
    }
}
