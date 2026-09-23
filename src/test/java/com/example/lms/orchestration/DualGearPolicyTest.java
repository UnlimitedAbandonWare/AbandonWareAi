package com.example.lms.orchestration;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DualGearPolicyTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void goldenRatioProfileKeepsStableBaselineAndThreeEmergencyGears() {
        GoldenRatioProfile profile = GoldenRatioProfile.defaults();

        assertEquals(0.30d, profile.baseline().temperature(), 0.0001d);
        assertEquals(0.20d, profile.baseline().topP(), 0.0001d);
        assertEquals(3, profile.gachaGears().size());
        assertTrue(profile.gachaGears().stream().allMatch(g -> g.temperature() > profile.baseline().temperature()));
    }

    @Test
    void policyUsesGachaBurstWhenRecallIsLowAndRecordsTrace() {
        DualGearPolicy policy = new DualGearPolicy(
                GoldenRatioProfile.defaults(),
                new StochasticCircuitBreaker(0.55d, 17L),
                new StrategyConflictResolver());

        DualGearPolicy.Decision decision = policy.decide(new StrategyConflictResolver.Signals(
                true,
                false,
                false,
                false));

        assertEquals(ExecutionPlan.PrimaryMode.EXTREMEZ, decision.plan().primaryMode());
        assertEquals("GACHA_BURST", decision.gearMode());
        assertEquals(3, decision.gachaVariants().size());
        assertEquals("GACHA_BURST", TraceStore.get("dualGear.mode"));
        assertEquals("EXTREMEZ", TraceStore.get("dualGear.executionPlan.primaryMode"));
    }

    @Test
    void gachaBurstRunnerReturnsBoundedDistinctVariants() {
        GachaBurstRunner runner = new GachaBurstRunner(GoldenRatioProfile.defaults());

        List<String> variants = runner.variants("RAG evidence starvation debug", 3);

        assertEquals(3, variants.size());
        assertEquals(variants.size(), variants.stream().distinct().count());
        assertTrue(variants.stream().noneMatch("RAG evidence starvation debug"::equals));
    }

    @Test
    void traceFallbackCatchesUseDirectSafeLogs() throws Exception {
        String policy = Files.readString(Path.of("main/java/com/example/lms/orchestration/DualGearPolicy.java"),
                StandardCharsets.UTF_8);
        String runner = Files.readString(Path.of("main/java/com/example/lms/orchestration/GachaBurstRunner.java"),
                StandardCharsets.UTF_8);
        String stochastic = Files.readString(Path.of("main/java/com/example/lms/orchestration/StochasticCircuitBreaker.java"),
                StandardCharsets.UTF_8);

        assertTrue(policy.contains("DualGearPolicy trace skipped errorType="));
        assertTrue(runner.contains("GachaBurstRunner trace skipped errorType="));
        assertTrue(stochastic.contains("StochasticCircuitBreaker trace skipped errorType="));
        assertTrue(policy.contains("SafeRedactor.traceLabelOrFallback(ignore.getClass().getSimpleName(), \"unknown\")"));
        assertTrue(runner.contains("SafeRedactor.traceLabelOrFallback(ignore.getClass().getSimpleName(), \"unknown\")"));
        assertTrue(stochastic.contains("SafeRedactor.traceLabelOrFallback(ignore.getClass().getSimpleName(), \"unknown\")"));
    }
}
