package com.example.lms.learning.ops;

import com.example.lms.debug.DebugEventStore;
import com.example.lms.llm.ModelRuntimeHealthTracker;
import com.example.lms.llm.OpenAiEndpointCompatibility;
import com.example.lms.resilience.RagFailureBlackboxService;
import com.example.lms.search.TraceStore;
import com.example.lms.service.ops.RagOpsLedgerService;
import com.example.lms.service.vector.VectorQuarantineDlqService;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.uaw.autolearn.UawAutolearnProperties;
import com.example.lms.uaw.autolearn.UawAutolearnQualityTracker;
import com.example.lms.learning.virtualpoint.VirtualPoint;
import com.example.lms.learning.virtualpoint.VirtualPointService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagLearningOpsDashboardServiceTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void clearTraceBefore() {
        TraceStore.clear();
    }

    @AfterEach
    void clearTraceAfter() {
        TraceStore.clear();
    }

    @Test
    void overviewParsesTrainingSamplesFailurePatternsAndPrometheusMetrics() throws Exception {
        Path dataset = tempDir.resolve("train_rag.jsonl");
        Files.writeString(dataset, """
                {"ts":"2026-05-30T01:00:00Z","dataset":"uaw-train","model":"gemma3:27b-it-q4_K_M","provider":"local","question":"How should the RAG sample be checked?","answer":"Use redacted evidence and quality gates.","passages":[{"title":"ops note","source":"file://rag-note","text":"operator-visible evidence"}],"qualityScore":0.82,"final_sigmoid":0.91,"finalGate":true,"validation":{"decision":"accepted","runtime":{"latencyMs":120,"evidenceCount":1,"afterFilterCount":1},"feedback":{"vectorDecision":"ACCEPT"}}}
                {"ts":"2026-05-30T01:05:00Z","dataset":"uaw-train","model":"qwen2.5:14b","provider":"local","question":"Why did the loop fail?","answer":"History contamination pressure.","evidenceCount":0,"finalGate":false,"validation":{"decision":"rejected","rejectReasons":["history contamination"],"feedback":{"vectorDecision":"QUARANTINE"},"anomalies":{"flags":["history_contamination"]}}}
                """, StandardCharsets.UTF_8);

        Path failures = tempDir.resolve("failure-pattern.jsonl");
        Files.writeString(failures, """
                {"tsEpochMillis":10,"kind":"timeout","key":"brave","source":"provider","cooldownMs":1000}
                {"tsEpochMillis":20,"kind":"timeout","key":"brave","source":"provider","cooldownMs":2000}
                {"tsEpochMillis":30,"kind":"after_filter","key":"zero_evidence","source":"rag"}
                """, StandardCharsets.UTF_8);
        Path reportDir = tempDir.resolve("__reports__");
        Files.createDirectories(reportDir);
        Files.writeString(reportDir.resolve("legacy-development-candidates.tsv"), """
                source_path\tsource_set_role\tcreation_time\tlast_write_time\tfeature_bucket\tdevelopment_function\tcandidate_action\treason\tpackage\tfqcn
                main\\java\\com\\example\\lms\\uaw\\autolearn\\UawAutolearnService.java\tactive-root\t2026-04-30T11:37:14Z\t2026-05-30T00:18:18Z\tautolearn_runtime\tchatbot_learning_loop\tKEEP\tactive_chatbot_learning_pipeline\tcom.example.lms.uaw.autolearn\tcom.example.lms.uaw.autolearn.UawAutolearnService
                main\\java\\com\\example\\lms\\prompt\\PromptBuilder.java\tactive-root\t2026-04-30T11:37:14Z\t2026-05-30T00:18:18Z\tprompt_boundary\tchatbot_prompt_assembly\tKEEP\tactive_chatbot_prompt_boundary\tcom.example.lms.prompt\tcom.example.lms.prompt.PromptBuilder
                """, StandardCharsets.UTF_8);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        UawAutolearnProperties props = new UawAutolearnProperties();
        props.getDataset().setPath(dataset.toString());
        UawAutolearnQualityTracker qualityTracker = new UawAutolearnQualityTracker(
                props,
                provider(registry),
                provider((DebugEventStore) null),
                provider((RagFailureBlackboxService) null));
        ModelRuntimeHealthTracker modelHealth = new ModelRuntimeHealthTracker();
        modelHealth.recordSuccess("local", "gemma3:27b-it-q4_K_M",
                OpenAiEndpointCompatibility.Endpoint.CHAT_COMPLETIONS);
        RagOpsLedgerService opsLedger = mock(RagOpsLedgerService.class);
        when(opsLedger.isEnabled()).thenReturn(true);
        when(opsLedger.summary(24)).thenReturn(Map.of("enabled", true, "total", 1));
        when(opsLedger.recent(null, null, 10)).thenReturn(List.of(Map.of("entryType", "RAG_RUN")));
        when(opsLedger.recent("AUTOLEARN_CYCLE", null, 10)).thenReturn(List.of(Map.of(
                "entryType", "AUTOLEARN_CYCLE",
                "decision", "BLOCK_RETRAIN",
                "failureClass", "gpu_hardware_pressure",
                "hotspot", "gpu_hardware",
                "sourceCounts", Map.of("attempted", 3, "accepted", 2),
                "quality", Map.of("trainDecision", "BLOCK_RETRAIN"),
                "matrix", Map.of("q_gpu_hardware_pressure", 0.55d),
                "gpuAdmission", Map.of("hardware", Map.of(
                        "admissionStatus", "degraded",
                        "reason", "gpu_memory_pressure",
                        "pressureLevel", "warn",
                        "pressure", 0.55d,
                        "retrainAllowed", false,
                        "rerankAllowed", true)),
                "createdAt", "2026-05-30T01:10:00")));
        VirtualPointService virtualPoints = new VirtualPointService();
        virtualPoints.put("history-contamination", new VirtualPoint(
                new float[]{0.1f, 0.2f},
                0.7d,
                0.9d,
                "history_contamination",
                "requery",
                "pattern-1",
                42L));
        TraceStore.put("prompt.context.composer.version", "ablation-spread-v2");
        TraceStore.put("prompt.context.composer.enabled", true);
        TraceStore.put("prompt.context.composer.activated", true);
        TraceStore.put("prompt.context.composer.reason", "trace_anchor_pressure");
        TraceStore.put("prompt.context.composer.pressureScore", 0.81d);
        TraceStore.put("prompt.context.composer.anchor.hash", "anchorhash123");
        TraceStore.put("prompt.context.composer.dropCounts", Map.of("web", 2));
        TraceStore.put("prompt.context.composer.anchorProbe.reductionRatio", 0.50d);
        TraceStore.put("prompt.context.composer.spreadProbe.applied", true);
        TraceStore.put("prompt.context.composer.spreadProbe.kSchedule", List.of(64, 48, 32, 16, 5));
        TraceStore.put("prompt.context.composer.spreadProbe.reductionRatio", 0.1667d);
        TraceStore.put("prompt.memory.compressor.activated", true);
        TraceStore.put("prompt.memory.compressor.outputLen", 256);
        TraceStore.put("cihRag.activeFileCount", 2);
        TraceStore.put("cihRag.skippedFileCount", 0);
        TraceStore.put("cihRag.mlaBreadcrumbCount", 3);
        TraceStore.put("cihRag.breadcrumb.queryRedacted", true);
        TraceStore.put("cihRag.implementationStage", "pass_through");
        TraceStore.put("ablation.traceAnchor.topHash", "traceanchor123");
        TraceStore.put("ablation.traceAnchor.topRouteHint", "BQ");
        TraceStore.put("ablation.traceAnchor.routeCorrectionNeed", 0.44d);
        TraceStore.put("blackbox.risk.traceAnchor", Map.of("routeHint", "BQ", "expectedDelta", 0.44d));
        TraceStore.put("blackbox.risk.matrix", Map.of(
                "q_anchor_drop_pressure", 0.44d,
                "q_compression_efficiency", 0.75d));
        TraceStore.put("extremez.enabled", true);
        TraceStore.put("extremez.activated", true);
        TraceStore.put("extremez.query.hash", "extremehash123");
        TraceStore.put("extremez.risk.primaryCause", "after_filter_starvation");
        TraceStore.put("overdrive.activated", true);
        TraceStore.put("overdrive.score", 0.67d);
        TraceStore.put("overdrive.stagesApplied", 3);
        TraceStore.put("overdrive.finalCandidateCount", 8);
        TraceStore.put("overdrive.query.hash", "overdrivehash123");
        TraceStore.put("boosterMode.active", "EXTREMEZ");
        TraceStore.put("boosterMode.excludedModes", List.of("OVERDRIVE", "HYPERNOVA"));
        TraceStore.put("boosterMode.priority", "EXTREMEZ>HYPERNOVA>OVERDRIVE");
        TraceStore.put("boosterMode.exclusionReason", "single_primary_mode:EXTREMEZ>HYPERNOVA>OVERDRIVE");
        TraceStore.put("routing.executionPlan.primaryMode", "EXTREMEZ");
        TraceStore.put("routing.executionPlan.triggers", List.of("lowRecall", "highRiskTail"));
        TraceStore.put("routing.executionPlan.applied", true);
        TraceStore.put("routing.executionPlan.applied.primaryMode", "EXTREMEZ");
        TraceStore.put("routing.executionPlan.applied.triggers", List.of("lowRecall", "highRiskTail"));
        TraceStore.put("specialMode.conflict.suppressed", "OVERDRIVE,HYPERNOVA");
        TraceStore.put("retrievalOrder.lastSetBy", "PLAN_DSL");
        TraceStore.put("retrievalOrder.lastOrder", List.of("VECTOR", "KG", "WEB"));
        TraceStore.put("retrievalOrder.authority.owner", "PLAN_DSL");
        TraceStore.put("retrievalOrder.authority.suppressedOwner", "MoE");
        TraceStore.put("retrievalOrder.authority.reason", "rulebreak_speed_first");
        TraceStore.put("retrievalOrder.authority.suppressedReason", "plan_dsl_preempts_strategy_selection");
        TraceStore.put("plan.id", "hypernova.v2");
        TraceStore.put("hypernova.twpmP", 4.0d);
        TraceStore.put("hypernova.cvarPhi", 0.618d);
        TraceStore.put("hypernova.clampApplied", true);
        TraceStore.put("hypernova.dppApplied", true);
        TraceStore.put("hypernova.sourceScoreScaleMismatchCount", 0);
        TraceStore.put("hypernova.sourceScoreScaleMismatchPolicy", "fallback_to_base_norm");
        TraceStore.put("nova.hypernova.riskK.used", true);
        TraceStore.put("nova.hypernova.riskK.candidateCount", 3);
        TraceStore.put("nova.hypernova.riskK.totalK", 12);
        TraceStore.put("nova.hypernova.riskK.alloc.sum", 12);
        TraceStore.put("hypernova.riskKAlloc", Map.of("used", true, "totalK", 12, "sum", 12));
        TraceStore.put("embed.matryoshka.slice.actual", 4096);
        TraceStore.put("embed.matryoshka.slice.target", 1536);
        TraceStore.put("embed.matryoshka.slice.reductionRatio", 0.625d);
        TraceStore.put("embed.matryoshka.slice.expectedDistanceOpsRatio", 0.375d);
        TraceStore.put("embed.matryoshka.slice.expectedDistanceOpsSpeedup", 2.6667d);
        TraceStore.put("embed.matryoshka.strategy", "MRL_PREFIX");
        TraceStore.put("localLlm.startup.status", "skipped");
        TraceStore.put("localLlm.startup.reason", "disabled_or_autostart_false");
        TraceStore.put("localLlm.startup.host", "user:secret@127.0.0.1:11435");
        TraceStore.put("localLlm.startup.hostHash", "hash:local-host");
        TraceStore.put("localLlm.warmup.status", "skipped");
        TraceStore.put("localLlm.warmup.modelHash", "hash:warmup-model");
        TraceStore.put("localLlm.warmup.modelLength", 14);
        TraceStore.put("localLlm.warmup.targetDim", 1536);
        TraceStore.put("llm.modelGuard.triggered", true);
        TraceStore.put("llm.modelGuard.mode", "FAIL_FAST");
        TraceStore.put("llm.modelGuard.endpoint", "/v1/chat/completions");
        TraceStore.put("llm.modelGuard.failReason", "EXPECTED_FAILURE_MODEL_ENDPOINT_MISMATCH");
        TraceStore.put("llm.modelGuard.requestedModelHash", "hash:modelguard");
        TraceStore.put("llm.modelGuard.requestedModelLength", 19);
        TraceStore.put("retrieval.kalloc.plan", "KPlan(webK=8,vectorK=4,kgK=2,poolLimit=14)");
        TraceStore.put("cfvm.kalloc.final", "web=8,vector=4,kg=2");
        TraceStore.put("cfvm.kalloc.traceAnchor.pressure", 0.44d);
        TraceStore.put("zero100.enabled", true);
        TraceStore.put("zero100.phase", "CONSENSUS");
        TraceStore.put("zero100.progressPct", 84);
        TraceStore.put("zero100.activeLane", "RC");
        TraceStore.put("zero100.branch.weights", Map.of("BQ", 0.7d, "ER", 0.9d, "RC", 1.1d));
        TraceStore.put("zero100.queryBurst.seedHashes", List.of("seedhash123"));
        TraceStore.put("zero100.mpIntent.hash12", "intenthash123");
        TraceStore.put("zero100.sessionId", "raw-session-must-not-leak");
        TraceStore.put("promptPose.application.enabled", true);
        TraceStore.put("promptPose.application.intentSlot", "explore");
        TraceStore.put("promptPose.application.evidenceSlot", "exploratory");
        TraceStore.put("promptPose.application.failureSlot", "none");
        TraceStore.put("promptPose.application.feedbackTile", "explore:none");
        TraceStore.put("promptPose.application.decisionHash12", "posehash123");
        TraceStore.put("promptPose.application.queryBurstMax", 14);
        TraceStore.put("promptPose.application.selfAskCount", 3);
        TraceStore.put("promptPose.application.laneWeights", Map.of("BQ", 0.9d, "ER", 1.05d, "RC", 1.25d));
        TraceStore.put("promptPose.application.compressionMode", "overdrive_hint");

        String collectorOutputName = "private-learning-ops-target.jsonl";
        Path collectorOutput = tempDir.resolve(collectorOutputName);
        MockEnvironment env = new MockEnvironment()
                .withProperty("uaw.autolearn.dataset.path", dataset.toString())
                .withProperty("nova.orch.failure.jsonl.path", failures.toString())
                .withProperty("awx.context-purity.report-dir", reportDir.toString())
                .withProperty("awx.node.role", "macmini-control-plane")
                .withProperty("awx.node.execution-node", "macmini-m4-16gb")
                .withProperty("awx.learning-ops.collector.enabled", "true")
                .withProperty("awx.learning-ops.collector.output-path", collectorOutput.toString())
                .withProperty("awx.learning-ops.collector.max-items", "20")
                .withProperty("retrieval.kalloc.enabled", "true")
                .withProperty("retrieval.kalloc.max-source-share", "0.60")
                .withProperty("retrieval.kalloc.hypernova-max-source-share", "0.75");

        RagLearningOpsDashboardService service = new RagLearningOpsDashboardService(
                env,
                new ObjectMapper(),
                props,
                qualityTracker,
                provider(opsLedger),
                provider((VectorQuarantineDlqService) null),
                provider(virtualPoints),
                provider(modelHealth),
                provider((MeterRegistry) registry));

        Map<String, Object> overview = service.overview(20);
        Map<String, Object> datasetSummary = childMap(overview.get("dataset"));
        Map<String, Object> failureSummary = childMap(overview.get("failurePatterns"));
        Map<String, Object> metrics = service.metrics();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> samples = (List<Map<String, Object>>) overview.get("samples");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> causes = (List<Map<String, Object>>) overview.get("failureTopCauses");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cards = (List<Map<String, Object>>) overview.get("modelCards");
        Map<String, Object> opsLedgerSnapshot = childMap(overview.get("opsLedger"));
        Map<String, Object> collectorSnapshot = childMap(overview.get("learningOpsCollector"));
        Map<String, Object> legacyDevelopment = childMap(overview.get("legacyDevelopmentCandidates"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> legacyItems = (List<Map<String, Object>>) legacyDevelopment.get("items");
        Map<String, Object> overlays = childMap(overview.get("orchestrationOverlays"));
        Map<String, Object> anchorCompression = childMap(overlays.get("anchorCompression"));
        Map<String, Object> anchorComposer = childMap(anchorCompression.get("composer"));
        Map<String, Object> anchorProbe = childMap(anchorCompression.get("anchorProbe"));
        Map<String, Object> spreadProbe = childMap(anchorCompression.get("spreadProbe"));
        Map<String, Object> traceAnchor = childMap(anchorCompression.get("traceAnchor"));
        Map<String, Object> cihRag = childMap(overlays.get("cihRag"));
        Map<String, Object> extremeZ = childMap(overlays.get("extremeZ"));
        Map<String, Object> extremeZOverdrive = childMap(extremeZ.get("overdrive"));
        Map<String, Object> routingPlan = childMap(overlays.get("routingPlan"));
        Map<String, Object> hypernova = childMap(overlays.get("hypernova"));
        Map<String, Object> hypernovaRuntime = childMap(hypernova.get("runtime"));
        Map<String, Object> hypernovaRiskK = childMap(hypernova.get("riskK"));
        Map<String, Object> matryoshka = childMap(overlays.get("matryoshka"));
        Map<String, Object> localLlm = childMap(overlays.get("localLlm"));
        Map<String, Object> localLlmStartup = childMap(localLlm.get("startup"));
        Map<String, Object> localLlmWarmup = childMap(localLlm.get("warmup"));
        Map<String, Object> modelGuard = childMap(overlays.get("modelGuard"));
        Map<String, Object> zero100 = childMap(overlays.get("zero100"));
        Map<String, Object> promptPose = childMap(overlays.get("promptPose"));
        Map<String, Object> virtualMatrix = childMap(overlays.get("virtualMatrix"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> recentAutolearn = (List<Map<String, Object>>) opsLedgerSnapshot.get("recentAutolearn");
        Map<String, Object> datasetPipelineQueue = childMap(opsLedgerSnapshot.get("datasetPipelineQueue"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> queueItems = (List<Map<String, Object>>) datasetPipelineQueue.get("items");

        assertEquals(2, datasetSummary.get("parsedLines"));
        assertEquals(1, datasetSummary.get("quarantineCount"));
        assertEquals(0.5d, datasetSummary.get("evidenceRate"));
        assertFalse(datasetSummary.containsKey("datasetFile"));
        assertEquals(SafeRedactor.hashValue("train_rag.jsonl"), datasetSummary.get("datasetFileHash"));
        assertEquals("train_rag.jsonl".length(), datasetSummary.get("datasetFileLength"));
        assertEquals(3, failureSummary.get("eventCount"));
        assertFalse(failureSummary.containsKey("logFile"));
        assertEquals(SafeRedactor.hashValue("failure-pattern.jsonl"), failureSummary.get("logFileHash"));
        assertEquals("failure-pattern.jsonl".length(), failureSummary.get("logFileLength"));
        assertEquals(2L, metrics.get("sampleTotal"));
        assertEquals(1L, metrics.get("sampleQuarantined"));
        assertEquals(1L, metrics.get("virtualPointCount"));
        assertEquals(0.5d, metrics.get("evidenceRate"));
        assertEquals(2, samples.size());
        assertTrue(samples.get(0).containsKey("questionPreview"));
        assertTrue(samples.get(0).containsKey("answerPreview"));
        assertFalse(samples.get(0).containsKey("question"));
        assertFalse(samples.get(0).containsKey("answer"));
        assertTrue(samples.stream().anyMatch(sample -> Double.valueOf(0.91d).equals(sample.get("finalSigmoid"))));
        assertTrue(samples.stream().anyMatch(sample -> Double.valueOf(0.82d).equals(sample.get("qualityScore"))));
        assertTrue(samples.stream().anyMatch(sample -> sample.get("passages") instanceof List<?> passages && !passages.isEmpty()));
        assertEquals("timeout", causes.get(0).get("kind"));
        assertEquals(2, causes.get(0).get("count"));
        assertTrue(cards.stream().anyMatch(card -> "gemma3:27b-it-q4_K_M".equals(card.get("model"))));
        assertTrue(cards.stream().anyMatch(card -> "gemma3:27b-it-q4_K_M".equals(card.get("model"))
                && Double.valueOf(1.0d).equals(card.get("successRate"))
                && Double.valueOf(0.0d).equals(card.get("failureRate"))
                && Double.valueOf(120.0d).equals(card.get("avgLatencyMs"))
                && Double.valueOf(1.0d).equals(card.get("evidenceRate"))));
        assertEquals("AUTOLEARN_CYCLE", recentAutolearn.get(0).get("entryType"));
        assertEquals("BLOCK_RETRAIN", recentAutolearn.get(0).get("decision"));
        assertEquals("ready", datasetPipelineQueue.get("status"));
        assertEquals(false, datasetPipelineQueue.get("writesDataset"));
        assertEquals("[AWX][learning-ops][collector]", collectorSnapshot.get("checkpoint"));
        assertEquals(true, collectorSnapshot.get("enabled"));
        assertEquals("ready_read_only_curation_queue", collectorSnapshot.get("status"));
        assertEquals("read_only_curation_candidates", collectorSnapshot.get("mode"));
        assertEquals("ops-ledger.datasetPipelineQueue", collectorSnapshot.get("inputSource"));
        assertEquals(false, collectorSnapshot.get("writesDataset"));
        assertEquals(true, collectorSnapshot.get("requiresReview"));
        assertEquals("macmini-control-plane", collectorSnapshot.get("nodeRole"));
        assertEquals("macmini-m4-16gb", collectorSnapshot.get("executionNode"));
        assertFalse(collectorSnapshot.toString().contains(collectorOutputName));
        assertFalse(collectorSnapshot.containsKey("outputFile"));
        assertEquals(SafeRedactor.hashValue(collectorOutputName), collectorSnapshot.get("outputFileHash"));
        assertEquals(collectorOutputName.length(), collectorSnapshot.get("outputFileLength"));
        assertEquals(SafeRedactor.hashValue(collectorOutput.toString()),
                collectorSnapshot.get("outputPathHash"));
        assertFalse(collectorSnapshot.toString().contains(tempDir.toString()));
        assertEquals("[AWX][legacy][development-candidates]", legacyDevelopment.get("checkpoint"));
        assertEquals("ready", legacyDevelopment.get("status"));
        assertEquals(2, legacyDevelopment.get("itemCount"));
        assertEquals(2L, legacyDevelopment.get("activeCount"));
        assertEquals(Map.of("autolearn_runtime", 1L, "prompt_boundary", 1L), legacyDevelopment.get("byFeature"));
        assertEquals(Map.of("keep", 2L), legacyDevelopment.get("byAction"));
        assertFalse(legacyDevelopment.containsKey("reportFile"));
        assertEquals(SafeRedactor.hashValue("legacy-development-candidates.tsv"), legacyDevelopment.get("reportFileHash"));
        assertEquals("legacy-development-candidates.tsv".length(), legacyDevelopment.get("reportFileLength"));
        assertFalse(legacyDevelopment.toString().contains(reportDir.toString()));
        assertFalse(legacyItems.isEmpty());
        assertFalse(legacyItems.get(0).containsKey("sourcePath"));
        assertTrue(legacyItems.get(0).get("sourcePathHash") instanceof String);
        assertTrue(legacyItems.get(0).get("sourcePathLength") instanceof Number);
        assertFalse(legacyDevelopment.toString().contains("main\\java\\com\\example\\lms\\web\\AssignmentController.java"));
        assertFalse(overview.toString().contains("legacy_corpus.jsonl"));
        assertFalse(overview.toString().contains("C:/private/data/legacy_corpus.jsonl"));
        assertEquals("[AWX][rag][overlays]", overlays.get("checkpoint"));
        assertTrue(((Number) overlays.get("activeCount")).intValue() >= 4);
        assertEquals(true, anchorCompression.get("active"));
        assertEquals("ablation-spread-v2", anchorComposer.get("version"));
        assertEquals(0.50d, anchorProbe.get("reductionRatio"));
        assertEquals(0.1667d, spreadProbe.get("reductionRatio"));
        assertEquals("traceanchor123", traceAnchor.get("topHash"));
        assertEquals(true, cihRag.get("active"));
        assertEquals(2, cihRag.get("activeFileCount"));
        assertEquals(0, cihRag.get("skippedFileCount"));
        assertEquals(3, cihRag.get("mlaBreadcrumbCount"));
        assertEquals(true, cihRag.get("breadcrumb.queryRedacted"));
        assertEquals("pass_through", cihRag.get("implementationStage"));
        assertEquals(true, extremeZ.get("active"));
        assertEquals("after_filter_starvation", extremeZ.get("risk.primaryCause"));
        assertEquals(3, extremeZOverdrive.get("stagesApplied"));
        assertEquals(8, extremeZOverdrive.get("finalCandidateCount"));
        assertEquals(true, routingPlan.get("active"));
        assertEquals("EXTREMEZ", routingPlan.get("booster.active"));
        assertEquals(List.of("OVERDRIVE", "HYPERNOVA"), routingPlan.get("booster.excludedModes"));
        assertEquals("EXTREMEZ_HYPERNOVA_OVERDRIVE", routingPlan.get("booster.priority"));
        assertEquals("single_primary_mode:EXTREMEZ_HYPERNOVA_OVERDRIVE",
                routingPlan.get("booster.exclusionReason"));
        assertEquals("EXTREMEZ", routingPlan.get("execution.primaryMode"));
        assertEquals(List.of("lowRecall", "highRiskTail"), routingPlan.get("execution.triggers"));
        assertEquals(true, routingPlan.get("execution.applied"));
        assertEquals("EXTREMEZ", routingPlan.get("execution.applied.primaryMode"));
        assertEquals("OVERDRIVE_HYPERNOVA", routingPlan.get("specialMode.conflict.suppressed"));
        assertEquals("PLAN_DSL", routingPlan.get("retrievalOrder.lastSetBy"));
        assertEquals(List.of("VECTOR", "KG", "WEB"), routingPlan.get("retrievalOrder.lastOrder"));
        assertEquals("PLAN_DSL", routingPlan.get("retrievalOrder.authority.owner"));
        assertEquals("MoE", routingPlan.get("retrievalOrder.authority.suppressedOwner"));
        assertEquals("rulebreak_speed_first", routingPlan.get("retrievalOrder.authority.reason"));
        assertEquals("plan_dsl_preempts_strategy_selection",
                routingPlan.get("retrievalOrder.authority.suppressedReason"));
        assertEquals(true, hypernova.get("active"));
        assertEquals(true, hypernova.get("kallocEnabled"));
        assertEquals(4.0d, hypernovaRuntime.get("twpmP"));
        assertEquals(0.618d, hypernovaRuntime.get("cvarPhi"));
        assertEquals(true, hypernovaRuntime.get("clampApplied"));
        assertEquals(true, hypernovaRuntime.get("dppApplied"));
        assertEquals("fallback_to_base_norm", hypernovaRuntime.get("sourceScoreScaleMismatchPolicy"));
        assertEquals(true, hypernovaRiskK.get("used"));
        assertEquals(12, hypernovaRiskK.get("totalK"));
        assertEquals(12, hypernovaRiskK.get("alloc.sum"));
        Map<String, Object> hypernovaRiskKAlloc = childMap(hypernovaRiskK.get("alloc"));
        assertEquals(true, hypernovaRiskKAlloc.get("used"));
        assertEquals(12, hypernovaRiskKAlloc.get("totalK"));
        assertEquals(12, hypernovaRiskKAlloc.get("sum"));
        assertEquals(true, matryoshka.get("active"));
        assertEquals(4096, matryoshka.get("slice.actual"));
        assertEquals(1536, matryoshka.get("slice.target"));
        assertEquals(0.625d, matryoshka.get("slice.reductionRatio"));
        assertEquals(0.375d, matryoshka.get("slice.expectedDistanceOpsRatio"));
        assertEquals(2.6667d, matryoshka.get("slice.expectedDistanceOpsSpeedup"));
        assertEquals("MRL_PREFIX", matryoshka.get("strategy"));
        assertEquals(true, localLlm.get("active"));
        assertEquals("skipped", localLlmStartup.get("status"));
        assertEquals("disabled_or_autostart_false", localLlmStartup.get("reason"));
        assertEquals("hash:local-host", localLlmStartup.get("hostHash"));
        assertFalse(localLlmStartup.containsKey("host"));
        assertEquals("skipped", localLlmWarmup.get("status"));
        assertEquals("hash:warmup-model", localLlmWarmup.get("modelHash"));
        assertEquals(14, localLlmWarmup.get("modelLength"));
        assertEquals(1536, localLlmWarmup.get("targetDim"));
        assertEquals(true, modelGuard.get("active"));
        assertEquals(true, modelGuard.get("triggered"));
        assertEquals("FAIL_FAST", modelGuard.get("mode"));
        assertEquals("/v1/chat/completions", modelGuard.get("endpoint"));
        assertEquals("EXPECTED_FAILURE_MODEL_ENDPOINT_MISMATCH", modelGuard.get("failReason"));
        assertEquals("hash:modelguard", modelGuard.get("requestedModelHash"));
        assertEquals(19, modelGuard.get("requestedModelLength"));
        assertEquals(true, zero100.get("active"));
        assertEquals("CONSENSUS", zero100.get("phase"));
        assertFalse(zero100.containsKey("sessionId"));
        assertEquals(true, promptPose.get("active"));
        assertEquals("explore", promptPose.get("application.intentSlot"));
        assertEquals("posehash123", promptPose.get("application.decisionHash12"));
        assertEquals(true, virtualMatrix.get("active"));
        assertFalse(overview.toString().contains("raw-session-must-not-leak"));
        assertTrue(queueItems.stream().anyMatch(item -> "demote_heavy_work".equals(item.get("action"))
                && "gpu_hardware_pressure".equals(item.get("reason"))));
        assertTrue(queueItems.toString().contains("gpu_memory_pressure"));
        assertFalse(queueItems.toString().contains(dataset.toString()));
        verify(opsLedger, atLeastOnce()).recent("AUTOLEARN_CYCLE", null, 10);

        String prometheus = service.prometheus();
        assertTrue(prometheus.contains("rag_learning_train_samples_total 2.0"));
        assertTrue(prometheus.contains("rag_learning_train_samples_quarantined 1.0"));
        assertTrue(prometheus.contains("rag_learning_failure_patterns_total 3.0"));
        assertTrue(prometheus.contains("rag_learning_model_success_rate"));
        assertFalse(prometheus.contains(dataset.toString()));
        assertNotNull(registry.find("rag.learning.samples.total").gauge());
        assertNotNull(registry.find("rag.learning.evidence_rate").gauge());
    }

    @Test
    @SuppressWarnings("unchecked")
    void curationQueueReasonDoesNotExposeRawFailureClassSecrets() throws Exception {
        Path dataset = tempDir.resolve("empty-train-rag.jsonl");
        Files.writeString(dataset, "", StandardCharsets.UTF_8);
        String secret = "sk-" + "test-learning-ops-secret-1234567890";
        UawAutolearnProperties props = new UawAutolearnProperties();
        props.getDataset().setPath(dataset.toString());
        RagOpsLedgerService opsLedger = mock(RagOpsLedgerService.class);
        when(opsLedger.isEnabled()).thenReturn(true);
        when(opsLedger.summary(24)).thenReturn(Map.of("enabled", true));
        when(opsLedger.recent(null, null, 10)).thenReturn(List.of());
        when(opsLedger.recent("AUTOLEARN_CYCLE", null, 10)).thenReturn(List.of(Map.of(
                "entryType", "AUTOLEARN_CYCLE",
                "decision", "OBSERVE",
                "failureClass", "provider_failed api_key=" + secret,
                "hotspot", "misc",
                "createdAt", "2026-05-30T01:10:00")));
        RagLearningOpsDashboardService service = new RagLearningOpsDashboardService(
                new MockEnvironment().withProperty("uaw.autolearn.dataset.path", dataset.toString()),
                new ObjectMapper(),
                props,
                null,
                provider(opsLedger),
                provider((VectorQuarantineDlqService) null),
                provider((VirtualPointService) null),
                provider((ModelRuntimeHealthTracker) null),
                provider((MeterRegistry) null));

        Map<String, Object> overview = service.overview(20);
        Map<String, Object> opsLedgerSnapshot = childMap(overview.get("opsLedger"));
        Map<String, Object> datasetPipelineQueue = childMap(opsLedgerSnapshot.get("datasetPipelineQueue"));
        List<Map<String, Object>> items = (List<Map<String, Object>>) datasetPipelineQueue.get("items");
        String rendered = String.valueOf(items);

        assertFalse(items.isEmpty());
        assertFalse(rendered.contains(secret));
        assertFalse(String.valueOf(items.get(0).get("failureClass")).contains(secret));
        assertFalse(String.valueOf(items.get(0).get("reason")).contains(secret));

        String source = Files.readString(Path.of("main/java/com/example/lms/learning/ops/RagLearningOpsDashboardService.java"),
                StandardCharsets.UTF_8);
        assertFalse(source.contains("out.put(\"reason\", safeLabel(SafeRedactor.safeMessage(asString(action.get(\"reason\")), 120), 120));"));
        assertTrue(source.contains("out.put(\"reason\", safeLabel(SafeRedactor.traceLabelOrFallback(asString(action.get(\"reason\")), \"unknown\"), 120));"));
    }

    @Test
    void overviewRedactsSensitiveTraceMapKeyLabels() throws Exception {
        Path dataset = tempDir.resolve("empty-train-rag-map-keys.jsonl");
        Files.writeString(dataset, "", StandardCharsets.UTF_8);
        UawAutolearnProperties props = new UawAutolearnProperties();
        props.getDataset().setPath(dataset.toString());
        String rawOwnerToken = "owner-token-must-not-leak";
        String rawApiKey = "api-key-must-not-leak";
        TraceStore.put("blackbox.risk.traceAnchor", Map.of(
                "ownerToken", rawOwnerToken,
                "apiKey", rawApiKey,
                "safeCount", 2));
        RagLearningOpsDashboardService service = new RagLearningOpsDashboardService(
                new MockEnvironment().withProperty("uaw.autolearn.dataset.path", dataset.toString()),
                new ObjectMapper(),
                props,
                null,
                provider((RagOpsLedgerService) null),
                provider((VectorQuarantineDlqService) null),
                provider((VirtualPointService) null),
                provider((ModelRuntimeHealthTracker) null),
                provider((MeterRegistry) null));

        Map<String, Object> overview = service.overview(1);
        Map<String, Object> overlays = childMap(overview.get("orchestrationOverlays"));
        Map<String, Object> anchorCompression = childMap(overlays.get("anchorCompression"));
        Map<String, Object> blackboxTraceAnchor = childMap(anchorCompression.get("blackboxTraceAnchor"));
        String rendered = String.valueOf(overview);

        assertEquals(2, blackboxTraceAnchor.get("safeCount"));
        assertTrue(blackboxTraceAnchor.keySet().stream().anyMatch(key -> key.startsWith("hash:")), rendered);
        assertFalse(rendered.contains("ownerToken"), rendered);
        assertFalse(rendered.contains("apiKey"), rendered);
        assertFalse(rendered.contains(rawOwnerToken), rendered);
        assertFalse(rendered.contains(rawApiKey), rendered);
    }

    @Test
    void dashboardNumericFallbackParserOnlyCatchesNumberFormatException() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/learning/ops/RagLearningOpsDashboardService.java"),
                StandardCharsets.UTF_8);

        assertParserCatchNarrowed(source, "private static Double tryNumber(Object value)");
    }

    @Test
    void dashboardInvalidNumberTraceUsesStableLabel() throws Exception {
        Method tryNumber = RagLearningOpsDashboardService.class.getDeclaredMethod("tryNumber", Object.class);
        tryNumber.setAccessible(true);

        Object parsed = tryNumber.invoke(null, "raw private dashboard number");

        assertNull(parsed);
        assertEquals(true, TraceStore.get("learning.ops.dashboard.suppressed.number_parse"));
        assertEquals("invalid_number", TraceStore.get("learning.ops.dashboard.suppressed.number_parse.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("raw private dashboard number"));

        TraceStore.clear();
        Object nonFinite = tryNumber.invoke(null, "NaN");

        assertNull(nonFinite);
        assertEquals(true, TraceStore.get("learning.ops.dashboard.suppressed.number_parse"));
        assertEquals("invalid_number", TraceStore.get("learning.ops.dashboard.suppressed.number_parse.errorType"));
    }

    @Test
    void dashboardFailSoftFallbacksLeaveRedactedBreadcrumbs() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/learning/ops/RagLearningOpsDashboardService.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("traceSkipped(\"quality_snapshot\", e);"));
        assertTrue(source.contains("traceSkipped(\"ops_ledger_snapshot\", e);"));
        assertTrue(source.contains("traceSkipped(\"source_manifest_snapshot\", e);"));
        assertTrue(source.contains("traceSkipped(\"jsonl_tail\", e);"));
        assertTrue(source.contains("traceSkipped(\"json_parse\", e);"));
        assertTrue(source.contains("traceSkipped(\"number_parse\", ignore);"));
        assertTrue(source.contains("TraceStore.put(\"learning.ops.dashboard.suppressed.\" + safeStage, true);"));
    }

    private static void assertParserCatchNarrowed(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "missing parser signature: " + signature);
        int parse = source.indexOf("parse", start);
        assertTrue(parse >= start, "parser must call a numeric parse method: " + signature);
        int end = source.indexOf("\n    }", parse);
        assertTrue(end > parse, "parser method end should be found: " + signature);
        String method = source.substring(start, end);
        assertTrue(method.contains("catch (NumberFormatException"),
                "numeric fallback parser should only catch NumberFormatException: " + signature);
        assertFalse(method.contains("catch (Exception"),
                "numeric fallback parser must not swallow all Exception: " + signature);
        assertFalse(method.contains("catch (Throwable"),
                "numeric fallback parser must not swallow Throwable: " + signature);
    }

    private static Map<String, Object> childMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            entry -> String.valueOf(entry.getKey()),
                            Map.Entry::getValue,
                            (a, b) -> a,
                            java.util.LinkedHashMap::new));
        }
        return Map.of();
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return getObject();
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }

            @Override
            public T getObject() {
                if (value == null) {
                    throw new NoSuchBeanDefinitionException(Object.class);
                }
                return value;
            }

            @Override
            public Iterator<T> iterator() {
                return stream().iterator();
            }

            @Override
            public Stream<T> stream() {
                return value == null ? Stream.empty() : Stream.of(value);
            }

            @Override
            public Stream<T> orderedStream() {
                return stream();
            }
        };
    }
}
