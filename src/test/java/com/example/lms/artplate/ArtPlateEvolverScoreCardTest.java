package com.example.lms.artplate;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArtPlateEvolverScoreCardTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void noEvidenceProposalIsDeterministicAndTraceable() {
        ArtPlateEvolver evolver = new ArtPlateEvolver();
        ArtPlateSpec base = new ArtPlateRegistry().get("AP9_COST_SAVER").orElseThrow();

        ArtPlateSpec candidate = evolver.propose(ArtPlateEvolver.PlateFailureBucket.NO_EVIDENCE, base).orElseThrow();

        assertEquals("AP9_COST_SAVER_M_NO_EVIDENCE", candidate.id());
        assertTrue(candidate.webTopK() > base.webTopK());
        assertTrue(candidate.vecTopK() > base.vecTopK());
        assertTrue(candidate.crossEncoderOn());
        assertTrue(candidate.minEvidence() > base.minEvidence());
        assertTrue(candidate.minDistinctSources() > base.minDistinctSources());
        assertEquals("NO_EVIDENCE", TraceStore.get("artplate.propose.bucket"));
        assertEquals("AP9_COST_SAVER", TraceStore.get("artplate.propose.base"));
        assertEquals("AP9_COST_SAVER_M_NO_EVIDENCE", TraceStore.get("artplate.propose.candidate"));
        assertEquals(candidate.webTopK(), TraceStore.get("artplate.propose.webTopK"));
        assertEquals(candidate.vecTopK(), TraceStore.get("artplate.propose.vecTopK"));
        assertEquals(candidate.minEvidence(), TraceStore.get("artplate.propose.minEvidence"));
        assertEquals(Boolean.FALSE, TraceStore.get("artplate.propose.promptTemplateBlocked"));
        assertEquals(Boolean.TRUE, TraceStore.get("moe.evolver.plateRegistered"));
        assertEquals(Boolean.TRUE, TraceStore.get("moe.evolverPlateRegistered"));
        assertEquals("AP9_COST_SAVER_M_NO_EVIDENCE", TraceStore.get("moe.evolver.plateId"));
        assertEquals("NO_EVIDENCE", TraceStore.get("moe.evolver.failureBucket"));
        assertEquals("AP9_COST_SAVER_M_NO_EVIDENCE", TraceStore.get("moe.evolver.candidate.id"));
        assertEquals("AP9_COST_SAVER_M_NO_EVIDENCE", TraceStore.get("moe.evolverCandidatePlateId"));
        assertEquals("", TraceStore.get("moe.evolver.skipReason"));
    }

    @Test
    void timeoutProposalReducesFanoutButExtendsStageBudgets() {
        ArtPlateEvolver evolver = new ArtPlateEvolver();
        ArtPlateSpec base = new ArtPlateRegistry().get("AP2_FRESH_WEB").orElseThrow();

        ArtPlateSpec candidate = evolver.propose(ArtPlateEvolver.PlateFailureBucket.TIMEOUT, base).orElseThrow();

        assertEquals("AP2_FRESH_WEB_M_TIMEOUT", candidate.id());
        assertTrue(candidate.webTopK() < base.webTopK());
        assertTrue(candidate.vecTopK() <= base.vecTopK());
        assertTrue(candidate.webBudgetMs() > base.webBudgetMs());
        assertTrue(candidate.vecBudgetMs() > base.vecBudgetMs());
        assertEquals("TIMEOUT", TraceStore.get("artplate.propose.bucket"));
        assertEquals(candidate.webBudgetMs(), TraceStore.get("artplate.propose.webBudgetMs"));
        assertEquals(candidate.vecBudgetMs(), TraceStore.get("artplate.propose.vecBudgetMs"));
    }

    @Test
    void contradictionProposalEnablesKgAndRaisesConsensusFloor() {
        ArtPlateEvolver evolver = new ArtPlateEvolver();
        ArtPlateSpec base = new ArtPlateRegistry().get("AP8_CONTRA_FACT").orElseThrow();

        ArtPlateSpec candidate = evolver.propose(ArtPlateEvolver.PlateFailureBucket.CONTRADICTION, base).orElseThrow();

        assertEquals("AP8_CONTRA_FACT_M_CONTRADICTION", candidate.id());
        assertTrue(candidate.kgOn());
        assertTrue(candidate.crossEncoderOn());
        assertTrue(candidate.authorityFloor() > base.authorityFloor());
        assertTrue(candidate.minDistinctSources() > base.minDistinctSources());
        assertEquals("CONTRADICTION", TraceStore.get("artplate.propose.bucket"));
        assertEquals(Boolean.TRUE, TraceStore.get("artplate.propose.kgOn"));
    }

    @Test
    void sourceDoesNotDescribeEvolverAsStub() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/artplate/ArtPlateEvolver.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("Stub evolver"));
        assertFalse(source.contains("new Random("));
        assertFalse(source.contains("catch (Throwable"));
        assertFalse(source.contains(".promptTemplate("));
        assertTrue(source.contains("hasPromptTemplateMutationSurface"));
        assertTrue(source.contains("prompt_template_mutation_blocked"));
        assertTrue(source.contains("traceSuppressedEvolutionFailure(\"repositoryProvider\", \"repository_unavailable\", ex);"));
        assertTrue(source.contains("traceSuppressedEvolutionFailure(\"repositorySave\", \"repository_error\", ex);"));
    }

    @Test
    void neutralScoreCardKeepsErrorPenaltySeparateFromSampleCount() {
        ArtPlateEvolver.ScoreCard neutral = ArtPlateEvolver.ScoreCard.neutral();

        assertEquals(0.05d, neutral.errorPenalty(), 1.0e-9d);
        assertEquals(0, neutral.samples());
    }

    @Test
    void strongScoreCardPromotesCandidateToFiftyPercentRollout() {
        ArtPlateEvolver evolver = new ArtPlateEvolver();
        ArtPlateSpec candidate = new ArtPlateRegistry().get("AP1_AUTH_WEB").orElseThrow();

        ArtPlateEvolver.RolloutDecision decision = evolver.abTest(candidate,
                new ArtPlateEvolver.ScoreCard(0.92d, 0.80d, 0.74d, 0.90d, 0.04d, 0.02d, 25));

        assertTrue(decision.promote());
        assertEquals(50, decision.rolloutPercent());
        assertEquals("scorecard_promote_50", decision.reason());
        assertEquals("AP1_AUTH_WEB", TraceStore.get("artplate.rollout.candidate"));
        assertEquals(50, TraceStore.get("artplate.rollout.percent"));
        assertEquals(50, TraceStore.get("moe.evolver.rolloutPercent"));
        assertEquals(Boolean.TRUE, TraceStore.get("moe.evolver.promoted"));
        assertEquals("AP1_AUTH_WEB", TraceStore.get("moe.evolverCandidatePlateId"));
    }

    @Test
    void mediumScoreCardUsesFifteenPercentProgressiveRollout() {
        ArtPlateEvolver evolver = new ArtPlateEvolver();
        ArtPlateSpec candidate = new ArtPlateRegistry().get("AP3_VEC_DENSE").orElseThrow();

        ArtPlateEvolver.RolloutDecision decision = evolver.abTest(candidate,
                new ArtPlateEvolver.ScoreCard(0.70d, 0.62d, 0.62d, 0.68d, 0.08d, 0.03d, 12));

        assertTrue(decision.promote());
        assertEquals(15, decision.rolloutPercent());
        assertEquals("scorecard_promote_15", decision.reason());
    }

    @Test
    void weakScoreCardBlocksRolloutButKeepsTraceableReason() {
        ArtPlateEvolver evolver = new ArtPlateEvolver();
        ArtPlateSpec candidate = new ArtPlateRegistry().get("AP9_COST_SAVER").orElseThrow();

        ArtPlateEvolver.RolloutDecision decision = evolver.abTest(candidate,
                new ArtPlateEvolver.ScoreCard(0.30d, 0.35d, 0.20d, 0.40d, 0.30d, 0.20d, 4));

        assertFalse(decision.promote());
        assertEquals(0, decision.rolloutPercent());
        assertEquals("authority_gate_failed", decision.reason());
        assertEquals("authority_gate_failed", TraceStore.get("artplate.rollout.reason"));
        assertEquals(Boolean.FALSE, TraceStore.get("artplate.rollout.guard.accepted"));
        assertEquals("authority_gate_failed", TraceStore.get("artplate.rollout.guard.reason"));
    }

    @Test
    void neutralScoreCardCannotCanaryWithoutEvidenceAuthorityGate() {
        ArtPlateEvolver evolver = new ArtPlateEvolver();
        ArtPlateSpec candidate = new ArtPlateRegistry().get("AP9_COST_SAVER").orElseThrow();

        ArtPlateEvolver.RolloutDecision decision = evolver.abTest(candidate, ArtPlateEvolver.ScoreCard.neutral());

        assertFalse(decision.promote());
        assertEquals(0, decision.rolloutPercent());
        assertEquals("evidence_gate_failed", decision.reason());
        assertEquals(Boolean.FALSE, TraceStore.get("artplate.rollout.guard.accepted"));
        assertEquals("evidence_gate_failed", TraceStore.get("artplate.rollout.guard.reason"));
    }

    @Test
    void nullCandidateRolloutIsBlockedWithMoeBreadcrumb() {
        ArtPlateEvolver evolver = new ArtPlateEvolver();

        ArtPlateEvolver.RolloutDecision decision = evolver.abTest(null, ArtPlateEvolver.ScoreCard.neutral());

        assertFalse(decision.promote());
        assertEquals(0, decision.rolloutPercent());
        assertEquals("candidate_null", decision.reason());
        assertEquals(Boolean.TRUE, TraceStore.get("moe.evolver.candidateNull"));
        assertEquals("candidate_null", TraceStore.get("moe.evolver.skipReason"));
    }

    @Test
    void rolloutTraceHashesUnsafeCandidateId() {
        ArtPlateEvolver evolver = new ArtPlateEvolver();
        ArtPlateSpec candidate = new ArtPlateSpec(
                "APX ownerToken=sk-" + "redactioncontract1234567890",
                "rag",
                4,
                8,
                false,
                false,
                500,
                500,
                java.util.List.of(),
                0.1d,
                0.4d,
                false,
                false,
                false,
                java.util.List.of(),
                false,
                2,
                1,
                0.3d,
                0.2d,
                0.2d,
                0.3d);

        ArtPlateEvolver.RolloutDecision decision = evolver.abTest(candidate, new ArtPlateEvolver.ScoreCard(
                0.92d, 0.80d, 0.74d, 0.90d, 0.04d, 0.02d, 25));

        Object tracedCandidate = TraceStore.get("artplate.rollout.candidate");
        assertTrue(decision.promote());
        assertEquals(50, decision.rolloutPercent());
        assertEquals("scorecard_promote_50", decision.reason());
        assertEquals(50, TraceStore.get("artplate.rollout.percent"));
        assertNotNull(tracedCandidate);
        assertTrue(String.valueOf(tracedCandidate).startsWith("hash:"));
        assertFalse(String.valueOf(tracedCandidate).contains("ownerToken"));
    }

    @Test
    void abTestPersistsEvolutionLogWithRedactedCandidateId() {
        ArtPlateEvolutionLogRepository repository = mock(ArtPlateEvolutionLogRepository.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ArtPlateEvolutionLogRepository> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(repository);
        ArtPlateEvolver evolver = new ArtPlateEvolver(provider);
        ArtPlateSpec candidate = new ArtPlateSpec(
                "APX ownerToken=sk-" + "redactioncontract1234567890",
                "rag",
                4,
                8,
                false,
                false,
                500,
                500,
                java.util.List.of(),
                0.1d,
                0.4d,
                false,
                false,
                false,
                java.util.List.of(),
                false,
                2,
                1,
                0.3d,
                0.2d,
                0.2d,
                0.3d);

        evolver.abTest(candidate, new ArtPlateEvolver.ScoreCard(
                0.92d, 0.80d, 0.74d, 0.90d, 0.04d, 0.02d, 25));

        ArgumentCaptor<ArtPlateEvolutionLog> saved = ArgumentCaptor.forClass(ArtPlateEvolutionLog.class);
        verify(repository).save(saved.capture());
        ArtPlateEvolutionLog log = saved.getValue();
        assertNotNull(log);
        assertTrue(log.getCandidateId().startsWith("hash:"));
        assertFalse(log.getCandidateId().contains("ownerToken"));
        assertTrue(log.isPromote());
        assertEquals(50, log.getRolloutPercent());
        assertEquals(25, log.getSamples());
        assertEquals(Boolean.TRUE, TraceStore.get("artplate.evolution.persisted"));
    }

    @Test
    void abTestPersistenceFailureIsFailSoftAndRedacted() {
        ArtPlateEvolutionLogRepository repository = mock(ArtPlateEvolutionLogRepository.class);
        when(repository.save(any(ArtPlateEvolutionLog.class)))
                .thenThrow(new IllegalStateException("database password=secret-value"));
        @SuppressWarnings("unchecked")
        ObjectProvider<ArtPlateEvolutionLogRepository> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(repository);
        ArtPlateEvolver evolver = new ArtPlateEvolver(provider);
        ArtPlateSpec candidate = new ArtPlateRegistry().get("AP1_AUTH_WEB").orElseThrow();

        evolver.abTest(candidate, new ArtPlateEvolver.ScoreCard(
                0.92d, 0.80d, 0.74d, 0.90d, 0.04d, 0.02d, 25));

        assertEquals(Boolean.FALSE, TraceStore.get("artplate.evolution.persisted"));
        assertEquals("repository_error", TraceStore.get("artplate.evolution.persist.reason"));
        Object errorHash = TraceStore.get("artplate.evolution.persist.errorHash");
        assertNotNull(errorHash);
        assertFalse(String.valueOf(errorHash).contains("secret-value"));
        verify(repository).save(any(ArtPlateEvolutionLog.class));
    }

    @Test
    void shouldRouteUsesRolloutBucketAndRedactedCandidateTrace() {
        ArtPlateEvolver evolver = new ArtPlateEvolver();
        ArtPlateSpec candidate = new ArtPlateSpec(
                "APX ownerToken=sk-" + "redactioncontract1234567890",
                "rag",
                4,
                8,
                false,
                false,
                500,
                500,
                java.util.List.of(),
                0.1d,
                0.4d,
                false,
                false,
                false,
                java.util.List.of(),
                false,
                2,
                1,
                0.3d,
                0.2d,
                0.2d,
                0.3d);
        ArtPlateEvolver.RolloutDecision decision =
                new ArtPlateEvolver.RolloutDecision(candidate, 0.80d, 5, true, "scorecard_canary_5");

        assertTrue(evolver.shouldRouteToCandidate(decision, "test-key-32"));

        assertEquals(Boolean.TRUE, TraceStore.get("artplate.routing.routed"));
        assertEquals(0, TraceStore.get("artplate.routing.bucket"));
        Object tracedCandidate = TraceStore.get("artplate.routing.candidate");
        assertNotNull(tracedCandidate);
        assertTrue(String.valueOf(tracedCandidate).startsWith("hash:"));
        assertFalse(String.valueOf(tracedCandidate).contains("ownerToken"));
    }
}
