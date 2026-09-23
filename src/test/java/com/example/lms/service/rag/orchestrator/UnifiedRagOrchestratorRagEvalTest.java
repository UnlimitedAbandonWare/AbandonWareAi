package com.example.lms.service.rag.orchestrator;

import com.example.lms.debug.DebugEvent;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.infra.selection.SelectionDecisionLedger;
import com.example.lms.infra.selection.SelectionEntropyFactory;
import com.example.lms.moe.NormalizedRagMetrics;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.service.rag.query.QueryAnalysisResult;
import com.example.lms.service.rag.query.QueryAnalysisService;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnifiedRagOrchestratorRagEvalTest {

    @BeforeEach
    void setUp() {
        GuardContextHolder.clear();
        TraceStore.clear();
    }

    @AfterEach
    void tearDown() {
        GuardContextHolder.clear();
        TraceStore.clear();
    }

    @Test
    void addsRagEvalSnapshotWithoutRawQuery() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        DebugEventStore debugEventStore = enabledDebugEventStore();
        ReflectionTestUtils.setField(orchestrator, "debugEventStore", debugEventStore);
        ReflectionTestUtils.setField(orchestrator, "ragEvalDebugEventsEnabled", true);
        UnifiedRagOrchestrator.QueryRequest request = new UnifiedRagOrchestrator.QueryRequest();
        request.query = "secret question text";
        TraceStore.put("selfask.logicDag.enabled", true);
        TraceStore.put("selfask.logicDag.dependencyMode", "entity_first");
        TraceStore.put("selfask.logicDag.nodeCount", 3);
        TraceStore.put("selfask.logicDag.edgeCount", 3);
        TraceStore.put("selfask.logicDag.topologicalOrder", List.of("ER", "BQ", "RC"));
        TraceStore.put("selfask.logicDag.prunedDuplicateCount", 0);
        TraceStore.put("selfask.logicDag.failureClass", "none");

        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

        assertEquals(0, response.debug.get("rag.eval.resultCount"));
        assertEquals(true, response.debug.get("rag.eval.emptyResult"));
        assertTrue(response.debug.containsKey("rag.eval.stageCounts"));
        assertTrue(response.debug.containsKey("rag.eval.providerDisabledSignals"));
        assertTrue(response.debug.containsKey("rag.eval.zeroResultSignals"));
        assertTrue(response.debug.containsKey("rag.eval.afterFilterStarvationSignals"));
        assertTrue(response.debug.containsKey("rag.eval.sourceDiversity"));
        assertTrue(response.debug.containsKey("rag.eval.stageSourceDiversity"));
        assertTrue(response.debug.containsKey("rag.eval.stageDrop"));
        assertTrue(response.debug.containsKey("rag.eval.normalized"));
        assertTrue(response.debug.containsKey("rag.eval.thresholdBreaks"));
        assertTrue(response.debug.containsKey("rag.eval.bottleneck"));
        assertTrue(response.debug.containsKey("rag.eval.scorecard"));
        assertTrue(response.debug.containsKey("rag.eval.queryFingerprint"));
        assertTrue(response.debug.containsKey("rag.eval.logicDag"));
        assertFalse(String.valueOf(response.debug).contains("secret question text"));

        @SuppressWarnings("unchecked")
        Map<String, Object> queryFingerprint =
                (Map<String, Object>) response.debug.get("rag.eval.queryFingerprint");
        assertTrue(queryFingerprint.containsKey("queryHash"));
        assertEquals(20, queryFingerprint.get("length"));
        assertEquals("1-4", queryFingerprint.get("tokenBucket"));
        assertEquals(response.debug.get("rag.eval.resultCount"), TraceStore.get("rag.eval.resultCount"));
        assertEquals(response.debug.get("rag.eval.sourceDiversity"), TraceStore.get("rag.eval.sourceDiversity"));
        assertEquals(response.debug.get("rag.eval.stageSourceDiversity"), TraceStore.get("rag.eval.stageSourceDiversity"));
        assertEquals(response.debug.get("rag.eval.stageDrop"), TraceStore.get("rag.eval.stageDrop"));
        assertEquals(response.debug.get("rag.eval.providerDisabledSignals"), TraceStore.get("rag.eval.providerDisabledSignals"));
        assertEquals(response.debug.get("rag.eval.zeroResultSignals"), TraceStore.get("rag.eval.zeroResultSignals"));
        assertEquals(response.debug.get("rag.eval.afterFilterStarvationSignals"), TraceStore.get("rag.eval.afterFilterStarvationSignals"));
        assertEquals(response.debug.get("rag.eval.logicDag"), TraceStore.get("rag.eval.logicDag"));

        @SuppressWarnings("unchecked")
        Map<String, Integer> stageCounts = (Map<String, Integer>) response.debug.get("rag.eval.stageCounts");
        assertEquals(0, stageCounts.get("final"));
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Integer>> stageSourceDiversity =
                (Map<String, Map<String, Integer>>) response.debug.get("rag.eval.stageSourceDiversity");
        assertTrue(stageSourceDiversity.containsKey("pool"));
        assertTrue(stageSourceDiversity.containsKey("fused"));
        assertTrue(stageSourceDiversity.containsKey("final"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> thresholdBreaks =
                (List<Map<String, Object>>) response.debug.get("rag.eval.thresholdBreaks");
        assertTrue(thresholdBreaks.stream()
                .anyMatch(row -> "retrieval_starvation".equals(row.get("label"))));
        assertTrue(thresholdBreaks.stream()
                .allMatch(row -> "request".equals(row.get("scope"))
                        && Integer.valueOf(1).equals(row.get("sampleCount"))
                        && "single-request".equals(row.get("aggregationWindow"))));

        @SuppressWarnings("unchecked")
        Map<String, Object> bottleneck = (Map<String, Object>) response.debug.get("rag.eval.bottleneck");
        assertTrue(bottleneck.containsKey("stage"));
        assertTrue(bottleneck.containsKey("label"));

        @SuppressWarnings("unchecked")
        Map<String, Object> scorecard = (Map<String, Object>) response.debug.get("rag.eval.scorecard");
        assertEquals(NormalizedRagMetrics.SCHEMA_VERSION, scorecard.get("schemaVersion"));
        assertEquals("degraded", scorecard.get("status"));
        assertEquals(0, scorecard.get("resultCount"));
        assertTrue(scorecard.get("stageCounts") instanceof Map<?, ?>);
        assertTrue(scorecard.get("stageDrop") instanceof Map<?, ?>);
        assertTrue(scorecard.get("sourceDiversity") instanceof Map<?, ?>);
        assertTrue(scorecard.get("normalized") instanceof Map<?, ?>);
        assertTrue(scorecard.get("kgAxis") instanceof Map<?, ?>);
        assertTrue(scorecard.get("bottleneck") instanceof Map<?, ?>);
        @SuppressWarnings("unchecked")
        List<String> thresholdLabels = (List<String>) scorecard.get("thresholdLabels");
        assertTrue(thresholdLabels.contains("retrieval_starvation"));
        assertEquals(scorecard, TraceStore.get("rag.eval.scorecard"));
        assertFalse(String.valueOf(scorecard).contains("secret question text"));

        @SuppressWarnings("unchecked")
        Map<String, Object> logicDag = (Map<String, Object>) response.debug.get("rag.eval.logicDag");
        assertEquals(Boolean.TRUE, logicDag.get("enabled"));
        assertEquals("entity_first", logicDag.get("dependencyMode"));
        assertEquals(List.of("ER", "BQ", "RC"), logicDag.get("topologicalOrder"));
        assertFalse(String.valueOf(logicDag).contains("secret question text"));

        assertFalse(String.valueOf(debugEventStore.list(10)).contains("secret question text"));
        assertTrue(String.valueOf(debugEventStore.list(10)).contains("queryFingerprint"));
        assertTrue(String.valueOf(debugEventStore.list(10)).contains("tokenBucket=1-4"));
        assertTrue(String.valueOf(debugEventStore.list(10)).contains("scorecard"));
        assertTrue(String.valueOf(debugEventStore.list(10)).contains("logicDag"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void providerDisabledSignalsDoNotRetainRawStageDiagnosticText() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        UnifiedRagOrchestrator.QueryRequest request = new UnifiedRagOrchestrator.QueryRequest();
        request.useWeb = true;
        Map<String, Object> debug = Map.of(
                "stage.web", "missing provider for Private Diagnostic Query");

        List<String> signals = ReflectionTestUtils.invokeMethod(
                orchestrator,
                "providerDisabledSignals",
                request,
                debug);

        assertTrue(signals != null);
        assertTrue(signals.contains("web:missing_webRetriever"));
        assertTrue(signals.contains("stage.web:missing"));
        assertFalse(String.valueOf(signals).contains("Private Diagnostic Query"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void providerDisabledSignalsRetainSafeReasonCodesForAllProviders() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        TraceStore.put("web.naver.providerDisabled", true);
        TraceStore.put("web.naver.disabledReason", "Private Naver Reason Query");
        TraceStore.put("web.brave.providerDisabled", true);
        TraceStore.put("web.brave.disabledReason", "missing_api_key");
        TraceStore.put("web.serpapi.providerDisabled", true);
        TraceStore.put("web.serpapi.disabledReason", "quota_exhausted");
        TraceStore.put("web.tavily.providerDisabled", true);
        TraceStore.put("web.tavily.disabledReason", "missing_tavily_api_key");

        List<String> signals = ReflectionTestUtils.invokeMethod(
                orchestrator,
                "providerDisabledSignals",
                null,
                Map.of());

        assertTrue(signals != null);
        assertTrue(signals.contains("naver:present"));
        assertTrue(signals.contains("brave:missing_api_key"));
        assertTrue(signals.contains("serpapi:quota_exhausted"));
        assertTrue(signals.contains("tavily:missing_tavily_api_key"));
        assertFalse(String.valueOf(signals).contains("Private Naver Reason Query"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void zeroResultSignalsDoNotRetainRawTraceFlagDetailText() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        UnifiedRagOrchestrator.QueryRequest request = new UnifiedRagOrchestrator.QueryRequest();
        request.useWeb = true;
        TraceStore.put("web.naver.zeroResults", true);
        TraceStore.put("web.naver.afterFilterCount", "Private Zero Result Query");

        List<String> signals = ReflectionTestUtils.invokeMethod(
                orchestrator,
                "zeroResultSignals",
                request,
                new UnifiedRagOrchestrator.QueryTrace(),
                List.of(),
                Map.of("web", 0, "pool", 0));

        assertTrue(signals != null);
        assertTrue(signals.contains("naver:zero_results:present"));
        assertFalse(String.valueOf(signals).contains("Private Zero Result Query"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void tavilyTraceSignalsUseStandardProviderTaxonomy() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        TraceStore.put("web.tavily.zeroResults", true);
        TraceStore.put("web.tavily.returnedCount", 3);
        TraceStore.put("web.tavily.afterFilterCount", 0);

        List<String> zeroSignals = ReflectionTestUtils.invokeMethod(
                orchestrator,
                "zeroResultSignals",
                null,
                new UnifiedRagOrchestrator.QueryTrace(),
                List.of(doc("tavily-doc")),
                Map.of("web", 1, "pool", 1));
        List<String> afterFilterSignals = ReflectionTestUtils.invokeMethod(
                orchestrator,
                "afterFilterStarvationSignals");

        assertTrue(zeroSignals != null);
        assertTrue(String.valueOf(zeroSignals).contains("tavily:zero_results"), () -> String.valueOf(zeroSignals));
        assertTrue(afterFilterSignals != null);
        assertTrue(afterFilterSignals.contains("tavily:after_filter_starvation"),
                () -> String.valueOf(afterFilterSignals));
    }

    @Test
    @SuppressWarnings("unchecked")
    void logicDagTraceDoesNotRetainRawTopologicalOrderLabels() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        UnifiedRagOrchestrator.QueryRequest request = new UnifiedRagOrchestrator.QueryRequest();
        request.query = "logic dag redaction query";
        TraceStore.put("selfask.logicDag.enabled", true);
        TraceStore.put("selfask.logicDag.dependencyMode", "entity_first");
        TraceStore.put("selfask.logicDag.topologicalOrder", List.of("ER", "Private Logic Node Label"));

        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

        Map<String, Object> logicDag = (Map<String, Object>) response.debug.get("rag.eval.logicDag");
        assertEquals(Boolean.TRUE, logicDag.get("enabled"));
        assertEquals("entity_first", logicDag.get("dependencyMode"));
        assertTrue(String.valueOf(logicDag).contains("ER"));
        assertTrue(String.valueOf(logicDag).contains("hash:"));
        assertFalse(String.valueOf(logicDag).contains("Private Logic Node Label"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void operationalThresholdBreaksDoNotRetainRawReasonText() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        UnifiedRagOrchestrator.QueryRequest request = new UnifiedRagOrchestrator.QueryRequest();
        request.query = "operational redaction query";
        request.useWeb = false;
        request.useVector = false;
        request.useKg = false;
        request.useBm25 = false;
        request.enableBiEncoder = false;
        request.enableDiversity = false;
        request.enableOnnx = false;
        TraceStore.put("rag.anchor.driftScore", 0.8d);
        TraceStore.put("rag.anchor.reason", "Private Anchor Reason Text");
        TraceStore.put("rag.metrics.burstReductionRate", 0.5d);
        TraceStore.put("rag.budget.reason", "Private Budget Reason Text anchor_drift_high");
        TraceStore.put("rag.offlineTexture.stale", true);
        TraceStore.put("rag.offlineTexture.onlineSearchNeeded", true);
        TraceStore.put("rag.offlineTexture.reason", "Private Offline Texture Reason Text");

        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

        List<Map<String, Object>> thresholdBreaks =
                (List<Map<String, Object>>) response.debug.get("rag.eval.thresholdBreaks");
        String rendered = String.valueOf(thresholdBreaks);
        assertTrue(rendered.contains("anchor_drift_high"), () -> rendered);
        assertTrue(rendered.contains("budget_overexpanded"), () -> rendered);
        assertTrue(rendered.contains("offline_texture_stale"), () -> rendered);
        assertTrue(rendered.contains("hash:"), () -> rendered);
        assertFalse(rendered.contains("Private Anchor Reason Text"));
        assertFalse(rendered.contains("Private Budget Reason Text"));
        assertFalse(rendered.contains("Private Offline Texture Reason Text"));
        assertFalse(String.valueOf(TraceStore.get("rag.eval.thresholdBreaks")).contains("Private Anchor Reason Text"));
    }

    @Test
    void queryAnalysisDebugDoesNotRetainRawEntityText() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        QueryAnalysisService analysisService = new QueryAnalysisService() {
            @Override
            public QueryAnalysisResult analyze(String userQuery) {
                return new QueryAnalysisResult(
                        userQuery,
                        QueryAnalysisResult.QueryIntent.SEARCH,
                        List.of("Private Entity Raw Text"),
                        List.of(),
                        false,
                        false,
                        List.of(),
                        0.8d,
                        0.5d,
                        0.1d,
                        "MEDIUM",
                        "Private Domain Raw Text",
                        List.of(),
                        List.of("Private Noise Raw Text"));
            }
        };
        ReflectionTestUtils.setField(orchestrator, "queryAnalysisService", analysisService);
        UnifiedRagOrchestrator.QueryRequest request = new UnifiedRagOrchestrator.QueryRequest();
        request.query = "analysis redaction query";
        request.useWeb = false;
        request.useVector = false;
        request.useKg = false;
        request.useBm25 = false;
        request.enableBiEncoder = false;
        request.enableDiversity = false;
        request.enableOnnx = false;

        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

        String rendered = String.valueOf(response.debug);
        assertEquals("SEARCH", response.debug.get("analysis.intent"));
        assertEquals(true, response.debug.get("analysis.isEntityQuery"));
        assertEquals("Private Domain Raw Text", request.expectedDomain);
        assertEquals(List.of("Private Noise Raw Text"), request.noiseDomains);
        assertTrue(rendered.contains("hash:"), () -> rendered);
        assertFalse(rendered.contains("Private Entity Raw Text"));
        assertFalse(rendered.contains("Private Domain Raw Text"));
        assertFalse(rendered.contains("Private Noise Raw Text"));
    }

    @Test
    void emitsDegradedRagEvalDebugEventEvenWhenOptionalFlagIsDisabled() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        DebugEventStore debugEventStore = enabledDebugEventStore();
        ReflectionTestUtils.setField(orchestrator, "debugEventStore", debugEventStore);
        ReflectionTestUtils.setField(orchestrator, "ragEvalDebugEventsEnabled", false);
        UnifiedRagOrchestrator.QueryRequest request = new UnifiedRagOrchestrator.QueryRequest();
        request.query = "secret fallback question";

        orchestrator.query(request);

        DebugEvent event = debugEventStore.list(10).stream()
                .filter(ev -> "UnifiedRagOrchestrator.ragEval".equals(ev.where()))
                .findFirst()
                .orElseThrow();
        assertEquals(DebugProbeType.ORCHESTRATION, event.probe());
        assertEquals(Boolean.TRUE, event.data().get("emptyResult"));
        assertEquals(0, event.data().get("resultCount"));
        assertTrue(event.data().get("stageCounts") instanceof Map<?, ?>);
        assertTrue(event.data().get("thresholdBreaks") instanceof List<?>);
        assertTrue(event.data().get("queryFingerprint") instanceof Map<?, ?>);
        assertFalse(String.valueOf(event).contains("secret fallback question"));
    }

    @Test
    void addsStageDropThresholdBreakWhenCandidateDropIsHigh() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        UnifiedRagOrchestrator.QueryRequest request = new UnifiedRagOrchestrator.QueryRequest();
        request.query = "drop check";
        request.topK = 1;
        request.seedOnly = true;
        request.seedCandidates = List.of(doc("a"), doc("b"), doc("c"), doc("d"), doc("e"), doc("f"), doc("g"));
        request.useWeb = false;
        request.useVector = false;
        request.useKg = false;
        request.useBm25 = false;
        request.enableBiEncoder = false;
        request.enableDiversity = false;
        request.enableOnnx = false;

        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> thresholdBreaks =
                (List<Map<String, Object>>) response.debug.get("rag.eval.thresholdBreaks");
        assertTrue(thresholdBreaks.stream().anyMatch(row ->
                "stage_drop_high".equals(row.get("label"))
                        && "pool".equals(row.get("fromStage"))
                        && "fused".equals(row.get("toStage"))
                        && Integer.valueOf(7).equals(row.get("beforeCount"))
                        && Integer.valueOf(3).equals(row.get("afterCount"))),
                () -> String.valueOf(thresholdBreaks));

        @SuppressWarnings("unchecked")
        Map<String, Object> bottleneck = (Map<String, Object>) response.debug.get("rag.eval.bottleneck");
        assertEquals("drop_bottleneck", bottleneck.get("label"));
        @SuppressWarnings("unchecked")
        Map<String, Double> traceStageDrop = (Map<String, Double>) TraceStore.get("rag.eval.stageDrop");
        assertTrue(traceStageDrop.values().stream().anyMatch(value -> value >= 0.50d));
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Integer>> stageSourceDiversity =
                (Map<String, Map<String, Integer>>) TraceStore.get("rag.eval.stageSourceDiversity");
        assertEquals(Map.of("WEB", 7), stageSourceDiversity.get("pool"));
        assertEquals(Map.of("WEB", 1), stageSourceDiversity.get("final"));
    }

    @Test
    void preservesKgScoreAndAddsKgAxisMetrics() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        ContentRetriever kgRetriever = query -> List.of(Content.from(TextSegment.from(
                "Alpha graph evidence",
                Metadata.from(Map.of(
                        "doc_id", "kg-alpha",
                        "kg_score", 0.77d)))));
        ReflectionTestUtils.setField(orchestrator, "kgRetriever", kgRetriever);

        UnifiedRagOrchestrator.QueryRequest request = new UnifiedRagOrchestrator.QueryRequest();
        request.query = "graph relation";
        request.topK = 3;
        request.useWeb = false;
        request.useVector = false;
        request.useKg = true;
        request.useBm25 = false;
        request.enableBiEncoder = false;
        request.enableDiversity = false;
        request.enableOnnx = false;

        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

        assertEquals(1, response.results.size());
        assertEquals("KG", response.results.get(0).source);
        assertEquals(0.77d, response.results.get(0).score, 1e-9);
        assertEquals(0.77d, (Double) response.results.get(0).meta.get("kg_score"), 1e-9);

        @SuppressWarnings("unchecked")
        Map<String, Object> kgAxis = (Map<String, Object>) response.debug.get("rag.eval.kgAxis");
        assertEquals(1, kgAxis.get("retrievedCount"));
        assertEquals(1, kgAxis.get("finalCount"));
        assertEquals(1.0d, (Double) kgAxis.get("retrievalHitRate"), 1e-9);
        assertEquals(1.0d, (Double) kgAxis.get("finalRetention"), 1e-9);
        assertEquals("contributed", kgAxis.get("status"));
        assertEquals(kgAxis, TraceStore.get("rag.eval.kgAxis"));
        @SuppressWarnings("unchecked")
        Map<String, Object> scorecard = (Map<String, Object>) response.debug.get("rag.eval.scorecard");
        assertEquals(kgAxis, scorecard.get("kgAxis"));
    }

    @Test
    void preservesWebMetadataScoreInStandaloneWebLoop() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        ContentRetriever webRetriever = query -> List.of(Content.from(TextSegment.from(
                "Web evidence",
                Metadata.from(Map.of(
                        "doc_id", "web-alpha",
                        "score", 0.42d,
                        "url", "https://example.test/web-alpha")))));
        ReflectionTestUtils.setField(orchestrator, "webRetriever", webRetriever);

        UnifiedRagOrchestrator.QueryRequest request = new UnifiedRagOrchestrator.QueryRequest();
        request.query = "standalone web score sensitive text";
        request.topK = 3;
        request.useWeb = true;
        request.useVector = false;
        request.useKg = false;
        request.useBm25 = false;
        request.enableBiEncoder = false;
        request.enableDiversity = false;
        request.enableOnnx = false;

        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

        assertEquals(1, response.results.size());
        assertEquals("WEB", response.results.get(0).source);
        assertEquals(0.42d, response.results.get(0).score, 1e-9);
        assertEquals(0.42d, (Double) response.results.get(0).meta.get("score"), 1e-9);
        assertFalse(String.valueOf(TraceStore.getAll()).contains("standalone web score sensitive text"));
    }

    @Test
    void kgAxisExposesV2AliasesWithoutDroppingLegacyKeys() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        ContentRetriever kgRetriever = query -> List.of(Content.from(TextSegment.from(
                "Alpha graph evidence",
                Metadata.from(Map.of(
                        "doc_id", "kg-alpha",
                        "kg_score", 0.77d)))));
        ReflectionTestUtils.setField(orchestrator, "kgRetriever", kgRetriever);

        UnifiedRagOrchestrator.QueryRequest request = new UnifiedRagOrchestrator.QueryRequest();
        request.query = "graph relation";
        request.topK = 3;
        request.useWeb = false;
        request.useVector = false;
        request.useKg = true;
        request.useBm25 = false;
        request.enableBiEncoder = false;
        request.enableDiversity = false;
        request.enableOnnx = false;

        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

        @SuppressWarnings("unchecked")
        Map<String, Object> kgAxis = (Map<String, Object>) response.debug.get("rag.eval.kgAxis");
        assertEquals(2, kgAxis.get("schemaVersion"));
        assertEquals(1, kgAxis.get("retrievedCount"));
        assertEquals(1, kgAxis.get("finalCount"));
        assertEquals(1, kgAxis.get("kgPoolCount"));
        assertEquals(1, kgAxis.get("kgFinalCount"));
        assertEquals(1.0d, (Double) kgAxis.get("finalRetention"), 1e-9);
        assertEquals(1.0d, (Double) kgAxis.get("kgFinalRetention"), 1e-9);
        assertEquals(0.77d, (Double) kgAxis.get("kgScoreMean"), 1e-9);
        assertEquals(0.77d, (Double) kgAxis.get("kgScoreP95"), 1e-9);
        assertEquals(false, kgAxis.get("graphOpportunity"));
        assertEquals(kgAxis, TraceStore.get("rag.eval.kgAxis"));
        assertEquals(2, TraceStore.get("rag.eval.kgAxis.schemaVersion"));
        assertEquals(1, TraceStore.get("rag.eval.kgAxis.kgPoolCount"));
    }

    @Test
    void kgAxisNeo4jFailureAddsThresholdBreakAndScorecardLabel() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        ContentRetriever kgRetriever = query -> List.of();
        ReflectionTestUtils.setField(orchestrator, "kgRetriever", kgRetriever);
        TraceStore.put("retrieval.kg.neo4j.status", "failed");
        TraceStore.put("retrieval.kg.neo4j.disabledReason", "neo4j_query_failed");
        TraceStore.put("retrieval.kg.neo4j.failureClass", "silent-failure");

        UnifiedRagOrchestrator.QueryRequest request = new UnifiedRagOrchestrator.QueryRequest();
        request.query = "graph relation";
        request.topK = 3;
        request.useWeb = false;
        request.useVector = false;
        request.useKg = true;
        request.useBm25 = false;
        request.enableBiEncoder = false;
        request.enableDiversity = false;
        request.enableOnnx = false;

        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

        @SuppressWarnings("unchecked")
        Map<String, Object> kgAxis = (Map<String, Object>) response.debug.get("rag.eval.kgAxis");
        assertEquals("failed", kgAxis.get("neo4jStatus"));
        assertEquals("neo4j_query_failed", kgAxis.get("neo4jDisabledReason"));
        assertEquals("silent-failure", kgAxis.get("neo4jFailureClass"));
        @SuppressWarnings("unchecked")
        List<String> signals = (List<String>) kgAxis.get("signals");
        assertTrue(signals.contains("kg_neo4j_failed") || signals.contains("kg_neo4j_degraded"),
                () -> String.valueOf(signals));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> thresholdBreaks =
                (List<Map<String, Object>>) response.debug.get("rag.eval.thresholdBreaks");
        assertTrue(thresholdBreaks.stream().anyMatch(row -> "kg_starvation".equals(row.get("label"))),
                () -> String.valueOf(thresholdBreaks));
        assertTrue(thresholdBreaks.stream().anyMatch(row ->
                        "kg_neo4j_degraded".equals(row.get("label"))
                                && "kgAxis.neo4jStatus".equals(row.get("metric"))
                                && "kg".equals(row.get("category"))
                                && "kg".equals(row.get("stage"))
                                && "failed".equals(row.get("neo4jStatus"))
                                && "silent-failure".equals(row.get("neo4jFailureClass"))),
                () -> String.valueOf(thresholdBreaks));
        @SuppressWarnings("unchecked")
        Map<String, Object> scorecard = (Map<String, Object>) response.debug.get("rag.eval.scorecard");
        @SuppressWarnings("unchecked")
        List<String> thresholdLabels = (List<String>) scorecard.get("thresholdLabels");
        assertTrue(thresholdLabels.contains("kg_starvation"), () -> String.valueOf(thresholdLabels));
        assertTrue(thresholdLabels.contains("kg_neo4j_degraded"), () -> String.valueOf(thresholdLabels));
        assertEquals(kgAxis, TraceStore.get("rag.eval.kgAxis"));
        assertEquals("failed", TraceStore.get("rag.eval.kgAxis.neo4jStatus"));
        assertEquals("neo4j_query_failed", TraceStore.get("rag.eval.kgAxis.neo4jDisabledReason"));
        assertEquals("silent-failure", TraceStore.get("rag.eval.kgAxis.neo4jFailureClass"));
        assertFalse(String.valueOf(response.debug).contains("graph relation"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("graph relation"));
    }

    @Test
    void relationThumbnailOverlapReranksKgDocsWithoutLeakingRawQuery() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        ContentRetriever kgRetriever = query -> List.of(
                Content.from(TextSegment.from(
                        "relationBreadcrumbs: Alpha -RELATIONSHIP_SUPPORTS-> Gamma",
                        Metadata.from(Map.of(
                                "doc_id", "kg-anchor-alpha",
                                "kg_score", 0.10d,
                                "kg_relation_thumbnail_mode", "relation_thumbnail_v1",
                                "kg_relation_thumbnail_hash", "thumb-alpha",
                                "kg_relation_breadcrumb_hashes", "bread-alpha")))),
                Content.from(TextSegment.from(
                        "relationBreadcrumbs: Delta -RELATIONSHIP_SUPPORTS-> Noise",
                        Metadata.from(Map.of(
                                "doc_id", "kg-anchor-delta",
                                "kg_score", 0.90d,
                                "kg_relation_thumbnail_mode", "relation_thumbnail_v1",
                                "kg_relation_thumbnail_hash", "thumb-delta",
                                "kg_relation_breadcrumb_hashes", "bread-delta")))));
        ReflectionTestUtils.setField(orchestrator, "kgRetriever", kgRetriever);

        UnifiedRagOrchestrator.QueryRequest request = new UnifiedRagOrchestrator.QueryRequest();
        request.query = "Alpha Gamma relationship sensitive";
        request.topK = 1;
        request.kgTopK = 2;
        request.useWeb = false;
        request.useVector = false;
        request.useKg = true;
        request.useBm25 = false;
        request.enableBiEncoder = false;
        request.enableDiversity = false;
        request.enableOnnx = false;

        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

        assertEquals(1, response.results.size());
        assertEquals("kg-anchor-alpha", response.results.get(0).meta.get("doc_id"));
        assertEquals(Boolean.TRUE, response.results.get(0).meta.get("kg_relation_thumbnail_selected"));
        assertEquals("applied", response.debug.get("retrieval.kg.relationThumbnail.status"));
        assertEquals(2, response.debug.get("retrieval.kg.relationThumbnail.candidateCount"));
        assertEquals(1, response.debug.get("retrieval.kg.relationThumbnail.selectedCount"));
        assertTrue(String.valueOf(response.debug.get("retrieval.kg.relationThumbnail.queryHash12"))
                .matches("[0-9a-f]{12}"));
        assertEquals("applied", TraceStore.get("retrieval.kg.relationThumbnail.status"));
        assertEquals(1, TraceStore.get("retrieval.kg.relationThumbnail.selectedCount"));
        assertFalse(String.valueOf(response.debug).contains("Alpha Gamma relationship sensitive"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("Alpha Gamma relationship sensitive"));
    }

    @Test
    void relationThumbnailTopHashNormalizesNonHexThumbnailLabel() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        ContentRetriever kgRetriever = query -> List.of(
                Content.from(TextSegment.from(
                        "relationBreadcrumbs: Alpha -RELATIONSHIP_SUPPORTS-> Gamma",
                        Metadata.from(Map.of(
                                "doc_id", "kg-anchor-alpha",
                                "kg_score", 0.10d,
                                "kg_relation_thumbnail_mode", "relation_thumbnail_v1",
                                "kg_relation_thumbnail_hash", "thumb-alpha",
                                "kg_relation_breadcrumb_hashes", "bread-alpha")))));
        ReflectionTestUtils.setField(orchestrator, "kgRetriever", kgRetriever);

        UnifiedRagOrchestrator.QueryRequest request = new UnifiedRagOrchestrator.QueryRequest();
        request.query = "Alpha Gamma relationship sensitive";
        request.topK = 1;
        request.kgTopK = 1;
        request.useWeb = false;
        request.useVector = false;
        request.useKg = true;
        request.useBm25 = false;
        request.enableBiEncoder = false;
        request.enableDiversity = false;
        request.enableOnnx = false;

        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

        String topHash = String.valueOf(response.debug.get("retrieval.kg.relationThumbnail.topHash"));
        assertTrue(topHash.matches("[0-9a-f]{12}"), () -> topHash);
        assertFalse(topHash.contains("thumb-alpha"));
        assertEquals(topHash, TraceStore.get("retrieval.kg.relationThumbnail.topHash"));
        @SuppressWarnings("unchecked")
        Map<String, Object> kgAxis = (Map<String, Object>) response.debug.get("rag.eval.kgAxis");
        assertEquals(topHash, kgAxis.get("relationThumbnailTopHash"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("thumb-alpha"));
        assertFalse(String.valueOf(response.debug).contains("Alpha Gamma relationship sensitive"));
    }

    @Test
    void relationThumbnailPromptContextUsesEmbeddedContextLineWithoutInjectingBody() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        ContentRetriever kgRetriever = query -> List.of(Content.from(TextSegment.from(
                "Noisy graph body SHOULD_NOT_INJECT_BODY.\n"
                        + "relationThumbnailContext: Alpha --RELATIONSHIP_SUPPORTS--> Gamma",
                Metadata.from(Map.of(
                        "doc_id", "kg-context-line-alpha",
                        "kg_score", 0.10d,
                        "kg_relation_thumbnail_mode", "relation_thumbnail_v1",
                        "kg_relation_thumbnail_hash", "thumb-context-line-alpha")))));
        ReflectionTestUtils.setField(orchestrator, "kgRetriever", kgRetriever);

        UnifiedRagOrchestrator.QueryRequest request = new UnifiedRagOrchestrator.QueryRequest();
        request.query = "Alpha Gamma relationship sensitive";
        request.topK = 1;
        request.kgTopK = 1;
        request.useWeb = false;
        request.useVector = false;
        request.useKg = true;
        request.useBm25 = false;
        request.enableBiEncoder = false;
        request.enableDiversity = false;
        request.enableOnnx = false;

        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

        assertEquals(1, response.results.size());
        UnifiedRagOrchestrator.Doc selected = response.results.get(0);
        assertEquals("breadcrumb", selected.meta.get("kg_relation_prompt_context_layer"));
        assertTrue(selected.snippet.startsWith("relationThumbnailContext:"));
        assertTrue(selected.snippet.contains("Alpha --RELATIONSHIP_SUPPORTS--> Gamma"));
        assertFalse(selected.snippet.contains("SHOULD_NOT_INJECT_BODY"));
        assertFalse(String.valueOf(response.debug).contains("Alpha Gamma relationship sensitive"));
    }

    @Test
    void relationThumbnailOverlapIgnoresNoisyBodyWhenBreadcrumbDoesNotMatch() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        ContentRetriever kgRetriever = query -> List.of(
                Content.from(TextSegment.from(
                        "Noisy KG body mentions Alpha Gamma many times but relation anchor is unrelated.\n"
                                + "relationBreadcrumbs: Delta -RELATIONSHIP_SUPPORTS-> Noise",
                        Metadata.from(Map.of(
                                "doc_id", "kg-noisy-body",
                                "kg_score", 0.99d,
                                "kg_relation_thumbnail_mode", "relation_thumbnail_v1",
                                "kg_relation_thumbnail_hash", "thumb-noisy",
                                "kg_relation_breadcrumb", "Delta -RELATIONSHIP_SUPPORTS-> Noise",
                                "kg_relation_breadcrumb_hashes", "bread-noisy")))),
                Content.from(TextSegment.from(
                        "Small KG body.\nrelationBreadcrumbs: Alpha -RELATIONSHIP_SUPPORTS-> Gamma",
                        Metadata.from(Map.of(
                                "doc_id", "kg-anchor-alpha",
                                "kg_score", 0.10d,
                                "kg_relation_thumbnail_mode", "relation_thumbnail_v1",
                                "kg_relation_thumbnail_hash", "thumb-alpha",
                                "kg_relation_breadcrumb", "Alpha -RELATIONSHIP_SUPPORTS-> Gamma",
                                "kg_relation_breadcrumb_hashes", "bread-alpha")))));
        ReflectionTestUtils.setField(orchestrator, "kgRetriever", kgRetriever);

        UnifiedRagOrchestrator.QueryRequest request = new UnifiedRagOrchestrator.QueryRequest();
        request.query = "Alpha Gamma relationship sensitive";
        request.topK = 1;
        request.kgTopK = 2;
        request.useWeb = false;
        request.useVector = false;
        request.useKg = true;
        request.useBm25 = false;
        request.enableBiEncoder = false;
        request.enableDiversity = false;
        request.enableOnnx = false;

        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

        assertEquals(1, response.results.size());
        assertEquals("kg-anchor-alpha", response.results.get(0).meta.get("doc_id"));
        assertFalse(response.results.stream()
                .anyMatch(doc -> "kg-noisy-body".equals(doc.meta.get("doc_id"))));
        assertEquals(1, response.debug.get("retrieval.kg.relationThumbnail.selectedCount"));
        assertEquals(1, response.debug.get("retrieval.kg.relationThumbnail.droppedCount"));
        assertFalse(String.valueOf(response.debug).contains("Alpha Gamma relationship sensitive"));
    }

    @Test
    void relationThumbnailOverlapIgnoresGenericRelationWordWhenOnlyOneEndpointMatches() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        ContentRetriever kgRetriever = query -> List.of(
                relationThumbnail("kg-noise-gamma", "Noise", "Gamma", 0.99d),
                relationThumbnail("kg-anchor-alpha", "Alpha", "Gamma", 0.10d));
        ReflectionTestUtils.setField(orchestrator, "kgRetriever", kgRetriever);

        UnifiedRagOrchestrator.QueryRequest request = new UnifiedRagOrchestrator.QueryRequest();
        request.query = "Alpha Gamma relationship sensitive";
        request.topK = 2;
        request.kgTopK = 2;
        request.useWeb = false;
        request.useVector = false;
        request.useKg = true;
        request.useBm25 = false;
        request.enableBiEncoder = false;
        request.enableDiversity = false;
        request.enableOnnx = false;

        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

        assertEquals(1, response.results.size());
        assertEquals("kg-anchor-alpha", response.results.get(0).meta.get("doc_id"));
        assertFalse(response.results.stream()
                .anyMatch(doc -> "kg-noise-gamma".equals(doc.meta.get("doc_id"))));
        assertEquals(1, response.debug.get("retrieval.kg.relationThumbnail.selectedCount"));
        assertEquals(1, response.debug.get("retrieval.kg.relationThumbnail.droppedCount"));
        assertEquals(1, TraceStore.get("retrieval.kg.relationThumbnail.droppedCount"));
        assertFalse(String.valueOf(response.debug).contains("Alpha Gamma relationship sensitive"));
    }

    @Test
    void relationThumbnailOverlapIgnoresNoisyBodyWhenSummaryDoesNotMatch() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        ContentRetriever kgRetriever = query -> List.of(
                Content.from(TextSegment.from(
                        "Noisy KG body mentions Alpha Gamma many times but relation summary is unrelated.\n"
                                + "relationSummary: Delta supports Noise through compact relation summary.",
                        Metadata.from(Map.of(
                                "doc_id", "kg-summary-noisy",
                                "kg_score", 0.99d,
                                "kg_relation_thumbnail_mode", "relation_thumbnail_v1",
                                "kg_relation_thumbnail_hash", "thumb-summary-noisy")))),
                Content.from(TextSegment.from(
                        "Small KG body.\nrelationSummary: Alpha supports Gamma through compact relation summary.",
                        Metadata.from(Map.of(
                                "doc_id", "kg-summary-alpha",
                                "kg_score", 0.10d,
                                "kg_relation_thumbnail_mode", "relation_thumbnail_v1",
                                "kg_relation_thumbnail_hash", "thumb-summary-alpha")))));
        ReflectionTestUtils.setField(orchestrator, "kgRetriever", kgRetriever);

        UnifiedRagOrchestrator.QueryRequest request = new UnifiedRagOrchestrator.QueryRequest();
        request.query = "Alpha Gamma relationship sensitive";
        request.topK = 2;
        request.kgTopK = 2;
        request.useWeb = false;
        request.useVector = false;
        request.useKg = true;
        request.useBm25 = false;
        request.enableBiEncoder = false;
        request.enableDiversity = false;
        request.enableOnnx = false;

        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

        assertEquals(1, response.results.size());
        UnifiedRagOrchestrator.Doc selected = response.results.get(0);
        assertEquals("kg-summary-alpha", selected.meta.get("doc_id"));
        assertFalse(response.results.stream()
                .anyMatch(doc -> "kg-summary-noisy".equals(doc.meta.get("doc_id"))));
        assertEquals("summary", selected.meta.get("kg_relation_prompt_context_layer"));
        assertTrue(selected.snippet.startsWith("relationThumbnailContext:"));
        assertTrue(selected.snippet.contains("Alpha supports Gamma through compact relation summary."));
        assertFalse(selected.snippet.contains("Small KG body"));
        assertFalse(selected.snippet.contains("Noisy KG body"));
        assertEquals(1, response.debug.get("retrieval.kg.relationThumbnail.droppedCount"));
        assertFalse(String.valueOf(response.debug).contains("Alpha Gamma relationship sensitive"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void kgAxisExposesRelationThumbnailContextLayerMix() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        ContentRetriever kgRetriever = query -> List.of(
                Content.from(TextSegment.from(
                        "relationBreadcrumbs: Alpha --RELATIONSHIP_SUPPORTS--> Gamma",
                        Metadata.from(Map.of(
                                "doc_id", "kg-layer-breadcrumb",
                                "kg_score", 0.20d,
                                "kg_relation_thumbnail_mode", "relation_thumbnail_v1",
                                "kg_relation_thumbnail_hash", "aaaaaaaaaaaa")))),
                Content.from(TextSegment.from(
                        "Compact relation body.\nrelationSummary: Beta supports Delta through compact relation summary.",
                        Metadata.from(Map.of(
                                "doc_id", "kg-layer-summary",
                                "kg_score", 0.10d,
                                "kg_relation_thumbnail_mode", "relation_thumbnail_v1",
                                "kg_relation_thumbnail_hash", "bbbbbbbbbbbb")))));
        ReflectionTestUtils.setField(orchestrator, "kgRetriever", kgRetriever);

        UnifiedRagOrchestrator.QueryRequest request = new UnifiedRagOrchestrator.QueryRequest();
        request.query = "Alpha Gamma Beta Delta relationship sensitive";
        request.topK = 2;
        request.kgTopK = 2;
        request.useWeb = false;
        request.useVector = false;
        request.useKg = true;
        request.useBm25 = false;
        request.enableBiEncoder = false;
        request.enableDiversity = false;
        request.enableOnnx = false;

        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

        Map<String, Object> kgAxis = (Map<String, Object>) response.debug.get("rag.eval.kgAxis");
        assertEquals(List.of("breadcrumb", "summary"), kgAxis.get("relationThumbnailContextLayers"));
        assertEquals(Map.of("breadcrumb", 1, "summary", 1), kgAxis.get("relationThumbnailContextLayerCounts"));
        List<String> signals = (List<String>) kgAxis.get("signals");
        assertTrue(signals.contains("kg_relation_thumbnail_breadcrumb_layer"), () -> String.valueOf(signals));
        assertTrue(signals.contains("kg_relation_thumbnail_summary_layer"), () -> String.valueOf(signals));
        assertEquals(List.of("breadcrumb", "summary"),
                TraceStore.get("rag.eval.kgAxis.relationThumbnailContextLayers"));
        assertEquals(Map.of("breadcrumb", 1, "summary", 1),
                TraceStore.get("rag.eval.kgAxis.relationThumbnailContextLayerCounts"));
        assertFalse(String.valueOf(kgAxis).contains("Alpha Gamma Beta Delta relationship sensitive"));
    }

    @Test
    void relationThumbnailSliceDropsUnmatchedThumbnailCandidates() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        ContentRetriever kgRetriever = query -> List.of(
                Content.from(TextSegment.from(
                        "relationBreadcrumbs: Alpha -RELATIONSHIP_SUPPORTS-> Gamma",
                        Metadata.from(Map.of(
                                "doc_id", "kg-anchor-alpha",
                                "kg_score", 0.10d,
                                "kg_relation_thumbnail_mode", "relation_thumbnail_v1",
                                "kg_relation_thumbnail_hash", "thumb-alpha",
                                "kg_relation_breadcrumb_hashes", "bread-alpha")))),
                Content.from(TextSegment.from(
                        "relationBreadcrumbs: Delta -RELATIONSHIP_SUPPORTS-> Noise",
                        Metadata.from(Map.of(
                                "doc_id", "kg-anchor-delta",
                                "kg_score", 0.90d,
                                "kg_relation_thumbnail_mode", "relation_thumbnail_v1",
                                "kg_relation_thumbnail_hash", "thumb-delta",
                                "kg_relation_breadcrumb_hashes", "bread-delta")))));
        ReflectionTestUtils.setField(orchestrator, "kgRetriever", kgRetriever);

        UnifiedRagOrchestrator.QueryRequest request = new UnifiedRagOrchestrator.QueryRequest();
        request.query = "Alpha Gamma relationship sensitive";
        request.topK = 2;
        request.kgTopK = 2;
        request.useWeb = false;
        request.useVector = false;
        request.useKg = true;
        request.useBm25 = false;
        request.enableBiEncoder = false;
        request.enableDiversity = false;
        request.enableOnnx = false;

        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

        assertEquals(1, response.results.size());
        assertEquals("kg-anchor-alpha", response.results.get(0).meta.get("doc_id"));
        assertFalse(response.results.stream()
                .anyMatch(doc -> "kg-anchor-delta".equals(doc.meta.get("doc_id"))));
        assertEquals(1, response.debug.get("retrieval.kg.relationThumbnail.droppedCount"));
        assertEquals(1, TraceStore.get("retrieval.kg.relationThumbnail.droppedCount"));
        assertFalse(String.valueOf(response.debug).contains("Alpha Gamma relationship sensitive"));
    }

    @Test
    void relationThumbnailPrefetchKeepsRelevantSubgraphCandidateBeyondDefaultKgCut() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        ContentRetriever kgRetriever = query -> List.of(
                relationThumbnail("kg-noise-1", "NoiseOne", "DistractorOne", 0.99d),
                relationThumbnail("kg-noise-2", "NoiseTwo", "DistractorTwo", 0.98d),
                relationThumbnail("kg-noise-3", "NoiseThree", "DistractorThree", 0.97d),
                relationThumbnail("kg-noise-4", "NoiseFour", "DistractorFour", 0.96d),
                relationThumbnail("kg-anchor-alpha", "Alpha", "Gamma", 0.10d));
        ReflectionTestUtils.setField(orchestrator, "kgRetriever", kgRetriever);

        UnifiedRagOrchestrator.QueryRequest request = new UnifiedRagOrchestrator.QueryRequest();
        request.query = "Alpha Gamma relationship sensitive";
        request.topK = 8;
        request.useWeb = false;
        request.useVector = false;
        request.useKg = true;
        request.useBm25 = false;
        request.enableBiEncoder = false;
        request.enableDiversity = false;
        request.enableOnnx = false;

        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

        assertEquals(1, response.results.size());
        UnifiedRagOrchestrator.Doc selected = response.results.get(0);
        assertEquals("kg-anchor-alpha", selected.meta.get("doc_id"));
        assertEquals(Boolean.TRUE, selected.meta.get("kg_relation_thumbnail_selected"));
        assertEquals(5, response.debug.get("retrieval.kg.relationThumbnail.candidateCount"));
        assertEquals(1, response.debug.get("retrieval.kg.relationThumbnail.selectedCount"));
        assertEquals(4, response.debug.get("retrieval.kg.relationThumbnail.droppedCount"));
        assertEquals(16, response.debug.get("retrieval.kg.relationThumbnail.prefetchK"));
        assertFalse(String.valueOf(response.debug).contains("Alpha Gamma relationship sensitive"));
    }

    @Test
    void relationThumbnailNoOverlapDropsOnlyThumbnailCandidates() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        ContentRetriever kgRetriever = query -> List.of(
                Content.from(TextSegment.from(
                        "relationBreadcrumbs: Delta -RELATIONSHIP_SUPPORTS-> Noise",
                        Metadata.from(Map.of(
                                "doc_id", "kg-thumb-delta",
                                "kg_score", 0.99d,
                                "kg_relation_thumbnail_mode", "relation_thumbnail_v1",
                                "kg_relation_thumbnail_hash", "thumb-delta",
                                "kg_relation_breadcrumb_hashes", "bread-delta")))),
                Content.from(TextSegment.from(
                        "relationBreadcrumbs: Epsilon -RELATIONSHIP_CONTRASTS-> Zeta",
                        Metadata.from(Map.of(
                                "doc_id", "kg-thumb-epsilon",
                                "kg_score", 0.98d,
                                "kg_relation_thumbnail_mode", "relation_thumbnail_v1",
                                "kg_relation_thumbnail_hash", "thumb-epsilon",
                                "kg_relation_breadcrumb_hashes", "bread-epsilon")))),
                Content.from(TextSegment.from(
                        "General KG support fact without relation thumbnail metadata.",
                        Metadata.from(Map.of(
                                "doc_id", "kg-general",
                                "kg_score", 0.20d)))));
        ReflectionTestUtils.setField(orchestrator, "kgRetriever", kgRetriever);

        UnifiedRagOrchestrator.QueryRequest request = new UnifiedRagOrchestrator.QueryRequest();
        request.query = "Alpha Gamma relationship sensitive";
        request.topK = 3;
        request.kgTopK = 3;
        request.useWeb = false;
        request.useVector = false;
        request.useKg = true;
        request.useBm25 = false;
        request.enableBiEncoder = false;
        request.enableDiversity = false;
        request.enableOnnx = false;

        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

        assertEquals(1, response.results.size());
        assertEquals("kg-general", response.results.get(0).meta.get("doc_id"));
        assertFalse(response.results.stream()
                .anyMatch(doc -> String.valueOf(doc.meta.get("doc_id")).startsWith("kg-thumb-")));
        assertEquals("no_overlap", response.debug.get("retrieval.kg.relationThumbnail.status"));
        assertEquals(2, response.debug.get("retrieval.kg.relationThumbnail.candidateCount"));
        assertEquals(0, response.debug.get("retrieval.kg.relationThumbnail.selectedCount"));
        assertEquals(2, response.debug.get("retrieval.kg.relationThumbnail.droppedCount"));
        @SuppressWarnings("unchecked")
        Map<String, Object> kgAxis = (Map<String, Object>) response.debug.get("rag.eval.kgAxis");
        @SuppressWarnings("unchecked")
        List<String> signals = (List<String>) kgAxis.get("signals");
        assertTrue(signals.contains("kg_relation_thumbnail_no_selection"), () -> String.valueOf(signals));
        assertTrue(signals.contains("kg_relation_thumbnail_sliced"), () -> String.valueOf(signals));
        assertEquals("no_overlap", TraceStore.get("retrieval.kg.relationThumbnail.status"));
        assertEquals(2, TraceStore.get("retrieval.kg.relationThumbnail.droppedCount"));
        assertFalse(String.valueOf(response.debug).contains("Alpha Gamma relationship sensitive"));
    }

    @Test
    void relationThumbnailNoQueryTokensDropsOnlyThumbnailCandidates() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        ContentRetriever kgRetriever = query -> List.of(
                relationThumbnail("kg-thumb-delta", "Delta", "Noise", 0.99d),
                relationThumbnail("kg-thumb-epsilon", "Epsilon", "Zeta", 0.98d),
                Content.from(TextSegment.from(
                        "General KG support fact without relation thumbnail metadata.",
                        Metadata.from(Map.of(
                                "doc_id", "kg-general",
                                "kg_score", 0.20d)))));
        ReflectionTestUtils.setField(orchestrator, "kgRetriever", kgRetriever);

        UnifiedRagOrchestrator.QueryRequest request = new UnifiedRagOrchestrator.QueryRequest();
        request.query = "the and for with from into about this that 관계 관련 질문 요약 근거";
        request.topK = 3;
        request.kgTopK = 3;
        request.useWeb = false;
        request.useVector = false;
        request.useKg = true;
        request.useBm25 = false;
        request.enableBiEncoder = false;
        request.enableDiversity = false;
        request.enableOnnx = false;

        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

        assertEquals(1, response.results.size());
        assertEquals("kg-general", response.results.get(0).meta.get("doc_id"));
        assertFalse(response.results.stream()
                .anyMatch(doc -> String.valueOf(doc.meta.get("doc_id")).startsWith("kg-thumb-")));
        assertEquals("skipped:no_query_tokens", response.debug.get("retrieval.kg.relationThumbnail.status"));
        assertEquals(2, response.debug.get("retrieval.kg.relationThumbnail.candidateCount"));
        assertEquals(0, response.debug.get("retrieval.kg.relationThumbnail.selectedCount"));
        assertEquals(2, response.debug.get("retrieval.kg.relationThumbnail.droppedCount"));
        @SuppressWarnings("unchecked")
        Map<String, Object> kgAxis = (Map<String, Object>) response.debug.get("rag.eval.kgAxis");
        @SuppressWarnings("unchecked")
        List<String> signals = (List<String>) kgAxis.get("signals");
        assertTrue(signals.contains("kg_relation_thumbnail_no_selection"), () -> String.valueOf(signals));
        assertTrue(signals.contains("kg_relation_thumbnail_sliced"), () -> String.valueOf(signals));
        assertEquals("skipped:no_query_tokens", TraceStore.get("retrieval.kg.relationThumbnail.status"));
        assertEquals(2, TraceStore.get("retrieval.kg.relationThumbnail.droppedCount"));
        assertFalse(String.valueOf(response.debug).contains("the and for with from into"));
    }

    @Test
    void relationThumbnailNoQueryTokensStillUsesAnchorSeed() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        TraceStore.put("retrieval.kg.brainState.queryAnchorMap.seedHashes", List.of("abcdef123456"));
        ContentRetriever kgRetriever = query -> List.of(
                Content.from(TextSegment.from(
                        "relationBreadcrumbs: NoiseOne -RELATIONSHIP_SUPPORTS-> DistractorOne",
                        Metadata.from(Map.of(
                                "doc_id", "kg-anchor-noise",
                                "kg_score", 0.99d,
                                "kg_relation_thumbnail_mode", "relation_thumbnail_v1",
                                "kg_relation_anchor_hash12", "000000000000",
                                "kg_relation_breadcrumb_hashes", "111111111111")))),
                Content.from(TextSegment.from(
                        "relationBreadcrumbs: SparseAlias -RELATIONSHIP_SUPPORTS-> HiddenNode",
                        Metadata.from(Map.of(
                                "doc_id", "kg-anchor-sparse",
                                "kg_score", 0.10d,
                                "kg_relation_thumbnail_mode", "relation_thumbnail_v1",
                                "kg_relation_anchor_hash12", "abcdef123456",
                                "kg_relation_breadcrumb_hashes", "222222222222")))));
        ReflectionTestUtils.setField(orchestrator, "kgRetriever", kgRetriever);

        UnifiedRagOrchestrator.QueryRequest request = new UnifiedRagOrchestrator.QueryRequest();
        request.query = "the and for with from into about this that 관계 관련 질문 요약 근거";
        request.topK = 2;
        request.kgTopK = 2;
        request.useWeb = false;
        request.useVector = false;
        request.useKg = true;
        request.useBm25 = false;
        request.enableBiEncoder = false;
        request.enableDiversity = false;
        request.enableOnnx = false;

        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

        assertEquals(1, response.results.size());
        UnifiedRagOrchestrator.Doc selected = response.results.get(0);
        assertEquals("kg-anchor-sparse", selected.meta.get("doc_id"));
        assertEquals(Boolean.TRUE, selected.meta.get("kg_relation_thumbnail_selected"));
        assertEquals("anchor_seed", selected.meta.get("kg_relation_thumbnail_selection_reason"));
        assertEquals("applied", response.debug.get("retrieval.kg.relationThumbnail.status"));
        assertEquals(2, response.debug.get("retrieval.kg.relationThumbnail.candidateCount"));
        assertEquals(1, response.debug.get("retrieval.kg.relationThumbnail.selectedCount"));
        assertEquals(1, response.debug.get("retrieval.kg.relationThumbnail.droppedCount"));
        assertEquals("anchor_seed", response.debug.get("retrieval.kg.relationThumbnail.selectionReason"));
        @SuppressWarnings("unchecked")
        Map<String, Object> kgAxis = (Map<String, Object>) response.debug.get("rag.eval.kgAxis");
        assertEquals(true, kgAxis.get("relationThumbnailAnchorSeedUsed"));
        assertEquals("anchor_seed", TraceStore.get("retrieval.kg.relationThumbnail.selectionReason"));
        assertFalse(String.valueOf(response.debug).contains("the and for with from into"));
    }

    @Test
    void relationThumbnailAnchorSeedSelectsSparseRelationWithoutTextOverlap() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        TraceStore.put("retrieval.kg.brainState.queryAnchorMap.applied", true);
        TraceStore.put("retrieval.kg.brainState.queryAnchorMap.seedHashes", List.of("abcdef123456"));
        ContentRetriever kgRetriever = query -> List.of(
                Content.from(TextSegment.from(
                        "relationBreadcrumbs: UnrelatedNoise --RELATIONSHIP_SUPPORTS-> Distractor",
                        Metadata.from(Map.of(
                                "doc_id", "kg-anchor-noise",
                                "kg_score", 0.90d,
                                "kg_relation_thumbnail_mode", "relation_thumbnail_v1",
                                "kg_relation_anchor_hash12", "000000000000",
                                "kg_relation_breadcrumb_hashes", "111111111111")))),
                Content.from(TextSegment.from(
                        "relationBreadcrumbs: SparseAlias --RELATIONSHIP_SUPPORTS-> HiddenNode",
                        Metadata.from(Map.of(
                                "doc_id", "kg-anchor-sparse",
                                "kg_score", 0.10d,
                                "kg_relation_thumbnail_mode", "relation_thumbnail_v1",
                                "kg_relation_anchor_hash12", "abcdef123456",
                                "kg_relation_breadcrumb_hashes", "222222222222")))));
        ReflectionTestUtils.setField(orchestrator, "kgRetriever", kgRetriever);

        UnifiedRagOrchestrator.QueryRequest request = new UnifiedRagOrchestrator.QueryRequest();
        request.query = "please use the mapped anchor";
        request.topK = 2;
        request.kgTopK = 2;
        request.useWeb = false;
        request.useVector = false;
        request.useKg = true;
        request.useBm25 = false;
        request.enableBiEncoder = false;
        request.enableDiversity = false;
        request.enableOnnx = false;

        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

        assertEquals(1, response.results.size());
        UnifiedRagOrchestrator.Doc selected = response.results.get(0);
        assertEquals("kg-anchor-sparse", selected.meta.get("doc_id"));
        assertEquals(Boolean.TRUE, selected.meta.get("kg_relation_thumbnail_selected"));
        assertEquals("anchor_seed", selected.meta.get("kg_relation_thumbnail_selection_reason"));
        assertEquals("anchor_seed", response.debug.get("retrieval.kg.relationThumbnail.selectionReason"));
        assertEquals("anchor_seed", TraceStore.get("retrieval.kg.relationThumbnail.selectionReason"));
        @SuppressWarnings("unchecked")
        Map<String, Object> kgAxis = (Map<String, Object>) response.debug.get("rag.eval.kgAxis");
        assertEquals(2, kgAxis.get("relationThumbnailCandidateCount"));
        assertEquals(1, kgAxis.get("relationThumbnailSelectedCount"));
        assertEquals(1, kgAxis.get("relationThumbnailDroppedCount"));
        assertEquals(0.5d, (Double) kgAxis.get("relationThumbnailSelectionRate"), 1e-9);
        assertEquals(0.5d, (Double) kgAxis.get("relationThumbnailDropRate"), 1e-9);
        assertEquals("anchor_seed", kgAxis.get("relationThumbnailSelectionReason"));
        assertEquals(true, kgAxis.get("relationThumbnailAnchorSeedUsed"));
        @SuppressWarnings("unchecked")
        Map<String, Object> scorecard = (Map<String, Object>) response.debug.get("rag.eval.scorecard");
        assertEquals(kgAxis, scorecard.get("kgAxis"));
        assertEquals("anchor_seed", TraceStore.get("rag.eval.kgAxis.relationThumbnailSelectionReason"));
        assertEquals(1, TraceStore.get("rag.eval.kgAxis.relationThumbnailDroppedCount"));
        assertFalse(String.valueOf(response.debug).contains("please use the mapped anchor"));
    }

    @Test
    void relationThumbnailAnchorSeedUsesSparsePortMappingHash() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        TraceStore.put("retrieval.kg.brainState.queryAnchorMap.applied", true);
        TraceStore.put("retrieval.kg.brainState.portMappingHashes", List.of("feedface1234"));
        ContentRetriever kgRetriever = query -> List.of(
                Content.from(TextSegment.from(
                        "relationBreadcrumbs: Irrelevant --UAW_THUMBNAIL_RELATED_TO--> Noise",
                        Metadata.from(Map.of(
                                "doc_id", "kg-port-noise",
                                "kg_score", 0.90d,
                                "kg_relation_thumbnail_mode", "relation_thumbnail_v1",
                                "kg_relation_thumbnail_hash", "000000000000")))),
                Content.from(TextSegment.from(
                        "relationBreadcrumbs: PairAlias --UAW_THUMBNAIL_RELATED_TO--> HiddenNode",
                        Metadata.from(Map.of(
                                "doc_id", "kg-port-pair",
                                "kg_score", 0.10d,
                                "kg_relation_thumbnail_mode", "relation_thumbnail_v1",
                                "kg_relation_thumbnail_hash", "feedface1234")))));
        ReflectionTestUtils.setField(orchestrator, "kgRetriever", kgRetriever);

        UnifiedRagOrchestrator.QueryRequest request = new UnifiedRagOrchestrator.QueryRequest();
        request.query = "please use mapped connector";
        request.topK = 2;
        request.kgTopK = 2;
        request.useWeb = false;
        request.useVector = false;
        request.useKg = true;
        request.useBm25 = false;
        request.enableBiEncoder = false;
        request.enableDiversity = false;
        request.enableOnnx = false;

        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

        assertEquals(1, response.results.size());
        UnifiedRagOrchestrator.Doc selected = response.results.get(0);
        assertEquals("kg-port-pair", selected.meta.get("doc_id"));
        assertEquals(Boolean.TRUE, selected.meta.get("kg_relation_thumbnail_selected"));
        assertEquals("anchor_seed", selected.meta.get("kg_relation_thumbnail_selection_reason"));
        @SuppressWarnings("unchecked")
        Map<String, Object> kgAxis = (Map<String, Object>) response.debug.get("rag.eval.kgAxis");
        assertEquals(List.of("feedface1234"), kgAxis.get("sparseNodePortMappingHashes"));
        assertEquals("anchor_seed", kgAxis.get("relationThumbnailSelectionReason"));
        assertEquals("anchor_seed", TraceStore.get("rag.eval.kgAxis.relationThumbnailSelectionReason"));
        assertFalse(String.valueOf(response.debug).contains("please use mapped connector"));
    }

    @Test
    void relationThumbnailSelectedKgDocInjectsClippedPromptContext() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        ContentRetriever kgRetriever = query -> List.of(
                Content.from(TextSegment.from(
                        "Long KG body that should not be injected. UNRELATED_CONTEXT_SHOULD_BE_CLIPPED.\n"
                                + "relationBreadcrumbs: Alpha -RELATIONSHIP_SUPPORTS-> Gamma",
                        Metadata.from(Map.of(
                                "doc_id", "kg-anchor-alpha",
                                "kg_score", 0.10d,
                                "kg_relation_thumbnail_mode", "relation_thumbnail_v1",
                                "kg_relation_thumbnail_hash", "thumb-alpha",
                                "kg_relation_breadcrumb_hashes", "bread-alpha")))),
                Content.from(TextSegment.from(
                        "relationBreadcrumbs: Delta -RELATIONSHIP_SUPPORTS-> Noise",
                        Metadata.from(Map.of(
                                "doc_id", "kg-anchor-delta",
                                "kg_score", 0.90d,
                                "kg_relation_thumbnail_mode", "relation_thumbnail_v1",
                                "kg_relation_thumbnail_hash", "thumb-delta",
                                "kg_relation_breadcrumb_hashes", "bread-delta")))));
        ReflectionTestUtils.setField(orchestrator, "kgRetriever", kgRetriever);

        UnifiedRagOrchestrator.QueryRequest request = new UnifiedRagOrchestrator.QueryRequest();
        request.query = "Alpha Gamma relationship sensitive";
        request.topK = 1;
        request.kgTopK = 2;
        request.useWeb = false;
        request.useVector = false;
        request.useKg = true;
        request.useBm25 = false;
        request.enableBiEncoder = false;
        request.enableDiversity = false;
        request.enableOnnx = false;

        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

        assertEquals(1, response.results.size());
        UnifiedRagOrchestrator.Doc selected = response.results.get(0);
        assertEquals("kg-anchor-alpha", selected.meta.get("doc_id"));
        assertEquals(Boolean.TRUE, selected.meta.get("kg_relation_thumbnail_selected"));
        assertEquals("relation_thumbnail_context_v1", selected.meta.get("kg_relation_prompt_context_mode"));
        assertTrue(selected.snippet.startsWith("relationThumbnailContext:"));
        assertTrue(selected.snippet.contains("Alpha -RELATIONSHIP_SUPPORTS-> Gamma"));
        assertFalse(selected.snippet.contains("UNRELATED_CONTEXT_SHOULD_BE_CLIPPED"));
        assertFalse(String.valueOf(response.debug).contains("Alpha Gamma relationship sensitive"));
    }

    @Test
    void relationThumbnailSliceMapSummarizesSelectedSubgraphWithoutRawEntities() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        ContentRetriever kgRetriever = query -> List.of(
                Content.from(TextSegment.from(
                        "relationBreadcrumbs: Alpha --RELATIONSHIP_SUPPORTS--> Gamma",
                        Metadata.from(Map.of(
                                "doc_id", "kg-map-alpha",
                                "kg_score", 0.30d,
                                "kg_relation_thumbnail_mode", "relation_thumbnail_v1",
                                "kg_relation_thumbnail_hash", "aaaaaaaaaaaa",
                                "kg_relation_breadcrumb_hashes", "111111111111")))),
                Content.from(TextSegment.from(
                        "relationBreadcrumbs: Beta --RELATIONSHIP_CONTRASTS--> Delta",
                        Metadata.from(Map.of(
                                "doc_id", "kg-map-beta",
                                "kg_score", 0.20d,
                                "kg_relation_thumbnail_mode", "relation_thumbnail_v1",
                                "kg_relation_thumbnail_hash", "bbbbbbbbbbbb",
                                "kg_relation_breadcrumb_hashes", "222222222222")))),
                Content.from(TextSegment.from(
                        "relationBreadcrumbs: Noise --RELATIONSHIP_SUPPORTS--> Distractor",
                        Metadata.from(Map.of(
                                "doc_id", "kg-map-noise",
                                "kg_score", 0.90d,
                                "kg_relation_thumbnail_mode", "relation_thumbnail_v1",
                                "kg_relation_thumbnail_hash", "cccccccccccc",
                                "kg_relation_breadcrumb_hashes", "333333333333")))));
        ReflectionTestUtils.setField(orchestrator, "kgRetriever", kgRetriever);

        UnifiedRagOrchestrator.QueryRequest request = new UnifiedRagOrchestrator.QueryRequest();
        request.query = "Alpha Gamma Beta Delta relationship sensitive";
        request.topK = 3;
        request.kgTopK = 3;
        request.useWeb = false;
        request.useVector = false;
        request.useKg = true;
        request.useBm25 = false;
        request.enableBiEncoder = false;
        request.enableDiversity = false;
        request.enableOnnx = false;

        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sliceMap =
                (List<Map<String, Object>>) response.debug.get("retrieval.kg.relationThumbnail.sliceMap");
        assertFalse(sliceMap == null);
        assertEquals(2, sliceMap.size());
        assertEquals("aaaaaaaaaaaa", sliceMap.get(0).get("hash"));
        assertEquals("RELATIONSHIP_SUPPORTS", sliceMap.get(0).get("relationKind"));
        assertEquals("breadcrumb", sliceMap.get(0).get("contextLayer"));
        assertEquals("token_overlap", sliceMap.get(0).get("selectionReason"));
        assertEquals("bbbbbbbbbbbb", sliceMap.get(1).get("hash"));
        assertEquals("RELATIONSHIP_CONTRASTS", sliceMap.get(1).get("relationKind"));
        assertEquals(2, TraceStore.get("retrieval.kg.relationThumbnail.sliceMapCount"));
        assertEquals(sliceMap, TraceStore.get("retrieval.kg.relationThumbnail.sliceMap"));
        assertFalse(String.valueOf(sliceMap).contains("Alpha"));
        assertFalse(String.valueOf(sliceMap).contains("Beta"));
        assertFalse(String.valueOf(sliceMap).contains("relationship sensitive"));

        @SuppressWarnings("unchecked")
        Map<String, Object> kgAxis = (Map<String, Object>) response.debug.get("rag.eval.kgAxis");
        @SuppressWarnings("unchecked")
        List<String> signals = (List<String>) kgAxis.get("signals");
        assertTrue(signals.contains("kg_relation_thumbnail_applied"), () -> String.valueOf(signals));
        assertTrue(signals.contains("kg_relation_thumbnail_sliced"), () -> String.valueOf(signals));
        assertEquals("applied", kgAxis.get("relationThumbnailStatus"));
        assertEquals("token_overlap", kgAxis.get("relationThumbnailSelectionReason"));
        assertEquals(1, kgAxis.get("relationThumbnailDroppedCount"));
        assertEquals("applied", TraceStore.get("rag.eval.kgAxis.relationThumbnailStatus"));
        assertEquals(1, TraceStore.get("rag.eval.kgAxis.relationThumbnailDroppedCount"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void relationThumbnailTraceClearsSliceMapWhenNextKgQueryHasNoThumbnailCandidates() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        ContentRetriever firstRetriever = query -> List.of(Content.from(TextSegment.from(
                "relationBreadcrumbs: Alpha --RELATIONSHIP_SUPPORTS--> Gamma",
                Metadata.from(Map.of(
                        "doc_id", "kg-map-alpha",
                        "kg_score", 0.30d,
                        "kg_relation_thumbnail_mode", "relation_thumbnail_v1",
                        "kg_relation_thumbnail_hash", "aaaaaaaaaaaa")))));
        ReflectionTestUtils.setField(orchestrator, "kgRetriever", firstRetriever);

        UnifiedRagOrchestrator.QueryRequest first = new UnifiedRagOrchestrator.QueryRequest();
        first.query = "Alpha Gamma relationship sensitive";
        first.topK = 1;
        first.kgTopK = 1;
        first.useWeb = false;
        first.useVector = false;
        first.useKg = true;
        first.useBm25 = false;
        first.enableBiEncoder = false;
        first.enableDiversity = false;
        first.enableOnnx = false;

        UnifiedRagOrchestrator.QueryResponse firstResponse = orchestrator.query(first);

        assertEquals("applied", firstResponse.debug.get("retrieval.kg.relationThumbnail.status"));
        assertEquals(1, TraceStore.get("retrieval.kg.relationThumbnail.sliceMapCount"));
        assertEquals("aaaaaaaaaaaa", TraceStore.get("retrieval.kg.relationThumbnail.topHash"));

        ContentRetriever secondRetriever = query -> List.of(Content.from(TextSegment.from(
                "Ordinary KG context for a later query without relation thumbnail metadata.",
                Metadata.from(Map.of(
                        "doc_id", "kg-ordinary",
                        "kg_score", 0.20d)))));
        ReflectionTestUtils.setField(orchestrator, "kgRetriever", secondRetriever);
        UnifiedRagOrchestrator.QueryRequest second = new UnifiedRagOrchestrator.QueryRequest();
        second.query = "ordinary later graph query";
        second.topK = 1;
        second.kgTopK = 1;
        second.useWeb = false;
        second.useVector = false;
        second.useKg = true;
        second.useBm25 = false;
        second.enableBiEncoder = false;
        second.enableDiversity = false;
        second.enableOnnx = false;

        UnifiedRagOrchestrator.QueryResponse secondResponse = orchestrator.query(second);

        assertEquals("skipped:no_relation_thumbnail",
                secondResponse.debug.get("retrieval.kg.relationThumbnail.status"));
        assertEquals(0, secondResponse.debug.get("retrieval.kg.relationThumbnail.sliceMapCount"));
        assertEquals(List.of(), secondResponse.debug.get("retrieval.kg.relationThumbnail.sliceMap"));
        assertEquals(0, TraceStore.get("retrieval.kg.relationThumbnail.sliceMapCount"));
        assertEquals(List.of(), TraceStore.get("retrieval.kg.relationThumbnail.sliceMap"));
        assertEquals(null, TraceStore.get("retrieval.kg.relationThumbnail.topHash"));
        Map<String, Object> kgAxis = (Map<String, Object>) secondResponse.debug.get("rag.eval.kgAxis");
        assertEquals("", kgAxis.get("relationThumbnailTopHash"));
        assertFalse(String.valueOf(secondResponse.debug).contains("Alpha"));
    }

    @Test
    void relationThumbnailPromptContextUsesSmallestMatryoshkaLayer() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        ContentRetriever kgRetriever = query -> List.of(Content.from(TextSegment.from(
                "Large relation body SHOULD_NOT_INJECT_BODY.\n"
                        + "relationBreadcrumbs: Alpha -RELATIONSHIP_SUPPORTS-> Gamma",
                Metadata.from(Map.of(
                        "doc_id", "kg-anchor-alpha",
                        "kg_score", 0.10d,
                        "kg_relation_thumbnail_mode", "relation_thumbnail_v1",
                        "kg_relation_thumbnail_hash", "thumb-alpha",
                        "kg_relation_breadcrumb", "Alpha -RELATIONSHIP_SUPPORTS-> Gamma",
                        "kg_relation_summary", "Verbose relation summary SHOULD_NOT_INJECT_SUMMARY")))));
        ReflectionTestUtils.setField(orchestrator, "kgRetriever", kgRetriever);

        UnifiedRagOrchestrator.QueryRequest request = new UnifiedRagOrchestrator.QueryRequest();
        request.query = "Alpha Gamma relationship sensitive";
        request.topK = 1;
        request.kgTopK = 1;
        request.useWeb = false;
        request.useVector = false;
        request.useKg = true;
        request.useBm25 = false;
        request.enableBiEncoder = false;
        request.enableDiversity = false;
        request.enableOnnx = false;

        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

        assertEquals(1, response.results.size());
        UnifiedRagOrchestrator.Doc selected = response.results.get(0);
        assertEquals("breadcrumb", selected.meta.get("kg_relation_prompt_context_layer"));
        assertTrue(selected.snippet.startsWith("relationThumbnailContext:"));
        assertTrue(selected.snippet.contains("Alpha -RELATIONSHIP_SUPPORTS-> Gamma"));
        assertFalse(selected.snippet.contains("SHOULD_NOT_INJECT_BODY"));
        assertFalse(selected.snippet.contains("SHOULD_NOT_INJECT_SUMMARY"));
    }

    @Test
    void kgAxisMirrorsSparseNodeDiagnosticsAsAliases() {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        ContentRetriever kgRetriever = query -> List.of();
        ReflectionTestUtils.setField(orchestrator, "kgRetriever", kgRetriever);
        TraceStore.put("retrieval.kg.brainState.status", "no_graph_path");
        TraceStore.put("retrieval.kg.brainState.disabledReason", "no_graph_path");
        TraceStore.put("retrieval.kg.brainState.failureClass", "sparse_node_starvation");
        TraceStore.put("retrieval.kg.brainState.portMappingCount", 2L);
        TraceStore.put("retrieval.kg.brainState.queryAnchorMap.applied", true);
        TraceStore.put("retrieval.kg.brainState.queryAnchorMap.seedCount", 2L);
        TraceStore.put("retrieval.kg.brainState.queryAnchorMap.seedHashes",
                List.of("aaaaaaaaaaaa", "bbbbbbbbbbbb"));
        TraceStore.put("retrieval.kg.brainState.queryAnchorMap.reason", "cue_seeded_landmark_anchors");
        TraceStore.put("retrieval.kg.sparsePath.status", "success");
        TraceStore.put("retrieval.kg.sparsePath.transitivePathCount", 1);

        UnifiedRagOrchestrator.QueryRequest request = new UnifiedRagOrchestrator.QueryRequest();
        request.query = "sparse node diagnostic query";
        request.topK = 3;
        request.useWeb = false;
        request.useVector = false;
        request.useKg = true;
        request.useBm25 = false;
        request.enableBiEncoder = false;
        request.enableDiversity = false;
        request.enableOnnx = false;

        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

        @SuppressWarnings("unchecked")
        Map<String, Object> kgAxis = (Map<String, Object>) response.debug.get("rag.eval.kgAxis");
        assertEquals("no_graph_path", kgAxis.get("sparseNodeStatus"));
        assertEquals("no_graph_path", kgAxis.get("sparseNodeDisabledReason"));
        assertEquals("sparse_node_starvation", kgAxis.get("sparseNodeFailureClass"));
        assertEquals(2, kgAxis.get("sparseNodePortMappingCount"));
        assertEquals(1, kgAxis.get("sparseNodeTransitivePathCount"));
        assertEquals(true, kgAxis.get("sparseNodeQueryAnchorMapApplied"));
        assertEquals(2, kgAxis.get("sparseNodeQueryAnchorMapSeedCount"));
        assertEquals(List.of("aaaaaaaaaaaa", "bbbbbbbbbbbb"),
                kgAxis.get("sparseNodeQueryAnchorMapSeedHashes"));
        assertEquals("cue_seeded_landmark_anchors", kgAxis.get("sparseNodeQueryAnchorMapReason"));
        @SuppressWarnings("unchecked")
        List<String> signals = (List<String>) kgAxis.get("signals");
        assertTrue(signals.contains("kg_sparse_node_no_graph_path"), () -> String.valueOf(signals));
        assertEquals("no_graph_path", TraceStore.get("rag.eval.kgAxis.sparseNodeDisabledReason"));
        assertEquals("sparse_node_starvation", TraceStore.get("rag.eval.kgAxis.sparseNodeFailureClass"));
        assertEquals(2, TraceStore.get("rag.eval.kgAxis.sparseNodePortMappingCount"));
        assertFalse(String.valueOf(response.debug).contains("sparse node diagnostic query"));
    }

    @Test
    void exactRrfTiesFollowStableDocumentKeyAcrossInputPermutationsWithoutADraw() {
        List<UnifiedRagOrchestrator.Doc> forward = List.of(
                doc("doc-c", "https://example.test/c", "same", "WEB", 0.5d, 1),
                doc("doc-a", "https://example.test/a", "same", "WEB", 0.5d, 1),
                doc("doc-b", "https://example.test/b", "same", "WEB", 0.5d, 1));
        List<UnifiedRagOrchestrator.Doc> reverse =
                List.of(forward.get(2), forward.get(1), forward.get(0));

        RankingRun first = runSeedOnly(forward);
        RankingRun second = runSeedOnly(reverse);

        List<String> expected = List.of("doc-a", "doc-b", "doc-c");
        assertEquals(expected, first.response().results.stream().map(doc -> doc.id).toList());
        assertEquals(expected, second.response().results.stream().map(doc -> doc.id).toList());
        assertEquals(1, first.snapshot().decisionCount());
        assertEquals(1, first.snapshot().stableTieBreakCount());
        assertEquals(0, first.snapshot().drawCount());
        assertEquals(first.snapshot().decisionDigest(), second.snapshot().decisionDigest());
    }

    @Test
    void stableDocumentKeyUsesIdCanonicalUrlSourceIdAndLengthFramedContent() {
        UnifiedRagOrchestrator.Doc explicit =
                doc(" explicit ", null, "body-a", "WEB", 0.5d, 1);
        UnifiedRagOrchestrator.Doc url =
                doc(null, "HTTPS://Example.Test/a/../b?Q=One#fragment", "body-b", "WEB", 0.5d, 1);
        UnifiedRagOrchestrator.Doc source =
                doc(null, "https://[malformed", "", "VECTOR", 0.5d, 1);
        source.meta.put("sourceId", " Source-7 ");
        UnifiedRagOrchestrator.Doc content =
                doc(null, null, "  SAME\nCONTENT  ", null, 0.5d, 1);
        UnifiedRagOrchestrator.Doc splitOne = doc(null, null, "ab", null, 0.5d, 1);
        splitOne.snippet = "c";
        UnifiedRagOrchestrator.Doc splitTwo = doc(null, null, "a", null, 0.5d, 1);
        splitTwo.snippet = "bc";

        assertEquals("id:explicit", UnifiedRagOrchestrator.stableDocumentKeyForTest(explicit));
        assertEquals("url:https://example.test/b?Q=One",
                UnifiedRagOrchestrator.stableDocumentKeyForTest(url));
        assertEquals("source:vector:source-7",
                UnifiedRagOrchestrator.stableDocumentKeyForTest(source));
        String contentKey = UnifiedRagOrchestrator.stableDocumentKeyForTest(content);
        assertTrue(contentKey.startsWith("content:"));
        assertEquals(72, contentKey.length());
        assertFalse(UnifiedRagOrchestrator.stableDocumentKeyForTest(splitOne)
                .equals(UnifiedRagOrchestrator.stableDocumentKeyForTest(splitTwo)));
        assertEquals(null, UnifiedRagOrchestrator.stableDocumentKeyForTest(
                doc(null, null, "", null, 0.5d, 1)));
    }

    private static UnifiedRagOrchestrator.Doc doc(String id) {
        UnifiedRagOrchestrator.Doc doc = new UnifiedRagOrchestrator.Doc();
        doc.id = id;
        doc.title = id;
        doc.snippet = "evidence " + id;
        doc.source = "WEB";
        return doc;
    }

    private static UnifiedRagOrchestrator.Doc doc(
            String id,
            String url,
            String text,
            String source,
            double score,
            int rank) {
        UnifiedRagOrchestrator.Doc doc = new UnifiedRagOrchestrator.Doc();
        doc.id = id;
        doc.title = text;
        doc.snippet = "";
        doc.source = source;
        doc.score = score;
        doc.rank = rank;
        doc.meta = url == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(Map.of("url", url));
        return doc;
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "true,false,MEMORY,false", "true,true,MEMORY,false", "true,false,NONE,false",
            "true,false,MEMORY,true", "true,true,MEMORY,true", "true,false,NONE,true",
            "true,true,NONE,false", "true,true,NONE,true",
            "false,true,MEMORY,false", "false,false,NONE,false"
    })
    void explicitWhitelistSurvivesModeFlagsWithoutRestoringRejectedSeeds(
            boolean whitelistOnly, boolean aggressive, String memoryProfile, boolean allDenied) {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        com.example.lms.service.rag.auth.DomainWhitelist whitelist = new com.example.lms.service.rag.auth.DomainWhitelist();
        whitelist.setDomainAllowlist(List.of("official.test"));
        ReflectionTestUtils.setField(orchestrator, "domainWhitelist", whitelist);
        UnifiedRagOrchestrator.QueryRequest request = new UnifiedRagOrchestrator.QueryRequest();
        request.query = "Synthetic explicit whitelist fixture.";
        request.topK = 2;
        request.seedOnly = true;
        request.seedMode = "candidates";
        request.seedCandidates = List.of(
                doc("first", allDenied ? "https://denied.test/a" : "https://official.test/a", "first document", "WEB", 1d, 1),
                doc("second", "https://other.test/b", "second document", "VECTOR", .9d, 2));
        request.useWeb = request.useVector = request.useKg = request.useBm25 = false;
        request.enableOnnx = request.enableBiEncoder = request.enableDiversity = false;
        request.whitelistOnly = whitelistOnly;
        request.aggressive = aggressive;
        request.memoryProfile = memoryProfile;

        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

        assertEquals(Boolean.TRUE, response.debug.get("seed.only"));
        if (whitelistOnly) {
            assertEquals(allDenied ? List.of() : List.of("first"), response.results.stream().map(d -> d.id).toList());
            assertEquals(allDenied ? 2 : 1, response.debug.get("stage.whitelist.filtered"));
            assertEquals(allDenied ? 0 : 1, response.debug.get("stage.whitelist"));
            assertFalse(response.debug.containsKey("stage.whitelist.skipped"));
        } else {
            assertEquals(java.util.Set.of("first", "second"), response.results.stream().map(d -> d.id).collect(java.util.stream.Collectors.toSet()));
            assertFalse(response.debug.containsKey("stage.whitelist.filtered"));
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "https://official.test/a,official.test,true",
            "https://docs.official.test/a,official.test,true",
            "https://OFFICIAL.TEST/a,official.test,true",
            "https://docs.OFFICIAL.TEST/a,official.test,true",
            "https://official.test/a,OFFICIAL.TEST,true",
            "https://notofficial.test/a,official.test,false",
            "https://sub.notofficial.test/a,official.test,false",
            "https://official.test.attacker.test/a,official.test,false",
            "https://official.test@attacker.test/a,official.test,false",
            "https://attacker.test/official.test,official.test,false",
            "http://[invalid,official.test,false",
            ",official.test,false"
    })
    void finalWhitelistUsesDnsHostBoundariesAndCanonicalCase(String url, String allowedHost, boolean allowed) {
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        com.example.lms.service.rag.auth.DomainWhitelist whitelist = new com.example.lms.service.rag.auth.DomainWhitelist();
        whitelist.setDomainAllowlist(List.of(" " + allowedHost + " "));
        ReflectionTestUtils.setField(orchestrator, "domainWhitelist", whitelist);
        UnifiedRagOrchestrator.QueryRequest request = new UnifiedRagOrchestrator.QueryRequest();
        request.query = "Synthetic hostname admission fixture.";
        request.topK = 1;
        request.seedOnly = true;
        request.seedMode = "candidates";
        request.seedCandidates = List.of(doc("candidate", url, "synthetic document", "VECTOR", 1d, 1));
        request.useWeb = request.useVector = request.useKg = request.useBm25 = false;
        request.enableOnnx = request.enableBiEncoder = request.enableDiversity = false;
        request.whitelistOnly = true;
        request.memoryProfile = "MEMORY";

        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

        assertEquals(Boolean.TRUE, response.debug.get("seed.only"));
        assertEquals(allowed ? List.of("candidate") : List.of(), response.results.stream().map(d -> d.id).toList());
        assertEquals(allowed ? 0 : 1, response.debug.get("stage.whitelist.filtered"));
    }

    private static RankingRun runSeedOnly(List<UnifiedRagOrchestrator.Doc> documents) {
        SelectionDecisionLedger ledger = SelectionDecisionLedger.forStandard();
        GuardContext context = new GuardContext();
        context.attachSelectionEntropy(SelectionEntropyFactory.standard(), ledger);
        GuardContextHolder.set(context);
        try {
            UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
            UnifiedRagOrchestrator.QueryRequest request = new UnifiedRagOrchestrator.QueryRequest();
            request.query = "fixed ranking fixture";
            request.seedOnly = true;
            request.seedMode = "candidates";
            request.seedCandidates = documents;
            request.topK = documents.size();
            request.useWeb = false;
            request.useVector = false;
            request.useKg = false;
            request.useBm25 = false;
            request.enableOnnx = false;
            request.enableBiEncoder = false;
            request.enableDiversity = false;
            return new RankingRun(orchestrator.query(request), ledger.snapshot(false));
        } finally {
            GuardContextHolder.clear();
        }
    }

    private record RankingRun(
            UnifiedRagOrchestrator.QueryResponse response,
            SelectionDecisionLedger.Snapshot snapshot) {
    }

    private static Content relationThumbnail(String id, String left, String right, double score) {
        return Content.from(TextSegment.from(
                "relationBreadcrumbs: " + left + " -RELATIONSHIP_SUPPORTS-> " + right,
                Metadata.from(Map.of(
                        "doc_id", id,
                        "kg_score", score,
                        "kg_relation_thumbnail_mode", "relation_thumbnail_v1",
                        "kg_relation_thumbnail_hash", id + "-thumb",
                        "kg_relation_breadcrumb_hashes", id + "-bread"))));
    }

    private static DebugEventStore enabledDebugEventStore() {
        DebugEventStore debugEventStore = new DebugEventStore();
        ReflectionTestUtils.setField(debugEventStore, "enabled", true);
        ReflectionTestUtils.setField(debugEventStore, "maxSize", 20);
        ReflectionTestUtils.setField(debugEventStore, "windowMs", 60_000L);
        ReflectionTestUtils.setField(debugEventStore, "maxPerWindow", 20L);
        ReflectionTestUtils.setField(debugEventStore, "flushIntervalMs", 15_000L);
        return debugEventStore;
    }
}
