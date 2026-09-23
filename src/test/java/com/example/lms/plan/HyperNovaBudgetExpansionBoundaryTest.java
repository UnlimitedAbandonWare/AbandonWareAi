package com.example.lms.plan;

import com.example.lms.orchestration.OrchestrationHints;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class HyperNovaBudgetExpansionBoundaryTest {
    private static final String ID = "hyper_nova.v1";
    private static final String COUNT = "expand.selfAsk.count";
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper JSON = new ObjectMapper();

    @AfterEach void clearTrace() { TraceStore.clear(); }

    static Stream<Arguments> leaves() {
        return Stream.of("budget.time_ms", "concurrency.reranker_max_parallel", "expansion.self_ask")
                .flatMap(f -> Stream.of("changed", "absent").map(v -> Arguments.of(f, v)));
    }

    @ParameterizedTest(name = "hyper-options:{0}:{1}")
    @MethodSource("leaves")
    void exactAuthoredOptionsLeaveThreeCompleteProjectionsUnchanged(String field, String variant) throws Exception {
        ObjectNode base = authored(), input = base.deepCopy();
        String[] path = field.split("\\.");
        JsonNode old = base.path(path[0]).path(path[1]);
        assertTrue(old.isIntegralNumber());
        if (variant.equals("absent")) ((ObjectNode) input.get(path[0])).remove(path[1]);
        else ((ObjectNode) input.get(path[0])).put(path[1], old.intValue() + 7);
        assertNotEquals(base, input);
        ObjectNode restored = input.deepCopy(); ((ObjectNode) restored.get(path[0])).set(path[1], old);
        assertEquals(base, restored);
        assertEquals(legacyHints(base), legacyHints(input));
        assertEquals(protocol(base), protocol(input));
        assertEquals(nova(base), nova(input));
        System.out.printf("TBL07_HYPER_OPTIONS field=%s variant=%s fullHintEqual=true protocolEqual=true novaEqual=true hintReads=2 protocolReads=2 novaReads=2%n", field, variant);
    }

    @Test
    void supportedTotalBudgetChangesOnlyTwoTypedBudgetsAndTheirActualConsumers() throws Exception {
        ObjectNode base = authored(); base.with("budget").put("total_ms", 9000);
        ObjectNode input = base.deepCopy(); input.with("budget").put("total_ms", 11000);
        ObjectNode expected = JSON.valueToTree(apply(base, false, null)), actual = JSON.valueToTree(apply(input, false, null));
        assertNotEquals(expected, actual);
        for (String owner : List.of("plan", "hints", "metadata")) {
            ((ObjectNode) expected.get(owner)).put("webBudgetMs", 11000L);
            ((ObjectNode) expected.get(owner)).put("vecBudgetMs", 11000L);
        }
        assertEquals(expected, actual);
        System.out.println("TBL07_HYPER_TOTAL_DELTA expectedFullDelta=true hintReads=2");
    }

    static Stream<Arguments> totals() {
        return Stream.of(Arguments.of(null, null, "absent"), Arguments.of(0L, null, "non_positive"),
                Arguments.of(-1L, null, "non_positive"), Arguments.of(1L, 1L, "accepted"),
                Arguments.of(120000L, 120000L, "accepted"), Arguments.of(120001L, null, "out_of_range"));
    }

    @ParameterizedTest(name = "hyper-total-bound:{0}")
    @MethodSource("totals")
    void totalBudgetBoundsRejectInsteadOfClamping(Long value, Long expected, String reason) throws Exception {
        ObjectNode input = authored();
        if (value != null) input.with("budget").put("total_ms", value);
        Effect baseline = apply(authored(), false, null), actual = apply(input, false, null);
        assertBudget(actual, baseline, "web", expected); assertBudget(actual, baseline, "vec", expected);
        assertDecision(actual, "budget_ms", reason);
        System.out.printf("TBL07_HYPER_TOTAL_BOUND input=%s expected=%s reason=%s hintReads=2%n", value, expected, reason);
    }

    @ParameterizedTest(name = "hyper-specific-budget:{0}")
    @ValueSource(strings = {"web", "both", "web_rejected", "vector_rejected", "earlier_alias_rejected"})
    void specificBudgetPrecedenceIncludesRejectedValueBlockingTotalFallback(String variant) throws Exception {
        ObjectNode input = authored(); input.with("budget").put("total_ms", 9000);
        ObjectNode budgets = input.putObject("budgets");
        Long web = 9000L, vec = 9000L;
        switch (variant) {
            case "web" -> { budgets.put("web_ms", 600); web = 600L; }
            case "both" -> { budgets.put("web_ms", 600).put("vec_ms", 800); web = 600L; vec = 800L; }
            case "web_rejected" -> { budgets.put("web_ms", 0); web = null; }
            case "vector_rejected" -> { budgets.put("vec_ms", 120001); vec = null; }
            case "earlier_alias_rejected" -> { budgets.put("web_ms", 0).put("webMs", 600); web = null; }
            default -> fail(variant);
        }
        Effect baseline = apply(authored(), false, null), actual = apply(input, false, null);
        assertBudget(actual, baseline, "web", web); assertBudget(actual, baseline, "vec", vec);
        assertDecision(actual, "budget_ms", "accepted");
        if (web == null) assertDecision(actual, "budget_ms.web", "non_positive");
        if (vec == null) assertDecision(actual, "budget_ms.vector", "out_of_range");
        System.out.printf("TBL07_HYPER_SPECIFIC variant=%s web=%s vec=%s hintReads=2%n", variant, web, vec);
    }

    static Stream<Arguments> selfAsk() {
        List<Arguments> values = List.of(Arguments.of("absent", null, null, null),
                Arguments.of("zero", 0, null, 0), Arguments.of("negative", -1, null, -1),
                Arguments.of("params", 3, null, 3), Arguments.of("knobs", null, 5, 5),
                Arguments.of("knobs_disable", 3, 0, 0), Arguments.of("knobs_enable", 0, 5, 5));
        return values.stream().flatMap(a -> Stream.of(false, true).map(caller ->
                Arguments.of(a.get()[0], a.get()[1], a.get()[2], a.get()[3], caller)));
    }

    @ParameterizedTest(name = "hyper-selfask:{0}:{4}")
    @MethodSource("selfAsk")
    void literalSelfAskCountHasKnobPrecedenceAndPositiveOnlyActivation(
            String variant, Integer params, Integer knobs, Integer effective, boolean caller) throws Exception {
        Effect actual = apply(selfAskInput(params, knobs), caller, null);
        boolean positive = effective != null && effective > 0;
        assertEquals(caller || positive, actual.hints().path("enableSelfAsk").asBoolean());
        assertEquals(effective, actual.metadata().get(COUNT)); assertEquals(effective, actual.overrides().get(COUNT));
        if (positive) {
            assertEquals("true", actual.metadata().get("selfask.enabled"));
            assertEquals("true", actual.metadata().get("enableSelfAsk"));
            assertEquals(true, actual.overrides().get("selfask.enabled"));
            assertEquals(COUNT, actual.metadata().get("selfask.planOverride.reason"));
            assertEquals(COUNT, actual.overrides().get("selfask.planOverride.reason"));
        } else {
            assertFalse(actual.metadata().containsKey("selfask.enabled"));
            assertFalse(actual.metadata().containsKey("enableSelfAsk"));
            assertFalse(actual.overrides().containsKey("selfask.enabled"));
        }
        System.out.printf("TBL07_HYPER_SELFASK variant=%s caller=%s effective=%s active=%s hintReads=1%n",
                variant, caller, effective, caller || positive);
    }

    @ParameterizedTest(name = "hyper-selfask-guard:{0}")
    @ValueSource(strings = {"absent", "zero", "positive"})
    void positiveSpecialWriteAndNonpositivePassthroughTreatExistingGuardCountDifferently(String variant) throws Exception {
        Integer count = variant.equals("absent") ? null : variant.equals("zero") ? 0 : 3;
        Effect actual = apply(selfAskInput(count, null), false, 99);
        boolean positive = "positive".equals(variant);
        assertEquals(positive ? 3 : 99, actual.overrides().get(COUNT));
        assertEquals(positive, actual.overrides().get("selfask.enabled"));
        assertEquals(count, actual.metadata().get(COUNT));
        assertEquals(positive, actual.hints().path("enableSelfAsk").asBoolean());
        System.out.printf("TBL07_HYPER_SELFASK_GUARD variant=%s positiveOverridesSeed=%s guardCount=%s hintReads=1%n",
                variant, positive, actual.overrides().get(COUNT));
    }

    private static void assertBudget(Effect actual, Effect baseline, String lane, Long expected) {
        String key = lane + "BudgetMs";
        assertEquals(expected, lane.equals("web") ? actual.plan().webBudgetMs() : actual.plan().vecBudgetMs());
        JsonNode expectedHint = expected == null ? baseline.hints().path(key) : JSON.valueToTree(expected);
        assertEquals(expectedHint, actual.hints().path(key));
        assertEquals(actual.hints().path(key), JSON.valueToTree(actual.metadata().get(key)));
    }

    private static void assertDecision(Effect actual, String field, String reason) {
        List<?> applied = (List<?>) actual.plan().raw().get("fieldApplied");
        List<?> rejected = (List<?>) actual.plan().raw().get("fieldRejected");
        assertEquals(reason.equals("accepted"), applied.contains(field));
        if (reason.equals("absent") || reason.equals("accepted"))
            assertTrue(rejected.stream().noneMatch(v -> v.toString().startsWith(field + ":")));
        else assertTrue(rejected.contains(field + ":" + reason));
    }

    private static ObjectNode selfAskInput(Integer params, Integer knobs) throws Exception {
        ObjectNode root = authored();
        if (params != null) root.with("params").put(COUNT, params);
        if (knobs != null) root.with("plan").with("overrides").with("knobs").put(COUNT, knobs);
        return root;
    }

    private static ObjectNode authored() throws Exception {
        return (ObjectNode) YAML.readTree(Files.readAllBytes(Path.of("main/resources/plans/" + ID + ".yaml")));
    }

    private static Object legacyHints(ObjectNode root) {
        return ReflectionTestUtils.invokeMethod(PlanRootLlmBoundaryTest.class, "apply", ID, root);
    }

    private static ObjectNode nova(ObjectNode root) {
        return JSON.valueToTree(ReflectionTestUtils.invokeMethod(PlanRootLlmBoundaryTest.class, "nova", ID, root));
    }

    private static ObjectNode protocol(ObjectNode root) throws Exception {
        return ReflectionTestUtils.invokeMethod(Class.forName("com.nova.protocol.plan.HyperNovaAllocationBoundaryTest"),
                "protocol", root, "block");
    }

    private record Effect(PlanHints plan, JsonNode hints, Map<String, Object> metadata, Map<String, Object> overrides) {}

    private static Effect apply(ObjectNode root, boolean callerSelfAsk, Integer guardCount) throws Exception {
        byte[] bytes = YAML.writeValueAsBytes(root);
        AtomicInteger lookups = new AtomicInteger(), reads = new AtomicInteger();
        PlanHintApplier applier = new PlanHintApplier(new DefaultResourceLoader() {
            @Override public Resource getResource(String location) {
                assertEquals("classpath:plans/" + ID + ".yaml", location); lookups.incrementAndGet();
                return new ByteArrayResource(bytes) {
                    @Override public String getFilename() { return ID + ".yaml"; }
                    @Override public InputStream getInputStream() throws java.io.IOException {
                        reads.incrementAndGet(); return super.getInputStream();
                    }
                };
            }
        });
        PlanHints plan = applier.load(ID);
        assertFalse(plan.isEmpty()); assertEquals(ID, plan.planId());
        OrchestrationHints hints = OrchestrationHints.defaults(); hints.setEnableSelfAsk(callerSelfAsk);
        Map<String, Object> metadata = new LinkedHashMap<>(); GuardContext guard = new GuardContext();
        if (guardCount != null) { guard.putPlanOverride(COUNT, guardCount); guard.putPlanOverride("selfask.enabled", false); }
        applier.applyToHintsAndMeta(plan, hints, metadata);
        applier.applyToGuardContext(plan, guard);
        assertEquals(1, lookups.get()); assertEquals(1, reads.get());
        return new Effect(plan, JSON.valueToTree(hints), metadata, new LinkedHashMap<>(guard.getPlanOverrides()));
    }
}
