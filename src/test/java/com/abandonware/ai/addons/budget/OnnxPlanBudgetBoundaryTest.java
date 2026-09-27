package com.abandonware.ai.addons.budget;

import com.example.lms.orchestration.OrchestrationHints;
import com.example.lms.plan.PlanHintApplier;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.onnx.OnnxCrossEncoderReranker;
import com.example.lms.service.onnx.OnnxRuntimeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class OnnxPlanBudgetBoundaryTest {
    private static final String PLAN = "rulebreak.v1";
    private static final String KEY = "rerank.onnx.budgetMs";
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    @AfterEach
    void clearContext() {
        TimeBudgetContext.clear();
        TraceStore.clear();
    }

    static Stream<Arguments> rootBudgetCases() {
        return Stream.of("authored", "changed", "absent").flatMap(raw ->
                Stream.of("none", "expired", "low29", "exact30", "ample", "during")
                        .map(context -> Arguments.of(raw, context)));
    }

    @ParameterizedTest(name = "root={0} context={1}")
    @MethodSource("rootBudgetCases")
    void fullRootBudgetHasNoProjectionAndCounterfactualRuntimeUsesOnlyExistingContext(
            String raw, String contextMode) throws Exception {
        var clock = new AtomicLong();
        TimeBudget budget = installBudget(contextMode, clock);
        var applier = applier(raw, null);
        var plan = applier.load(PLAN);
        var baseline = new PlanHintApplier(new DefaultResourceLoader()).load(PLAN);
        assertEquals(PLAN, plan.planId()); assertFalse(plan.isEmpty());
        assertEquals(Boolean.TRUE, plan.onnxEnabled());
        assertEquals(baseline, plan, "only the exact root budget changes; no PlanHints field represents it");
        assertFalse(plan.raw().containsKey(KEY));
        Map<String, Object> metadata = new LinkedHashMap<>();
        var guard = new GuardContext();
        applier.applyToHintsAndMeta(plan, OrchestrationHints.defaults(), metadata);
        applier.applyToGuardContext(plan, guard);
        assertFalse(metadata.containsKey(KEY)); assertNull(guard.getPlanOverride(KEY));
        metadata.put(KEY, 8); guard.putPlanOverride(KEY, 9);
        applier.applyToHintsAndMeta(plan, OrchestrationHints.defaults(), metadata);
        applier.applyToGuardContext(plan, guard);
        assertEquals(8, metadata.get(KEY)); assertEquals(9, guard.getPlanOverride(KEY));
        assertSame(budget, TimeBudgetContext.get());
        var runtime = new RecordingRuntime(true, clock, contextMode.equals("during"));
        var reranker = reranker(runtime);
        List<Content> candidates = List.of(Content.from("alpha"), Content.from("beta"), Content.from("gamma"));
        var before = List.copyOf(candidates);
        List<Content> result = reranker.rerank("fixture query", candidates, 2);
        int expectedCalls = switch (contextMode) { case "expired", "low29" -> 0; case "during" -> 1; default -> 3; };
        assertEquals(1, runtime.availabilityChecks);
        assertEquals(expectedCalls, runtime.documents.size());
        assertEquals(before, candidates);
        assertEquals(expectedCalls == 3 ? List.of("gamma", "beta") : List.of("alpha", "beta"),
                result.stream().map(c -> c.textSegment().text()).toList());
        String reason = switch (contextMode) {
            case "expired" -> "onnx:budget_expired";
            case "low29" -> "onnx:budget_low";
            case "during" -> "onnx:budget_exhausted";
            default -> null;
        };
        if (reason == null) assertNull(TraceStore.get("rerank.skip"));
        else assertTrue(String.valueOf(TraceStore.get("rerank.skip")).contains(reason));
        assertSame(budget, TimeBudgetContext.get());
        if (contextMode.equals("exact30")) assertEquals(30, budget.remainingMillis());
        if (contextMode.equals("during")) assertEquals(29, budget.remainingMillis());
        System.out.printf("TBL07_ONNX_BUDGET root=%s context=%s projection=false forcedRuntimeAvailable=true scoreCalls=%d fallback=%s contextPreserved=true externalRequests=0%n",
                raw, contextMode, runtime.documents.size(), expectedCalls != 3);
    }

    @ParameterizedTest(name = "stockRuntime context={0}")
    @ValueSource(strings = {"none", "expired", "ample"})
    void stockRuntimeReadinessPreventsScoringBeforeAnyBudgetCheck(String contextMode) {
        var clock = new AtomicLong();
        TimeBudget budget = installBudget(contextMode, clock);
        var runtime = new RecordingRuntime(false, clock, false);
        assertFalse(new OnnxRuntimeService().available());
        List<Content> out = reranker(runtime).rerank("fixture query",
                List.of(Content.from("alpha"), Content.from("beta"), Content.from("gamma")), 2);
        assertEquals(1, runtime.availabilityChecks);
        assertTrue(runtime.documents.isEmpty());
        assertEquals(List.of("alpha", "beta"), out.stream().map(c -> c.textSegment().text()).toList());
        assertEquals(Boolean.FALSE, TraceStore.get("rerank.onnx.ready"));
        assertNull(TraceStore.get("rerank.skip"));
        assertSame(budget, TimeBudgetContext.get());
        System.out.printf("TBL07_ONNX_BUDGET_STOCK context=%s runtimeAvailable=false scoreCalls=0 budgetGateReached=false externalRequests=0%n", contextMode);
    }

    @ParameterizedTest(name = "rawLocation={0}")
    @ValueSource(strings = {"params", "knobs"})
    void exactBudgetInRawMapsDoesNotPassToMetadataOrGuard(String location) throws Exception {
        var applier = applier("authored", location);
        var plan = applier.load(PLAN);
        assertEquals(PLAN, plan.planId()); assertFalse(plan.isEmpty());
        assertEquals(7, ((Map<?, ?>) plan.raw().get(location)).get(KEY));
        Map<String, Object> meta = new LinkedHashMap<>(); var guard = new GuardContext();
        applier.applyToHintsAndMeta(plan, OrchestrationHints.defaults(), meta);
        applier.applyToGuardContext(plan, guard);
        assertFalse(meta.containsKey(KEY)); assertNull(guard.getPlanOverride(KEY));
        assertNull(TimeBudgetContext.get());
    }

    @ParameterizedTest(name = "genericBudget={0}")
    @ValueSource(strings = {"genericParams", "genericRoot"})
    void genericTotalBudgetAliasesRemainSeparateFromExactOnnxBudget(String location) throws Exception {
        var plan = applier("authored", location).load(PLAN);
        assertEquals(PLAN, plan.planId()); assertFalse(plan.isEmpty());
        assertEquals(1234L, plan.webBudgetMs());
        assertEquals(1234L, plan.vecBudgetMs());
        assertFalse(plan.raw().containsKey(KEY));
        assertNull(TimeBudgetContext.get());
    }

    private static TimeBudget installBudget(String mode, AtomicLong clock) {
        TimeBudgetContext.clear();
        if (mode.equals("none")) return null;
        var budget = new TimeBudget(100, clock::get);
        long elapsed = switch (mode) { case "expired" -> 100; case "low29" -> 71; case "exact30" -> 70; default -> 0; };
        clock.set(TimeUnit.MILLISECONDS.toNanos(elapsed));
        TimeBudgetContext.set(budget);
        return budget;
    }

    private static OnnxCrossEncoderReranker reranker(RecordingRuntime runtime) {
        var reranker = new OnnxCrossEncoderReranker(runtime);
        ReflectionTestUtils.setField(reranker, "enabled", true);
        return reranker;
    }

    private static final class RecordingRuntime extends OnnxRuntimeService {
        final boolean forceAvailable;
        final AtomicLong clock;
        final boolean exhaustAfterFirst;
        final List<String> documents = new ArrayList<>();
        int availabilityChecks;
        RecordingRuntime(boolean forceAvailable, AtomicLong clock, boolean exhaustAfterFirst) {
            this.forceAvailable = forceAvailable; this.clock = clock; this.exhaustAfterFirst = exhaustAfterFirst;
        }
        @Override public boolean available() { availabilityChecks++; return forceAvailable || super.available(); }
        @Override public double scorePair(String query, String document) {
            documents.add(document);
            if (exhaustAfterFirst && documents.size() == 1) clock.set(TimeUnit.MILLISECONDS.toNanos(71));
            return switch (document) { case "alpha" -> 1d; case "beta" -> 2d; case "gamma" -> 3d; default -> throw new AssertionError("unexpected synthetic candidate"); };
        }
    }

    private static PlanHintApplier applier(String raw, String extraLocation) throws Exception {
        var original = YAML.readTree(Files.readString(Path.of("main/resources/plans", PLAN + ".yaml")));
        var modified = original.deepCopy(); var onnx = (ObjectNode) modified.path("rerank").path("onnx");
        assertEquals(2000, onnx.path("budgetMs").asInt());
        if (raw.equals("changed")) onnx.put("budgetMs", 1);
        else if (raw.equals("absent")) onnx.remove("budgetMs");
        else assertEquals("authored", raw);
        var restored = modified.deepCopy();
        ((ObjectNode) restored.path("rerank").path("onnx")).set("budgetMs", original.path("rerank").path("onnx").path("budgetMs"));
        assertEquals(original, restored, "only exact root budget changes before independent producer controls");
        if (extraLocation != null) {
            if (extraLocation.equals("genericParams")) ((ObjectNode) modified).with("params").put("budgetMs", 1234);
            else if (extraLocation.equals("genericRoot")) ((ObjectNode) modified).with("budgets").put("totalMs", 1234);
            else {
                ObjectNode destination = extraLocation.equals("params") ? ((ObjectNode) modified).with("params")
                        : ((ObjectNode) modified).with("plan").with("overrides").with("knobs");
                assertFalse(destination.has(KEY)); destination.put(KEY, 7);
            }
        }
        byte[] yaml = YAML.writeValueAsBytes(modified);
        var resources = new DefaultResourceLoader() {
            @Override public Resource getResource(String location) {
                if (location.endsWith("plans/" + PLAN + ".yaml")) return new ByteArrayResource(yaml) {
                    @Override public String getFilename() { return PLAN + ".yaml"; }
                };
                return super.getResource(location);
            }
        };
        return new PlanHintApplier(original.equals(modified) ? new DefaultResourceLoader() : resources);
    }
}
