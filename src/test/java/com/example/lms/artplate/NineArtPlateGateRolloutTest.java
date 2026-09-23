package com.example.lms.artplate;

import com.example.lms.cfvm.RawMatrixBuffer;
import com.example.lms.artplate.sse.StochasticEvolverConfig;
import com.example.lms.artplate.sse.StochasticTransformerEvolver;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NineArtPlateGateRolloutTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void authorityWebPlateUsesScoreCardRolloutTrace() {
        NineArtPlateGate gate = new NineArtPlateGate(new ArtPlateRegistry(), new ArtPlateEvolver());

        ArtPlateSpec plate = gate.decide(new PlateContext(
                true, true,
                0, 3,
                0.90d, false,
                0.80d, 0.35d, 0.30d,
                0.42d));

        assertTrue(plate.id().startsWith("AP1_AUTH_WEB_M"));
        assertEquals("AP1_AUTH_WEB", TraceStore.get("artplate.selector.base"));
        assertTrue(String.valueOf(TraceStore.get("artplate.selector.selected")).startsWith("AP1_AUTH_WEB_M"));
        assertEquals(50, TraceStore.get("artplate.rollout.percent"));
    }

    @Test
    void weakNoisyPlateFallsBackAndKeepsBlockedRolloutReason() {
        NineArtPlateGate gate = new NineArtPlateGate(new ArtPlateRegistry(), new ArtPlateEvolver());

        ArtPlateSpec plate = gate.decide(new PlateContext(
                true, true,
                0, 0,
                0.10d, true,
                0.30d, 0.30d, 0.10d,
                0.70d));

        assertEquals("AP7_SAFE_FALLBACK", plate.id());
        assertEquals("evidence_gate_failed", TraceStore.get("artplate.rollout.reason"));
        assertEquals("evidence_gate_failed", TraceStore.get("artplate.selector.rollout.reason"));
    }

    @Test
    void rolloutTraceDoesNotDowngradeNormalVectorRecallBasePlate() {
        NineArtPlateGate gate = new NineArtPlateGate(new ArtPlateRegistry(), new ArtPlateEvolver());

        ArtPlateSpec plate = gate.decide(new PlateContext(
                true, true,
                0, 0,
                0.0d, false,
                0.55d, 0.65d, 0.30d,
                0.70d));

        assertEquals("AP3_VEC_DENSE", plate.id());
        assertEquals("AP3_VEC_DENSE", TraceStore.get("artplate.selector.selected"));
        assertEquals("evidence_gate_failed", TraceStore.get("artplate.selector.rollout.reason"));
    }

    @Test
    void scoreCardRolloutCanHoldMoECandidateWhenCanaryBucketDoesNotMatch() {
        NineArtPlateGate gate = new NineArtPlateGate(new ArtPlateRegistry(), new ArtPlateEvolver());

        ArtPlateSpec plate = gate.decide(new PlateContext(
                true, false,
                0, 2,
                0.62d, false,
                0.85d, 0.20d, 0.10d,
                0.75d));

        assertEquals("AP9_COST_SAVER", plate.id());
        assertEquals("AP9_COST_SAVER", TraceStore.get("artplate.selector.base"));
        assertTrue(String.valueOf(TraceStore.get("artplate.selector.candidate")).startsWith("AP1_AUTH_WEB_M"));
        assertEquals("AP9_COST_SAVER", TraceStore.get("artplate.selector.selected"));
        assertEquals(Boolean.TRUE, TraceStore.get("artplate.selector.rollout.promote"));
        assertEquals(Boolean.FALSE, TraceStore.get("artplate.routing.routed"));
    }

    @Test
    void decideMutatesCandidateThroughEvolverProposeBeforeRollout() {
        DeterministicEvolver evolver = new DeterministicEvolver();
        NineArtPlateGate gate = new NineArtPlateGate(new ArtPlateRegistry(), evolver);

        ArtPlateSpec plate = gate.decide(new PlateContext(
                true, true,
                0, 3,
                0.90d, false,
                0.80d, 0.35d, 0.30d,
                0.42d));

        assertTrue(evolver.proposeCalled);
        assertTrue(plate.id().endsWith("_MTEST"));
        assertEquals(Boolean.TRUE, TraceStore.get("artplate.propose.mutated"));
        assertEquals(100, TraceStore.get("artplate.rollout.percent"));
        assertEquals(Boolean.TRUE, TraceStore.get("artplate.routing.routed"));
    }

    @Test
    void selectedPlatePublishesMoeBridgeTraceWithoutRawPromptData() {
        NineArtPlateGate gate = new NineArtPlateGate(new ArtPlateRegistry(), new DeterministicEvolver());

        ArtPlateSpec plate = gate.decide(new PlateContext(
                true, true,
                0, 3,
                0.90d, false,
                0.80d, 0.35d, 0.30d,
                0.42d));

        assertEquals(plate.id(), TraceStore.get("moe.selectedPlate"));
        assertEquals(plate.id(), TraceStore.get("artplate.selected.id"));
        assertTrue((Double) TraceStore.get("artplate.selected.score") > 0.0d);
        assertTrue((Integer) TraceStore.get("artplate.gate.evaluated") >= 9);
        assertEquals("", TraceStore.get("artplate.gate.skipReason"));
        assertEquals("AP1_AUTH_WEB_MTEST", TraceStore.get("moe.evolverCandidatePlateId"));
        assertEquals(Boolean.TRUE, TraceStore.get("moe.gate.plateRegistered"));
        assertEquals("experiment", TraceStore.get("moe.abSlot"));
        assertEquals("experiment", TraceStore.get("moe.evolver.abSlot"));
        assertTrue(TraceStore.get("moe.signalVector") instanceof Map<?, ?>);
        Map<?, ?> signalVector = (Map<?, ?>) TraceStore.get("moe.signalVector");
        assertEquals(Boolean.TRUE, signalVector.get("useWeb"));
        assertEquals(Boolean.TRUE, signalVector.get("useRag"));
        assertEquals(3, signalVector.get("evidenceCount"));
        assertFalse(signalVector.containsKey("query"));
        assertFalse(signalVector.containsKey("prompt"));
    }

    @Test
    void moePrimaryStrategySeedsArtPlateBaseAlias() {
        NineArtPlateGate gate = new NineArtPlateGate(new ArtPlateRegistry(), new ArtPlateEvolver());
        TraceStore.put("moe.strategy.primary", "R_ONLY");

        gate.decide(new PlateContext(
                true, true,
                0, 1,
                0.30d, false,
                0.30d, 0.35d, 0.10d,
                0.45d));

        assertEquals("AP3_VEC_DENSE", TraceStore.get("artplate.gate.moeStrategy.alias"));
        assertEquals("AP3_VEC_DENSE", TraceStore.get("artplate.selector.base"));
    }

    @Test
    void kgReasoningSignalsCanPromoteAp5Candidate() {
        NineArtPlateGate gate = new NineArtPlateGate(new ArtPlateRegistry(), new DeterministicEvolver());

        ArtPlateSpec plate = gate.decide(new PlateContext(
                true, true,
                0, 3,
                0.58d, false,
                0.40d, 0.78d, 0.20d,
                0.85d));

        assertEquals("AP5_KG_REASON_MTEST", plate.id());
        assertEquals("AP3_VEC_DENSE", TraceStore.get("artplate.selector.base"));
        assertEquals("AP5_KG_REASON_MTEST", TraceStore.get("artplate.selector.candidate"));
        assertEquals("AP5_KG_REASON_MTEST", TraceStore.get("artplate.selector.selected"));
    }

    @Test
    void longFormEvidenceSignalsCanPromoteAp6Candidate() {
        NineArtPlateGate gate = new NineArtPlateGate(new ArtPlateRegistry(), new DeterministicEvolver());

        ArtPlateSpec plate = gate.decide(new PlateContext(
                true, true,
                4, 5,
                0.55d, false,
                0.60d, 0.55d, 0.35d,
                0.65d));

        assertEquals("AP6_LONG_POLISH_MTEST", plate.id());
        assertEquals("AP9_COST_SAVER", TraceStore.get("artplate.selector.base"));
        assertEquals("AP6_LONG_POLISH_MTEST", TraceStore.get("artplate.selector.candidate"));
        assertEquals("AP6_LONG_POLISH_MTEST", TraceStore.get("artplate.selector.selected"));
    }

    @Test
    void balancedHighRecallSignalsCanPromoteAp8Candidate() {
        NineArtPlateGate gate = new NineArtPlateGate(new ArtPlateRegistry(), new DeterministicEvolver());

        ArtPlateSpec plate = gate.decide(new PlateContext(
                true, true,
                0, 4,
                0.52d, false,
                0.72d, 0.70d, 0.20d,
                0.82d));

        assertEquals("AP8_CONTRA_FACT_MTEST", plate.id());
        assertEquals("AP9_COST_SAVER", TraceStore.get("artplate.selector.base"));
        assertEquals("AP8_CONTRA_FACT_MTEST", TraceStore.get("artplate.selector.candidate"));
        assertEquals("AP8_CONTRA_FACT_MTEST", TraceStore.get("artplate.selector.selected"));
    }

    @Test
    void trainingRagSparseSignalsCanPromoteAp10CandidateWithoutRawPromptTrace() {
        NineArtPlateGate gate = new NineArtPlateGate(new ArtPlateRegistry(), new DeterministicEvolver());

        ArtPlateSpec plate = gate.decide(new PlateContext(
                false, true,
                3, 1,
                0.36d, false,
                0.22d, 0.86d, 0.72d,
                0.88d));

        assertEquals("AP10_TRAIN_RAG_MTEST", plate.id());
        assertEquals("AP4_MEM_HARVEST", TraceStore.get("artplate.selector.base"));
        assertEquals("AP10_TRAIN_RAG_MTEST", TraceStore.get("artplate.selector.candidate"));
        assertEquals("AP10_TRAIN_RAG_MTEST", TraceStore.get("artplate.selector.selected"));
        assertEquals("AP10_TRAIN_RAG_MTEST", TraceStore.get("moe.selectedPlate"));
        assertTrue(TraceStore.get("moe.signalVector") instanceof Map<?, ?>);
        Map<?, ?> signalVector = (Map<?, ?>) TraceStore.get("moe.signalVector");
        assertEquals(Boolean.FALSE, signalVector.get("useWeb"));
        assertEquals(Boolean.TRUE, signalVector.get("useRag"));
        assertEquals(1, signalVector.get("evidenceCount"));
        assertFalse(signalVector.containsKey("query"));
        assertFalse(signalVector.containsKey("prompt"));
    }

    @Test
    void cfvmDominantSlotOverridesProposalBucketWithoutChangingBaseSelection() {
        RawMatrixBuffer buffer = new RawMatrixBuffer();
        buffer.updateWeight(6, 4.0d);
        DeterministicEvolver evolver = new DeterministicEvolver();
        NineArtPlateGate gate = new NineArtPlateGate(new ArtPlateRegistry(), evolver, buffer);

        gate.decide(new PlateContext(
                true, true,
                0, 3,
                0.90d, false,
                0.80d, 0.35d, 0.30d,
                0.42d));

        assertEquals(ArtPlateEvolver.PlateFailureBucket.CONTRADICTION, evolver.lastBucket);
        assertEquals("AP1_AUTH_WEB", TraceStore.get("artplate.selector.base"));
        assertEquals("CONTRADICTION", TraceStore.get("artplate.cfvm.bucket"));
        assertEquals(6, TraceStore.get("artplate.cfvm.maxSlot"));
        assertEquals(Boolean.TRUE, TraceStore.get("artplate.cfvm.override"));
    }

    @Test
    void missingRawBufferLeavesMoeAbsentBreadcrumbAndContinues() {
        NineArtPlateGate gate = new NineArtPlateGate(new ArtPlateRegistry(), new ArtPlateEvolver(), null);

        ArtPlateSpec plate = gate.decide(new PlateContext(
                true, true,
                0, 3,
                0.90d, false,
                0.80d, 0.35d, 0.30d,
                0.42d));

        assertTrue(plate.id().startsWith("AP1_AUTH_WEB"));
        assertEquals(Boolean.TRUE, TraceStore.get("moe.rawBuffer.absent"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void emptyRegistryLeavesExplicitMoeBreadcrumb() throws Exception {
        ArtPlateRegistry registry = new ArtPlateRegistry();
        Field platesField = ArtPlateRegistry.class.getDeclaredField("plates");
        platesField.setAccessible(true);
        ((Map<String, ArtPlateSpec>) platesField.get(registry)).clear();

        assertTrue(registry.all().isEmpty());
        assertEquals(Boolean.TRUE, TraceStore.get("moe.registry.empty"));
    }

    @Test
    void evolverRolloutFailureReturnsBasePlateAndTracesErrorTypeOnly() {
        NineArtPlateGate gate = new NineArtPlateGate(new ArtPlateRegistry(), new ThrowingRolloutEvolver());

        ArtPlateSpec plate = assertDoesNotThrow(() -> gate.decide(new PlateContext(
                true, true,
                0, 3,
                0.90d, false,
                0.80d, 0.35d, 0.30d,
                0.42d)));

        assertEquals("AP1_AUTH_WEB", plate.id());
        assertEquals("IllegalStateException", TraceStore.get("moe.evolver.error"));
        assertEquals("AP1_AUTH_WEB", TraceStore.get("moe.selectedPlate"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(fakeKey()));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("ownerToken"));
    }

    @Test
    void missingRegistryKeysFallBackToAvailableSafePlateWithoutThrowing() {
        NineArtPlateGate gate = new NineArtPlateGate(new SparseArtPlateRegistry(), new ArtPlateEvolver());

        ArtPlateSpec plate = assertDoesNotThrow(() -> gate.decide(new PlateContext(
                true, true,
                0, 0,
                0.10d, true,
                0.30d, 0.30d, 0.10d,
                0.70d)));

        assertEquals("AP9_COST_SAVER", plate.id());
        assertEquals("AP9_COST_SAVER", TraceStore.get("artplate.selector.base"));
        assertEquals("missing_plate:AP7_SAFE_FALLBACK", TraceStore.get("artplate.selector.fallbackReason"));
    }

    @Test
    void emergencyFallbackDoesNotConstructANewRegistry() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/artplate/NineArtPlateGate.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("new ArtPlateRegistry().get(\"AP9_COST_SAVER\").orElseThrow()"));
    }

    @Test
    void failSoftLogsUseHashAndLengthOnly() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/artplate/NineArtPlateGate.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(ex), 180)"));
        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(ex), 120)"));
        assertTrue(source.contains("[NineArtPlateGate] MoE bridge fail-soft. errorHash={} errorLength={}"));
        assertTrue(source.contains("[NineArtPlateGate] Art plate propose fail-soft. errorHash={} errorLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(messageOf(ex)), messageLength(ex)"));
    }

    @Test
    void sseRuntimeStateIsUpdatedAsOneAtomicUnit() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/artplate/NineArtPlateGate.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("private volatile SseSessionState sseState"));
        assertFalse(source.contains("private volatile double lastSseScore"));
        assertTrue(source.contains("AtomicReference<SseRuntimeState>"));
        assertTrue(source.contains("new SseRuntimeState("));
    }

    @Test
    void sseRuntimeStateStartsFreshForDifferentBasePlate() throws Exception {
        NineArtPlateGate gate = new NineArtPlateGate(new ArtPlateRegistry(), new ArtPlateEvolver());
        injectSse(gate);

        gate.decide(new PlateContext(
                true, true,
                0, 3,
                0.90d, false,
                0.80d, 0.35d, 0.30d,
                0.42d));

        assertEquals(1, TraceStore.get("sse.iteration"));
        assertEquals("AP1_AUTH_WEB", TraceStore.get("sse.basePlateId"));

        TraceStore.clear();

        gate.decide(new PlateContext(
                true, true,
                0, 0,
                0.10d, true,
                0.30d, 0.30d, 0.10d,
                0.70d));

        assertEquals(1, TraceStore.get("sse.iteration"));
        assertEquals("AP3_VEC_DENSE", TraceStore.get("sse.basePlateId"));
        assertEquals("plate", TraceStore.get("sse.stateScope"));
        assertEquals("AP3_VEC_DENSE", TraceStore.get("sse.stateKey"));
    }

    private static final class DeterministicEvolver extends ArtPlateEvolver {
        boolean proposeCalled;
        PlateFailureBucket lastBucket;

        @Override
        public Optional<ArtPlateSpec> propose(PlateFailureBucket bucket, ArtPlateSpec base) {
            proposeCalled = true;
            lastBucket = bucket;
            return Optional.of(copyWithId(base, base.id() + "_MTEST"));
        }

        @Override
        public RolloutDecision abTest(ArtPlateSpec candidate, ScoreCard scoreCard) {
            RolloutDecision decision = new RolloutDecision(candidate, 0.99d, 100, true, "test_force");
            TraceStore.put("artplate.rollout.candidate", candidate.id());
            TraceStore.put("artplate.rollout.percent", decision.rolloutPercent());
            TraceStore.put("artplate.rollout.promote", decision.promote());
            TraceStore.put("artplate.rollout.reason", decision.reason());
            return decision;
        }

        @Override
        public boolean shouldRouteToCandidate(RolloutDecision decision, String routeKey) {
            TraceStore.put("artplate.routing.routed", true);
            return true;
        }

        private static ArtPlateSpec copyWithId(ArtPlateSpec base, String id) {
            return new ArtPlateSpec(
                    id,
                    base.intent(),
                    base.webTopK(),
                    base.vecTopK(),
                    base.allowMemory(),
                    base.kgOn(),
                    base.webBudgetMs(),
                    base.vecBudgetMs(),
                    base.domainAllow(),
                    base.noveltyFloor(),
                    base.authorityFloor(),
                    base.includeHistory(),
                    base.includeDraft(),
                    base.includePrevAnswer(),
                    base.modelCandidates(),
                    base.crossEncoderOn(),
                    base.minEvidence(),
                    base.minDistinctSources(),
                    base.wAuthority(),
                    base.wNovelty(),
                    base.wFd(),
                    base.wMatch());
        }
    }

    private static final class ThrowingRolloutEvolver extends ArtPlateEvolver {
        @Override
        public RolloutDecision abTest(ArtPlateSpec candidate, ScoreCard scoreCard) {
            throw new IllegalStateException("ownerToken=" + fakeKey());
        }
    }

    private static final class SparseArtPlateRegistry extends ArtPlateRegistry {
        private final ArtPlateSpec costSaver = new ArtPlateRegistry().get("AP9_COST_SAVER").orElseThrow();

        @Override
        public Collection<ArtPlateSpec> all() {
            return List.of(costSaver);
        }

        @Override
        public Optional<ArtPlateSpec> get(String id) {
            return "AP9_COST_SAVER".equals(id) ? Optional.of(costSaver) : Optional.empty();
        }
    }

    private static String fakeKey() {
        return "sk-" + "artPlateSecretLeak123456789012";
    }

    private static void injectSse(NineArtPlateGate gate) throws Exception {
        Field field = NineArtPlateGate.class.getDeclaredField("sseEvolver");
        field.setAccessible(true);
        field.set(gate, new StochasticTransformerEvolver(new StochasticEvolverConfig(
                true, 4, 0.25d, 0.05d, -0.04d, 0.08d, 1.5d, 0.5d, 2)));
    }
}
