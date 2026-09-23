package com.example.lms.plan;

import com.example.lms.domain.enums.AnswerMode;
import com.example.lms.nova.BravePlan;
import com.example.lms.nova.NovaRequestContext;
import com.example.lms.nova.PlanSelectionService;
import com.example.lms.orchestration.OrchestrationHints;
import com.example.lms.orchestration.WorkflowOrchestrator;
import com.example.lms.rag.model.QueryDomain;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class PlanWhenProjectionBoundaryTest {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final List<String> PLANS = List.of("brave.v1", "safe_autorun.v1", "zero_break.v1");

    @AfterEach
    void clearTrace() { TraceStore.clear(); }

    static Stream<Arguments> controls() {
        return PLANS.stream().flatMap(plan -> Stream.of("authored", "true", "false", "absent_any")
                .map(mutation -> Arguments.of(plan, mutation)));
    }

    @ParameterizedTest(name = "when plan={0} mutation={1}")
    @MethodSource("controls")
    void whenAnyDoesNotControlCurrentProjectionOrBoundedPlanSelection(String planId, String mutation) throws Exception {
        var original = resources(); var modified = new LinkedHashMap<String, ObjectNode>();
        original.forEach((id, root) -> modified.put(id, root.deepCopy()));
        var originalWhen = original.get(planId).path("plan").path("when");
        assertTrue(originalWhen.path("any").isArray()); assertFalse(originalWhen.path("any").isEmpty());
        assertEquals(1, originalWhen.size(), "the authored when container has only the selected any key");
        var when = (ObjectNode) modified.get(planId).path("plan").path("when");
        if (mutation.equals("absent_any")) when.remove("any");
        else if (!mutation.equals("authored")) {
            assertTrue(mutation.equals("true") || mutation.equals("false"));
            when.set("any", YAML.createArrayNode().add(Boolean.parseBoolean(mutation)));
        }
        var restored = modified.get(planId).deepCopy();
        ((ObjectNode) restored.path("plan").path("when")).set("any", originalWhen.path("any"));
        assertEquals(original.get(planId), restored, "only exact plan.when.any changes");

        var originalReads = new ArrayList<String>(); var modifiedReads = new ArrayList<String>();
        var baselineApplier = applier(original, originalReads); var changedApplier = applier(modified, modifiedReads);
        String explicitId = planId.equals("zero_break.v1") ? "zero_break" : planId;
        var baselineGuard = new GuardContext(); var changedGuard = new GuardContext();
        baselineGuard.setPlanId(explicitId); changedGuard.setPlanId(explicitId);
        assertEquals(explicitId, new WorkflowOrchestrator(baselineApplier).ensurePlanSelected(
                baselineGuard, AnswerMode.BALANCED, QueryDomain.GENERAL, "fixture", false));
        assertEquals(explicitId, new WorkflowOrchestrator(changedApplier).ensurePlanSelected(
                changedGuard, AnswerMode.BALANCED, QueryDomain.GENERAL, "fixture", false));
        assertTrue(originalReads.isEmpty()); assertTrue(modifiedReads.isEmpty(), "explicit ID is kept before any YAML read");
        var baseline = baselineApplier.load(baselineGuard.getPlanId());
        var changed = changedApplier.load(changedGuard.getPlanId());
        assertEquals(List.of(planId), originalReads); assertEquals(originalReads, modifiedReads);
        assertEquals(planId, baseline.planId()); assertEquals(planId, changed.planId());
        assertFalse(baseline.isEmpty()); assertEquals(baseline, changed, "all typed/raw fields remain equal");
        assertTrue(PlanHintApplier.dslUnwiredKeys(changed).contains("plan.when"), "container presence remains diagnostic-only even when any is absent");
        assertFalse(changed.raw().containsKey("plan.when")); assertFalse(changed.raw().containsKey("when"));
        Map<String, Object> baselineMeta = new LinkedHashMap<>(); Map<String, Object> changedMeta = new LinkedHashMap<>();
        baselineApplier.applyToHintsAndMeta(baseline, OrchestrationHints.defaults(), baselineMeta);
        changedApplier.applyToHintsAndMeta(changed, OrchestrationHints.defaults(), changedMeta);
        assertEquals(baselineMeta, changedMeta);
        baselineApplier.applyToGuardContext(baseline, baselineGuard); changedApplier.applyToGuardContext(changed, changedGuard);
        assertEquals(baselineGuard.getPlanOverrides(), changedGuard.getPlanOverrides());
        assertNull(changedGuard.getPlanOverride("plan.when")); assertNull(changedGuard.getPlanOverride("plan.when.any"));

        var baselineNova = nova(original, planId, () -> new com.example.lms.nova.PlanDslLoader().load(planId));
        var changedNova = nova(modified, planId, () -> new com.example.lms.nova.PlanDslLoader().load(planId));
        assertTrue(baselineNova.enabled); assertTrue(changedNova.enabled);
        assertEquals(YAML.valueToTree(baselineNova), YAML.valueToTree(changedNova));
        boolean previousBrave = NovaRequestContext.isBrave();
        try {
            for (boolean brave : List.of(false, true)) {
                NovaRequestContext.setBrave(brave);
                String expected = brave ? "brave.v1" : "safe_autorun.v1";
                var originalSelection = nova(original, expected, () -> new PlanSelectionService(new com.example.lms.nova.PlanDslLoader()).resolve());
                var changedSelection = nova(modified, expected, () -> new PlanSelectionService(new com.example.lms.nova.PlanDslLoader()).resolve());
                assertTrue(originalSelection.enabled); assertTrue(changedSelection.enabled);
                assertEquals(YAML.valueToTree(originalSelection), YAML.valueToTree(changedSelection));
                assertEquals(brave, NovaRequestContext.isBrave());
            }
        } finally { NovaRequestContext.setBrave(previousBrave); }

        for (AnswerMode mode : List.of(AnswerMode.BALANCED, AnswerMode.CREATIVE)) {
            String expected = mode == AnswerMode.CREATIVE ? "brave.v1" : "safe_autorun.v1";
            var beforeReads = new ArrayList<String>(); var afterReads = new ArrayList<String>();
            var beforeCtx = new GuardContext(); var afterCtx = new GuardContext();
            assertEquals(expected, automatic(applier(original, beforeReads)).ensurePlanSelected(
                    beforeCtx, mode, QueryDomain.GENERAL, "fixture", false));
            assertEquals(expected, automatic(applier(modified, afterReads)).ensurePlanSelected(
                    afterCtx, mode, QueryDomain.GENERAL, "fixture", false));
            assertEquals(expected, beforeCtx.getPlanId()); assertEquals(expected, afterCtx.getPlanId());
            assertEquals(List.of(expected), beforeReads); assertEquals(beforeReads, afterReads);
        }
        System.out.printf("TBL07_WHEN_BOUNDARY plan=%s mutation=%s fullHintsEqual=true metadataEqual=true guardOverridesEqual=true novaDtoEqual=true explicitReadsBeforeSelection=0 novaFlagSelections=2 workflowModeSelections=2 mvcProof=false externalRequests=0%n", planId, mutation);
    }

    private static Map<String, ObjectNode> resources() throws Exception {
        var result = new LinkedHashMap<String, ObjectNode>();
        for (String plan : PLANS) result.put(plan, (ObjectNode) YAML.readTree(Files.readString(Path.of("main/resources/plans", plan + ".yaml"))));
        return result;
    }

    private static PlanHintApplier applier(Map<String, ObjectNode> roots, List<String> reads) throws Exception {
        var contents = new LinkedHashMap<String, byte[]>();
        for (var entry : roots.entrySet()) contents.put(entry.getKey(), YAML.writeValueAsBytes(entry.getValue()));
        return new PlanHintApplier(new DefaultResourceLoader() {
            @Override public Resource getResource(String location) {
                for (var entry : contents.entrySet()) if (location.equals("classpath:plans/" + entry.getKey() + ".yaml")) {
                    reads.add(entry.getKey()); return new ByteArrayResource(entry.getValue()) {
                        @Override public String getFilename() { return entry.getKey() + ".yaml"; }
                    };
                }
                return super.getResource(location);
            }
        });
    }

    private static BravePlan nova(Map<String, ObjectNode> roots, String expected, Supplier<BravePlan> action) throws Exception {
        var contents = new LinkedHashMap<String, byte[]>();
        for (var entry : roots.entrySet()) contents.put("plans/" + entry.getKey() + ".yaml", YAML.writeValueAsBytes(entry.getValue()));
        var reads = new ArrayList<String>(); Thread thread = Thread.currentThread(); ClassLoader previous = thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(new ClassLoader(previous) {
                @Override public java.io.InputStream getResourceAsStream(String name) {
                    if (contents.containsKey(name)) { reads.add(name); return new java.io.ByteArrayInputStream(contents.get(name)); }
                    return super.getResourceAsStream(name);
                }
            });
            BravePlan result = action.get(); assertEquals(List.of("plans/" + expected + ".yaml"), reads);
            return result;
        } finally { thread.setContextClassLoader(previous); }
    }

    private static WorkflowOrchestrator automatic(PlanHintApplier applier) {
        var workflow = new WorkflowOrchestrator(applier);
        ReflectionTestUtils.setField(workflow, "enabled", true);
        ReflectionTestUtils.setField(workflow, "defaultPlanId", "safe_autorun.v1");
        ReflectionTestUtils.setField(workflow, "safePlanId", "safe_autorun.v1");
        ReflectionTestUtils.setField(workflow, "creativePlanId", "brave.v1");
        return workflow;
    }
}
