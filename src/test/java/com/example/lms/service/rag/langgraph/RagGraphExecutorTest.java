package com.example.lms.service.rag.langgraph;

import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.config.SearchExecutorConfig;
import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.handler.EvidenceRepairHandler;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator.Doc;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator.QueryRequest;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator.QueryResponse;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator.QueryTrace;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagGraphExecutorTest {

    private final List<ExecutorService> ownedExecutors = new ArrayList<>();

    @AfterEach
    void tearDownOwnedGraphExecutors() throws Exception {
        TimeBudgetContext.clear();
        TraceStore.clear();
        MDC.clear();
        for (ExecutorService executor : ownedExecutors) {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
        ownedExecutors.clear();
    }

    @Test
    void langGraphFailSoftCatchesLeaveStageBreadcrumbs() throws Exception {
        String executor = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/langgraph/RagGraphExecutor.java"));
        String policy = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/langgraph/RagGraphControlPolicy.java"));
        String facade = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/langgraph/RagOrchestratorFacade.java"));

        for (String stage : List.of(
                "stage=timeout.trace",
                "stage=invokeFallback.trace",
                "langgraph.invoke.suppressed.npe",
                "langgraph.invoke.suppressed.npe.errorType",
                "langgraph.invoke.suppressed.error",
                "langgraph.invoke.suppressed.error.errorType",
                "langgraph.thresholdBreak.suppressed.numberParse")) {
            assertTrue(executor.contains(stage), "RagGraphExecutor needs stage breadcrumb: " + stage);
        }
        assertTrue(policy.contains("langgraph.control.suppressed.intValue"));
        assertTrue(facade.contains("langgraph.facade.suppressed.asDouble"));
    }

    @Test
    void langGraphNumericFallbacksUseStableInvalidNumberLabels() {
        TraceStore.clear();

        assertEquals(0.0d,
                (Double) ReflectionTestUtils.invokeMethod(RagGraphExecutor.class,
                        "firstNumber", Map.of("score", "not-a-number"), new String[]{"score"}),
                1.0e-9d);
        assertEquals(0.0d,
                (Double) ReflectionTestUtils.invokeMethod(RagGraphExecutor.class,
                        "firstNumber", Map.of("score", Double.POSITIVE_INFINITY), new String[]{"score"}),
                1.0e-9d);
        assertEquals(0,
                (Integer) ReflectionTestUtils.invokeMethod(RagGraphControlPolicy.class,
                        "intValue", "not-an-int"));
        assertEquals(0.75d,
                (Double) ReflectionTestUtils.invokeMethod(RagOrchestratorFacade.class,
                        "asDouble", "not-a-double", 0.75d),
                1.0e-9d);

        assertEquals("invalid_number", TraceStore.get("langgraph.thresholdBreak.suppressed.numberParse.errorType"));
        assertEquals("invalid_number", TraceStore.get("langgraph.control.suppressed.intValue.errorType"));
        assertEquals("invalid_number", TraceStore.get("langgraph.facade.suppressed.asDouble.errorType"));
    }

    @Test
    void langGraphExecutesPrepareRetrieveQualityGateFinalize() {
        RagGraphProperties properties = new RagGraphProperties();
        properties.setTimeoutMs(0);
        FakeOrchestrator orchestrator = new FakeOrchestrator();
        RagGraphExecutor executor = new RagGraphExecutor(orchestrator, new FixedProvider<>(null), properties);
        QueryRequest request = new QueryRequest();
        request.query = "langgraph safety";
        request.planId = "brave.v1";
        request.threadId = "chat-42";

        QueryResponse response = executor.execute(request);

        assertEquals(1, orchestrator.traceCalls);
        assertEquals("brave.v1", response.planApplied);
        assertEquals(1, response.results.size());
        assertEquals("ok", response.debug.get("langgraph.node.prepare"));
        assertEquals("ok", response.debug.get("langgraph.node.retrieve"));
        assertEquals(1, response.debug.get("langgraph.node.quality_gate.resultCount"));
        assertEquals("ok", response.debug.get("langgraph.node.decide_control"));
        assertEquals("ok", response.debug.get("langgraph.node.apply_policy"));
        assertEquals("RELAXED", response.debug.get("langgraph.control.mode"));
        assertEquals("ok", response.debug.get("langgraph.node.finalize"));
        assertEquals("memory", response.debug.get("langgraph.checkpoint.backend"));
        assertEquals(true, response.debug.get("langgraph.checkpoint.enabled"));
        assertEquals(RagGraphExecutor.hashThreadId("chat-42"), response.debug.get("langgraph.threadIdHash"));
        assertTrue(response.debug.containsKey("langgraph.invoke.fallbackTriggered"));
        assertEquals("compiled_graph_invoke", response.debug.get("langgraph.invoke.source"));
        assertEquals("relaxed.signal", response.debug.get("langgraph.control.policyRuleId"));
        assertTrue(response.debug.containsKey("langgraph.control.transitionScore"));
        if (Boolean.TRUE.equals(response.debug.get("langgraph.invoke.fallbackTriggered"))) {
            assertEquals(false, response.debug.get("langgraph.invoke.primaryCandidate"));
            assertTrue(response.debug.get("langgraph.control.promotionBlockers").toString().contains("invoke_fallback"));
        }
    }

    @Test
    void postgresCheckpointWithoutPasswordFallsBackToMemoryAndReportsReason() {
        RagGraphProperties properties = new RagGraphProperties();
        properties.setTimeoutMs(0);
        properties.setCheckpoint("postgres");
        properties.getPostgres().setCreateTables(true);
        FakeOrchestrator orchestrator = new FakeOrchestrator();
        RagGraphExecutor executor = new RagGraphExecutor(orchestrator, new FixedProvider<>(null), properties);
        QueryRequest request = new QueryRequest();
        request.query = "postgres checkpoint";
        request.threadId = "chat-777";

        QueryResponse response = executor.execute(request);

        assertEquals(1, orchestrator.traceCalls);
        assertEquals("postgres", response.debug.get("langgraph.checkpoint.backend"));
        assertEquals(false, response.debug.get("langgraph.checkpoint.enabled"));
        assertEquals(false, response.debug.get("langgraph.checkpoint.hasPassword"));
        assertEquals(true, response.debug.get("langgraph.checkpoint.createTables"));
        assertEquals("missing password", response.debug.get("langgraph.checkpoint.disabledReason"));
        assertEquals(RagGraphExecutor.hashThreadId("chat-777"), response.debug.get("langgraph.threadIdHash"));
    }

    @Test
    void langGraphFinalizesEmptyResultsWhenRepairUnavailable() {
        TraceStore.clear();
        try {
            RagGraphProperties properties = new RagGraphProperties();
            properties.setTimeoutMs(0);
            EmptyOrchestrator orchestrator = new EmptyOrchestrator();
            RagGraphExecutor executor = new RagGraphExecutor(orchestrator, new FixedProvider<>(null), properties);
            QueryRequest request = new QueryRequest();
            request.query = "empty langgraph";
            request.threadId = "chat-empty";

            QueryResponse response = executor.execute(request);

            assertTrue(String.valueOf(response.debug.get("langgraph.emptyReason")).startsWith("graph_"));
            assertEquals("repair_unavailable", response.debug.get("langgraph.failureReason"));
            assertEquals("unavailable", response.debug.get("langgraph.node.repair"));
            assertEquals("ok", response.debug.get("langgraph.node.fail_soft_finalize"));
            assertEquals("memory", response.debug.get("langgraph.checkpoint.backend"));
            assertEquals(true, response.debug.get("langgraph.invoke.fallbackTriggered"));
            assertTrue(String.valueOf(response.debug.get("langgraph.invoke.trigger")).startsWith("graph_"));
            assertTrue(String.valueOf(response.debug.get("langgraph.invoke.failureClass")).startsWith("langgraph-"));
            assertEquals(false, response.debug.get("langgraph.invoke.primaryCandidate"));
            assertEquals(response.debug.get("langgraph.invoke.trigger"), TraceStore.get("langgraph.invoke.trigger"));
            assertEquals(response.debug.get("langgraph.invoke.failureClass"), TraceStore.get("langgraph.invoke.failureClass"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> trace = (List<Map<String, Object>>) response.debug.get("langgraph.transitionTrace");
            assertTrue(trace.stream().anyMatch(row -> "invoke_fallback".equals(row.get("node"))));
            assertTrue(response.debug.get("langgraph.control.promotionBlockers").toString().contains("invoke_fallback"));
            assertFalse(String.valueOf(response.debug).contains("empty langgraph"));
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void strictModeRunsStrictVerifyAndFailSoftWhenEvidenceIsWeak() {
        RagGraphProperties properties = new RagGraphProperties();
        properties.setTimeoutMs(0);
        RagGraphExecutor executor = new RagGraphExecutor(new FakeOrchestrator(), new FixedProvider<>(null), properties);
        QueryRequest request = new QueryRequest();
        request.query = "strict weak evidence";
        request.jamminiMode = "STRICT";

        QueryResponse response = executor.execute(request);

        assertEquals("STRICT", response.debug.get("langgraph.control.mode"));
        assertEquals("EVIDENCE_SHARPEN", response.debug.get("langgraph.control.retrievalPosture"));
        assertEquals("ok", response.debug.get("langgraph.node.strict_verify"));
        assertEquals(false, response.debug.get("langgraph.strictVerify.passed"));
        assertEquals("ok", response.debug.get("langgraph.node.fail_soft_finalize"));
        assertEquals("strict_verify_failed", response.debug.get("langgraph.failSoft.reasonCode"));
    }

    @Test
    void relaxedModeWidensRetrievalBeforeCallingOrchestrator() {
        RagGraphProperties properties = new RagGraphProperties();
        properties.setTimeoutMs(0);
        CapturingOrchestrator orchestrator = new CapturingOrchestrator();
        RagGraphExecutor executor = new RagGraphExecutor(orchestrator, new FixedProvider<>(null), properties);
        QueryRequest request = new QueryRequest();
        request.query = "relaxed retrieval";
        request.planId = "brave.v1";
        request.topK = 2;

        QueryResponse response = executor.execute(request);

        assertEquals("RELAXED", response.debug.get("langgraph.control.mode"));
        assertTrue(orchestrator.captured.topK >= 10);
        assertTrue(orchestrator.captured.enableSelfAsk);
        assertEquals(Integer.valueOf(orchestrator.captured.topK), orchestrator.captured.webTopK);
    }

    @Test
    void recoveryRepairCanAddEvidenceAndFinalize() {
        RagGraphProperties properties = new RagGraphProperties();
        properties.setTimeoutMs(0);
        RagGraphExecutor executor = new RagGraphExecutor(
                new EmptyOrchestrator(),
                new FixedProvider<>(new FixedRepairHandler(List.of(Content.from("repair evidence")))),
                properties);
        QueryRequest request = new QueryRequest();
        request.query = "needs repair";

        QueryResponse response = executor.execute(request);

        assertEquals(1, response.results.size());
        assertEquals("ok", response.debug.get("langgraph.node.repair"));
        assertEquals(1, response.debug.get("langgraph.repair.added"));
        assertEquals("FINALIZE", response.debug.get("langgraph.control.failureAction"));
        assertFalse(response.debug.containsKey("langgraph.failureReason"));
    }

    @Test
    void qualityGateUsesScorecardKgDropAsRepairReasonAndPreservesResponse() {
        RagGraphProperties properties = new RagGraphProperties();
        properties.setTimeoutMs(0);
        RagGraphExecutor executor = new RagGraphExecutor(new ScorecardDropOrchestrator(), new FixedProvider<>(null), properties);
        QueryRequest request = new QueryRequest();
        request.query = "kg final drop";
        request.threadId = "chat-kg-drop";

        QueryResponse response = executor.execute(request);

        assertEquals(1, response.results.size());
        assertEquals("kg_final_drop", response.debug.get("langgraph.node.quality_gate.reason"));
        assertEquals(true, response.debug.get("langgraph.node.quality_gate.degraded"));
        assertEquals("unavailable", response.debug.get("langgraph.node.repair"));
        assertEquals("repair_unavailable", response.debug.get("langgraph.failureReason"));
    }

    @Test
    void qualityGateFallsBackToThresholdBreaksWhenScorecardIsAbsent() {
        RagGraphProperties properties = new RagGraphProperties();
        properties.setTimeoutMs(0);
        RagGraphExecutor executor = new RagGraphExecutor(new ThresholdBreakOrchestrator(), new FixedProvider<>(null), properties);
        QueryRequest request = new QueryRequest();
        request.query = "stage drop";

        QueryResponse response = executor.execute(request);

        assertEquals(1, response.results.size());
        assertEquals("stage_drop_high", response.debug.get("langgraph.node.quality_gate.reason"));
        assertEquals(true, response.debug.get("langgraph.node.quality_gate.degraded"));
        assertEquals("repair_unavailable", response.debug.get("langgraph.failureReason"));
    }

    @Test
    void retrieveCancellationIsTerminalAndNeverEntersFallbackOrRepair() {
        RagGraphProperties properties = new RagGraphProperties();
        properties.setTimeoutMs(0);
        CancellingOrchestrator orchestrator = new CancellingOrchestrator();
        CountingRepairHandler repairHandler = new CountingRepairHandler();
        RagGraphExecutor executor = new RagGraphExecutor(
                orchestrator,
                new FixedProvider<>(repairHandler),
                properties);
        QueryRequest request = new QueryRequest();
        request.query = "private cancellation query";

        CancellationException failure = assertThrows(CancellationException.class, () -> executor.execute(request));

        assertEquals(1, orchestrator.traceCalls,
                "a terminal cancellation must not re-enter retrieval through sequential fallback");
        assertEquals(0, repairHandler.retrieveCalls,
                "a terminal cancellation must not be converted into a repair candidate");
        assertTrue(failure.getMessage().contains("cancelled"));
    }

    @Test
    void retrieveInterruptionIsTerminalRestoresInterruptAndNeverEntersFallbackOrRepair() {
        RagGraphProperties properties = new RagGraphProperties();
        properties.setTimeoutMs(0);
        InterruptingOrchestrator orchestrator = new InterruptingOrchestrator();
        CountingRepairHandler repairHandler = new CountingRepairHandler();
        RagGraphExecutor executor = new RagGraphExecutor(
                orchestrator,
                new FixedProvider<>(repairHandler),
                properties);
        QueryRequest request = new QueryRequest();
        request.query = "private interruption query";

        try {
            CancellationException failure = assertThrows(CancellationException.class, () -> executor.execute(request));

            assertTrue(Thread.currentThread().isInterrupted(),
                    "InterruptedException must restore the caller interrupt flag before becoming terminal cancellation");
            assertTrue(failure.getCause() instanceof InterruptedException);
            assertEquals(1, orchestrator.traceCalls,
                    "an interruption must not re-enter retrieval through sequential fallback");
            assertEquals(0, repairHandler.retrieveCalls,
                    "an interruption must not reach repair");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void qualityGateReadsKgAxisV2SignalsWhenScorecardIsAbsent() {
        RagGraphProperties properties = new RagGraphProperties();
        properties.setTimeoutMs(0);
        RagGraphExecutor executor = new RagGraphExecutor(new KgAxisDropOrchestrator(), new FixedProvider<>(null), properties);
        QueryRequest request = new QueryRequest();
        request.query = "kg axis drop";

        QueryResponse response = executor.execute(request);

        assertEquals(1, response.results.size());
        assertEquals("kg_final_drop", response.debug.get("langgraph.node.quality_gate.reason"));
        assertEquals(true, response.debug.get("langgraph.node.quality_gate.degraded"));
        assertEquals("repair_unavailable", response.debug.get("langgraph.failureReason"));
    }

    @Test
    void qualityGateUsesKgNeo4jDegradedFromScorecardOrKgAxis() {
        RagGraphProperties properties = new RagGraphProperties();
        properties.setTimeoutMs(0);
        RagGraphExecutor executor = new RagGraphExecutor(new KgNeo4jDegradedOrchestrator(), new FixedProvider<>(null), properties);
        QueryRequest request = new QueryRequest();
        request.query = "kg neo4j degraded";

        QueryResponse response = executor.execute(request);

        assertEquals(1, response.results.size());
        assertEquals("kg_neo4j_degraded", response.debug.get("langgraph.node.quality_gate.reason"));
        assertEquals(true, response.debug.get("langgraph.node.quality_gate.degraded"));
        assertEquals("repair_unavailable", response.debug.get("langgraph.failureReason"));
        assertFalse(String.valueOf(response.debug).contains("raw prompt must not escape"));
        assertFalse(String.valueOf(response.debug).contains("sensitive query"));
    }

    @Test
    void qualityGateDoesNotTreatOfflineTextureMissingAsRepairReason() {
        RagGraphProperties properties = new RagGraphProperties();
        properties.setTimeoutMs(0);
        RagGraphExecutor executor = new RagGraphExecutor(
                new OfflineTextureBreakOrchestrator("offline_texture_missing"),
                new FixedProvider<>(null),
                properties);
        QueryRequest request = new QueryRequest();
        request.query = "latest KG status";

        QueryResponse response = executor.execute(request);

        assertEquals("", response.debug.get("langgraph.node.quality_gate.reason"));
        assertEquals(false, response.debug.get("langgraph.node.quality_gate.degraded"));
        assertFalse(response.debug.containsKey("langgraph.failureReason"));
    }

    @Test
    void qualityGateUsesOfflineTextureStaleOnlyForFreshnessQuery() {
        RagGraphProperties properties = new RagGraphProperties();
        properties.setTimeoutMs(0);
        RagGraphExecutor executor = new RagGraphExecutor(
                new OfflineTextureBreakOrchestrator("offline_texture_stale"),
                new FixedProvider<>(null),
                properties);
        QueryRequest freshRequest = new QueryRequest();
        freshRequest.query = "latest KG status";

        QueryResponse fresh = executor.execute(freshRequest);

        assertEquals("offline_texture_stale", fresh.debug.get("langgraph.node.quality_gate.reason"));
        assertEquals(true, fresh.debug.get("langgraph.node.quality_gate.degraded"));
        assertFalse(fresh.debug.containsKey("langgraph.failureReason"));

        QueryRequest stableRequest = new QueryRequest();
        stableRequest.query = "KG status";
        QueryResponse stable = executor.execute(stableRequest);

        assertEquals("", stable.debug.get("langgraph.node.quality_gate.reason"));
        assertEquals(false, stable.debug.get("langgraph.node.quality_gate.degraded"));
        assertFalse(stable.debug.containsKey("langgraph.failureReason"));
    }

    @Test
    void selectedTraceStoreIncludesKgAxisWithoutRawPromptOrSecret() {
        TraceStore.clear();
        try {
            TraceStore.put("rag.eval.kgAxis", Map.of(
                    "schemaVersion", 2,
                    "kgPoolCount", 1,
                    "kgFinalCount", 0,
                    "kgFinalRetention", 0.0d,
                    "graphOpportunity", true,
                    "signals", List.of("kg_final_drop"),
                    "rawPrompt", "raw prompt must not escape"));
            TraceStore.put("rag.eval.scorecard", Map.of(
                    "reasonCode", "kg_final_drop",
                    "thresholdLabels", List.of("kg_final_drop"),
                    "rawSecret", "provider-secret-must-not-escape"));
            TraceStore.put("rag.eval.thresholdBreaks", List.of(Map.of(
                    "label", "kg_final_drop",
                    "rawQuery", "secret query")));
            TraceStore.put("langgraph.invoke.fallbackTriggered", true);
            TraceStore.put("langgraph.invoke.trigger", "graph_invoke_npe");
            TraceStore.put("langgraph.invoke.failureClass", "langgraph-invoke-null");
            TraceStore.put("retrieval.kg.brainState.status", "success");
            TraceStore.put("retrieval.kg.brainState.matchedEntityCount", 2);
            TraceStore.put("retrieval.kg.brainState.inferredRelationCount", 1);
            TraceStore.put("retrieval.kg.brainState.fallbackUsed", true);
            TraceStore.put("retrieval.kg.brainState.queryAnchorMap.applied", true);
            TraceStore.put("retrieval.kg.brainState.queryAnchorMap.seedCount", 1);
            TraceStore.put("retrieval.kg.brainState.queryAnchorMap.seedHashes", List.of("123456789abc"));
            TraceStore.put("retrieval.kg.brainState.queryHash12", "abcdef123456");
            TraceStore.put("retrieval.kg.brainState.events", List.of(Map.of(
                    "relation", "Alpha raw relation should not escape",
                    "query", "raw query should not escape")));

            @SuppressWarnings("unchecked")
            Map<String, Object> trace = (Map<String, Object>) ReflectionTestUtils.invokeMethod(
                    RagGraphExecutor.class,
                    "selectedTraceStore");

            assertTrue(trace.containsKey("rag.eval.kgAxis"));
            assertTrue(trace.containsKey("rag.eval.scorecard"));
            assertTrue(trace.containsKey("rag.eval.thresholdBreaks"));
            assertEquals(true, trace.get("langgraph.invoke.fallbackTriggered"));
            assertEquals("graph_invoke_npe", trace.get("langgraph.invoke.trigger"));
            assertEquals("langgraph-invoke-null", trace.get("langgraph.invoke.failureClass"));
            assertEquals("success", trace.get("retrieval.kg.brainState.status"));
            assertEquals(2, trace.get("retrieval.kg.brainState.matchedEntityCount"));
            assertEquals(true, trace.get("retrieval.kg.brainState.queryAnchorMap.applied"));
            assertEquals(List.of("123456789abc"), trace.get("retrieval.kg.brainState.queryAnchorMap.seedHashes"));
            assertEquals("abcdef123456", trace.get("retrieval.kg.brainState.queryHash12"));
            assertEquals(1, trace.get("retrieval.kg.brainState.events.count"));
            assertFalse(trace.containsKey("retrieval.kg.brainState.events"));
            String rendered = String.valueOf(trace);
            assertTrue(rendered.contains("kg_final_drop"));
            assertFalse(rendered.contains("raw prompt must not escape"));
            assertFalse(rendered.contains("provider-secret-must-not-escape"));
            assertFalse(rendered.contains("secret query"));
            assertFalse(rendered.contains("Alpha raw relation should not escape"));
            assertFalse(rendered.contains("raw query should not escape"));
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void selectedTraceStoreIncludesFusionScoreMeanAliases() {
        TraceStore.clear();
        try {
            TraceStore.put("rag.fusion.score.mean.web", 0.42d);
            TraceStore.put("rag.fusion.score.mean.vector", 0.73d);
            TraceStore.put("rag.fusion.score.mean.kg", 0.91d);

            @SuppressWarnings("unchecked")
            Map<String, Object> trace = (Map<String, Object>) ReflectionTestUtils.invokeMethod(
                    RagGraphExecutor.class,
                    "selectedTraceStore");

            assertEquals(0.42d, trace.get("rag.fusion.score.mean.web"));
            assertEquals(0.73d, trace.get("rag.fusion.score.mean.vector"));
            assertEquals(0.91d, trace.get("rag.fusion.score.mean.kg"));
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void resolvesThreadIdFromMdcSessionWhenRequestDoesNotProvideOne() {
        QueryRequest request = new QueryRequest();
        MDC.put("sid", "chat-99");
        try {
            assertEquals("chat-99", RagGraphExecutor.resolveThreadId(request));
        } finally {
            MDC.remove("sid");
        }
    }

    @Test
    void selectedTraceStoreIncludesKgNeo4jFields() {
        TraceStore.clear();
        try {
            TraceStore.put("rag.eval.kgAxis", Map.of(
                    "schemaVersion", 2,
                    "signals", List.of("kg_neo4j_degraded"),
                    "neo4jStatus", "failed",
                    "neo4jDisabledReason", "neo4j_query_failed",
                    "neo4jFailureClass", "silent-failure",
                    "rawPrompt", "raw prompt must not escape"));

            @SuppressWarnings("unchecked")
            Map<String, Object> trace = (Map<String, Object>) ReflectionTestUtils.invokeMethod(
                    RagGraphExecutor.class,
                    "selectedTraceStore");

            @SuppressWarnings("unchecked")
            Map<String, Object> kgAxis = (Map<String, Object>) trace.get("rag.eval.kgAxis");
            assertEquals("failed", kgAxis.get("neo4jStatus"));
            assertEquals("neo4j_query_failed", kgAxis.get("neo4jDisabledReason"));
            assertEquals("silent-failure", kgAxis.get("neo4jFailureClass"));
            assertFalse(String.valueOf(trace).contains("raw prompt must not escape"));
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void stateSnapshotsDoNotExposeRawFailureReasonSecrets() {
        RagGraphExecutor executor = new RagGraphExecutor(
                new FakeOrchestrator(),
                new FixedProvider<>(null),
                new RagGraphProperties());
        String failureReason = "retrieve_error api_key=<test-secret>";

        @SuppressWarnings("unchecked")
        Map<String, Object> output = (Map<String, Object>) ReflectionTestUtils.invokeMethod(
                executor,
                "outputContext",
                Map.of(),
                Map.of(RagGraphState.FAILURE_REASON, failureReason));
        @SuppressWarnings("unchecked")
        Map<String, Object> snapshot = (Map<String, Object>) ReflectionTestUtils.invokeMethod(
                executor,
                "snapshotState",
                new RagGraphState(Map.of(RagGraphState.FAILURE_REASON, failureReason)));

        String rendered = String.valueOf(List.of(output.get("failureReason"), snapshot.get("failureReason")));
        assertFalse(rendered.contains("<test-secret>"));
        assertFalse(rendered.contains("api_key=<test-secret>"));
    }

    @Test
    void publicFailureAndEmptyReasonsUseLabelOrHash() {
        String rawReason = "student private langgraph reason";

        String freeText = ReflectionTestUtils.invokeMethod(
                RagGraphExecutor.class,
                "safeFailureReason",
                rawReason);
        String canonical = ReflectionTestUtils.invokeMethod(
                RagGraphExecutor.class,
                "safeFailureReason",
                "repair_unavailable");

        QueryResponse response = ReflectionTestUtils.invokeMethod(
                RagGraphExecutor.class,
                "emptyResponse",
                null,
                rawReason);

        String emptyReason = String.valueOf(response.debug.get("langgraph.emptyReason"));
        assertTrue(freeText.startsWith("hash:"), freeText);
        assertFalse(freeText.contains("student"), freeText);
        assertEquals("repair_unavailable", canonical);
        assertTrue(emptyReason.startsWith("hash:"), emptyReason);
        assertFalse(emptyReason.contains("student"), emptyReason);
    }

    @Test
    void numericStateParserOnlyCatchesNumberFormatException() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/langgraph/RagGraphExecutor.java"));
        int start = source.indexOf("private static double firstNumber");
        int parse = source.indexOf("Double.parseDouble", start);
        int end = source.indexOf("\n    }", parse);
        assertTrue(start >= 0 && parse > start && end > parse, "firstNumber parser should be locatable");
        String helper = source.substring(start, end);

        assertFalse(helper.contains("catch (Exception"),
                "numeric fallback parser must not swallow all Exception");
        assertTrue(helper.contains("catch (NumberFormatException"),
                "numeric fallback parser should only catch NumberFormatException");
    }

    @Test
    void invokeFallbackDebugLogDoesNotAttachRawThrowable() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/langgraph/RagGraphExecutor.java"));

        assertFalse(source.contains("log.debug(\"[AWX2AF2][langgraph] invoke fail-soft fallback reason={}\", invokeFailureReason, cause);"));
        assertTrue(source.contains(
                "log.debug(\"[AWX2AF2][langgraph] invoke fail-soft fallback reason={} errorType={} errorHash={} errorLength={}\""));
        assertTrue(source.contains("SafeRedactor.hashValue(messageOf(cause))"));
        assertTrue(source.contains("messageLength(cause)"));
    }

    @Test
    void timeoutAsyncExecutionPropagatesTraceStoreAndMdc() throws Exception {
        TraceStore.clear();
        MDC.clear();
        try {
            TraceStore.put("langgraph.test.marker", "trace-present");
            MDC.put("sid", "sid-propagated");
            RagGraphProperties properties = new RagGraphProperties();
            properties.setTimeoutMs(2_000);
            RagGraphExecutor executor = newAsyncGraphExecutor(
                    new ContextProbeOrchestrator(),
                    properties,
                    newOwnedGraphRuntime(1, 0));
            QueryRequest request = new QueryRequest();
            request.query = "context propagation";
            request.planId = "safe_autorun.v1";

            QueryResponse response = executor.execute(request);

            assertEquals("trace-present", response.debug.get("worker.traceMarker"));
            assertEquals("sid-propagated", response.debug.get("worker.sid"));
        } finally {
            TraceStore.clear();
            MDC.clear();
        }
    }

    @Test
    void asyncGraphRunsOnSpringOwnedNamedExecutor() throws Exception {
        ThreadCapturingOrchestrator orchestrator = new ThreadCapturingOrchestrator();
        RagGraphProperties properties = new RagGraphProperties();
        properties.setTimeoutMs(2_000);
        RagGraphExecutor executor = newAsyncGraphExecutor(
                orchestrator,
                properties,
                newOwnedGraphRuntime(1, 0));
        QueryRequest request = new QueryRequest();
        request.query = "named graph runtime";

        QueryResponse response = executor.execute(request);

        assertEquals(1, response.results.size());
        assertTrue(orchestrator.threadName.get().startsWith("awx-rag-graph-"));
        assertFalse(orchestrator.threadName.get().contains("ForkJoinPool.commonPool"));
    }

    @Test
    void legacyTimeoutDisabledConstructorRemainsSynchronousWithAmbientBudget() {
        ThreadCapturingOrchestrator orchestrator = new ThreadCapturingOrchestrator();
        RagGraphProperties properties = new RagGraphProperties();
        properties.setTimeoutMs(0);
        RagGraphExecutor executor = new RagGraphExecutor(
                orchestrator,
                new FixedProvider<>(null),
                properties);
        QueryRequest request = new QueryRequest();
        request.query = "legacy synchronous graph";
        TimeBudgetContext.set(new TimeBudget(5_000));

        QueryResponse response = executor.execute(request);

        assertEquals(1, response.results.size());
        assertEquals(Thread.currentThread().getName(), orchestrator.threadName.get());
    }

    @Test
    void workerCompletionObservationNeverReportsFinishedWithoutStarted() throws Exception {
        AtomicBoolean started = new AtomicBoolean(false);
        AtomicBoolean finished = new AtomicBoolean(true);
        Method snapshotMethod = RagGraphExecutor.class.getDeclaredMethod(
                "snapshotWorkerObservation",
                AtomicBoolean.class,
                AtomicBoolean.class);
        snapshotMethod.setAccessible(true);

        Object observation = snapshotMethod.invoke(null, started, finished);
        Method startedAccessor = observation.getClass().getDeclaredMethod("started");
        Method finishedAccessor = observation.getClass().getDeclaredMethod("finished");
        startedAccessor.setAccessible(true);
        finishedAccessor.setAccessible(true);

        assertEquals(true, startedAccessor.invoke(observation));
        assertEquals(true, finishedAccessor.invoke(observation));
    }

    @Test
    void saturatedGraphExecutorFailsSoftWithoutRunningGraph() throws Exception {
        ExecutorService runtime = newOwnedGraphRuntime(1, 0);
        CountDownLatch occupied = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        runtime.execute(() -> {
            occupied.countDown();
            awaitReleaseIgnoringInterrupt(release);
        });
        assertTrue(occupied.await(1, TimeUnit.SECONDS));
        CountingOrchestrator orchestrator = new CountingOrchestrator();
        RagGraphProperties properties = new RagGraphProperties();
        properties.setTimeoutMs(2_000);
        RagGraphExecutor executor = newAsyncGraphExecutor(orchestrator, properties, runtime);
        QueryRequest request = new QueryRequest();
        request.query = "saturated graph runtime";

        try {
            QueryResponse response = executor.execute(request);

            assertTrue(response.results.isEmpty());
            assertEquals("graph_executor_saturated", response.debug.get("langgraph.failureClass"));
            assertEquals("graph_executor_saturated", response.debug.get("langgraph.emptyReason"));
            assertEquals(0, orchestrator.calls.get());
            assertEquals("graph_executor_saturated", TraceStore.get("langgraph.executor.reason"));
        } finally {
            release.countDown();
        }
    }

    @Test
    void requestBudgetClampsWaitWithoutClaimingWorkerTermination() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        BlockingOrchestrator orchestrator = new BlockingOrchestrator(started, release, finished);
        RagGraphProperties properties = new RagGraphProperties();
        properties.setTimeoutMs(2_000);
        RagGraphExecutor executor = newAsyncGraphExecutor(
                orchestrator,
                properties,
                newOwnedGraphRuntime(1, 0));
        QueryRequest request = new QueryRequest();
        request.query = "request budget graph";
        TimeBudgetContext.set(new TimeBudget(100));

        long startedNs = System.nanoTime();
        try {
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> executor.execute(request));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs);

            assertTrue(failure.getMessage().contains("timed out"));
            assertTrue(elapsedMs < 500L, "request budget must clamp the graph wait");
            assertTrue(started.await(1, TimeUnit.SECONDS));
            assertEquals(true, TraceStore.get("langgraph.cancelRequested"));
            assertEquals(false, TraceStore.get("langgraph.workerFinished"));
            assertEquals("unfinished", TraceStore.get("langgraph.workerTermination"));
            assertEquals(1L, finished.getCount());
        } finally {
            release.countDown();
            assertTrue(finished.await(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void langGraphTimeoutReportsNoInterruptCancelMode() throws Exception {
        TraceStore.clear();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean(false);
        RagGraphProperties properties = new RagGraphProperties();
        properties.setTimeoutMs(50);
        RagGraphExecutor executor = newAsyncGraphExecutor(
                new SlowOrchestrator(started, finished, interrupted),
                properties,
                newOwnedGraphRuntime(1, 0));
        QueryRequest request = new QueryRequest();
        request.query = "timeout langgraph";
        request.threadId = "chat-timeout";

        try {
            IllegalStateException error = assertThrows(IllegalStateException.class, () -> executor.execute(request));

            assertTrue(error.getMessage().contains("timed out"));
            assertTrue(started.await(1, TimeUnit.SECONDS));
            assertEquals(true, TraceStore.get("langgraph.timeout"));
            assertEquals("timeout", TraceStore.get("langgraph.failureClass"));
            assertEquals("no_interrupt", TraceStore.get("langgraph.cancelMode"));
            assertTrue(finished.await(2, TimeUnit.SECONDS));
            assertFalse(interrupted.get());
        } finally {
            TraceStore.clear();
        }
    }

    private ExecutorService newOwnedGraphRuntime(int workers, int queueCapacity) {
        SearchExecutorConfig config = new SearchExecutorConfig();
        ReflectionTestUtils.setField(config, "ragGraphWorkers", workers);
        ReflectionTestUtils.setField(config, "ragGraphQueueCapacity", queueCapacity);
        ExecutorService executor = ReflectionTestUtils.invokeMethod(config, "ragGraphWorkerExecutor");
        assertTrue(executor != null);
        ownedExecutors.add(executor);
        return executor;
    }

    private static RagGraphExecutor newAsyncGraphExecutor(
            UnifiedRagOrchestrator orchestrator,
            RagGraphProperties properties,
            ExecutorService runtime) throws Exception {
        Constructor<RagGraphExecutor> constructor = RagGraphExecutor.class.getConstructor(
                UnifiedRagOrchestrator.class,
                ObjectProvider.class,
                RagGraphProperties.class,
                ObjectProvider.class,
                ExecutorService.class);
        return constructor.newInstance(
                orchestrator,
                new FixedProvider<>(null),
                properties,
                new FixedProvider<>(null),
                runtime);
    }

    private static final class ContextProbeOrchestrator extends UnifiedRagOrchestrator {
        @Override
        public QueryTrace queryWithTrace(QueryRequest req) {
            QueryResponse response = response("context", req);
            response.debug.put("worker.traceMarker", TraceStore.get("langgraph.test.marker"));
            response.debug.put("worker.sid", MDC.get("sid"));
            QueryTrace trace = new QueryTrace();
            trace.response = response;
            trace.finalResults = response.results;
            return trace;
        }
    }

    private static final class ThreadCapturingOrchestrator extends UnifiedRagOrchestrator {
        private final AtomicReference<String> threadName = new AtomicReference<>("");

        @Override
        public QueryTrace queryWithTrace(QueryRequest req) {
            threadName.set(Thread.currentThread().getName());
            QueryResponse response = response("thread-capture", req);
            QueryTrace trace = new QueryTrace();
            trace.response = response;
            trace.finalResults = response.results;
            return trace;
        }
    }

    private static final class CountingOrchestrator extends UnifiedRagOrchestrator {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public QueryTrace queryWithTrace(QueryRequest req) {
            calls.incrementAndGet();
            QueryResponse response = response("counting", req);
            QueryTrace trace = new QueryTrace();
            trace.response = response;
            trace.finalResults = response.results;
            return trace;
        }
    }

    private static final class BlockingOrchestrator extends UnifiedRagOrchestrator {
        private final CountDownLatch started;
        private final CountDownLatch release;
        private final CountDownLatch finished;

        private BlockingOrchestrator(
                CountDownLatch started,
                CountDownLatch release,
                CountDownLatch finished) {
            this.started = started;
            this.release = release;
            this.finished = finished;
        }

        @Override
        public QueryTrace queryWithTrace(QueryRequest req) {
            started.countDown();
            try {
                awaitReleaseIgnoringInterrupt(release);
                QueryResponse response = response("blocking", req);
                QueryTrace trace = new QueryTrace();
                trace.response = response;
                trace.finalResults = response.results;
                return trace;
            } finally {
                finished.countDown();
            }
        }
    }

    private static final class FakeOrchestrator extends UnifiedRagOrchestrator {
        private int traceCalls;

        @Override
        public QueryTrace queryWithTrace(QueryRequest req) {
            traceCalls++;
            QueryResponse response = response("graph", req);
            QueryTrace trace = new QueryTrace();
            trace.response = response;
            trace.finalResults = response.results;
            return trace;
        }
    }

    private static final class CapturingOrchestrator extends UnifiedRagOrchestrator {
        private QueryRequest captured;

        @Override
        public QueryTrace queryWithTrace(QueryRequest req) {
            captured = req;
            QueryResponse response = response("captured", req);
            QueryTrace trace = new QueryTrace();
            trace.response = response;
            trace.finalResults = response.results;
            return trace;
        }
    }

    private static final class EmptyOrchestrator extends UnifiedRagOrchestrator {
        private int traceCalls;

        @Override
        public QueryTrace queryWithTrace(QueryRequest req) {
            traceCalls++;
            QueryResponse response = new QueryResponse();
            response.requestId = "empty";
            response.planApplied = req == null ? null : req.planId;
            QueryTrace trace = new QueryTrace();
            trace.response = response;
            trace.finalResults = response.results;
            return trace;
        }
    }

    private static final class CancellingOrchestrator extends UnifiedRagOrchestrator {
        private int traceCalls;

        @Override
        public QueryTrace queryWithTrace(QueryRequest req) {
            traceCalls++;
            throw new CancellationException("cancelled ownerToken fake-token");
        }
    }

    private static final class InterruptingOrchestrator extends UnifiedRagOrchestrator {
        private int traceCalls;

        @Override
        public QueryTrace queryWithTrace(QueryRequest req) {
            traceCalls++;
            return sneakyThrow(new InterruptedException("interrupted ownerToken fake-token"));
        }
    }

    private static final class CountingRepairHandler extends EvidenceRepairHandler {
        private int retrieveCalls;

        private CountingRepairHandler() {
            super(null, null, "", "");
        }

        @Override
        public List<Content> retrieve(Query query) {
            retrieveCalls++;
            return List.of(Content.from("repair must not run"));
        }
    }

    @SuppressWarnings("unchecked")
    private static <T, E extends Throwable> T sneakyThrow(Throwable throwable) throws E {
        throw (E) throwable;
    }

    private static final class FixedRepairHandler extends EvidenceRepairHandler {
        private final List<Content> repaired;

        private FixedRepairHandler(List<Content> repaired) {
            super(null, null, "", "");
            this.repaired = repaired;
        }

        @Override
        public List<Content> retrieve(Query query) {
            return repaired;
        }
    }

    private static final class ScorecardDropOrchestrator extends UnifiedRagOrchestrator {
        @Override
        public QueryTrace queryWithTrace(QueryRequest req) {
            QueryResponse response = response("scorecard-drop", req);
            response.debug.put("rag.eval.scorecard", Map.of(
                    "reasonCode", "kg_final_drop",
                    "thresholdLabels", List.of("kg_final_drop")));
            QueryTrace trace = new QueryTrace();
            trace.response = response;
            trace.finalResults = response.results;
            return trace;
        }
    }

    private static final class ThresholdBreakOrchestrator extends UnifiedRagOrchestrator {
        @Override
        public QueryTrace queryWithTrace(QueryRequest req) {
            QueryResponse response = response("threshold-drop", req);
            response.debug.put("rag.eval.thresholdBreaks", List.of(Map.of("label", "stage_drop_high")));
            QueryTrace trace = new QueryTrace();
            trace.response = response;
            trace.finalResults = response.results;
            return trace;
        }
    }

    private static final class KgAxisDropOrchestrator extends UnifiedRagOrchestrator {
        @Override
        public QueryTrace queryWithTrace(QueryRequest req) {
            QueryResponse response = response("kg-axis-drop", req);
            response.debug.put("rag.eval.kgAxis", Map.of(
                    "schemaVersion", 2,
                    "kgPoolCount", 1,
                    "kgFinalCount", 0,
                    "kgFinalRetention", 0.0d,
                    "graphOpportunity", true,
                    "signals", List.of("kg_final_drop")));
            QueryTrace trace = new QueryTrace();
            trace.response = response;
            trace.finalResults = response.results;
            return trace;
        }
    }

    private static final class KgNeo4jDegradedOrchestrator extends UnifiedRagOrchestrator {
        @Override
        public QueryTrace queryWithTrace(QueryRequest req) {
            QueryResponse response = response("kg-neo4j-degraded", req);
            response.debug.put("rag.eval.scorecard", Map.of(
                    "reasonCode", "",
                    "thresholdLabels", List.of("kg_starvation", "kg_neo4j_degraded")));
            response.debug.put("rag.eval.kgAxis", Map.of(
                    "schemaVersion", 2,
                    "kgPoolCount", 0,
                    "kgFinalCount", 0,
                    "graphOpportunity", true,
                    "neo4jStatus", "failed",
                    "neo4jDisabledReason", "neo4j_query_failed",
                    "neo4jFailureClass", "silent-failure",
                    "signals", List.of("kg_neo4j_failed", "kg_neo4j_degraded"),
                    "rawPrompt", "raw prompt must not escape"));
            response.debug.put("rag.eval.thresholdBreaks", List.of(Map.of(
                    "label", "kg_neo4j_degraded",
                    "metric", "kgAxis.neo4jStatus",
                    "rawQuery", "sensitive query")));
            QueryTrace trace = new QueryTrace();
            trace.response = response;
            trace.finalResults = response.results;
            return trace;
        }
    }

    private static final class OfflineTextureBreakOrchestrator extends UnifiedRagOrchestrator {
        private final String label;

        private OfflineTextureBreakOrchestrator(String label) {
            this.label = label;
        }

        @Override
        public QueryTrace queryWithTrace(QueryRequest req) {
            QueryResponse response = response("offline-texture-break", req);
            response.debug.put("rag.eval.thresholdBreaks", List.of(Map.of("label", label)));
            QueryTrace trace = new QueryTrace();
            trace.response = response;
            trace.finalResults = response.results;
            return trace;
        }
    }

    private static final class SlowOrchestrator extends UnifiedRagOrchestrator {
        private final CountDownLatch started;
        private final CountDownLatch finished;
        private final AtomicBoolean interrupted;

        private SlowOrchestrator(CountDownLatch started, CountDownLatch finished, AtomicBoolean interrupted) {
            this.started = started;
            this.finished = finished;
            this.interrupted = interrupted;
        }

        @Override
        public QueryTrace queryWithTrace(QueryRequest req) {
            started.countDown();
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            } finally {
                finished.countDown();
            }
            QueryTrace trace = new QueryTrace();
            trace.response = response("slow", req);
            trace.finalResults = trace.response.results;
            return trace;
        }
    }

    private static void awaitReleaseIgnoringInterrupt(CountDownLatch release) {
        boolean interrupted = false;
        try {
            while (release.getCount() > 0L) {
                try {
                    if (release.await(1, TimeUnit.SECONDS)) {
                        return;
                    }
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    static QueryResponse response(String id, QueryRequest req) {
        QueryResponse response = new QueryResponse();
        response.requestId = id;
        response.planApplied = req == null ? null : req.planId;
        response.debug = new LinkedHashMap<>();
        Doc doc = new Doc();
        doc.id = id + "-doc";
        doc.title = "title";
        doc.snippet = "snippet";
        doc.source = "test";
        doc.score = 1.0d;
        doc.rank = 1;
        doc.meta = new LinkedHashMap<>();
        response.results.add(doc);
        return response;
    }

    static final class FixedProvider<T> implements ObjectProvider<T> {
        private final T value;

        FixedProvider(T value) {
            this.value = value;
        }

        @Override
        public T getObject(Object... args) {
            return value;
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
            return value;
        }
    }
}
