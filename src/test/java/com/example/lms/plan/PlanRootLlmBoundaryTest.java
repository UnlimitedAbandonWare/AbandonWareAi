package com.example.lms.plan;

import com.example.lms.nova.BravePlan;
import com.example.lms.orchestration.OrchestrationHints;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class PlanRootLlmBoundaryTest {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final List<String> PLANS = List.of(
            "ap11_finance_special.v1", "ap1_auth_web.v1", "ap3_vec_dense.v1", "ap9_cost_saver.v1",
            "brave.v1", "hyper_nova.v1", "kg_first.v1", "recency_first.v1",
            "rulebreak.v1", "safe.v1", "safe_autorun.v1", "zero_break.v1");

    @AfterEach
    void clearTrace() { TraceStore.clear(); }

    static Stream<Arguments> rootCases() {
        return PLANS.stream().flatMap(plan -> Stream.of("model", "provider").flatMap(field ->
                Stream.of("changed", "absent").map(value -> Arguments.of(plan, field, value))));
    }

    @ParameterizedTest(name = "root:{0}:{1}:{2}")
    @MethodSource("rootCases")
    void rootLlmLeavesDoNotReachEitherCurrentPlanProjection(String plan, String field, String value) throws Exception {
        ObjectNode authored = authored(plan);
        assertTrue(authored.path("llm").path("model").isTextual());
        assertTrue(authored.path("llm").path("provider").isTextual());
        ObjectNode input = authored.deepCopy();
        ObjectNode llm = (ObjectNode) input.get("llm");
        if ("absent".equals(value)) llm.remove(field);
        else llm.put(field, "fixture_root_" + field);
        assertTrue(input.path("llm").isObject(), "retain the diagnostic container");
        assertNotEquals(authored, input);
        ObjectNode restored = input.deepCopy();
        ((ObjectNode) restored.get("llm")).set(field, authored.path("llm").get(field));
        assertEquals(authored, restored, "only one exact root leaf changes");
        Effect baseline = apply(plan, authored);
        Effect actual = apply(plan, input);
        assertEquals(baseline, actual, "full typed/raw hints, orchestration hints, metadata and guard overrides; no normalization");
        assertEquals(YAML.valueToTree(nova(plan, authored)), YAML.valueToTree(nova(plan, input)),
                "compare every field from the alternate Nova loader using the same resources");
        System.out.printf("TBL07_ROOT_LLM plan=%s field=%s value=%s fullEffectEqual=true novaDtoEqual=true applierReads=2 novaReads=2%n",
                plan, field, value);
    }

    static Stream<Arguments> passthroughCases() {
        return Stream.of("params", "knobs").flatMap(container ->
                Stream.of("model", "provider").map(field -> Arguments.of(container, field)));
    }

    @ParameterizedTest(name = "passthrough:{0}:{1}")
    @MethodSource("passthroughCases")
    void nestedPassthroughUsesItsOwnInputPath(String container, String field) throws Exception {
        String plan = "safe.v1";
        ObjectNode baselineRoot = authored(plan);
        assertFalse(container(baselineRoot, container).has("llm"));
        ObjectNode input = baselineRoot.deepCopy();
        String token = "fixture_nested_" + field;
        ObjectNode injected = YAML.createObjectNode().put(field, token);
        container(input, container).set("llm", injected);
        ObjectNode restored = input.deepCopy();
        container(restored, container).remove("llm");
        assertEquals(baselineRoot, restored);
        assertEquals(baselineRoot.get("llm"), input.get("llm"), "root values remain fixed");
        Effect baseline = apply(plan, baselineRoot);
        Effect actual = apply(plan, input);
        String key = "llm." + field;
        assertFalse(baseline.metadata().containsKey(key));
        assertFalse(baseline.overrides().containsKey(key));
        ObjectNode expectedPlan = YAML.valueToTree(baseline.plan());
        ((ObjectNode) expectedPlan.path("raw").path(container)).set("llm", injected);
        assertEquals(expectedPlan, YAML.valueToTree(actual.plan()), "only declared raw passthrough input changes");
        assertEquals(baseline.hints(), actual.hints());
        var expectedMeta = new LinkedHashMap<>(baseline.metadata()); expectedMeta.put(key, token);
        var expectedOverrides = new LinkedHashMap<>(baseline.overrides()); expectedOverrides.put(key, token);
        assertEquals(expectedMeta, actual.metadata());
        assertEquals(expectedOverrides, actual.overrides());
        System.out.printf("TBL07_LLM_PASSTHROUGH container=%s field=%s exactProjectedDelta=true rootPreserved=true applierReads=2 providerWire=false%n",
                container, field);
    }

    private static ObjectNode container(ObjectNode root, String name) {
        return "params".equals(name) ? root.with("params") : root.with("plan").with("overrides").with("knobs");
    }

    private static ObjectNode authored(String plan) throws Exception {
        return (ObjectNode) YAML.readTree(Files.readAllBytes(Path.of("main/resources/plans/" + plan + ".yaml")));
    }

    private record Effect(PlanHints plan, JsonNode hints, Map<String, Object> metadata, Map<String, Object> overrides) {}

    private static Effect apply(String plan, ObjectNode root) throws Exception {
        byte[] bytes = YAML.writeValueAsBytes(root);
        AtomicInteger lookups = new AtomicInteger(); AtomicInteger reads = new AtomicInteger();
        PlanHintApplier applier = new PlanHintApplier(new DefaultResourceLoader() {
            @Override public Resource getResource(String location) {
                assertEquals("classpath:plans/" + plan + ".yaml", location);
                lookups.incrementAndGet();
                return new ByteArrayResource(bytes) {
                    @Override public String getFilename() { return plan + ".yaml"; }
                    @Override public InputStream getInputStream() throws java.io.IOException {
                        reads.incrementAndGet(); return super.getInputStream();
                    }
                };
            }
        });
        PlanHints loaded = applier.load(plan);
        assertFalse(loaded.isEmpty()); assertEquals(plan, loaded.planId());
        assertTrue(PlanHintApplier.dslUnwiredKeys(loaded).contains("llm"));
        assertFalse(loaded.raw().containsKey("llm"));
        var hints = OrchestrationHints.defaults(); var meta = new LinkedHashMap<String, Object>();
        var guard = new GuardContext();
        applier.applyToHintsAndMeta(loaded, hints, meta);
        applier.applyToGuardContext(loaded, guard);
        assertEquals(1, lookups.get()); assertEquals(1, reads.get(), "fresh loader reads its own bytes");
        return new Effect(loaded, YAML.valueToTree(hints), meta, new LinkedHashMap<>(guard.getPlanOverrides()));
    }

    private static BravePlan nova(String plan, ObjectNode root) {
        // Existing helper asserts the selected resource stream and restores the context classloader.
        BravePlan result = ReflectionTestUtils.invokeMethod(PlanWhenProjectionBoundaryTest.class, "nova",
                Map.of(plan, root), plan, (Supplier<BravePlan>) () -> new com.example.lms.nova.PlanDslLoader().load(plan));
        assertNotNull(result); assertTrue(result.enabled, "disabled fallback must not explain equality");
        return result;
    }
}
