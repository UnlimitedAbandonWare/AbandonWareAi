package com.example.lms.plan;

import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.abandonware.ai.addons.config.AddonsProperties;
import com.abandonware.ai.addons.onnx.OnnxSemaphoreGate;
import com.example.lms.orchestration.OrchestrationHints;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class OnnxPlanConcurrencyBoundaryTest {
    private static final String KEY = "rerank.onnx.maxConcurrency";
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AddonsProperties.class)
    static class BindingConfiguration { }

    @AfterEach
    void clearContext() {
        Thread.interrupted();
        TimeBudgetContext.clear();
        TraceStore.clear();
    }

    static Stream<Arguments> rootCases() {
        return Stream.of("safe.v1", "rulebreak.v1").flatMap(plan ->
                Stream.of("authored", "changed", "absent").flatMap(raw ->
                        Stream.of(1, 2).map(global -> Arguments.of(plan, raw, global))));
    }

    @ParameterizedTest(name = "plan={0} root={1} global={2}")
    @MethodSource("rootCases")
    void fullPlanKeyIsUnprojectedWhileGlobalGateHasAnActualCapacityControl(
            String planId, String raw, int global) throws Exception {
        var applier = applier(planId, raw, null);
        var plan = applier.load(planId);
        assertEquals(planId, plan.planId());
        assertFalse(plan.isEmpty());
        assertEquals(planId.equals("rulebreak.v1"), plan.onnxEnabled());
        assertFalse(plan.raw().containsKey(KEY));
        Map<String, Object> meta = new LinkedHashMap<>();
        GuardContext guard = new GuardContext();
        applier.applyToHintsAndMeta(plan, OrchestrationHints.defaults(), meta);
        applier.applyToGuardContext(plan, guard);
        assertFalse(meta.containsKey(KEY));
        assertNull(guard.getPlanOverride(KEY));
        meta.put(KEY, 8); guard.putPlanOverride(KEY, 9);
        applier.applyToHintsAndMeta(plan, OrchestrationHints.defaults(), meta);
        applier.applyToGuardContext(plan, guard);
        assertEquals(8, meta.get(KEY));
        assertEquals(9, guard.getPlanOverride(KEY));
        runner().withPropertyValues("addons.onnx.max-concurrent=" + global,
                "addons.onnx.queue-wait-ms=15").run(context -> {
            assertNull(context.getStartupFailure());
            var props = context.getBean(AddonsProperties.class);
            assertEquals(global, props.getOnnx().getMaxConcurrent());
            assertEquals(15, props.getOnnx().getQueueWaitMs());
            // Direct component control: this does not assert that an ONNX-disabled plan invokes reranking.
            assertDoesNotThrow(() -> assertSecondCall(new OnnxSemaphoreGate(props), global));
            assertEquals(global, props.getOnnx().getMaxConcurrent());
        });
        System.out.printf("TBL07_ONNX_CONCURRENCY plan=%s root=%s global=%d projected=false held=1 secondCe=%d secondFallback=%d externalRequests=0%n",
                planId, raw, global, global == 2 ? 1 : 0, global == 1 ? 1 : 0);
    }

    @ParameterizedTest(name = "location={0}")
    @ValueSource(strings = {"params", "knobs"})
    void rawParamsAndKnobsRetainTheKeyButDoNotPassItToRequestOverrides(String location) throws Exception {
        var applier = applier("safe.v1", "authored", location);
        var plan = applier.load("safe.v1");
        assertEquals("safe.v1", plan.planId());
        assertFalse(plan.isEmpty());
        assertEquals(7, ((Map<?, ?>) plan.raw().get(location)).get(KEY));
        Map<String, Object> meta = new LinkedHashMap<>();
        GuardContext guard = new GuardContext();
        applier.applyToHintsAndMeta(plan, OrchestrationHints.defaults(), meta);
        applier.applyToGuardContext(plan, guard);
        assertFalse(meta.containsKey(KEY));
        assertNull(guard.getPlanOverride(KEY));
    }

    @ParameterizedTest(name = "binding={0} expectedCapacity={3} expectedWait={4}")
    @CsvSource({"default,0,0,4,120", "positive,2,15,2,15", "zero,0,0,1,1", "negative,-2,-5,1,1"})
    void realSpringBindingPreservesDefaultsAndClampsNumericInputs(
            String mode, int capacity, long wait, int expectedCapacity, long expectedWait) {
        var configured = runner();
        if (!mode.equals("default")) configured = configured.withPropertyValues(
                "addons.onnx.max-concurrent=" + capacity, "addons.onnx.queue-wait-ms=" + wait);
        configured.run(context -> {
            assertNull(context.getStartupFailure());
            var onnx = context.getBean(AddonsProperties.class).getOnnx();
            assertEquals(expectedCapacity, onnx.getMaxConcurrent());
            assertEquals(expectedWait, onnx.getQueueWaitMs());
        });
    }

    @Test
    void nonnumericGlobalCapacityFailsBinding() {
        runner().withPropertyValues("addons.onnx.max-concurrent=invalid-fixture-number")
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @ParameterizedTest(name = "capturedCapacity={0}")
    @ValueSource(ints = {1, 2})
    void constructedGateKeepsItsCapacityWhenFixturePropertiesLaterChange(int captured) throws Exception {
        var props = new AddonsProperties();
        props.getOnnx().setMaxConcurrent(captured);
        props.getOnnx().setQueueWaitMs(15);
        var gate = new OnnxSemaphoreGate(props);
        props.getOnnx().setMaxConcurrent(captured == 1 ? 2 : 1);
        assertSecondCall(gate, captured);
    }

    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(BindingConfiguration.class);
    }

    private static void assertSecondCall(OnnxSemaphoreGate gate, int capacity) throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var secondCe = new AtomicInteger();
        var secondFallback = new AtomicInteger();
        try {
            var held = executor.submit(() -> {
                try {
                    return gate.withPermit(() -> {
                        entered.countDown();
                        try { assertTrue(release.await(3, TimeUnit.SECONDS)); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                        return "held-ce";
                    }, () -> "unexpected-holder-fallback");
                } finally { TimeBudgetContext.clear(); TraceStore.clear(); }
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            String result = gate.withPermit(() -> { secondCe.incrementAndGet(); return "second-ce"; },
                    () -> { secondFallback.incrementAndGet(); return "second-fallback"; });
            assertEquals(capacity == 1 ? "second-fallback" : "second-ce", result);
            assertEquals(capacity == 1 ? 0 : 1, secondCe.get());
            assertEquals(capacity == 1 ? 1 : 0, secondFallback.get());
            if (capacity == 1) assertEquals("queue.wait.exceeded", TraceStore.get("rerank.onnx.gate.suppressed.stage"));
            release.countDown();
            assertEquals("held-ce", held.get(2, TimeUnit.SECONDS));
            assertEquals("recovered-ce", gate.withPermit(() -> "recovered-ce", () -> "unexpected-fallback"));
        } finally {
            release.countDown(); executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    private static PlanHintApplier applier(String planId, String raw, String extraLocation) throws Exception {
        var original = YAML.readTree(Files.readString(Path.of("main/resources/plans", planId + ".yaml")));
        var modified = original.deepCopy();
        var onnx = (ObjectNode) modified.path("rerank").path("onnx");
        assertTrue(onnx.path("maxConcurrency").isInt());
        assertEquals(planId.equals("safe.v1") ? 1 : 4, onnx.path("maxConcurrency").asInt());
        if (raw.equals("changed")) onnx.put("maxConcurrency", 7);
        else if (raw.equals("absent")) onnx.remove("maxConcurrency");
        else assertEquals("authored", raw);
        var restored = modified.deepCopy();
        ((ObjectNode) restored.path("rerank").path("onnx")).set("maxConcurrency", original.path("rerank").path("onnx").path("maxConcurrency"));
        assertEquals(original, restored, "only the declared root concurrency input changes");
        if (extraLocation != null) {
            ObjectNode destination = extraLocation.equals("params") ? ((ObjectNode) modified).with("params")
                    : ((ObjectNode) modified).with("plan").with("overrides").with("knobs");
            assertFalse(destination.has(KEY)); destination.put(KEY, 7);
        }
        byte[] yaml = YAML.writeValueAsBytes(modified);
        var resources = new DefaultResourceLoader() {
            @Override public Resource getResource(String location) {
                if (location.endsWith("plans/" + planId + ".yaml")) return new ByteArrayResource(yaml);
                return super.getResource(location);
            }
        };
        return new PlanHintApplier(original.equals(modified) ? new DefaultResourceLoader() : resources);
    }
}
