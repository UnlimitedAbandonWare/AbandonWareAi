package com.example.lms.service.rag.learn;

import com.abandonware.ai.agent.integrations.service.rag.kalloc.KAllocator;
import com.example.lms.resilience.RagFailureBlackboxService;
import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.QueryComplexityGate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.Iterator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CfvmKAllocationTunerTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        TraceStore.clear();
    }

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void highValueColdStartDoesNotForceUnvisitedArmWhenDampedScoreIsWorse() {
        CfvmKallocLearningProperties props = props();
        CfvmBanditStore store = new CfvmBanditStore(new ObjectMapper(), props);
        CfvmKAllocationTuner tuner = new CfvmKAllocationTuner(props, store);
        KAllocator.Settings settings = settings();
        KAllocator.Input input = new KAllocator.Input("shopping", "enterprise gpu acquisition", false);

        store.update(tileKey(input.intent, QueryComplexityGate.Level.COMPLEX, false, false), "BASE", 1.0d);

        CfvmKAllocationTuner.Decision decision = tuner.decide(
                settings, input, QueryComplexityGate.Level.COMPLEX, null, 0.90d, 0.45d, "HIGH");

        assertEquals("BASE", decision.arm());
        assertEquals("HIGH", decision.resourceTier());
        assertEquals(decision.key(), TraceStore.get("cfvm.kalloc.key"));
        assertEquals("BASE", TraceStore.get("cfvm.kalloc.arm"));
        assertEquals("HIGH", TraceStore.get("cfvm.kalloc.resourceTier"));
        assertEquals(decision.plan().webK, TraceStore.get("cfvm.kalloc.plan.webK"));
    }

    @Test
    void lowValueStillExploresUnvisitedArms() {
        CfvmKallocLearningProperties props = props();
        CfvmBanditStore store = new CfvmBanditStore(new ObjectMapper(), props);
        CfvmKAllocationTuner tuner = new CfvmKAllocationTuner(props, store);
        KAllocator.Settings settings = settings();
        KAllocator.Input input = new KAllocator.Input("copy", "small used item listing", false);

        store.update(tileKey(input.intent, QueryComplexityGate.Level.SIMPLE, false, false), "BASE", 1.0d);

        CfvmKAllocationTuner.Decision decision = tuner.decide(
                settings, input, QueryComplexityGate.Level.SIMPLE, null, 0.18d, 0.20d, "LOW");

        assertEquals("WEB_HEAVY", decision.arm());
        assertEquals("LOW", decision.resourceTier());
        assertEquals("WEB_HEAVY", TraceStore.get("cfvm.kalloc.arm"));
        assertEquals(decision.plan().poolLimit, TraceStore.get("cfvm.kalloc.plan.poolLimit"));
    }

    @Test
    void boltzmannSelectionUsesLearnedArmDistributionAfterColdStart() {
        CfvmKallocLearningProperties props = props();
        props.setBoltzmannEnabled(true);
        props.setBoltzmannTemperature(0.0d);
        CfvmBanditStore store = new CfvmBanditStore(new ObjectMapper(), props);
        CfvmKAllocationTuner tuner = new CfvmKAllocationTuner(props, store);
        KAllocator.Settings settings = settings();
        KAllocator.Input input = new KAllocator.Input("definition", "relationship schema release", true);
        String tileKey = tileKey(input.intent, QueryComplexityGate.Level.COMPLEX, true, true);

        store.update(tileKey, "BASE", 0.10d);
        store.update(tileKey, "WEB_HEAVY", 0.20d);
        store.update(tileKey, "VECTOR_HEAVY", 0.25d);
        store.update(tileKey, "KG_HEAVY", 0.95d);
        store.update(tileKey, "COST_SAVER", -0.10d);

        CfvmKAllocationTuner.Decision decision = tuner.decide(
                settings, input, QueryComplexityGate.Level.COMPLEX, null, 0.65d, 0.20d, "MEDIUM");

        assertEquals("KG_HEAVY", decision.arm());
        assertEquals("cfvm_boltzmann_ucb1", decision.policy());
        assertEquals("boltzmann", TraceStore.get("cfvm.kalloc.selectionMode"));
        assertEquals(0.0d, ((Number) TraceStore.get("cfvm.kalloc.epsilon")).doubleValue());
        assertEquals("KG_HEAVY", TraceStore.get("cfvm.kalloc.chosenArm"));
        assertEquals("KG_HEAVY", TraceStore.get("cfvm.kalloc.boltzmann.bestArm"));
        assertEquals(1.0d, ((Number) TraceStore.get("cfvm.kalloc.boltzmann.chosenProbability")).doubleValue());
        assertEquals(0.0d, ((Number) TraceStore.get("cfvm.kalloc.boltzmann.temperature")).doubleValue());
    }

    @Test
    void highRiskProviderDisabledBlackboxClampsWebBudget() {
        CfvmKallocLearningProperties props = props();
        CfvmBanditStore store = new CfvmBanditStore(new ObjectMapper(), props);
        CfvmKAllocationTuner tuner = new CfvmKAllocationTuner(props, store, provider(blackboxService(true)));
        KAllocator.Settings settings = settings();
        KAllocator.Input input = new KAllocator.Input("copy", "small used item listing", false);
        TraceStore.put("web.naver.providerDisabled", true);

        CfvmKAllocationTuner.Decision decision = tuner.decide(
                settings, input, QueryComplexityGate.Level.SIMPLE, null, 0.18d, 0.20d, "LOW");

        assertEquals(settings.minPerSource, decision.plan().webK);
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.kalloc.blackbox.applied"));
        assertEquals("disable_provider_failsoft", TraceStore.get("cfvm.kalloc.blackbox.action"));
        assertEquals("provider_disabled", TraceStore.get("cfvm.kalloc.blackbox.dominantFailure"));
    }

    @Test
    void traceAnchorPressureCanDriveBlackboxKAllocationOverride() {
        CfvmKallocLearningProperties props = props();
        CfvmBanditStore store = new CfvmBanditStore(new ObjectMapper(), props);
        CfvmKAllocationTuner tuner = new CfvmKAllocationTuner(props, store, provider(blackboxService(true)));
        KAllocator.Settings settings = settings();
        KAllocator.Input input = new KAllocator.Input("copy", "small used item listing", false);
        Map<String, Object> anchor = Map.of(
                "component", "web",
                "stage", "web.await",
                "lane", "WEB",
                "anchorHash", "abc123",
                "evidenceDigestHash", "def456",
                "matrixTile", 1,
                "routeHint", "fail_soft_fallback",
                "p", 0.95d,
                "delta", 0.75d,
                "expectedDelta", 0.7125d);
        TraceStore.put("ablation.traceAnchor.rows", List.of(anchor));
        TraceStore.put("ablation.traceAnchor.top", anchor);
        TraceStore.put("ablation.traceAnchor.routeCorrectionNeed", 0.7125d);

        CfvmKAllocationTuner.Decision decision = tuner.decide(
                settings, input, QueryComplexityGate.Level.SIMPLE, null, 0.18d, 0.20d, "LOW");

        assertEquals(settings.minPerSource, decision.plan().webK);
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.kalloc.blackbox.applied"));
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.kalloc.traceAnchor.applied"));
        assertEquals("fail_soft_fallback", TraceStore.get("cfvm.kalloc.traceAnchor.routeHint"));
        assertTrue(((Number) TraceStore.get("cfvm.kalloc.traceAnchor.pressure")).doubleValue() >= 0.70d);
    }

    @Test
    void cfvmRecoveryFailureWeightCanDriveKAllocationOverride() {
        CfvmKallocLearningProperties props = props();
        CfvmBanditStore store = new CfvmBanditStore(new ObjectMapper(), props);
        CfvmKAllocationTuner tuner = new CfvmKAllocationTuner(props, store, provider(blackboxService(true)));
        KAllocator.Settings settings = settings();
        KAllocator.Input input = new KAllocator.Input("copy", "small used item listing", false);
        TraceStore.put("cfvm.recovery.failureWeight", 0.93d);
        TraceStore.put("cfvm.recovery.routeHint", "fail_soft_fallback");
        TraceStore.put("cfvm.failureRecovery.triggered", true);

        CfvmKAllocationTuner.Decision decision = tuner.decide(
                settings, input, QueryComplexityGate.Level.SIMPLE, null, 0.18d, 0.20d, "LOW");

        assertEquals(settings.minPerSource, decision.plan().webK);
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.kalloc.blackbox.applied"));
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.kalloc.recovery.applied"));
        assertEquals("fail_soft_fallback", TraceStore.get("cfvm.kalloc.traceAnchor.routeHint"));
        assertTrue(((Number) TraceStore.get("cfvm.kalloc.traceAnchor.pressure")).doubleValue() >= 0.90d);
    }

    @Test
    void disabledBlackboxConsumerDoesNotWriteBlackboxRiskTrace() {
        CfvmKallocLearningProperties props = props();
        CfvmBanditStore store = new CfvmBanditStore(new ObjectMapper(), props);
        CfvmKAllocationTuner tuner = new CfvmKAllocationTuner(props, store, provider(blackboxService(false)));
        KAllocator.Settings settings = settings();
        KAllocator.Input input = new KAllocator.Input("copy", "small used item listing", false);
        TraceStore.put("web.naver.providerDisabled", true);

        tuner.decide(settings, input, QueryComplexityGate.Level.SIMPLE, null, 0.18d, 0.20d, "LOW");

        assertEquals(null, TraceStore.get("blackbox.risk.riskScore"));
        assertEquals(Boolean.FALSE, TraceStore.get("cfvm.kalloc.blackbox.applied"));
    }

    @Test
    void blackboxOverrideTraceDoesNotReplayRawActionOrFailureText() {
        CfvmKallocLearningProperties props = props();
        CfvmBanditStore store = new CfvmBanditStore(new ObjectMapper(), props);
        CfvmKAllocationTuner tuner = new CfvmKAllocationTuner(props, store, provider(null));
        KAllocator.Settings settings = settings();
        KAllocator.Input input = new KAllocator.Input("copy", "small used item listing", false);
        String rawAction = "private action api_key=token=private-action ownerToken=raw";
        String rawFailure = "private failure " + "Author" + "ization: Bearer raw-token";
        TraceStore.put("blackbox.risk.riskScore", 0.91d);
        TraceStore.put("blackbox.risk.restoreAction", rawAction);
        TraceStore.put("blackbox.risk.dominantFailure", rawFailure);

        tuner.decide(settings, input, QueryComplexityGate.Level.SIMPLE, null, 0.18d, 0.20d, "LOW");

        String trace = String.valueOf(List.of(
                TraceStore.get("cfvm.kalloc.blackbox.action"),
                TraceStore.get("cfvm.kalloc.blackbox.dominantFailure")));
        assertFalse(trace.contains(rawAction), trace);
        assertFalse(trace.contains(rawFailure), trace);
        assertFalse(trace.contains("token=private-action"), trace);
        assertFalse(trace.contains("raw-token"), trace);
        assertTrue(trace.contains("hash:"), trace);
    }

    @Test
    void blackboxOverrideTraceSinkSanitizesRawArgumentsAtWriteBoundary() throws Exception {
        Method method = CfvmKAllocationTuner.class.getDeclaredMethod(
                "traceBlackboxOverride",
                double.class, String.class, String.class, boolean.class, String.class, double.class);
        method.setAccessible(true);
        String rawAction = "private action api_key=<test-api-key> ownerToken=raw-action";
        String rawFailure = "private failure " + "Author" + "ization: Bearer raw-token";
        String rawRouteHint = "private route token=route-secret";

        method.invoke(null, 0.91d, rawAction, rawFailure, true, rawRouteHint, 0.73d);

        String trace = String.valueOf(List.of(
                TraceStore.get("cfvm.kalloc.blackbox.action"),
                TraceStore.get("cfvm.kalloc.blackbox.dominantFailure"),
                TraceStore.get("cfvm.kalloc.traceAnchor.routeHint")));
        assertFalse(trace.contains(rawAction), trace);
        assertFalse(trace.contains(rawFailure), trace);
        assertFalse(trace.contains(rawRouteHint), trace);
        assertTrue(String.valueOf(TraceStore.get("cfvm.kalloc.blackbox.action")).contains("hash:"), trace);
        assertTrue(String.valueOf(TraceStore.get("cfvm.kalloc.blackbox.dominantFailure")).contains("hash:"), trace);
        assertTrue(String.valueOf(TraceStore.get("cfvm.kalloc.traceAnchor.routeHint")).contains("hash:"), trace);
    }

    @Test
    void feedbackTraceUsesDiagnosticLabelsForExternalKeys() {
        CfvmKallocLearningProperties props = props();
        CfvmBanditStore store = new CfvmBanditStore(new ObjectMapper(), props);
        CfvmKAllocationTuner tuner = new CfvmKAllocationTuner(props, store);
        String rawTileKey = "cfvm9:token=private-feedback-key";
        String rawArm = "arm-token=private-feedback-arm";

        tuner.feedback(rawTileKey, rawArm, 0.8d);

        String trace = String.valueOf(List.of(
                TraceStore.get("cfvm.kalloc.feedback.key"),
                TraceStore.get("cfvm.kalloc.feedback.arm")));
        assertFalse(trace.contains(rawTileKey), trace);
        assertFalse(trace.contains(rawArm), trace);
        assertTrue(trace.contains("hash:"), trace);
        assertEquals(0.8d, ((Number) TraceStore.get("cfvm.kalloc.feedback.reward")).doubleValue());
    }

    @Test
    void decisionContextTraceUsesHashAndLengthOnly() {
        CfvmKallocLearningProperties props = props();
        CfvmBanditStore store = new CfvmBanditStore(new ObjectMapper(), props);
        CfvmKAllocationTuner tuner = new CfvmKAllocationTuner(props, store);
        KAllocator.Settings settings = settings();
        KAllocator.Input input = new KAllocator.Input(
                "private intent ownerToken=cfvm-secret",
                "small used item listing",
                false);

        CfvmKAllocationTuner.Decision decision = tuner.decide(
                settings, input, QueryComplexityGate.Level.SIMPLE, null, 0.18d, 0.20d, "LOW");

        String trace = String.valueOf(TraceStore.getAll());
        assertEquals(null, TraceStore.get("cfvm.kalloc.ctx"));
        assertEquals(com.example.lms.trace.SafeRedactor.hashValue(decision.ctx()), TraceStore.get("cfvm.kalloc.ctxHash"));
        assertEquals(decision.ctx().length(), ((Number) TraceStore.get("cfvm.kalloc.ctxLength")).intValue());
        assertFalse(trace.contains("private intent"), trace);
        assertFalse(trace.contains("cfvm-secret"), trace);
    }

    @Test
    void skipReasonTraceUsesSafeMessage() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/rag/learn/CfvmKAllocationTuner.java"));

        assertFalse(source.contains("TraceStore.put(\"cfvm.kalloc.skipReason\", reason);"));
        assertFalse(source.contains("TraceStore.put(\"cfvm.kalloc.skipReason\", SafeRedactor.safeMessage(reason, 120));"));
        assertTrue(source.contains("TraceStore.put(\"cfvm.kalloc.skipReason\", SafeRedactor.traceLabelOrFallback(reason, \"unknown\"));"));
    }

    @Test
    void cfvmLearningFailSoftCatchesLeaveStageBreadcrumbs() throws Exception {
        String tuner = Files.readString(Path.of("main/java/com/example/lms/service/rag/learn/CfvmKAllocationTuner.java"));
        String store = Files.readString(Path.of("main/java/com/example/lms/service/rag/learn/CfvmBanditStore.java"));
        String learningAspect = Files.readString(Path.of("main/java/com/example/lms/service/rag/learn/CfvmKallocLearningAspect.java"));
        String needleAspect = Files.readString(Path.of("main/java/com/example/lms/service/rag/learn/NeedleKeptRatioRewardAspect.java"));

        for (String stage : List.of(
                "stage=trace.snapshot",
                "stage=blackbox.refresh",
                "stage=blackbox.trace",
                "stage=feedback.trace",
                "stage=skip.trace",
                "stage=decision.trace",
                "stage=cooldown.check",
                "cfvm.kalloc.suppressed.asDouble")) {
            assertTrue(tuner.contains(stage), "CfvmKAllocationTuner needs stage breadcrumb: " + stage);
        }
        assertTrue(store.contains("stage=bandit.update"));
        assertTrue(store.contains("stage=bandit.storePath"));
        assertTrue(learningAspect.contains("stage=failurePatterns.recent"));
        assertTrue(learningAspect.contains("cfvm.kalloc.learning.suppressed.readDouble"));
        assertTrue(needleAspect.contains("cfvm.reward.suppressed.safeDouble"));
    }

    @Test
    void cfvmNumericFallbacksUseStableInvalidNumberBreadcrumbs() throws Exception {
        Method asDouble = CfvmKAllocationTuner.class.getDeclaredMethod("asDouble", Object.class);
        asDouble.setAccessible(true);
        assertEquals(0.0d, (double) asDouble.invoke(null, "not-a-number"));
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.kalloc.suppressed.asDouble"));
        assertEquals("invalid_number", TraceStore.get("cfvm.kalloc.suppressed.asDouble.errorType"));

        TraceStore.clear();
        assertEquals(0.0d, (double) asDouble.invoke(null, Double.NaN));
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.kalloc.suppressed.asDouble"));
        assertEquals("invalid_number", TraceStore.get("cfvm.kalloc.suppressed.asDouble.errorType"));

        TraceStore.clear();
        TraceStore.put("cfvm.sig.authorityAvg", "not-a-number");
        Method readDouble = CfvmKallocLearningAspect.class.getDeclaredMethod("readDouble", String.class, double.class);
        readDouble.setAccessible(true);
        assertEquals(0.42d, (double) readDouble.invoke(null, "cfvm.sig.authorityAvg", 0.42d));
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.kalloc.learning.suppressed.readDouble"));
        assertEquals("invalid_number", TraceStore.get("cfvm.kalloc.learning.suppressed.readDouble.errorType"));

        TraceStore.clear();
        Method safeDouble = NeedleKeptRatioRewardAspect.class.getDeclaredMethod("safeDouble", Object.class, double.class);
        safeDouble.setAccessible(true);
        assertEquals(0.24d, (double) safeDouble.invoke(null, "not-a-number", 0.24d));
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.reward.suppressed.safeDouble"));
        assertEquals("invalid_number", TraceStore.get("cfvm.reward.suppressed.safeDouble.errorType"));

        TraceStore.clear();
        assertEquals(0.24d, (double) safeDouble.invoke(null, Double.POSITIVE_INFINITY, 0.24d));
        assertEquals(Boolean.TRUE, TraceStore.get("cfvm.reward.suppressed.safeDouble"));
        assertEquals("invalid_number", TraceStore.get("cfvm.reward.suppressed.safeDouble.errorType"));
    }

    private CfvmKallocLearningProperties props() {
        CfvmKallocLearningProperties props = new CfvmKallocLearningProperties();
        props.setEnabled(true);
        props.setEpsilon(0.0d);
        props.setUcbC(1.4d);
        props.setOptimismDamping(0.35d);
        props.setStorePath(tempDir.resolve("bandit.json").toString());
        return props;
    }

    private static KAllocator.Settings settings() {
        KAllocator.Settings settings = new KAllocator.Settings();
        settings.enabled = true;
        settings.maxTotalK = 24;
        settings.minPerSource = 2;
        settings.kStep = 4;
        settings.recencyKeywords = List.of("release");
        return settings;
    }

    private static String tileKey(String intent, QueryComplexityGate.Level cx, boolean recency, boolean officialOnly) {
        int cxCode = switch (cx == null ? QueryComplexityGate.Level.AMBIGUOUS : cx) {
            case SIMPLE -> 0;
            case AMBIGUOUS -> 1;
            case COMPLEX -> 2;
        };
        int h = Objects.hash(intent == null ? "" : intent.trim(), cxCode, recency ? 1 : 0, officialOnly ? 1 : 0);
        return "cfvm9:t" + Math.floorMod(h, 9);
    }

    private static RagFailureBlackboxService blackboxService(boolean enabled) {
        RagFailureBlackboxService service = new RagFailureBlackboxService(null, null, null);
        ReflectionTestUtils.setField(service, "enabled", enabled);
        return service;
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }

            @Override
            public T getObject() {
                return value;
            }

            @Override
            public Iterator<T> iterator() {
                return value == null ? List.<T>of().iterator() : List.of(value).iterator();
            }

            @Override
            public Stream<T> stream() {
                return value == null ? Stream.empty() : Stream.of(value);
            }

            @Override
            public Stream<T> orderedStream() {
                return stream();
            }
        };
    }
}
