package com.example.lms.service.rag.orchestrator;

import com.example.lms.orchestration.OrchestrationHints;
import com.example.lms.plan.PlanHintApplier;
import com.example.lms.plan.PlanHints;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class PlanVectorPropertyBoundaryTest {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final String KEY = "retrieval.vector.enabled";

    @AfterEach
    void clearTrace() { TraceStore.clear(); }

    static Stream<Arguments> controls() {
        var rows = new ArrayList<Arguments>();
        for (String plan : List.of("brave.v1", "document_evidence.v1", "safe_autorun.v1"))
            for (String value : List.of("false", "absent")) {
                rows.add(Arguments.of(plan, "authored", value));
                if ("document_evidence.v1".equals(plan)) rows.add(Arguments.of(plan, "params-absent", value));
            }
        assertEquals(8, rows.size());
        return rows.stream();
    }

    @ParameterizedTest(name = "vector-property:{0}:{1}:{2}")
    @MethodSource("controls")
    void literalPropertyPairsKeepActualMetadataAndRetrieverCalls(String plan, String boundary, String value) throws Exception {
        ObjectNode authored = (ObjectNode) YAML.readTree(Files.readAllBytes(Path.of("main/resources/plans/" + plan + ".yaml")));
        assertTrue(properties(authored).path(KEY).isBoolean());
        assertTrue(properties(authored).path(KEY).booleanValue());
        ObjectNode baseline = authored.deepCopy();
        if ("params-absent".equals(boundary)) {
            assertEquals("document_evidence.v1", plan);
            ObjectNode paramsVector = (ObjectNode) baseline.path("params").path("retrieval").path("vector");
            var originalParam = paramsVector.remove("enabled");
            assertNotNull(originalParam);
            assertTrue(originalParam.isBoolean() && originalParam.booleanValue());
            ObjectNode restoredMask = baseline.deepCopy();
            ((ObjectNode) restoredMask.path("params").path("retrieval").path("vector")).set("enabled", originalParam);
            assertEquals(authored, restoredMask, "only the declared fixed masking value is removed");
        }
        ObjectNode input = baseline.deepCopy();
        if ("absent".equals(value)) properties(input).remove(KEY); else properties(input).put(KEY, false);
        assertNotEquals(baseline, input);
        ObjectNode restored = input.deepCopy();
        properties(restored).set(KEY, properties(baseline).get(KEY));
        assertEquals(baseline, restored, "only the literal dotted property changes within each pair");
        Boolean expectedMeta = "document_evidence.v1".equals(plan) && "authored".equals(boundary) ? Boolean.TRUE : null;
        assertEquals(expectedMeta != null, baseline.path("params").path("retrieval").path("vector").has("enabled"));
        Observed before = observe(plan, baseline);
        Observed after = observe(plan, input);
        assertEquals(before, after, "complete observed projections and actual request/call results without normalization");
        assertEquals(expectedMeta, before.effect().metadata().get(KEY));
        assertEquals(expectedMeta, before.effect().overrides().get(KEY));
        assertTrue(before.requestVector());
        assertTrue(before.requestKg());
        assertEquals(1, before.vectorCalls());
        assertEquals(1, before.kgCalls());
        assertEquals(nova(plan, baseline), nova(plan, input));
        System.out.printf("TBL07_VECTOR_PROPERTY plan=%s boundary=%s value=%s fullEffectEqual=true novaEqual=true metadata=%s vectorCallsPerRequest=1 kgCallsPerRequest=1 workflowReads=2 novaReads=2%n",
                plan, boundary, value, expectedMeta == null ? "absent" : "true");
    }

    private record Effect(PlanHints plan, JsonNode hints, Map<String, Object> metadata, Map<String, Object> overrides) {}
    private record Observed(Effect effect, boolean requestVector, boolean requestKg, int vectorCalls, int kgCalls) {}

    private static Observed observe(String plan, ObjectNode root) throws Exception {
        byte[] bytes = YAML.writeValueAsBytes(root);
        var lookups = new AtomicInteger(); var reads = new AtomicInteger(); var applications = new AtomicInteger();
        var effect = new AtomicReference<Effect>(); var result = new AtomicReference<Observed>();
        var resources = new DefaultResourceLoader() {
            @Override public Resource getResource(String location) {
                assertEquals("classpath:plans/" + plan + ".yaml", location);
                lookups.incrementAndGet();
                return new ByteArrayResource(bytes) {
                    @Override public String getFilename() { return plan + ".yaml"; }
                    @Override public InputStream getInputStream() throws java.io.IOException {
                        reads.incrementAndGet();
                        return super.getInputStream();
                    }
                };
            }
        };
        var applier = new PlanHintApplier(resources) {
            @Override public void applyToHintsAndMeta(PlanHints loaded, OrchestrationHints hints, Map<String, Object> meta) {
                assertFalse(loaded.isEmpty());
                assertEquals(plan, loaded.planId());
                assertNotEquals(Boolean.FALSE, loaded.allowRag());
                applications.incrementAndGet();
                super.applyToHintsAndMeta(loaded, hints, meta);
                GuardContext projectionGuard = new GuardContext();
                // Independent projection of the same loaded plan, not an assertion about URO's caller context.
                super.applyToGuardContext(loaded, projectionGuard);
                effect.set(new Effect(loaded, YAML.valueToTree(hints), new LinkedHashMap<>(meta),
                        new LinkedHashMap<>(projectionGuard.getPlanOverrides())));
            }
        };
        var vectorCalls = new AtomicInteger(); var kgCalls = new AtomicInteger();
        try {
            new ApplicationContextRunner()
                    .withBean(PlanHintApplier.class, () -> applier)
                    .withBean("vectorRetriever", ContentRetriever.class, () -> query -> {
                        vectorCalls.incrementAndGet();
                        return ReflectionTestUtils.invokeMethod(UnifiedRagOrchestratorPlanHintsTest.class, "contents", "property-local-vector", 3);
                    })
                    .withBean(com.example.lms.service.rag.LangChainRAGService.class,
                            () -> UnifiedRagOrchestratorPlanHintsTest.leafReturning(query -> {
                                vectorCalls.incrementAndGet();
                                return ReflectionTestUtils.invokeMethod(
                                        UnifiedRagOrchestratorPlanHintsTest.class, "contents", "property-local-vector", 3);
                            }))
                    .withBean("knowledgeGraphHandler", ContentRetriever.class, () -> query -> {
                        kgCalls.incrementAndGet();
                        return ReflectionTestUtils.invokeMethod(UnifiedRagOrchestratorPlanHintsTest.class, "contents", "property-local-kg", 2);
                    })
                    .withBean(UnifiedRagOrchestrator.class)
                    .run(context -> {
                        assertNull(context.getStartupFailure());
                        UnifiedRagOrchestrator.QueryRequest request = ReflectionTestUtils.invokeMethod(
                                UnifiedRagOrchestratorPlanHintsTest.class, "baseRequest", plan);
                        assertNotNull(request);
                        request.useWeb = false;
                        request.useVector = true;
                        request.useKg = true;
                        request.enableOnnx = false;
                        var response = context.getBean(UnifiedRagOrchestrator.class).query(request);
                        assertEquals(plan, response.debug.get("plan.id"));
                        assertEquals(true, response.debug.get("plan.applied"));
                        assertEquals("not_used", response.debug.get("planDsl.status"));
                        assertEquals(1, applications.get(), "the actual owner applies the loaded plan once");
                        assertNotNull(effect.get());
                        result.set(new Observed(effect.get(), request.useVector, request.useKg, vectorCalls.get(), kgCalls.get()));
                    });
            assertEquals(1, lookups.get());
            assertEquals(1, reads.get(), "the actual request loads this fresh resource, without a preloaded cache");
            assertNotNull(result.get());
            return result.get();
        } finally {
            TraceStore.clear();
        }
    }

    private static JsonNode nova(String plan, ObjectNode root) throws Exception {
        return YAML.valueToTree(ReflectionTestUtils.invokeMethod(
                Class.forName("com.example.lms.plan.PlanRootLlmBoundaryTest"), "nova", plan, root));
    }

    private static ObjectNode properties(ObjectNode root) {
        return (ObjectNode) root.path("plan").path("overrides").path("properties");
    }
}
