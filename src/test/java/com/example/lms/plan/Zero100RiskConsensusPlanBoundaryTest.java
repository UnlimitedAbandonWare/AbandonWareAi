package com.example.lms.plan;

import ai.abandonware.nova.config.Zero100EngineProperties;
import ai.abandonware.nova.orch.aop.Zero100SessionAspect;
import ai.abandonware.nova.orch.zero100.Zero100BranchScheduler;
import ai.abandonware.nova.orch.zero100.Zero100SessionRegistry;
import com.example.lms.orchestration.OrchestrationHints;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.service.rag.*;
import com.example.lms.service.rag.fusion.WeightedReciprocalRankFuser;
import com.example.lms.service.rag.handler.DynamicRetrievalHandlerChain;
import com.example.lms.service.rag.handler.KnowledgeGraphHandler;
import com.example.lms.strategy.RetrievalOrderService;
import com.example.lms.util.MetadataUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.core.io.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class Zero100RiskConsensusPlanBoundaryTest {
    private static final String ID = "zero100.v1", PREFIX = "search.zero100.riskConsensus.";
    private static final ObjectMapper JSON = new ObjectMapper(), YAML = new ObjectMapper(new YAMLFactory());
    private static final Map<String, Object> DEFAULTS = Map.of(
            "enabled", true, "minLaneCoverage", 2, "riskPenaltyLambda", 0.45d,
            "minRrfWeight", 0.05d, "maxRrfWeight", 1.25d);
    private static final Map<String, Object> CHANGED = Map.of(
            "enabled", false, "minLaneCoverage", 3, "riskPenaltyLambda", 0.9d,
            "minRrfWeight", 1.2d, "maxRrfWeight", 0.1d);

    @AfterEach void clear() { TraceStore.clear(); GuardContextHolder.clear(); }

    static Stream<Arguments> pairs() {
        return DEFAULTS.keySet().stream().sorted().flatMap(k ->
                Stream.of("changed", "absent").map(v -> Arguments.of(k, v)));
    }

    @ParameterizedTest(name = "zero-risk-pair:{0}:{1}")
    @MethodSource("pairs")
    void exactAuthoredKeysReachSchedulerButPublicHandlerDoesNotInvokePrivateRiskGate(String field, String variant) throws Throwable {
        String raw = authored(), edited = replace(raw, field, variant.equals("absent") ? null : CHANGED.get(field).toString());
        ObjectNode restored = tree(edited);
        ((ObjectNode) restored.path("params")).set(PREFIX + field, tree(raw).path("params").path(PREFIX + field));
        assertEquals(tree(raw), restored, "only one literal dotted key changes semantically");
        Effect before = run(raw, Map.of()), after = run(edited, Map.of());
        ObjectNode expectedProjection = before.projection().deepCopy();
        for (String section : List.of("metadata", "overrides")) {
            ObjectNode node = (ObjectNode) expectedProjection.path(section);
            if (variant.equals("absent")) node.remove(PREFIX + field);
            else node.set(PREFIX + field, JSON.valueToTree(CHANGED.get(field)));
        }
        ObjectNode params = (ObjectNode) expectedProjection.path("plan").path("raw").path("params");
        if (variant.equals("absent")) params.remove(PREFIX + field);
        else params.set(PREFIX + field, JSON.valueToTree(CHANGED.get(field)));
        assertEquals(expectedProjection, after.projection(), "complete producer projection delta");
        ObjectNode expectedSchedule = before.schedule().deepCopy();
        expectedSchedule.set(scheduleKey(field), JSON.valueToTree(variant.equals("absent") ? DEFAULTS.get(field) : CHANGED.get(field)));
        assertEquals(expectedSchedule, after.schedule(), "other Schedule components are unchanged");
        assertEquals(before.fuserInputs(), after.fuserInputs());
        assertEquals(before.publicOutput(), after.publicOutput());
        if (variant.equals("absent")) {
            assertEquals(before.privateWeights(), after.privateWeights());
            assertEquals(before.privateStatus(), after.privateStatus());
        } else if (field.equals("minLaneCoverage")) {
            assertEquals(before.privateWeights(), after.privateWeights());
            assertEquals("READY", before.privateStatus()); assertEquals("LOW_COVERAGE_FAILSOFT", after.privateStatus());
        } else {
            assertNotEquals(before.privateWeights(), after.privateWeights(), "separate private calculation responds");
        }
        assertPrivateResult(after);
        System.out.printf("TBL07_ZERO_RISK_PAIR field=%s variant=%s fullProducerDelta=true schedulerDelta=true publicRiskEvents=0 selfAskCalls=2 fuserCalls=2 fuserArgsEqual=true outputEqual=true privateGateExplicit=true resourceReads=2%n", field, variant);
    }

    static Stream<Arguments> boundaries() {
        return Stream.of(
                Arguments.of("enabled", "'bad'", true), Arguments.of("enabled", "0", false),
                Arguments.of("minLaneCoverage", "0", 1), Arguments.of("minLaneCoverage", "9", 3),
                Arguments.of("minLaneCoverage", "'bad'", 2),
                Arguments.of("riskPenaltyLambda", "-1.0", 0.0d), Arguments.of("riskPenaltyLambda", "2.0", 1.0d),
                Arguments.of("riskPenaltyLambda", "'bad'", 0.45d), Arguments.of("riskPenaltyLambda", "'NaN'", 0.0d),
                Arguments.of("riskPenaltyLambda", ".nan", null),
                Arguments.of("minRrfWeight", "-1.0", 0.01d), Arguments.of("minRrfWeight", "3.0", 1.25d),
                Arguments.of("minRrfWeight", "'bad'", 0.05d),
                Arguments.of("maxRrfWeight", "-1.0", 0.05d), Arguments.of("maxRrfWeight", "3.0", 2.5d),
                Arguments.of("maxRrfWeight", "'bad'", 1.25d),
                Arguments.of("maxWithRaisedMin", "0.2", 1.1d));
    }

    @ParameterizedTest(name = "zero-risk-bound:{0}:{1}")
    @MethodSource("boundaries")
    void realLoadAndAspectRetainDefaultClampAndEffectiveMinimumRules(String field, String value, Object expected) throws Throwable {
        String raw = authored();
        if (field.equals("maxWithRaisedMin")) {
            raw = replace(replace(raw, "minRrfWeight", "1.1"), "maxRrfWeight", value);
        } else raw = replace(raw, field, value);
        if (value.equals(".nan")) {
            Exception failure = assertThrows(Exception.class, () -> YAML.readValue(replace(authored(), field, value), Map.class));
            Loaded loaded = loadRaw(raw);
            assertTrue(loaded.plan().isEmpty()); assertEquals(ID, loaded.plan().planId());
            GuardContext caller = new GuardContext(); caller.putPlanOverride(PREFIX + field, 0.7d);
            OrchestrationHints hints = OrchestrationHints.defaults();
            Map<String, Object> metadata = new LinkedHashMap<>(Map.of("sentinel", "kept"));
            JsonNode beforeHints = JSON.valueToTree(hints);
            loaded.applier().applyToGuardContext(loaded.plan(), caller);
            loaded.applier().applyToHintsAndMeta(loaded.plan(), hints, metadata);
            assertEquals(Map.of(PREFIX + field, 0.7d), caller.getPlanOverrides());
            assertEquals(Map.of("sentinel", "kept"), metadata); assertEquals(beforeHints, JSON.valueToTree(hints));
            System.out.printf("TBL07_ZERO_RISK_REJECT token=yaml_nan parserRejected=true failureClass=%s fullPlanEmpty=true preservedCaller=true publicHandlerExecuted=false resourceReads=1%n", failure.getClass().getSimpleName());
            return;
        }
        Effect effect = run(raw, Map.of());
        String actualField = field.equals("maxWithRaisedMin") ? "maxRrfWeight" : field;
        assertEquals(JSON.valueToTree(expected), effect.schedule().path(scheduleKey(actualField)));
        if (field.equals("maxWithRaisedMin")) assertEquals(1.1d, effect.schedule().path("minRrfWeight").doubleValue());
        assertPrivateResult(effect);
        System.out.printf("TBL07_ZERO_RISK_BOUND field=%s case=%s publicRiskEvents=0 privateGateExplicit=true resourceReads=1%n",
                field, value.replace("'", ""));
    }

    static Stream<String> fields() { return DEFAULTS.keySet().stream().sorted(); }

    @ParameterizedTest(name = "zero-risk-caller:{0}")
    @MethodSource("fields")
    void existingRequestOverrideWinsWhilePlanMetadataRetainsAuthoredValue(String field) throws Throwable {
        Effect effect = run(authored(), Map.of(PREFIX + field, CHANGED.get(field)));
        assertEquals(JSON.valueToTree(CHANGED.get(field)), effect.projection().path("overrides").path(PREFIX + field));
        assertEquals(JSON.valueToTree(DEFAULTS.get(field)), effect.projection().path("metadata").path(PREFIX + field));
        assertEquals(JSON.valueToTree(CHANGED.get(field)), effect.schedule().path(scheduleKey(field)));
        assertPrivateResult(effect);
        System.out.printf("TBL07_ZERO_RISK_CALLER field=%s callerWins=true metadataAuthored=true publicRiskEvents=0 privateGateExplicit=true resourceReads=1%n", field);
    }

    private record Effect(ObjectNode projection, ObjectNode schedule, JsonNode fuserInputs, JsonNode publicOutput,
                          Map<String, Double> privateWeights, String privateStatus) {}
    private record Producer(PlanHints plan, JsonNode hints, Map<String, Object> metadata, Map<String, Object> overrides) {}
    private record Doc(String text, Map<String, Object> metadata) {}

    private record Loaded(PlanHintApplier applier, PlanHints plan) {}
    private static Loaded loadRaw(String raw) {
        byte[] bytes = raw.getBytes(StandardCharsets.UTF_8); AtomicInteger lookups = new AtomicInteger(), reads = new AtomicInteger();
        PlanHintApplier applier = new PlanHintApplier(new DefaultResourceLoader() {
            @Override public Resource getResource(String location) {
                assertEquals("classpath:plans/" + ID + ".yaml", location); lookups.incrementAndGet();
                return new ByteArrayResource(bytes) {
                    @Override public String getFilename() { return ID + ".yaml"; }
                    @Override public InputStream getInputStream() throws java.io.IOException { reads.incrementAndGet(); return super.getInputStream(); }
                };
            }
        });
        PlanHints plan = applier.load(ID); assertEquals(ID, plan.planId());
        assertEquals(1, reads.get()); assertEquals(1, lookups.get());
        return new Loaded(applier, plan);
    }

    @SuppressWarnings("unchecked")
    private static Effect run(String raw, Map<String, Object> caller) throws Throwable {
        TraceStore.clear(); GuardContextHolder.clear();
        Loaded loaded = loadRaw(raw); PlanHintApplier applier = loaded.applier(); PlanHints plan = loaded.plan();
        assertFalse(plan.isEmpty());
        OrchestrationHints hints = OrchestrationHints.defaults(); Map<String, Object> meta = new LinkedHashMap<>();
        GuardContext guard = new GuardContext(); caller.forEach(guard::putPlanOverride);
        applier.applyToHintsAndMeta(plan, hints, meta); applier.applyToGuardContext(plan, guard);
        ObjectNode projection = JSON.valueToTree(new Producer(plan, JSON.valueToTree(hints), meta, new LinkedHashMap<>(guard.getPlanOverrides())));
        GuardContextHolder.set(guard);
        Zero100SessionRegistry.Slice slice = new Zero100SessionRegistry.Slice(
                "synthetic-zero-risk", 81_000L, 1_000L, 101_000L, 400L, 0L, 0.2d,
                Zero100SessionRegistry.ClampMode.RECALL_CLAMP, 2_500L, 2_500L, "synthetic");
        ObjectNode schedule = JSON.valueToTree(Zero100BranchScheduler.schedule(slice, guard));
        Zero100EngineProperties props = new Zero100EngineProperties(); props.setEngineEnabled(true);
        Zero100SessionRegistry registry = mock(Zero100SessionRegistry.class);
        when(registry.touch(anyString(), anyString(), anyLong(), anyLong(), anyLong(), anyLong())).thenReturn(slice);
        Zero100SessionAspect aspect = new Zero100SessionAspect(props, registry, new MockEnvironment());
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class); when(pjp.getArgs()).thenReturn(new Object[]{"synthetic risk contract"});
        Effect[] captured = new Effect[1];
        when(pjp.proceed()).thenAnswer(invocation -> {
            for (String field : DEFAULTS.keySet())
                assertEquals(schedule.path(scheduleKey(field)), JSON.valueToTree(TraceStore.get("zero100.riskConsensus." + field)));
            assertEquals(true, TraceStore.get("zero100.consensus.enabled"));
            assertNull(TraceStore.get("zero100.scheduler.failureClass"));
            var self = mock(SelfAskWebSearchRetriever.class); var gate = mock(QueryComplexityGate.class);
            var web = mock(WebSearchRetriever.class); var rag = mock(LangChainRAGService.class);
            var kg = mock(KnowledgeGraphHandler.class); var order = mock(RetrievalOrderService.class);
            var fuser = spy(new WeightedReciprocalRankFuser(60, null, ""));
            List<Content> selfDocs = List.of(content("self-bq", "BQ"), content("self-er", "ER"));
            List<Content> webDocs = List.of(content("web", "web")), vectorDocs = List.of(content("vector", "vector"));
            List<Content> kgDocs = List.of(content("kg", "kg"));
            when(gate.needsSelfAsk(anyString())).thenReturn(true); when(self.retrieve(any(Query.class))).thenReturn(selfDocs);
            when(web.retrieve(any(Query.class))).thenReturn(webDocs); when(kg.retrieve(any(Query.class))).thenReturn(kgDocs);
            when(rag.asContentRetriever("synthetic-index")).thenReturn(q -> vectorDocs);
            when(order.decideOrder(anyString())).thenReturn(List.of(RetrievalOrderService.Source.WEB, RetrievalOrderService.Source.VECTOR, RetrievalOrderService.Source.KG));
            DynamicRetrievalHandlerChain chain = new DynamicRetrievalHandlerChain(
                    null, self, null, null, web, null, rag, null, gate, kg, order, fuser, null, null, null);
            ReflectionTestUtils.setField(chain, "pineconeIndexName", "synthetic-index");
            ReflectionTestUtils.setField(chain, "topK", 10);
            List<Content> output = new ArrayList<>();
            chain.handle(QueryUtils.buildQuery("synthetic risk contract", meta), output);
            verify(self, times(1)).retrieve(any(Query.class));
            verify(fuser, times(1)).fuse(eq(List.of(webDocs, vectorDocs, kgDocs)), eq(10));
            verify(fuser, times(1)).fuse(eq(List.of(webDocs, vectorDocs, kgDocs)), isNull(), eq(10));
            assertEquals(5, output.size());
            assertEquals(Set.of("self-bq", "self-er", "web", "vector", "kg"),
                    output.stream().map(c -> c.textSegment().metadata().getString("doc_id")).collect(java.util.stream.Collectors.toSet()));
            assertNull(TraceStore.get("zero100.consensus.laneRisk.events"), "public handle did not call the private risk gate");
            assertNull(TraceStore.get("zero100.consensus.rrfWeights"));
            assertNull(TraceStore.get("selfask.laneGate.events"));
            JsonNode inputs = JSON.valueToTree(List.of(docs(webDocs), docs(vectorDocs), docs(kgDocs)));
            JsonNode publicOutput = JSON.valueToTree(docs(output));

            // Explicit separate characterization. This is not a call made by public handle.
            TraceStore.put("zero100.riskConsensus.laneRdi.BQ", 0.8d);
            TraceStore.put("zero100.riskConsensus.laneRdi.ER", 0.8d);
            assertNotNull(ReflectionTestUtils.invokeMethod(chain, "gateSelfAskLanes", selfDocs, webDocs, vectorDocs));
            Map<String, Double> weights = (Map<String, Double>) TraceStore.get("zero100.consensus.rrfWeights");
            assertNotNull(weights); assertEquals(Set.of("BQ", "ER"), weights.keySet());
            assertEquals(2, TraceStore.get("zero100.consensus.laneCoverage"));
            assertEquals(2, ((List<?>) TraceStore.get("zero100.consensus.laneRisk.events")).size());
            captured[0] = new Effect(projection, schedule, inputs, publicOutput, Map.copyOf(weights),
                    String.valueOf(TraceStore.get("zero100.consensus.status")));
            return "synthetic-complete";
        });
        try {
            assertEquals("synthetic-complete", aspect.aroundChatEntry(pjp));
            verify(pjp, times(1)).proceed();
            verify(registry, times(1)).touch(anyString(), anyString(), anyLong(), anyLong(), anyLong(), anyLong());
            assertNotNull(captured[0]); return captured[0];
        } finally { TraceStore.clear(); GuardContextHolder.clear(); }
    }

    private static void assertPrivateResult(Effect e) {
        double min = e.schedule().path("minRrfWeight").doubleValue(), max = e.schedule().path("maxRrfWeight").doubleValue();
        boolean enabled = e.schedule().path("riskConsensusEnabled").booleanValue();
        double lambda = e.schedule().path("riskPenaltyLambda").doubleValue();
        // Fixed nonduplicate lane evidence: RDI .8, authority .5, contradiction/branch risk zero => laneRisk .33.
        double expected = Math.max(min, Math.min(max, Math.max(min, Math.min(max, 1.0d)) * (enabled ? 1.0d - lambda * 0.33d : 1.0d)));
        expected = Math.round(expected * 10_000.0d) / 10_000.0d;
        for (double weight : e.privateWeights().values()) assertEquals(expected, weight, 0.00001d);
        assertEquals(e.schedule().path("minLaneCoverage").intValue() > 2 ? "LOW_COVERAGE_FAILSOFT" : "READY", e.privateStatus());
    }
    private static String scheduleKey(String field) { return field.equals("enabled") ? "riskConsensusEnabled" : field; }
    private static Content content(String id, String lane) {
        return Content.from(TextSegment.from("synthetic evidence " + id,
                Metadata.from(Map.of("doc_id", id, "retrieval_lane", lane, "url", "https://" + id + ".example.invalid/evidence"))));
    }
    private static List<Doc> docs(List<Content> list) {
        return list.stream().map(c -> new Doc(c.textSegment().text(), new TreeMap<>(MetadataUtils.toMap(c.textSegment().metadata())))).toList();
    }
    private static String authored() throws Exception { return Files.readString(Path.of("main/resources/plans/" + ID + ".yaml"), StandardCharsets.UTF_8); }
    private static ObjectNode tree(String raw) throws Exception { return (ObjectNode) YAML.readTree(raw); }
    private static String replace(String raw, String field, String value) {
        String newline = raw.contains("\r\n") ? "\r\n" : "\n";
        String old = "  " + PREFIX + field + ": " + DEFAULTS.get(field) + newline;
        assertTrue(raw.contains(old)); assertEquals(raw.indexOf(old), raw.lastIndexOf(old));
        return raw.replace(old, value == null ? "" : "  " + PREFIX + field + ": " + value + newline);
    }
}

