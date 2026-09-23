package com.example.lms.artplate.sse;

import com.example.lms.artplate.ArtPlateEvolver;
import com.example.lms.artplate.ArtPlateRegistry;
import com.example.lms.artplate.ArtPlateSpec;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StochasticTransformerEvolverTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SseContextConfig.class)
            .withBean(StochasticTransformerEvolver.class);

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void disabledReturnsBaseWithoutChangingDeterministicDefaults() {
        StochasticTransformerEvolver sse = new StochasticTransformerEvolver(disabledConfig());
        ArtPlateSpec base = costSaverPlate();

        StochasticTransformerEvolver.SseResult result =
                sse.perturb(base, SseSessionState.initial(), null);

        assertSame(base, result.mutatedSpec());
        assertEquals(Boolean.FALSE, TraceStore.get("sse.enabled"));
    }

    @Test
    void resetPhaseAfterTwoConsecutivePenaltiesKeepsSpecWithinSafeBounds() {
        StochasticTransformerEvolver sse = new StochasticTransformerEvolver(enabledConfig());
        SseSessionState state = new SseSessionState(3, 0.50d, 2, SsePhase.EXPLOIT);
        SseScoreCard scoreCard = new SseScoreCard(0.60d, 0.40d, 2, 3);

        StochasticTransformerEvolver.SseResult result =
                sse.perturb(costSaverPlate(), state, scoreCard);

        ArtPlateSpec mutated = result.mutatedSpec();
        assertTrue(mutated.id().contains("SSE_RESET"));
        assertEquals("RESET", TraceStore.get("sse.phase"));
        assertTrue(mutated.webTopK() >= 1 && mutated.webTopK() <= 16);
        assertTrue(mutated.vecTopK() >= 1 && mutated.vecTopK() <= 32);
        assertTrue(mutated.webBudgetMs() >= 300 && mutated.webBudgetMs() <= 5000);
        assertTrue(mutated.vecBudgetMs() >= 300 && mutated.vecBudgetMs() <= 5000);
    }

    @Test
    void singleConfiguredResetIsAllowedAtTwoConsecutivePenalties() {
        StochasticEvolverConfig config = new StochasticEvolverConfig(
                true, 4, 0.25d, 0.05d, -0.04d, 0.08d, 1.5d, 0.5d, 1);
        StochasticTransformerEvolver sse = new StochasticTransformerEvolver(config);
        SseSessionState state = new SseSessionState(3, 0.50d, 2, SsePhase.EXPLOIT);
        SseScoreCard scoreCard = new SseScoreCard(0.60d, 0.40d, 2, 3);

        StochasticTransformerEvolver.SseResult result = sse.perturb(costSaverPlate(), state, scoreCard);

        assertTrue(result.mutatedSpec().id().contains("SSE_RESET"));
        assertEquals("RESET", TraceStore.get("sse.phase"));
    }

    @Test
    void resetLimitExceededReturnsBaseFailSoftWithReason() {
        StochasticEvolverConfig config = new StochasticEvolverConfig(
                true, 4, 0.25d, 0.05d, -0.04d, 0.08d, 1.5d, 0.5d, 1);
        StochasticTransformerEvolver sse = new StochasticTransformerEvolver(config);
        ArtPlateSpec base = costSaverPlate();
        SseSessionState state = new SseSessionState(3, 0.50d, 3, SsePhase.RESET);
        SseScoreCard scoreCard = new SseScoreCard(0.60d, 0.40d, 3, 3);

        StochasticTransformerEvolver.SseResult result = sse.perturb(base, state, scoreCard);

        assertSame(base, result.mutatedSpec());
        assertEquals("fallback_to_deterministic", TraceStore.get("sse.source"));
        assertEquals("max_reset_exceeded", TraceStore.get("sse.bypassReason"));
        assertEquals(3, TraceStore.get("sse.resetCount"));
        assertEquals(Boolean.FALSE, TraceStore.get("sse.guard.accepted"));
    }

    @Test
    void highRewardExpandsNumericSearchSpaceButKeepsPromptAndModelSurfacesImmutable() {
        StochasticTransformerEvolver sse = new StochasticTransformerEvolver(enabledConfig());
        SseSessionState state = new SseSessionState(1, 0.50d, 0, SsePhase.EXPLOIT);
        SseScoreCard scoreCard = new SseScoreCard(0.50d, 0.70d, 0, 1);
        ArtPlateSpec base = costSaverPlate();

        StochasticTransformerEvolver.SseResult result = sse.perturb(base, state, scoreCard);

        ArtPlateSpec mutated = result.mutatedSpec();
        assertTrue(mutated.id().contains("SSE_EXPLOIT"));
        assertTrue(mutated.webTopK() >= base.webTopK());
        assertTrue(mutated.vecTopK() >= base.vecTopK());
        assertSame(base.domainAllow(), mutated.domainAllow());
        assertSame(base.modelCandidates(), mutated.modelCandidates());
        assertEquals(base.intent(), mutated.intent());
        assertEquals(base.includeHistory(), mutated.includeHistory());
        assertEquals(base.includeDraft(), mutated.includeDraft());
        assertEquals(base.includePrevAnswer(), mutated.includePrevAnswer());
        assertEquals("sse_block", TraceStore.get("sse.source"));
        assertEquals(Boolean.TRUE, TraceStore.get("sse.highReward"));
        assertEquals(Boolean.FALSE, TraceStore.get("sse.lowPenalty"));
        assertEquals(0, TraceStore.get("sse.resetCount"));
        assertEquals(Boolean.TRUE, TraceStore.get("sse.guard.accepted"));
    }

    @Test
    void artPlateSseGuardRejectsForbiddenSurfaceMutationBeforeAdoption() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/artplate/ArtPlateEvolver.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("guard_rejected_candidate"));
        assertTrue(source.contains("sameSurface(base, mutated)"));
        assertTrue(source.contains("Objects.equals(base.domainAllow(), mutated.domainAllow())"));
        assertTrue(source.contains("Objects.equals(base.modelCandidates(), mutated.modelCandidates())"));
        assertTrue(source.contains("base.includeHistory() == mutated.includeHistory()"));
        assertTrue(source.contains("within(mutated.webTopK(), 1, 16)"));
        assertTrue(source.contains("within(mutated.vecTopK(), 1, 32)"));
        assertTrue(source.contains("within(mutated.webBudgetMs(), 300, 5000)"));
    }

    @Test
    void rewardSignalsPersistAndReversePreviousSearchDirection() {
        StochasticTransformerEvolver sse = new StochasticTransformerEvolver(enabledConfig());
        ArtPlateSpec base = costSaverPlate();

        SseSessionState negativeDirection = new SseSessionState(1, 0.50d, 0, SsePhase.EXPLOIT, -1.0d);
        StochasticTransformerEvolver.SseResult highReward = sse.perturb(
                base,
                negativeDirection,
                new SseScoreCard(0.50d, 0.70d, 0, 1));

        assertTrue(highReward.mutatedSpec().webTopK() <= base.webTopK());
        assertEquals(-1.0d, highReward.nextState().directionSign(), 1.0e-9d);
        assertEquals(-1.0d, ((Number) TraceStore.get("sse.directionSign")).doubleValue(), 1.0e-9d);

        TraceStore.clear();

        StochasticTransformerEvolver.SseResult lowPenalty = sse.perturb(
                base,
                negativeDirection,
                new SseScoreCard(0.70d, 0.50d, 0, 1));

        assertTrue(lowPenalty.mutatedSpec().webTopK() >= base.webTopK());
        assertEquals(1.0d, lowPenalty.nextState().directionSign(), 1.0e-9d);
        assertEquals(1.0d, ((Number) TraceStore.get("sse.directionSign")).doubleValue(), 1.0e-9d);
    }

    @Test
    void rewardFactorsCannotInvertBoostAndPenaltyDirectionWhenMisconfigured() {
        StochasticEvolverConfig config = new StochasticEvolverConfig(
                true, 4, 0.25d, 0.05d, -0.04d, 0.20d, 0.50d, 2.0d, 2);

        assertTrue(config.lrBoostFactor() >= 1.0d);
        assertTrue(config.lrDecayFactor() <= 1.0d);

        StochasticTransformerEvolver sse = new StochasticTransformerEvolver(config);
        SseSessionState state = new SseSessionState(1, 0.50d, 0, SsePhase.EXPLOIT);
        SseScoreCard lowPenalty = new SseScoreCard(0.70d, 0.50d, 0, 1);

        sse.perturb(costSaverPlate(), state, lowPenalty);

        assertTrue(((Number) TraceStore.get("sse.lr")).doubleValue() <= 0.20d);
    }

    @Test
    void rewardFactorsCannotCollapseBoostAndPenaltyToNoopWhenMisconfigured() {
        StochasticEvolverConfig config = new StochasticEvolverConfig(
                true, 4, 0.25d, 0.05d, -0.04d, 0.20d, 1.0d, 1.0d, 2);

        assertTrue(config.lrBoostFactor() > 1.0d);
        assertTrue(config.lrDecayFactor() < 1.0d);

        StochasticTransformerEvolver sse = new StochasticTransformerEvolver(config);
        SseSessionState state = new SseSessionState(1, 0.50d, 0, SsePhase.EXPLOIT);

        sse.perturb(costSaverPlate(), state, new SseScoreCard(0.50d, 0.70d, 0, 1));
        assertTrue(((Number) TraceStore.get("sse.lr")).doubleValue() > 0.20d);

        TraceStore.clear();

        sse.perturb(costSaverPlate(), state, new SseScoreCard(0.70d, 0.50d, 0, 1));
        assertTrue(((Number) TraceStore.get("sse.lr")).doubleValue() < 0.20d);
    }

    @Test
    void rewardThresholdsCannotInvertHighRewardAndLowPenaltyClassificationWhenMisconfigured() {
        StochasticEvolverConfig config = new StochasticEvolverConfig(
                true, 4, 0.25d, -0.20d, 0.10d, 0.20d, 1.5d, 0.5d, 2);

        assertTrue(config.highRewardThreshold() > 0.0d);
        assertTrue(config.lowPenaltyThreshold() < 0.0d);

        SseScoreCard flatScore = new SseScoreCard(0.50d, 0.50d, 0, 1);
        assertFalse(flatScore.isHighReward(config.highRewardThreshold()));
        assertFalse(flatScore.isLowPenalty(config.lowPenaltyThreshold()));
    }

    @Test
    void artPlateEvolverFallsBackToDeterministicProposalWhenSseDisabled() {
        ArtPlateEvolver evolver = new ArtPlateEvolver();
        ArtPlateSpec base = costSaverPlate();
        StochasticTransformerEvolver disabled = new StochasticTransformerEvolver(disabledConfig());

        ArtPlateEvolver.SseProposal proposal = evolver.proposeWithSse(
                ArtPlateEvolver.PlateFailureBucket.NO_EVIDENCE,
                base,
                disabled,
                SseSessionState.initial(),
                new SseScoreCard(0.0d, 0.55d, 0, 0));

        ArtPlateSpec candidate = proposal.candidate().orElseThrow();
        assertEquals("AP9_COST_SAVER_M_NO_EVIDENCE", candidate.id());
        assertEquals(Boolean.FALSE, TraceStore.get("sse.enabled"));
        assertEquals("fallback_to_deterministic", TraceStore.get("sse.source"));
        assertEquals("NO_EVIDENCE", TraceStore.get("artplate.propose.bucket"));
    }

    @Test
    void artPlateEvolverTracesEnabledNullBaseFallback() {
        ArtPlateEvolver evolver = new ArtPlateEvolver();
        StochasticTransformerEvolver enabled = new StochasticTransformerEvolver(enabledConfig());

        ArtPlateEvolver.SseProposal proposal = evolver.proposeWithSse(
                ArtPlateEvolver.PlateFailureBucket.NO_EVIDENCE,
                null,
                enabled,
                SseSessionState.initial(),
                new SseScoreCard(0.0d, 0.55d, 0, 0));

        assertTrue(proposal.candidate().isEmpty());
        assertEquals(Boolean.TRUE, TraceStore.get("sse.enabled"));
        assertEquals("sse_null_fallback", TraceStore.get("sse.source"));
        assertEquals("null_base", TraceStore.get("sse.skipReason"));
    }

    @Test
    void artPlateEvolverFallsBackToDeterministicProposalWhenSseReturnsBaseFailSoft() {
        ArtPlateEvolver evolver = new ArtPlateEvolver();
        ArtPlateSpec base = costSaverPlate();
        StochasticEvolverConfig config = new StochasticEvolverConfig(
                true, 4, 0.25d, 0.05d, -0.04d, 0.08d, 1.5d, 0.5d, 1);
        StochasticTransformerEvolver sse = new StochasticTransformerEvolver(config);
        SseSessionState exhaustedResetState = new SseSessionState(3, 0.50d, 3, SsePhase.RESET);

        ArtPlateEvolver.SseProposal proposal = evolver.proposeWithSse(
                ArtPlateEvolver.PlateFailureBucket.NO_EVIDENCE,
                base,
                sse,
                exhaustedResetState,
                new SseScoreCard(0.60d, 0.40d, 3, 3));

        ArtPlateSpec candidate = proposal.candidate().orElseThrow();
        assertEquals("AP9_COST_SAVER_M_NO_EVIDENCE", candidate.id());
        assertEquals("fallback_to_deterministic", TraceStore.get("sse.source"));
        assertEquals("max_reset_exceeded", TraceStore.get("sse.bypassReason"));
        assertEquals("NO_EVIDENCE", TraceStore.get("artplate.propose.bucket"));
        assertEquals(0, proposal.nextState().iteration());
    }

    @Test
    void proposeWithSseReturnsBaseWhenDeterministicFallbackThrows() {
        ArtPlateEvolver evolver = new ThrowingDeterministicFallbackEvolver();
        ArtPlateSpec base = costSaverPlate();
        StochasticTransformerEvolver disabled = new StochasticTransformerEvolver(disabledConfig());

        ArtPlateEvolver.SseProposal proposal = evolver.proposeWithSse(
                ArtPlateEvolver.PlateFailureBucket.NO_EVIDENCE,
                base,
                disabled,
                SseSessionState.initial(),
                new SseScoreCard(0.0d, 0.55d, 0, 0));

        assertSame(base, proposal.candidate().orElseThrow());
        assertEquals("deterministic_propose_exception", TraceStore.get("sse.bypassReason"));
        assertEquals("deterministic_propose_exception", TraceStore.get("artplate.propose.skipReason"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("secret-value"));
    }

    @Test
    void sourceDoesNotBypassPromptBuilderOrMutateForbiddenSpecSurfaces() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/artplate/sse/StochasticTransformerEvolver.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("PromptContext"));
        assertFalse(source.contains("setFinalPromptText"));
        assertFalse(source.contains("new ArrayList<>(base.domainAllow())"));
        assertFalse(source.contains("new ArrayList<>(base.modelCandidates())"));
    }

    @Test
    void springContextBindsArtPlateSsePropertiesAndCreatesDisabledByDefaultEvolver() {
        contextRunner.run(context -> {
            assertTrue(context.containsBean("stochasticTransformerEvolver"));
            assertTrue(context.containsBean("artplate.sse-com.example.lms.artplate.sse.StochasticEvolverConfig"));
            assertFalse(context.getBean(StochasticTransformerEvolver.class).isEnabled());
            assertFalse(context.getBean(StochasticEvolverConfig.class).enabled());
        });
    }

    @Test
    void springContextBindsOptInSsePropertiesWithoutChangingSecretOrPromptSurfaces() {
        contextRunner
                .withPropertyValues(
                        "artplate.sse.enabled=true",
                        "artplate.sse.stagnation-threshold=7",
                        "artplate.sse.noise-scale=0.4",
                        "artplate.sse.high-reward-threshold=0.07",
                        "artplate.sse.low-penalty-threshold=-0.05",
                        "artplate.sse.learning-rate=0.11",
                        "artplate.sse.lr-boost-factor=1.7",
                        "artplate.sse.lr-decay-factor=0.4",
                        "artplate.sse.max-reset-count=3")
                .run(context -> {
                    StochasticEvolverConfig config = context.getBean(StochasticEvolverConfig.class);
                    assertTrue(context.getBean(StochasticTransformerEvolver.class).isEnabled());
                    assertTrue(config.enabled());
                    assertEquals(7, config.stagnationThreshold());
                    assertEquals(0.4d, config.noiseScale(), 1.0e-9d);
                    assertEquals(0.07d, config.highRewardThreshold(), 1.0e-9d);
                    assertEquals(-0.05d, config.lowPenaltyThreshold(), 1.0e-9d);
                    assertEquals(0.11d, config.learningRate(), 1.0e-9d);
                    assertEquals(1.7d, config.lrBoostFactor(), 1.0e-9d);
                    assertEquals(0.4d, config.lrDecayFactor(), 1.0e-9d);
                    assertEquals(3, config.maxResetCount());
                });
    }

    private static StochasticEvolverConfig disabledConfig() {
        return new StochasticEvolverConfig(false, 4, 0.25d, 0.05d, -0.04d, 0.08d, 1.5d, 0.5d, 2);
    }

    private static StochasticEvolverConfig enabledConfig() {
        return new StochasticEvolverConfig(true, 4, 0.25d, 0.05d, -0.04d, 0.08d, 1.5d, 0.5d, 2);
    }

    private static ArtPlateSpec costSaverPlate() {
        return new ArtPlateRegistry().get("AP9_COST_SAVER").orElseThrow();
    }

    private static final class ThrowingDeterministicFallbackEvolver extends ArtPlateEvolver {
        @Override
        public java.util.Optional<ArtPlateSpec> propose(PlateFailureBucket bucket, ArtPlateSpec base) {
            throw new IllegalStateException("secret-value");
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(StochasticEvolverConfig.class)
    static class SseContextConfig {
    }
}
