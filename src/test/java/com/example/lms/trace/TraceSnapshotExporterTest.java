package com.example.lms.trace;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceSnapshotExporterTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void exportsAllowlistedTraceKeysToNdjsonWithoutRawSessionOrQuery() throws Exception {
        String rawQuery = "private trace query should not be exported";
        String rawSession = "session-secret-token";
        TraceStore.put("cfvm.snapshot.saved", true);
        TraceStore.put("extremeZ.timeBudgetConsumedMs", 17L);
        TraceStore.put("embed.matryoshka.slice.actual", 2560);
        TraceStore.put("embed.matryoshka.slice.target", 1536);
        TraceStore.put("embed.matryoshka.slice.reductionRatio", 0.40d);
        TraceStore.put("embed.matryoshka.slice.expectedDistanceOpsRatio", 0.6d);
        TraceStore.put("embed.matryoshka.slice.expectedDistanceOpsSpeedup", 1.6667d);
        TraceStore.put("boosterMode.active", "EXTREMEZ");
        TraceStore.put("boosterMode.excludedModes", java.util.List.of("OVERDRIVE", "HYPERNOVA"));
        TraceStore.put("boosterMode.priority", "EXTREMEZ>HYPERNOVA>OVERDRIVE");
        TraceStore.put("boosterMode.exclusionReason", "single_primary_mode:EXTREMEZ>HYPERNOVA>OVERDRIVE");
        TraceStore.put("routing.executionPlan.primaryMode", "EXTREMEZ");
        TraceStore.put("routing.executionPlan.triggers", java.util.List.of("lowRecall", "highRiskTail"));
        TraceStore.put("routing.executionPlan.extremeZ", true);
        TraceStore.put("routing.executionPlan.overdrive", false);
        TraceStore.put("routing.executionPlan.hypernova", false);
        TraceStore.put("routing.executionPlan.applied", true);
        TraceStore.put("routing.executionPlan.applied.primaryMode", "EXTREMEZ");
        TraceStore.put("routing.executionPlan.applied.triggers", java.util.List.of("lowRecall", "highRiskTail"));
        TraceStore.put("routing.executionPlan.applied.stages", java.util.List.of("plan", "guard", "apply"));
        TraceStore.put("specialMode.conflict.suppressed", "OVERDRIVE,HYPERNOVA");
        TraceStore.put("specialMode.priority", "EXTREMEZ>HYPERNOVA>OVERDRIVE");
        TraceStore.put("retrievalOrder.lastSetBy", "PLAN_DSL");
        TraceStore.put("retrievalOrder.lastOrder", java.util.List.of("VECTOR", "KG", "WEB"));
        TraceStore.put("retrievalOrder.lastOrderSize", 3);
        TraceStore.put("retrievalOrder.authority.owner", "PLAN_DSL");
        TraceStore.put("retrievalOrder.authority.suppressedOwner", "MoE");
        TraceStore.put("retrievalOrder.authority.reason", "rulebreak_speed_first");
        TraceStore.put("retrievalOrder.authority.suppressedReason", "plan_dsl_preempts_strategy_selection");
        TraceStore.put("retrieval.order.strategy.applied", false);
        TraceStore.put("retrieval.order.strategy.reason", "plan_dsl_authority");
        TraceStore.put("nova.hypernova.riskK.used", true);
        TraceStore.put("nova.hypernova.riskK.candidateCount", 3);
        TraceStore.put("nova.hypernova.riskK.totalK", 12);
        TraceStore.put("nova.hypernova.riskK.alloc.sum", 12);
        TraceStore.put("cihRag.mlaBreadcrumbCount", 3);
        TraceStore.put("cihRag.breadcrumb.queryRedacted", true);
        TraceStore.put("localLlm.startup.status", "skipped");
        TraceStore.put("localLlm.startup.host", "user:secret@127.0.0.1:11435");
        TraceStore.put("localLlm.startup.hostHash", "hash:hostabcd");
        TraceStore.put("localLlm.warmup.modelHash", "hash:modelabcd");
        TraceStore.put("localLlm.warmup.modelLength", 14);
        TraceStore.put("llm.modelGuard.triggered", true);
        TraceStore.put("llm.modelGuard.mode", "FAIL_FAST");
        TraceStore.put("llm.modelGuard.endpoint", "/v1/chat/completions");
        TraceStore.put("llm.modelGuard.failReason", "EXPECTED_FAILURE_MODEL_ENDPOINT_MISMATCH");
        TraceStore.put("llm.modelGuard.requestedModelHash", "hash:modelguard");
        TraceStore.put("llm.modelGuard.requestedModelLength", 19);
        TraceStore.put("private.raw.query", rawQuery);

        TraceSnapshotExporter exporter = new TraceSnapshotExporter(tempDir, true);
        exporter.exportCurrentTrace("rid-001", rawSession);

        Path file = tempDir.resolve("trace_" + java.time.LocalDate.now() + ".ndjson");
        assertTrue(Files.exists(file));
        String body = Files.readString(file);

        assertTrue(body.contains("cfvm.snapshot.saved"));
        assertTrue(body.contains("extremeZ.timeBudgetConsumedMs"));
        assertTrue(body.contains("embed.matryoshka.slice.actual"));
        assertTrue(body.contains("embed.matryoshka.slice.target"));
        assertTrue(body.contains("embed.matryoshka.slice.reductionRatio"));
        assertTrue(body.contains("embed.matryoshka.slice.expectedDistanceOpsRatio"));
        assertTrue(body.contains("embed.matryoshka.slice.expectedDistanceOpsSpeedup"));
        assertTrue(body.contains("\"routing.executionPlan.primaryMode\":\"EXTREMEZ\""));
        assertTrue(body.contains("\"routing.executionPlan.extremeZ\":true"));
        assertTrue(body.contains("\"routing.executionPlan.overdrive\":false"));
        assertTrue(body.contains("\"routing.executionPlan.hypernova\":false"));
        assertTrue(body.contains("\"routing.executionPlan.applied\":true"));
        assertTrue(body.contains("\"routing.executionPlan.applied.triggers\":[\"lowRecall\",\"highRiskTail\"]"));
        assertTrue(body.contains("\"routing.executionPlan.applied.stages\":[\"plan\",\"guard\",\"apply\"]"));
        assertTrue(body.contains("\"specialMode.conflict.suppressed\":\"OVERDRIVE_HYPERNOVA\""));
        assertTrue(body.contains("\"specialMode.priority\":\"EXTREMEZ_HYPERNOVA_OVERDRIVE\""));
        assertTrue(body.contains("\"boosterMode.priority\":\"EXTREMEZ_HYPERNOVA_OVERDRIVE\""));
        assertTrue(body.contains("\"retrievalOrder.lastSetBy\":\"PLAN_DSL\""));
        assertTrue(body.contains("\"retrievalOrder.lastOrder\":[\"VECTOR\",\"KG\",\"WEB\"]"));
        assertTrue(body.contains("\"retrievalOrder.lastOrderSize\":3"));
        assertTrue(body.contains("\"retrievalOrder.authority.owner\":\"PLAN_DSL\""));
        assertTrue(body.contains("\"retrievalOrder.authority.suppressedOwner\":\"MoE\""));
        assertTrue(body.contains("\"retrievalOrder.authority.reason\":\"rulebreak_speed_first\""));
        assertTrue(body.contains("\"retrievalOrder.authority.suppressedReason\":\"plan_dsl_preempts_strategy_selection\""));
        assertTrue(body.contains("\"retrieval.order.strategy.applied\":false"));
        assertTrue(body.contains("\"retrieval.order.strategy.reason\":\"plan_dsl_authority\""));
        assertTrue(body.contains("\"nova.hypernova.riskK.used\":true"));
        assertTrue(body.contains("\"nova.hypernova.riskK.candidateCount\":3"));
        assertTrue(body.contains("\"nova.hypernova.riskK.totalK\":12"));
        assertTrue(body.contains("\"nova.hypernova.riskK.alloc.sum\":12"));
        assertTrue(body.contains("\"cihRag.breadcrumb.queryRedacted\":true"));
        assertTrue(body.contains("\"cihRag.mlaBreadcrumbCount\":3"));
        assertTrue(body.contains("\"localLlm.startup.status\":\"skipped\""));
        assertTrue(body.contains("\"localLlm.startup.hostHash\":\"hash:hostabcd\""));
        assertTrue(body.contains("\"localLlm.warmup.modelHash\":\"hash:modelabcd\""));
        assertTrue(body.contains("\"localLlm.warmup.modelLength\":14"));
        assertTrue(body.contains("\"llm.modelGuard.triggered\":true"));
        assertTrue(body.contains("\"llm.modelGuard.mode\":\"FAIL_FAST\""));
        assertTrue(body.contains("\"llm.modelGuard.endpoint\":\"/v1/chat/completions\""));
        assertTrue(body.contains("\"llm.modelGuard.failReason\":\"EXPECTED_FAILURE_MODEL_ENDPOINT_MISMATCH\""));
        assertTrue(body.contains("\"llm.modelGuard.requestedModelHash\":\"hash:modelguard\""));
        assertTrue(body.contains("\"llm.modelGuard.requestedModelLength\":19"));
        assertTrue(body.contains("_requestHash"));
        assertTrue(body.contains("_sessionHash"));
        assertFalse(body.contains(rawQuery));
        assertFalse(body.contains(rawSession));
        assertFalse(body.contains("private.raw.query"));
        assertFalse(body.contains("user:secret"));
        assertFalse(body.contains("localLlm.startup.host\""));
        assertEquals(Boolean.TRUE, TraceStore.get("trace.snapshot.exported"));
    }

    @Test
    void exportsStageCountsFromLastFallbackWhenCanonicalKeyIsAbsent() throws Exception {
        TraceStore.put("stageCountsSelectedFromOut.last", java.util.Map.of("NOFILTER_SAFE", 2, "OFFICIAL", 0));
        TraceStore.put("web.failsoft.stageCountsSelectedFromOut.last.runId", "9001");

        TraceSnapshotExporter exporter = new TraceSnapshotExporter(tempDir, true);
        exporter.exportCurrentTrace("rid-002", "session-002");

        Path file = tempDir.resolve("trace_" + java.time.LocalDate.now() + ".ndjson");
        String body = Files.readString(file);

        assertTrue(body.contains("\"stageCountsSelectedFromOut\""), body);
        assertTrue(body.contains("\"NOFILTER_SAFE\":2"), body);
        assertTrue(body.contains("\"OFFICIAL\":0"), body);
        assertFalse(body.contains("stageCountsSelectedFromOut.last\""), body);
    }

    @Test
    void exportsAllowedMapValuesWithoutSensitiveFieldLabels() throws Exception {
        TraceStore.put("stageCountsSelectedFromOut", java.util.Map.of(
                "OFFICIAL", 0,
                "apiKey", "provider-secret-value",
                "ownerToken", "owner-token-value"));

        TraceSnapshotExporter exporter = new TraceSnapshotExporter(tempDir, true);
        exporter.exportCurrentTrace("rid-008", "session-008");

        Path file = tempDir.resolve("trace_" + java.time.LocalDate.now() + ".ndjson");
        String body = Files.readString(file);

        assertTrue(body.contains("\"stageCountsSelectedFromOut\""), body);
        assertTrue(body.contains("\"OFFICIAL\":0"), body);
        assertFalse(body.contains("apiKey"), body);
        assertFalse(body.contains("ownerToken"), body);
        assertFalse(body.contains("provider-secret-value"), body);
        assertFalse(body.contains("owner-token-value"), body);
    }

    @Test
    void exportsVectorFallbackKpisWithoutRawQuery() throws Exception {
        String rawQuery = "private vector fallback snapshot query ownerToken=secret";
        TraceStore.put("vectorFallback.used", true);
        TraceStore.put("vectorFallback.reason", "web_empty");
        TraceStore.put("vectorFallback.effectiveTopK", 10);
        TraceStore.put("vectorFallback.rawQuery", rawQuery);

        TraceSnapshotExporter exporter = new TraceSnapshotExporter(tempDir, true);
        exporter.exportCurrentTrace("rid-004", "session-004");

        Path file = tempDir.resolve("trace_" + java.time.LocalDate.now() + ".ndjson");
        String body = Files.readString(file);

        assertTrue(body.contains("\"vectorFallback.used\":true"), body);
        assertTrue(body.contains("\"vectorFallback.reason\":\"web_empty\""), body);
        assertTrue(body.contains("\"vectorFallback.effectiveTopK\":10"), body);
        assertFalse(body.contains(rawQuery), body);
        assertFalse(body.contains("vectorFallback.rawQuery"), body);
    }

    @Test
    void exportsNamespacedRescueKpisAsCanonicalSnapshotFields() throws Exception {
        TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.rescueMerge.tracePool.size", 4);
        TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.rescueMerge.used", true);

        TraceSnapshotExporter exporter = new TraceSnapshotExporter(tempDir, true);
        exporter.exportCurrentTrace("rid-005", "session-005");

        Path file = tempDir.resolve("trace_" + java.time.LocalDate.now() + ".ndjson");
        String body = Files.readString(file);

        assertTrue(body.contains("\"tracePool.size\":4"), body);
        assertTrue(body.contains("\"rescueMerge.used\":true"), body);
        assertFalse(body.contains("hybridEmptyFallback.cacheOnly.rescueMerge.tracePool.size"), body);
        assertFalse(body.contains("hybridEmptyFallback.cacheOnly.rescueMerge.used"), body);
    }

    @Test
    void exportsNamespacedStarvationKpisAsCanonicalSnapshotFields() throws Exception {
        TraceStore.put("web.failsoft.starvationFallback.used", true);
        TraceStore.put("web.failsoft.starvationFallback.poolUsed", "NOFILTER_SAFE");
        TraceStore.put("web.failsoft.starvationFallback.pool.safe.size", 3);
        TraceStore.put("web.failsoft.starvationFallback.pool.dev.size", 1);
        TraceStore.put("web.failsoft.starvationFallback.count", 2);
        TraceStore.put("web.failsoft.starvationFallback.added", 2);

        TraceSnapshotExporter exporter = new TraceSnapshotExporter(tempDir, true);
        exporter.exportCurrentTrace("rid-006", "session-006");

        Path file = tempDir.resolve("trace_" + java.time.LocalDate.now() + ".ndjson");
        String body = Files.readString(file);

        assertTrue(body.contains("\"starvationFallback.used\":true"), body);
        assertTrue(body.contains("\"starvationFallback.poolUsed\":\"NOFILTER_SAFE\""), body);
        assertTrue(body.contains("\"starvationFallback.pool.safe.size\":3"), body);
        assertTrue(body.contains("\"starvationFallback.pool.dev.size\":1"), body);
        assertTrue(body.contains("\"starvationFallback.count\":2"), body);
        assertTrue(body.contains("\"starvationFallback.added\":2"), body);
        assertFalse(body.contains("web.failsoft.starvationFallback.used"), body);
        assertFalse(body.contains("web.failsoft.starvationFallback.poolUsed"), body);
    }

    @Test
    void exportsBareNamespacedStarvationFallbackAsCanonicalTrigger() throws Exception {
        TraceStore.put("web.failsoft.starvationFallback", "officialOnly->NOFILTER_SAFE");

        TraceSnapshotExporter exporter = new TraceSnapshotExporter(tempDir, true);
        exporter.exportCurrentTrace("rid-007", "session-007");

        Path file = tempDir.resolve("trace_" + java.time.LocalDate.now() + ".ndjson");
        String body = Files.readString(file);

        assertTrue(body.contains("\"starvationFallback.trigger\":\"officialOnly_NOFILTER_SAFE\""), body);
        assertFalse(body.contains("web.failsoft.starvationFallback\""), body);
    }

    @Test
    void exportsProviderSkippedReasonsWithoutRawProviderQuery() throws Exception {
        String rawProviderQuery = "private provider query ownerToken=secret";
        TraceStore.put("web.brave.skipped.reason", "missing_brave_api_key");
        TraceStore.put("web.serpapi.skipped.reason", "missing_serpapi_api_key");
        TraceStore.put("web.brave.query", rawProviderQuery);

        TraceSnapshotExporter exporter = new TraceSnapshotExporter(tempDir, true);
        exporter.exportCurrentTrace("rid-003", "session-003");

        Path file = tempDir.resolve("trace_" + java.time.LocalDate.now() + ".ndjson");
        String body = Files.readString(file);

        assertTrue(body.contains("\"web.brave.skipped.reason\":\"missing_brave_api_key\""), body);
        assertTrue(body.contains("\"web.serpapi.skipped.reason\":\"missing_serpapi_api_key\""), body);
        assertFalse(body.contains(rawProviderQuery), body);
        assertFalse(body.contains("web.brave.query"), body);
    }
}
