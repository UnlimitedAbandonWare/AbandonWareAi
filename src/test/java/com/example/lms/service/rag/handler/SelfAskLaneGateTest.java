package com.example.lms.service.rag.handler;

import com.example.lms.search.TraceStore;
import com.example.lms.search.probe.BranchQualityProbe;
import com.example.lms.service.rag.auth.AuthorityScorer;
import com.example.lms.service.rag.energy.ContradictionScorer;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelfAskLaneGateTest {

    @BeforeEach
    void setUp() {
        TraceStore.clear();
    }

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void normalLaneRequiresStrongCitationsAndCrossSupportBeforeFusion() {
        DynamicRetrievalHandlerChain chain = chain();

        invokeGate(chain,
                List.of(
                        content("BQ", "Project alpha KPI improves retention in 2026. source: official",
                                "https://official.example/a"),
                        content("BQ", "Project alpha KPI improves conversion in 2026. source: docs",
                                "https://docs.example/b")),
                List.of(content("WEB", "Official project alpha KPI retention evidence for 2026.",
                        "https://official.example/a")),
                List.of());

        assertEquals("NORMAL", TraceStore.get("selfask.laneGate.bq.action"));
        assertEquals("threshold_pass", TraceStore.get("selfask.laneGate.bq.reason"));
        assertEquals(2, TraceStore.get("selfask.laneGate.bq.promptEligibleCount"));
    }

    @Test
    void zero100CrossVerifyUsesSiblingSupportButNotOwnLaneOnly() {
        DynamicRetrievalHandlerChain chain = chain();
        TraceStore.put("zero100.enabled", true);
        TraceStore.put("zero100.crossVerify.required", true);

        invokeGate(chain,
                List.of(
                        content("BQ", "Project alpha KPI retention official evidence 2026.",
                                "https://official.example/a"),
                        content("ER", "Project alpha KPI retention official evidence 2026.",
                                "https://docs.example/b")),
                List.of(),
                List.of());
        double siblingSupport = Double.parseDouble(String.valueOf(
                TraceStore.get("selfask.laneGate.bq.crossLaneSupportRate")));

        TraceStore.clear();
        TraceStore.put("zero100.enabled", true);
        TraceStore.put("zero100.crossVerify.required", true);
        invokeGate(chain,
                List.of(content("BQ", "Project alpha KPI retention official evidence 2026.",
                        "https://official.example/a")),
                List.of(),
                List.of());
        double ownOnlySupport = Double.parseDouble(String.valueOf(
                TraceStore.get("selfask.laneGate.bq.crossLaneSupportRate")));

        assertTrue(siblingSupport > ownOnlySupport);
        assertEquals(0.0d, ownOnlySupport);
    }

    @Test
    @SuppressWarnings("unchecked")
    void zero100ConsensusLaneWeightIsReflectedInRrfSourceWeights() {
        DynamicRetrievalHandlerChain chain = chain();
        TraceStore.put("zero100.enabled", true);
        TraceStore.put("zero100.consensus.enabled", true);
        TraceStore.put("zero100.riskConsensus.enabled", true);
        TraceStore.put("zero100.riskConsensus.minLaneCoverage", 2);
        TraceStore.put("zero100.riskConsensus.riskPenaltyLambda", 0.45d);
        TraceStore.put("zero100.riskConsensus.minRrfWeight", 0.05d);
        TraceStore.put("zero100.riskConsensus.maxRrfWeight", 1.25d);
        TraceStore.put("zero100.branch.weights", Map.of("BQ", 1.25d, "ER", 0.25d, "RC", 0.25d));

        invokeGate(chain,
                List.of(
                        content("BQ", "Project alpha KPI improves retention in 2026. source: official",
                                "https://official.example/a"),
                        content("BQ", "Project alpha KPI improves conversion in 2026. source: docs",
                                "https://docs.example/b")),
                List.of(content("WEB", "Official project alpha KPI retention evidence for 2026.",
                        "https://official.example/a")),
                List.of());

        Object weightsObj = TraceStore.get("zero100.consensus.rrfWeights");
        assertTrue(weightsObj instanceof Map<?, ?>);
        Map<String, Object> weights = (Map<String, Object>) weightsObj;
        assertTrue(Double.parseDouble(String.valueOf(weights.get("BQ"))) > 0.0d);
        assertTrue(TraceStore.get("zero100.consensus.laneRisk.events") instanceof List<?>);
        assertEquals(1, TraceStore.get("zero100.consensus.laneCoverage"));
        assertEquals("LOW_COVERAGE_FAILSOFT", TraceStore.get("zero100.consensus.status"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void zero100RiskConsensusDampensContradictoryLaneWeight() {
        DynamicRetrievalHandlerChain chain = chain();
        enableBranchQuality(chain);
        TraceStore.put("zero100.enabled", true);
        TraceStore.put("zero100.consensus.enabled", true);
        TraceStore.put("zero100.riskConsensus.enabled", true);
        TraceStore.put("zero100.riskConsensus.minLaneCoverage", 1);
        TraceStore.put("zero100.riskConsensus.riskPenaltyLambda", 0.80d);
        TraceStore.put("zero100.riskConsensus.minRrfWeight", 0.05d);
        TraceStore.put("zero100.riskConsensus.maxRrfWeight", 1.25d);
        TraceStore.put("zero100.branch.weights", Map.of("BQ", 1.25d));

        invokeGate(chain,
                List.of(
                        content("BQ", "Regulator evidence says project alpha improved in 2026. source: fsc",
                                "https://www.fsc.go.kr/a"),
                        content("BQ", "Disclosure evidence says project alpha degraded in 2027. source: dart",
                                "https://dart.fss.or.kr/b")),
                List.of(content("WEB", "Regulator evidence says project alpha improved in 2026. source: fsc",
                        "https://www.fsc.go.kr/a")),
                List.of());

        Object weightsObj = TraceStore.get("zero100.consensus.rrfWeights");
        assertTrue(weightsObj instanceof Map<?, ?>);
        Map<String, Object> weights = (Map<String, Object>) weightsObj;
        assertTrue(Double.parseDouble(String.valueOf(weights.get("BQ"))) < 1.25d);
        Object eventsObj = TraceStore.get("zero100.consensus.laneRisk.events");
        assertTrue(eventsObj instanceof List<?>);
        Map<String, Object> event = (Map<String, Object>) ((List<?>) eventsObj).get(0);
        assertEquals("risk_adjusted", event.get("reason"));
        assertTrue(Double.parseDouble(String.valueOf(event.get("riskMultiplier"))) < 1.0d);
        assertTrue(Double.parseDouble(String.valueOf(event.get("contradictionRate"))) > 0.0d);
    }

    @Test
    void aliasLaneCompressesWhenDuplicateHeavyButNotContradictory() {
        DynamicRetrievalHandlerChain chain = chain();
        ReflectionTestUtils.setField(chain, "selfAskLaneGateMinDistinctCitations", 1);
        ReflectionTestUtils.setField(chain, "selfAskLaneGateCompressTopK", 1);
        ReflectionTestUtils.setField(chain, "selfAskLaneGateAnchorCompressionMinGain", 0.0d);

        invokeGate(chain,
                List.of(
                        content("ER", "Skirk alias project alpha KPI context source: one",
                                "https://docs.example/skirk"),
                        content("ER", "Skirk alias project alpha KPI context source: one",
                                "https://docs.example/skirk"),
                        content("ER", "Skirk alias project alpha KPI context source: one",
                                "https://docs.example/skirk"),
                        content("ER", "Skirk alias project alpha KPI context source: one",
                                "https://docs.example/skirk")),
                List.of(content("WEB", "Skirk alias project alpha KPI context source: web",
                        "https://docs.example/skirk")),
                List.of());

        assertEquals("ANCHOR_COMPRESS", TraceStore.get("selfask.laneGate.er.action"));
        assertEquals(1, TraceStore.get("selfask.laneGate.er.promptEligibleCount"));
        assertEquals(Boolean.FALSE, TraceStore.get("selfask.compressor.wired"));
        assertEquals("not_wired", TraceStore.get("selfask.compressor.skipReason"));
        assertEquals(0, TraceStore.get("selfask.compressor.appliedCount"));
    }

    @Test
    void branchQualityRecomputesCompressedLaneContribution() {
        DynamicRetrievalHandlerChain chain = chain();
        enableBranchQuality(chain);
        ReflectionTestUtils.setField(chain, "selfAskLaneGateMinDistinctCitations", 1);
        ReflectionTestUtils.setField(chain, "selfAskLaneGateCompressTopK", 1);
        ReflectionTestUtils.setField(chain, "selfAskLaneGateAnchorCompressionMinGain", 0.0d);

        Object gate = invokeGate(chain,
                List.of(
                        content("ER", "Skirk alias project alpha KPI context source: one",
                                "https://docs.example/skirk"),
                        content("ER", "Skirk alias project alpha KPI context source: one",
                                "https://docs.example/skirk"),
                        content("ER", "Skirk alias project alpha KPI context source: one",
                                "https://docs.example/skirk"),
                        content("ER", "Skirk alias project alpha KPI context source: one",
                                "https://docs.example/skirk")),
                List.of(content("WEB", "Skirk alias project alpha KPI context source: web",
                        "https://docs.example/skirk")),
                List.of());

        assertEquals("ANCHOR_COMPRESS", TraceStore.get("selfask.laneGate.er.action"));
        assertEquals("PASS", TraceStore.get("selfask.branchQuality.er.action"));
        @SuppressWarnings("unchecked")
        List<Content> fusionContents = (List<Content>) ReflectionTestUtils.invokeMethod(gate, "fusionContents");
        Map<String, Object> metadata = fusionContents.get(0).textSegment().metadata().toMap();
        assertEquals("ER", metadata.get("branch_quality_branch_id"));
        assertEquals("alias_synonym", metadata.get("branch_quality_intent_axis"));
        assertTrue(Double.parseDouble(String.valueOf(metadata.get("branch_quality_rrf_weight"))) >= 1.0d);
    }

    @Test
    void branchQualityFusionPoolWidensOnlyUnderDppPressure() throws Exception {
        DynamicRetrievalHandlerChain chain = chain();
        enableBranchQuality(chain);

        BranchQualityProbe.BranchQualityMetrics healthy = new BranchQualityProbe.BranchQualityMetrics(
                "BQ", "domain_definition", 4, 0.0d, 0.90d, 0.90d, 0.90d, 0.05d, 3,
                1.0d, 1.0d, 5, 0.20d, 0.70d, BranchQualityProbe.BranchAction.PASS, "threshold_pass");
        BranchQualityProbe.BranchQualityMetrics duplicate = new BranchQualityProbe.BranchQualityMetrics(
                "ER", "alias_synonym", 5, 0.80d, 0.55d, 0.45d, 0.35d, 0.70d, 5,
                0.8d, 0.5d, 3, 0.18d, 0.45d, BranchQualityProbe.BranchAction.SHRINK, "duplicate_pressure");

        Integer healthyPool = ReflectionTestUtils.invokeMethod(chain, "branchQualityFusionTopK", List.of(healthy), 5);
        Integer duplicatePool = ReflectionTestUtils.invokeMethod(chain, "branchQualityFusionTopK", List.of(duplicate), 5);
        assertEquals(5, healthyPool);
        assertEquals(15, duplicatePool);

        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/handler/DynamicRetrievalHandlerChain.java"));
        assertFalse(source.contains("signalsByLane.getOrDefault(lane,"));
        assertTrue(source.contains("EvidenceSignals.LaneEvidenceSignals sig = signalsByLane.get(lane);"));
        assertTrue(source.contains("BranchQualityProbe.BranchQualityMetrics compressedMetric = branchQualityMetric(compressedSig);"));
    }

    @Test
    void branchQualityMetadataReasonUsesSafeMessage() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/handler/DynamicRetrievalHandlerChain.java"));

        assertFalse(source.contains("metadata.put(\"branch_quality_reason\", branchMetric.reason());"));
        assertTrue(source.contains(
                "metadata.put(\"branch_quality_reason\", SafeRedactor.traceLabelOrFallback(branchMetric.reason(), \"unknown\"));"));
    }

    @Test
    void blackboxGraphPriorIgnoredUntilAppliedAndLaneTargeted() {
        DynamicRetrievalHandlerChain chain = chain();
        enableBranchQuality(chain);
        TraceStore.put("blackbox.risk.graphRecommendation.restoreAction", "disable_provider_failsoft");
        TraceStore.put("blackbox.risk.graphRecommendation.confidence", 0.82d);
        TraceStore.put("blackbox.risk.graphRecommendation.applied", false);

        BranchQualityProbe.BranchQualityMetrics metric = new BranchQualityProbe.BranchQualityMetrics(
                "BQ", "domain_definition", 4, 0.0d, 0.90d, 0.90d, 0.90d, 0.05d, 3,
                1.0d, 1.0d, 5, 0.20d, 0.70d, BranchQualityProbe.BranchAction.PASS, "threshold_pass");

        Double adjusted = ReflectionTestUtils.invokeMethod(chain, "branchQualityWeight", metric);

        assertEquals(metric.rrfWeight(), adjusted);
        assertEquals(0L, TraceStore.getAll().keySet().stream()
                .filter(key -> String.valueOf(key).contains("fake"))
                .count());
    }

    @Test
    void blackboxGraphPriorDampensOnlyTargetedAnchorLane() {
        DynamicRetrievalHandlerChain chain = chain();
        enableBranchQuality(chain);
        TraceStore.put("blackbox.risk.graphRecommendation.restoreAction", "anchor_compression_topup");
        TraceStore.put("blackbox.risk.graphRecommendation.confidence", 0.82d);
        TraceStore.put("blackbox.risk.graphRecommendation.applied", true);
        TraceStore.put("blackbox.risk.anchorStack", List.of(Map.of(
                "lane", "BQ",
                "component", "web",
                "stage", "web.await",
                "expectedDelta", 0.20d)));

        BranchQualityProbe.BranchQualityMetrics bq = new BranchQualityProbe.BranchQualityMetrics(
                "BQ", "domain_definition", 4, 0.0d, 0.90d, 0.90d, 0.90d, 0.05d, 3,
                1.0d, 1.0d, 5, 0.20d, 0.70d, BranchQualityProbe.BranchAction.PASS, "threshold_pass");
        BranchQualityProbe.BranchQualityMetrics er = new BranchQualityProbe.BranchQualityMetrics(
                "ER", "alias_synonym", 4, 0.0d, 0.90d, 0.90d, 0.90d, 0.05d, 3,
                1.0d, 1.0d, 5, 0.20d, 0.70d, BranchQualityProbe.BranchAction.PASS, "threshold_pass");

        Double bqAdjusted = ReflectionTestUtils.invokeMethod(chain, "branchQualityWeight", bq);
        Double erAdjusted = ReflectionTestUtils.invokeMethod(chain, "branchQualityWeight", er);

        assertTrue(bqAdjusted != null && bqAdjusted < bq.rrfWeight());
        assertEquals(er.rrfWeight(), erAdjusted);
    }

    @Test
    void relationLaneBypassesWhenUnsupported() {
        DynamicRetrievalHandlerChain chain = chain();

        invokeGate(chain,
                List.of(content("RC", "Project alpha caused KPI increase in 2026. source: hypothesis",
                        "https://hypothesis.example/one")),
                List.of(),
                List.of());

        assertEquals("BYPASS_ROUTING", TraceStore.get("selfask.laneGate.rc.action"));
        assertEquals("relation_support_missing", TraceStore.get("selfask.laneGate.rc.reason"));
        assertEquals(0, TraceStore.get("selfask.laneGate.rc.promptEligibleCount"));
    }

    @Test
    void rareTailLaneUsesTailProbeWhenAuthorityAndCitationAreSafe() {
        DynamicRetrievalHandlerChain chain = chainWithAuthority();

        Object gate = invokeGate(chain,
                List.of(
                        content("BQ", "Rare regulator evidence for KPI outcome. source: fsc",
                                "https://www.fsc.go.kr/a", 1.0d),
                        content("BQ", "Supporting exchange evidence for KPI outcome. source: krx",
                                "https://kind.krx.co.kr/b", 0.20d),
                        content("BQ", "Additional disclosure evidence for KPI outcome. source: dart",
                                "https://dart.fss.or.kr/c", 0.18d)),
                List.of(),
                List.of());

        assertEquals("TAIL_PROBE", TraceStore.get("selfask.laneGate.bq.action"));
        assertEquals("tail_probe_fusion_only", TraceStore.get("selfask.laneGate.bq.reason"));
        assertEquals(0, TraceStore.get("selfask.laneGate.bq.promptEligibleCount"));
        @SuppressWarnings("unchecked")
        List<Content> fusionContents = (List<Content>) ReflectionTestUtils.invokeMethod(gate, "fusionContents");
        assertEquals(3, fusionContents.size());
        Map<String, Object> metadata = fusionContents.get(0).textSegment().metadata().toMap();
        assertEquals("false", String.valueOf(metadata.get("promptEligible")));
        assertEquals("tail_probe_fusion_only", metadata.get("selfask_lane_gate_reason"));
    }

    @Test
    void rareTailLaneCanEnterPromptOnlyWithCrossSupportAndReadiness() {
        DynamicRetrievalHandlerChain chain = chainWithAuthority();
        ReflectionTestUtils.setField(chain, "selfAskLaneGateMinEvidenceRate", 1.01d);

        invokeGate(chain,
                List.of(
                        content("BQ", "Rare regulator evidence for KPI outcome. source: fsc",
                                "https://www.fsc.go.kr/a", 1.0d),
                        content("BQ", "Supporting exchange evidence for KPI outcome. source: krx",
                                "https://kind.krx.co.kr/b", 0.20d),
                        content("BQ", "Additional disclosure evidence for KPI outcome. source: dart",
                                "https://dart.fss.or.kr/c", 0.18d)),
                List.of(
                        content("WEB", "Rare regulator evidence for KPI outcome. source: fsc",
                                "https://www.fsc.go.kr/a"),
                        content("WEB", "Supporting exchange evidence for KPI outcome. source: krx",
                                "https://kind.krx.co.kr/b"),
                        content("WEB", "Additional disclosure evidence for KPI outcome. source: dart",
                                "https://dart.fss.or.kr/c")),
                List.of());

        assertEquals("TAIL_PROBE", TraceStore.get("selfask.laneGate.bq.action"));
        assertEquals("tail_probe_prompt_pass", TraceStore.get("selfask.laneGate.bq.reason"));
        assertEquals(3, TraceStore.get("selfask.laneGate.bq.promptEligibleCount"));
    }

    @Test
    void highContradictionTailLaneFallsBackToStrictVerify() {
        DynamicRetrievalHandlerChain chain = chainWithAuthority();

        invokeGate(chain,
                List.of(
                        content("BQ", "Rare regulator evidence says KPI improved in 2026. source: fsc",
                                "https://www.fsc.go.kr/a", 1.0d),
                        content("BQ", "Exchange evidence says KPI degraded in 2027. source: krx",
                                "https://kind.krx.co.kr/b", 0.20d),
                        content("BQ", "Disclosure evidence says KPI degraded in 2027. source: dart",
                                "https://dart.fss.or.kr/c", 0.18d)),
                List.of(),
                List.of());

        assertEquals("STRICT_VERIFY", TraceStore.get("selfask.laneGate.bq.action"));
        assertEquals("tail_strict_contradiction", TraceStore.get("selfask.laneGate.bq.reason"));
        assertEquals(0, TraceStore.get("selfask.laneGate.bq.promptEligibleCount"));
    }

    @Test
    void laneGateReasonTraceUsesSafeMessage() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/handler/DynamicRetrievalHandlerChain.java"));

        assertFalse(source.contains("TraceStore.put(\"selfask.laneGate.\" + lane + \".reason\", reason);"));
        assertFalse(source.contains("event.put(\"reason\", reason);"));
        assertFalse(source.contains(
                "TraceStore.put(\"selfask.laneGate.\" + lane + \".reason\", SafeRedactor.safeMessage(reason, 120));"));
        assertFalse(source.contains("event.put(\"reason\", SafeRedactor.safeMessage(reason, 120));"));
        assertTrue(source.contains(
                "TraceStore.put(\"selfask.laneGate.\" + lane + \".reason\", SafeRedactor.traceLabelOrFallback(reason, \"unknown\"));"));
        assertTrue(source.contains("event.put(\"reason\", SafeRedactor.traceLabelOrFallback(reason, \"unknown\"));"));
    }

    @Test
    void laneGateTraceRedactsUntrustedLaneRole() {
        DynamicRetrievalHandlerChain chain = chain();
        String rawLane = "token=test-secret-lane";

        invokeGate(chain,
                List.of(content(rawLane, "Project alpha KPI retention official evidence 2026.",
                        "https://official.example/a")),
                List.of(),
                List.of());

        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(rawLane));
        assertFalse(trace.contains("test-secret-lane"));
    }

    @Test
    void shippedRiskPenaltyChangesCalculatedWeightsButNotGateOrSourceFusionResults() throws Throwable {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(
                new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var original = mapper.readTree(Files.readString(Path.of("main/resources/plans/zero100.v1.yaml"),
                java.nio.charset.StandardCharsets.UTF_8));
        String key = "search.zero100.riskConsensus.riskPenaltyLambda";
        assertEquals(0.45d, original.path("params").path(key).asDouble());
        List<List<Map<String, Object>>> returnedMetadata = new java.util.ArrayList<>();
        List<List<String>> returnedOrder = new java.util.ArrayList<>();
        List<Map<?, ?>> calculatedWeights = new java.util.ArrayList<>();
        for (double rawLambda : new double[]{0.45d, 0.0d}) {
            TraceStore.clear();
            var modified = original.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) modified.path("params")).put(key, rawLambda);
            var restored = modified.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) restored.path("params"))
                    .set(key, original.path("params").path(key));
            assertEquals(original, restored, "only the raw risk penalty differs between controls");
            byte[] bytes = mapper.writeValueAsBytes(modified);
            var resources = new org.springframework.core.io.DefaultResourceLoader() {
                @Override public org.springframework.core.io.Resource getResource(String location) {
                    if ("classpath:plans/zero100.v1.yaml".equals(location)) {
                        return new org.springframework.core.io.ByteArrayResource(bytes) {
                            @Override public String getFilename() { return "zero100.v1.yaml"; }
                        };
                    }
                    return super.getResource(location);
                }
            };
            var applier = new com.example.lms.plan.PlanHintApplier(original.equals(modified)
                    ? new org.springframework.core.io.DefaultResourceLoader() : resources);
            var ctx = new com.example.lms.service.guard.GuardContext();
            applier.applyToGuardContext(applier.load("zero100.v1"), ctx);
            assertEquals(rawLambda, ctx.planDouble(key, -1.0d));
            var props = new ai.abandonware.nova.config.Zero100EngineProperties();
            props.setEngineEnabled(true);
            var registry = new ai.abandonware.nova.orch.zero100.Zero100SessionRegistry(props);
            registry.touch(com.example.lms.trace.LogCorrelation.sessionId(), "bounded risk fixture",
                    ctx.planLong("search.zero100.maxMinutes", -1L),
                    ctx.planLong("search.zero100.sliceMs", -1L),
                    ctx.planLong("search.zero100.webTimeboxMs", -1L),
                    ctx.planLong("search.zero100.backoffHardCapMs", -1L));
            var sessions = (Map<?, ?>) ReflectionTestUtils.getField(registry, "sessions");
            assertEquals(1, sessions.size());
            Object state = sessions.values().iterator().next();
            assertEquals(6_000_000L, ReflectionTestUtils.getField(state, "budgetMs"));
            // Age only this fixture's registry state; the actual touch/scheduler selects CONSENSUS.
            ReflectionTestUtils.setField(state, "createdAtMs", System.currentTimeMillis() - 5_400_100L);
            var aspect = new ai.abandonware.nova.orch.aop.Zero100SessionAspect(
                    props, registry, new org.springframework.mock.env.MockEnvironment());
            var pjp = org.mockito.Mockito.mock(org.aspectj.lang.ProceedingJoinPoint.class);
            org.mockito.Mockito.when(pjp.getArgs()).thenReturn(new Object[]{"bounded risk fixture"});
            org.mockito.Mockito.when(pjp.proceed()).thenAnswer(invocation -> {
                assertEquals("CONSENSUS", TraceStore.get("zero100.phase"));
                assertEquals(Boolean.TRUE, TraceStore.get("zero100.consensus.enabled"));
                assertEquals(Boolean.TRUE, TraceStore.get("zero100.riskConsensus.enabled"));
                assertEquals(rawLambda, TraceStore.get("zero100.riskConsensus.riskPenaltyLambda"));
                DynamicRetrievalHandlerChain chain = chain();
                enableBranchQuality(chain);
                List<Content> laneInputs = List.of(
                        content("BQ", "Regulator evidence says project alpha improved in 2026. source: fsc", "https://www.fsc.go.kr/a", 0.50d),
                        content("BQ", "Disclosure evidence says project alpha degraded in 2027. source: dart", "https://dart.fss.or.kr/b", 0.50d),
                        content("ER", "Project beta KPI retention in 2026. source: official", "https://official.example/c"),
                        content("ER", "Project beta KPI conversion in 2026. source: docs", "https://docs.example/d"));
                List<Content> vector = List.of(content("VEC", "Independent local reference. source: fixture", "https://local.example/e"));
                Object gate = invokeGate(chain, laneInputs, List.of(), vector);
                @SuppressWarnings("unchecked")
                List<Content> admitted = (List<Content>) ReflectionTestUtils.invokeMethod(gate, "fusionContents");
                assertEquals("TAIL_PROBE", TraceStore.get("selfask.laneGate.bq.action"));
                assertEquals(4, admitted.size(), "both lanes, including contradictory BQ, must survive the gate");
                assertEquals(laneInputs.stream().map(c -> c.textSegment().text()).toList(),
                        admitted.stream().map(c -> c.textSegment().text()).toList());
                assertEquals("READY", TraceStore.get("zero100.consensus.status"));
                Map<?, ?> weights = (Map<?, ?>) TraceStore.get("zero100.consensus.rrfWeights");
                assertEquals(java.util.Set.of("BQ", "ER"), weights.keySet());
                var events = (List<?>) TraceStore.get("zero100.consensus.laneRisk.events");
                Map<?, ?> bq = (Map<?, ?>) events.stream()
                        .filter(e -> "BQ".equals(((Map<?, ?>) e).get("lane"))).findFirst().orElseThrow();
                assertTrue(((Number) bq.get("contradictionRate")).doubleValue() > 0.5d);
                assertTrue(((Number) bq.get("laneRisk")).doubleValue() > 0.0d);
                var fuser = new com.example.lms.service.rag.fusion.WeightedReciprocalRankFuser(60, null, "");
                // Same source-list overload used by the handler; no lane-to-source mapping is invented.
                List<List<Content>> sources = List.of(admitted, vector);
                var fused = fuser.fuse(sources, 5);
                assertEquals(5, fused.size(), "nonempty results must not mask the missing weight consumer");
                returnedMetadata.add(admitted.stream().map(c -> c.textSegment().metadata().toMap()).toList());
                returnedOrder.add(fused.stream().map(c -> c.textSegment().text()).toList());
                calculatedWeights.add(Map.copyOf(weights));
                // Independent sensitivity control: explicit source weights can change this fuser's top result.
                var sourceA = fuser.fuse(sources, List.of(10.0d, 0.1d), 1);
                var sourceB = fuser.fuse(sources, List.of(0.1d, 10.0d), 1);
                assertEquals(admitted.get(0), sourceA.get(0));
                assertEquals(vector.get(0), sourceB.get(0));
                System.out.printf(java.util.Locale.ROOT,
                        "TBL07_RISK rawLambda=%.2f bqWeight=%.4f erWeight=%.4f contradiction=%.4f admitted=%d fused=%d phase=CONSENSUS coverage=READY sourceWeightControlChanged=true externalRequests=0%n",
                        rawLambda, ((Number) weights.get("BQ")).doubleValue(), ((Number) weights.get("ER")).doubleValue(),
                        ((Number) bq.get("contradictionRate")).doubleValue(), admitted.size(), fused.size());
                return fused;
            });
            com.example.lms.service.guard.GuardContextHolder.set(ctx);
            try {
                assertEquals(5, ((List<?>) aspect.aroundChatEntry(pjp)).size());
                org.mockito.Mockito.verify(pjp, org.mockito.Mockito.times(1)).proceed();
            } finally {
                com.example.lms.service.guard.GuardContextHolder.clear();
                TraceStore.clear();
            }
        }
        for (String lane : List.of("BQ", "ER")) {
            assertTrue(((Number) calculatedWeights.get(0).get(lane)).doubleValue()
                    < ((Number) calculatedWeights.get(1).get(lane)).doubleValue());
        }
        assertEquals(returnedMetadata.get(0), returnedMetadata.get(1), "risk weights are not attached to admitted content");
        assertEquals(returnedOrder.get(0), returnedOrder.get(1), "characterizes the missing result consumer, not an approved no-op policy");
        System.out.println("TBL07_RISK_PAIR calculatedWeightsChanged=true gateMetadataChanged=false finalOrderChanged=false finalMembershipChanged=false missingConsumerObserved=true");
    }
    private static Object invokeGate(
            DynamicRetrievalHandlerChain chain,
            List<Content> selfAsk,
            List<Content> web,
            List<Content> vector) {
        return ReflectionTestUtils.invokeMethod(chain, "gateSelfAskLanes", selfAsk, web, vector);
    }

    private static DynamicRetrievalHandlerChain chain() {
        DynamicRetrievalHandlerChain chain = new DynamicRetrievalHandlerChain(
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null);
        ReflectionTestUtils.setField(chain, "selfAskLaneGateEnabled", true);
        ReflectionTestUtils.setField(chain, "selfAskLaneGateMinEvidenceRate", 0.45d);
        ReflectionTestUtils.setField(chain, "selfAskLaneGateMaxDuplicateRate", 0.70d);
        ReflectionTestUtils.setField(chain, "selfAskLaneGateMaxContradictionRate", 0.55d);
        ReflectionTestUtils.setField(chain, "selfAskLaneGateMinConfidence", 0.50d);
        ReflectionTestUtils.setField(chain, "selfAskLaneGateBypassConfidence", 0.25d);
        ReflectionTestUtils.setField(chain, "selfAskLaneGateCompressTopK", 3);
        ReflectionTestUtils.setField(chain, "selfAskLaneGateContradictionMaxPairs", 3);
        ReflectionTestUtils.setField(chain, "selfAskLaneGateCitationMin", 3);
        ReflectionTestUtils.setField(chain, "selfAskLaneGateMinStrongCitationRate", 0.50d);
        ReflectionTestUtils.setField(chain, "selfAskLaneGateMinDistinctCitations", 2);
        ReflectionTestUtils.setField(chain, "selfAskLaneGateMinCrossLaneSupport", 0.25d);
        ReflectionTestUtils.setField(chain, "selfAskLaneGateMaxGrandasContradictionRate", 0.40d);
        ReflectionTestUtils.setField(chain, "selfAskLaneGateAnchorCompressionMinGain", 0.10d);
        ReflectionTestUtils.setField(chain, "selfAskLaneGatePromptMinReadiness", 0.62d);
        ReflectionTestUtils.setField(chain, "selfAskLaneGateTailProbeMinSignal", 0.70d);
        ReflectionTestUtils.setField(chain, "selfAskLaneGateTailProbeMinAuthority", 0.65d);
        ReflectionTestUtils.setField(chain, "selfAskLaneGateTailProbeMaxContradictionRate", 0.25d);
        ReflectionTestUtils.setField(chain, "selfAskLaneGateTailProbeStrictMinAuthority", 0.50d);
        ReflectionTestUtils.setField(chain, "contradictionScorer", new FixedContradictionScorer());
        return chain;
    }

    private static void enableBranchQuality(DynamicRetrievalHandlerChain chain) {
        ReflectionTestUtils.setField(chain, "branchQualityProbe", new BranchQualityProbe());
        ReflectionTestUtils.setField(chain, "selfAskBranchQualityEnabled", true);
        ReflectionTestUtils.setField(chain, "selfAskBranchQualityMinContextContribution", 0.35d);
        ReflectionTestUtils.setField(chain, "selfAskBranchQualityMaxRiskPenalty", 0.65d);
        ReflectionTestUtils.setField(chain, "selfAskBranchQualityMoeTemperature", 0.65d);
        ReflectionTestUtils.setField(chain, "selfAskBranchQualityDppEnabled", true);
        ReflectionTestUtils.setField(chain, "selfAskBranchQualityDppMinLambda", 0.35d);
        ReflectionTestUtils.setField(chain, "selfAskBranchQualityDppMaxLambda", 0.85d);
        ReflectionTestUtils.setField(chain, "selfAskBranchQualityDppMinPressure", 0.20d);
        ReflectionTestUtils.setField(chain, "selfAskBranchQualityDppPoolMultiplier", 3);
        ReflectionTestUtils.setField(chain, "selfAskBranchQualityDppPoolMax", 24);
        ReflectionTestUtils.setField(chain, "selfAskBranchQualityBlackboxPriorEnabled", true);
        ReflectionTestUtils.setField(chain, "diversityEnabled", true);
        ReflectionTestUtils.setField(chain, "mmrLambda", 0.70d);
    }

    private static DynamicRetrievalHandlerChain chainWithAuthority() {
        DynamicRetrievalHandlerChain chain = new DynamicRetrievalHandlerChain(
                null, null, null, null, null,
                new AuthorityScorer("", "", "", "", "", "", "", "", "", 1.0d, 0.85d, 0.80d, 0.70d, 0.55d, 0.25d),
                null, null, null, null,
                null, null, null, null, null);
        ReflectionTestUtils.setField(chain, "selfAskLaneGateEnabled", true);
        ReflectionTestUtils.setField(chain, "selfAskLaneGateMinEvidenceRate", 0.45d);
        ReflectionTestUtils.setField(chain, "selfAskLaneGateMaxDuplicateRate", 0.70d);
        ReflectionTestUtils.setField(chain, "selfAskLaneGateMaxContradictionRate", 0.55d);
        ReflectionTestUtils.setField(chain, "selfAskLaneGateMinConfidence", 0.50d);
        ReflectionTestUtils.setField(chain, "selfAskLaneGateBypassConfidence", 0.25d);
        ReflectionTestUtils.setField(chain, "selfAskLaneGateCompressTopK", 3);
        ReflectionTestUtils.setField(chain, "selfAskLaneGateContradictionMaxPairs", 3);
        ReflectionTestUtils.setField(chain, "selfAskLaneGateCitationMin", 3);
        ReflectionTestUtils.setField(chain, "selfAskLaneGateMinStrongCitationRate", 0.50d);
        ReflectionTestUtils.setField(chain, "selfAskLaneGateMinDistinctCitations", 2);
        ReflectionTestUtils.setField(chain, "selfAskLaneGateMinCrossLaneSupport", 0.25d);
        ReflectionTestUtils.setField(chain, "selfAskLaneGateMaxGrandasContradictionRate", 0.40d);
        ReflectionTestUtils.setField(chain, "selfAskLaneGateAnchorCompressionMinGain", 0.10d);
        ReflectionTestUtils.setField(chain, "selfAskLaneGatePromptMinReadiness", 0.62d);
        ReflectionTestUtils.setField(chain, "selfAskLaneGateTailProbeMinSignal", 0.70d);
        ReflectionTestUtils.setField(chain, "selfAskLaneGateTailProbeMinAuthority", 0.65d);
        ReflectionTestUtils.setField(chain, "selfAskLaneGateTailProbeMaxContradictionRate", 0.25d);
        ReflectionTestUtils.setField(chain, "selfAskLaneGateTailProbeStrictMinAuthority", 0.50d);
        ReflectionTestUtils.setField(chain, "contradictionScorer", new FixedContradictionScorer());
        return chain;
    }

    private static Content content(String lane, String text, String url) {
        return Content.from(TextSegment.from(text, Metadata.from(Map.of(
                "retrieval_lane", lane,
                "retrieval_stage", "selfask3way",
                "url", url,
                "source", "web:selfask"))));
    }

    private static Content content(String lane, String text, String url, double score) {
        return Content.from(TextSegment.from(text, Metadata.from(Map.of(
                "retrieval_lane", lane,
                "retrieval_stage", "selfask3way",
                "url", url,
                "source", "web:selfask",
                "grandas_base_score", score))));
    }

    private static final class FixedContradictionScorer extends ContradictionScorer {
        @Override
        public double score(String a, String b) {
            if (String.valueOf(a).contains("2026") && String.valueOf(b).contains("2027")) {
                return 0.8d;
            }
            if (String.valueOf(a).contains("2027") && String.valueOf(b).contains("2026")) {
                return 0.8d;
            }
            return 0.05d;
        }
    }
}
