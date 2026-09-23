package com.example.lms.plan;

import com.example.lms.orchestration.OrchestrationHints;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class PlanStageProjectionBoundaryTest {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    @AfterEach
    void clearTrace() { TraceStore.clear(); }

    static Stream<Arguments> nonRetrievalControls() {
        return Stream.of("ap11_finance_special.v1", "ap1_auth_web.v1", "ap3_vec_dense.v1", "ap9_cost_saver.v1")
                .flatMap(plan -> Stream.of("explicit_order", "chain_fallback").flatMap(order ->
                        Stream.of("remove", "relabel").map(mutation -> Arguments.of(plan, order, mutation))));
    }

    @ParameterizedTest(name = "plan={0} order={1} stages={2}")
    @MethodSource("nonRetrievalControls")
    void nonRetrievalStageLabelsDoNotReachTypedHintsOrRequestOverrides(
            String planId, String orderControl, String mutation) throws Exception {
        var original = (ObjectNode) YAML.readTree(Files.readString(Path.of("main/resources/plans", planId + ".yaml")));
        var baselineRoot = original.deepCopy();
        assertTrue(original.path("retrieval").path("order").isArray());
        if (orderControl.equals("chain_fallback")) ((ObjectNode) baselineRoot.path("retrieval")).remove("order");
        else assertEquals("explicit_order", orderControl);
        var modified = baselineRoot.deepCopy();
        var chain = YAML.createArrayNode(); var retrievalStages = new ArrayList<String>();
        int unsupportedStages = 0;
        for (var stage : baselineRoot.path("chain")) {
            String label = stage.asText();
            if (label.startsWith("retrieve_")) { chain.add(stage); retrievalStages.add(label); }
            else {
                unsupportedStages++;
                if (mutation.equals("relabel")) chain.add("fixture_unsupported_stage_" + unsupportedStages);
                else assertEquals("remove", mutation);
            }
        }
        assertFalse(retrievalStages.isEmpty()); assertTrue(unsupportedStages > 0);
        modified.set("chain", chain); assertNotEquals(baselineRoot, modified);
        var restored = modified.deepCopy(); restored.set("chain", original.path("chain"));
        if (orderControl.equals("chain_fallback")) ((ObjectNode) restored.path("retrieval"))
                .set("order", original.path("retrieval").path("order"));
        assertEquals(original, restored, "only declared stage labels and explicit-order control change");
        assertEquals(retrievalStages, Stream.iterate(0, i -> i + 1).limit(chain.size())
                .map(i -> chain.get(i).asText()).filter(s -> s.startsWith("retrieve_")).toList());

        var baselineApplier = applier(planId, baselineRoot);
        var changedApplier = applier(planId, modified);
        var baseline = baselineApplier.load(planId); var changed = changedApplier.load(planId);
        assertEquals(planId, baseline.planId()); assertFalse(baseline.isEmpty());
        assertEquals(baseline, changed, "non-retrieval labels do not change any PlanHints field or raw value");
        assertFalse(changed.raw().containsKey("chain"));
        List<String> expected = planId.equals("ap1_auth_web.v1") ? List.of("web")
                : planId.equals("ap3_vec_dense.v1") ? List.of("vector") : List.of("web", "vector");
        assertEquals(expected, changed.retrievalOrder());
        Map<String, Object> baselineMeta = new LinkedHashMap<>();
        Map<String, Object> changedMeta = new LinkedHashMap<>();
        baselineApplier.applyToHintsAndMeta(baseline, OrchestrationHints.defaults(), baselineMeta);
        changedApplier.applyToHintsAndMeta(changed, OrchestrationHints.defaults(), changedMeta);
        assertEquals(baselineMeta, changedMeta); assertFalse(changedMeta.containsKey("chain"));
        var baselineGuard = new GuardContext(); var changedGuard = new GuardContext();
        baselineApplier.applyToGuardContext(baseline, baselineGuard);
        changedApplier.applyToGuardContext(changed, changedGuard);
        assertEquals(baselineGuard.getPlanOverrides(), changedGuard.getPlanOverrides());
        assertNull(changedGuard.getPlanOverride("chain"));
        System.out.printf("TBL07_AP_STAGE_BOUNDARY plan=%s order=%s mutation=%s retrievalStages=%d unsupportedStageLabels=%d fullHintsEqual=true metadataEqual=true guardOverridesEqual=true stageDispatcherClaim=false externalRequests=0%n",
                planId, orderControl, mutation, retrievalStages.size(), unsupportedStages);
    }

    static Stream<Arguments> pipelineControls() {
        return Stream.of("brave.v1", "safe_autorun.v1", "zero_break.v1").flatMap(plan ->
                Stream.of("authored", "relabel", "absent").map(mutation -> Arguments.of(plan, mutation)));
    }

    @ParameterizedTest(name = "pipeline plan={0} mutation={1}")
    @MethodSource("pipelineControls")
    void nestedPipelineIsAnUnwiredDiagnosticRatherThanAnExecutableStageProjection(
            String planId, String mutation) throws Exception {
        var original = (ObjectNode) YAML.readTree(Files.readString(Path.of("main/resources/plans", planId + ".yaml")));
        assertTrue(original.path("plan").path("pipeline").isArray());
        assertFalse(original.path("plan").path("pipeline").isEmpty());
        assertFalse(original.has("pipeline"), "projection-agent root pipeline is a distinct location");
        var modified = original.deepCopy(); var wrapper = (ObjectNode) modified.path("plan");
        if (mutation.equals("relabel")) wrapper.set("pipeline", YAML.createArrayNode().add("fixture_unsupported_pipeline"));
        else if (mutation.equals("absent")) wrapper.remove("pipeline");
        else assertEquals("authored", mutation);
        var restored = modified.deepCopy();
        ((ObjectNode) restored.path("plan")).set("pipeline", original.path("plan").path("pipeline"));
        assertEquals(original, restored, "only exact plan.pipeline changes");
        var baselineApplier = applier(planId, original); var changedApplier = applier(planId, modified);
        var baseline = baselineApplier.load(planId); var changed = changedApplier.load(planId);
        assertEquals(planId, baseline.planId()); assertFalse(baseline.isEmpty());
        assertEquals(planId, changed.planId()); assertFalse(changed.isEmpty());
        var baselineDiagnostics = PlanHintApplier.dslUnwiredKeys(baseline);
        assertTrue(baselineDiagnostics.contains("plan.pipeline"));
        var expectedDiagnostics = new ArrayList<>(baselineDiagnostics);
        if (mutation.equals("absent")) assertTrue(expectedDiagnostics.remove("plan.pipeline"));
        assertEquals(expectedDiagnostics, PlanHintApplier.dslUnwiredKeys(changed));
        ObjectNode normalized = YAML.valueToTree(changed);
        ((ObjectNode) normalized.path("raw")).set("dslUnwiredKeys", YAML.valueToTree(baselineDiagnostics));
        assertEquals(YAML.valueToTree(baseline), normalized, "only diagnostic presence may change; no stage values are projected");
        assertFalse(changed.raw().containsKey("pipeline")); assertFalse(changed.raw().containsKey("plan.pipeline"));
        Map<String, Object> baselineMeta = new LinkedHashMap<>(); Map<String, Object> changedMeta = new LinkedHashMap<>();
        baselineApplier.applyToHintsAndMeta(baseline, OrchestrationHints.defaults(), baselineMeta);
        changedApplier.applyToHintsAndMeta(changed, OrchestrationHints.defaults(), changedMeta);
        assertEquals(baselineMeta, changedMeta);
        assertFalse(changedMeta.containsKey("plan.pipeline")); assertFalse(changedMeta.containsKey("pipeline"));
        var baselineGuard = new GuardContext(); var changedGuard = new GuardContext();
        baselineApplier.applyToGuardContext(baseline, baselineGuard); changedApplier.applyToGuardContext(changed, changedGuard);
        assertEquals(baselineGuard.getPlanOverrides(), changedGuard.getPlanOverrides());
        assertNull(changedGuard.getPlanOverride("plan.pipeline"));
        var baselineNova = novaPlan(planId, original);
        var changedNova = novaPlan(planId, modified);
        assertTrue(baselineNova.enabled); assertTrue(changedNova.enabled);
        assertEquals(YAML.valueToTree(baselineNova), YAML.valueToTree(changedNova),
                "the separate Nova loader also projects no pipeline-stage values");
        System.out.printf("TBL07_PIPELINE_BOUNDARY plan=%s mutation=%s authoredStages=%d unwiredDiagnostic=%s typedProjection=false requestProjection=false novaProjectionEqual=true genericDispatcherClaim=false externalRequests=0%n",
                planId, mutation, original.path("plan").path("pipeline").size(), !mutation.equals("absent"));
    }

    private static com.example.lms.nova.BravePlan novaPlan(String planId, ObjectNode root) throws Exception {
        byte[] yaml = YAML.writeValueAsBytes(root);
        Thread thread = Thread.currentThread(); ClassLoader previous = thread.getContextClassLoader();
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        ClassLoader controlled = new ClassLoader(previous) {
            @Override public java.io.InputStream getResourceAsStream(String name) {
                if (name.equals("plans/" + planId + ".yaml")) {
                    reads.incrementAndGet(); return new java.io.ByteArrayInputStream(yaml);
                }
                return super.getResourceAsStream(name);
            }
        };
        try {
            thread.setContextClassLoader(controlled);
            var result = new com.example.lms.nova.PlanDslLoader().load(planId);
            assertEquals(1, reads.get(), "real Nova loader must read the controlled complete YAML once");
            return result;
        } finally { thread.setContextClassLoader(previous); }
    }

    private static PlanHintApplier applier(String planId, ObjectNode root) throws Exception {
        byte[] yaml = YAML.writeValueAsBytes(root);
        return new PlanHintApplier(new DefaultResourceLoader() {
            @Override public Resource getResource(String location) {
                if (location.equals("classpath:plans/" + planId + ".yaml")) return new ByteArrayResource(yaml) {
                    @Override public String getFilename() { return planId + ".yaml"; }
                };
                return super.getResource(location);
            }
        });
    }
}
