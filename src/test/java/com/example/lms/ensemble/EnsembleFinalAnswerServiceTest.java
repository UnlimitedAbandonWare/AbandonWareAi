package com.example.lms.ensemble;

import com.example.lms.guard.FinalSigmoidGate;
import com.example.lms.guard.ConversationFrameV1;
import com.example.lms.dto.RagEvidenceMetadata;
import com.example.lms.prompt.PromptContext;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.trace.SafeRedactor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EnsembleFinalAnswerServiceTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
        GuardContextHolder.clear();
    }

    @Test
    void disabledFeatureSkipsSamplingAndLeavesSinglePathAvailable() {
        DiverseSamplingOrchestrator sampler = mock(DiverseSamplingOrchestrator.class);
        EnsembleJudgeService judge = mock(EnsembleJudgeService.class);
        EnsembleFinalAnswerService service = new EnsembleFinalAnswerService(sampler, judge);

        assertTrue(service.tryGenerate(PromptContext.builder().userQuery("q").build(), 7L).isEmpty());
        assertEquals("disabled", TraceStore.get("ensemble.sampling.skipped"));
        assertEquals("disabled", TraceStore.get("ensemble.bypass.reason"));
        verify(sampler, never()).sample(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void enabledFeatureRecordsNoCandidateBypassReason() {
        DiverseSamplingOrchestrator sampler = mock(DiverseSamplingOrchestrator.class);
        EnsembleJudgeService judge = mock(EnsembleJudgeService.class);
        EnsembleFinalAnswerService service = new EnsembleFinalAnswerService(sampler, judge);
        ReflectionTestUtils.setField(service, "ensembleEnabled", true);
        PromptContext ctx = PromptContext.builder().userQuery("q").build();
        when(sampler.sample(ctx, "session-7")).thenReturn(List.of());

        assertTrue(service.tryGenerate(ctx, 7L).isEmpty());

        assertEquals("no_candidates", TraceStore.get("ensemble.judge.skipped"));
        assertEquals("no_candidates", TraceStore.get("ensemble.bypass.reason"));
        verify(judge, never()).judge(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void disabledRefinementSamplingDoesNotCallSamplerAndRecordsDisabledEvidence() {
        DiverseSamplingOrchestrator sampler = mock(DiverseSamplingOrchestrator.class);
        EnsembleJudgeService judge = mock(EnsembleJudgeService.class);
        EnsembleFinalAnswerService service = new EnsembleFinalAnswerService(sampler, judge);

        List<SampledCandidate> candidates = service.sampleCandidatesForRefinement(
                PromptContext.builder().userQuery("q").build(),
                7L);

        assertTrue(candidates.isEmpty());
        assertEquals("disabled", TraceStore.get("ensemble.sampling.skipped"));
        assertEquals("provider_disabled", TraceStore.get("ensemble.refiner.disabledReason"));
        assertEquals(0, TraceStore.get("ensemble.refiner.candidateCount"));
        verify(sampler, never()).sample(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
        verify(judge, never()).judge(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void enforcedRepairFrameAbstainsBeforeAnyRefinementOrProviderGate() {
        DiverseSamplingOrchestrator sampler = mock(DiverseSamplingOrchestrator.class);
        EnsembleJudgeService judge = mock(EnsembleJudgeService.class);
        EnsembleFinalAnswerService service = new EnsembleFinalAnswerService(sampler, judge);
        ReflectionTestUtils.setField(service, "ensembleEnabled", true);
        PromptContext ctx = PromptContext.builder()
                .userQuery("stop")
                .conversationFrame(new ConversationFrameV1(
                        ConversationFrameV1.Mode.ENFORCE,
                        ConversationFrameV1.Stance.REPAIR,
                        ConversationFrameV1.LightweightRole.OBSERVE_ONLY,
                        false,
                        false,
                        false,
                        ConversationFrameV1.ReasonCode.EXPLICIT_STOP))
                .build();

        List<SampledCandidate> candidates = service.sampleCandidatesForRefinement(ctx, 7L);

        assertTrue(candidates.isEmpty());
        assertEquals("conversation_stance", TraceStore.get("ensemble.refiner.disabledReason"));
        assertEquals(0, TraceStore.get("ensemble.refiner.candidateCount"));
        assertEquals(0, TraceStore.get("ensemble.refiner.modelCallCount"));
        assertEquals("primary_model", TraceStore.get("ensemble.refiner.decisionAuthority"));
        assertEquals(Boolean.FALSE, TraceStore.get("ensemble.refiner.mutationAllowed"));
        verify(sampler, never()).refinementEvidenceReady(org.mockito.ArgumentMatchers.any());
        verify(sampler, never()).sampleDualHypotheses(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
        verify(judge, never()).judge(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void dualRefinementLabelsHigherScoreForDiagnosticsButKeepsProbeOnlyAuthority() {
        DiverseSamplingOrchestrator sampler = mock(DiverseSamplingOrchestrator.class);
        EnsembleJudgeService judge = mock(EnsembleJudgeService.class);
        EnsembleFinalAnswerService service = new EnsembleFinalAnswerService(sampler, judge);
        ReflectionTestUtils.setField(service, "ensembleEnabled", true);
        PromptContext ctx = PromptContext.builder().userQuery("q").build();
        List<SampledCandidate> pair = validDualPair(0.95d, 0.85d);
        when(sampler.refinementEvidenceReady(ctx)).thenReturn(true);
        when(sampler.sampleDualHypotheses(ctx, "session-7")).thenReturn(pair);

        List<SampledCandidate> candidates = service.sampleCandidatesForRefinement(ctx, 7L);

        assertEquals(pair, candidates);
        assertEquals(2, TraceStore.get("ensemble.refiner.safeCandidateCount"));
        assertEquals(2, TraceStore.get("ensemble.refiner.candidateCount"));
        assertEquals("support", TraceStore.get("ensemble.refiner.selectionDecision"));
        assertEquals("support", TraceStore.get("ensemble.refiner.selectedCandidate"));
        assertEquals("probe_only", TraceStore.get("ensemble.refiner.decisionAuthority"));
        assertEquals(Boolean.FALSE, TraceStore.get("ensemble.refiner.mutationAllowed"));
        assertEquals(1, TraceStore.get("ensemble.refiner.maxAttempts"));
        assertEquals(Boolean.FALSE, TraceStore.get("ensemble.refiner.verificationGatePassed"));
        assertEquals(0.10d, (Double) TraceStore.get("ensemble.refiner.scoreGap"), 0.000_001d);
        assertEquals(Boolean.TRUE, TraceStore.get("ensemble.refiner.used"));
        verify(judge, never()).judge(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void eligibleCreativeRefinementUsesActiveTriadAndLeavesSynthesisToPrimaryModel() {
        DiverseSamplingOrchestrator sampler = mock(DiverseSamplingOrchestrator.class);
        EnsembleJudgeService judge = mock(EnsembleJudgeService.class);
        EnsembleFinalAnswerService service = new EnsembleFinalAnswerService(sampler, judge);
        ReflectionTestUtils.setField(service, "ensembleEnabled", true);
        PromptContext ctx = PromptContext.builder().userQuery("creative prompt").build();
        List<SampledCandidate> triad = validTriad();
        when(sampler.refinementEvidenceReady(ctx)).thenReturn(true);
        when(sampler.sample(ctx, "session-7")).thenReturn(triad);
        GuardContext creative = completeWildCreativeContext();
        GuardContextHolder.set(creative);

        List<SampledCandidate> candidates = service.sampleCandidatesForRefinement(ctx, 7L);

        assertEquals(triad, candidates);
        assertEquals("creative_synthesis", TraceStore.get("ensemble.refiner.selectionDecision"));
        assertEquals("primary_model", TraceStore.get("ensemble.refiner.decisionAuthority"));
        assertEquals(Boolean.TRUE, TraceStore.get("ensemble.refiner.used"));
        verify(sampler, times(1)).sample(ctx, "session-7");
        verify(sampler, never()).sampleDualHypotheses(ctx, "session-7");
        verify(judge, never()).judge(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString());

        PromptContext attached = ctx.toBuilder().ensembleCandidates(candidates).build();
        assertTrue(service.tryGenerate(attached, 7L).isEmpty());
        assertEquals("reference_only", TraceStore.get("ensemble.judge.skipped"));
        verify(judge, never()).judge(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void incompleteCreativeProfileUsesOrdinaryDualRefinementAndNeverClaimsCreativeSynthesis() {
        DiverseSamplingOrchestrator sampler = mock(DiverseSamplingOrchestrator.class);
        EnsembleJudgeService judge = mock(EnsembleJudgeService.class);
        EnsembleFinalAnswerService service = new EnsembleFinalAnswerService(sampler, judge);
        ReflectionTestUtils.setField(service, "ensembleEnabled", true);
        PromptContext ctx = PromptContext.builder().userQuery("partial creative prompt").build();
        List<SampledCandidate> pair = validDualPair(0.92d, 0.88d);
        when(sampler.refinementEvidenceReady(ctx)).thenReturn(true);
        when(sampler.sampleDualHypotheses(ctx, "session-7")).thenReturn(pair);
        GuardContext partial = new GuardContext();
        partial.putPlanOverride("creative.emergence.active", true);
        partial.putPlanOverride("creative.emergence.profile", "WILD");
        partial.putPlanOverride("creative.emergence.candidate.temperature", 1.36d);
        partial.putPlanOverride("creative.emergence.candidate.topP", 0.98d);
        partial.putPlanOverride("creative.emergence.requestedOptionsHash",
                SafeRedactor.hashValue("invalid-wild-profile"));
        partial.putPlanOverride("promptPose.application.intentSlot", "explore");
        GuardContextHolder.set(partial);

        List<SampledCandidate> candidates = service.sampleCandidatesForRefinement(ctx, 7L);

        assertEquals(pair, candidates);
        assertFalse("creative_synthesis".equals(TraceStore.get("ensemble.refiner.selectionDecision")));
        verify(sampler, never()).sample(ctx, "session-7");
        verify(sampler, times(1)).sampleDualHypotheses(ctx, "session-7");
    }

    @Test
    void alternativeSupportOptInReturnsCanonicalThreeRoleReferencesWithoutMajorityWinner() {
        DiverseSamplingOrchestrator sampler = mock(DiverseSamplingOrchestrator.class);
        EnsembleJudgeService judge = mock(EnsembleJudgeService.class);
        EnsembleFinalAnswerService service = new EnsembleFinalAnswerService(sampler, judge);
        ReflectionTestUtils.setField(service, "ensembleEnabled", true);
        ReflectionTestUtils.setField(service, "alternativeSupportEnabled", true);
        PromptContext ctx = PromptContext.builder().userQuery("q").build();
        List<SampledCandidate> threeRole = validThreeRoleRefinementSet();
        when(sampler.refinementEvidenceReady(ctx)).thenReturn(true);
        when(sampler.sampleThreeRoleHypotheses(ctx, "session-7")).thenReturn(List.of(
                threeRole.get(2), threeRole.get(1), threeRole.get(0)));

        List<SampledCandidate> candidates = service.sampleCandidatesForRefinement(ctx, 7L);

        assertEquals(List.of("support", "support_alternative", "falsify"),
                candidates.stream().map(SampledCandidate::nodeId).toList());
        assertEquals("underdetermined", TraceStore.get("ensemble.refiner.selectionDecision"));
        assertEquals("primary_model", TraceStore.get("ensemble.refiner.decisionAuthority"));
        verify(sampler, never()).sampleDualHypotheses(ctx, "session-7");
        verify(judge, never()).judge(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void attachedThreeRoleReferencesNeverInvokeLegacyJudgeEvenWithoutThreadLocalMarker() {
        DiverseSamplingOrchestrator sampler = mock(DiverseSamplingOrchestrator.class);
        EnsembleJudgeService judge = mock(EnsembleJudgeService.class);
        EnsembleFinalAnswerService service = new EnsembleFinalAnswerService(sampler, judge);
        ReflectionTestUtils.setField(service, "ensembleEnabled", true);
        ReflectionTestUtils.setField(service, "alternativeSupportEnabled", true);
        PromptContext ctx = PromptContext.builder()
                .userQuery("q")
                .ensembleCandidates(validThreeRoleRefinementSet())
                .build();
        assertTrue(service.tryGenerate(ctx, 7L).isEmpty());

        assertEquals("reference_only", TraceStore.get("ensemble.judge.skipped"));
        assertEquals("reference_only", TraceStore.get("ensemble.bypass.reason"));
        verify(judge, never()).judge(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString());
        verify(sampler, never()).sample(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void evidenceBackedContradictedFalsifyCandidateReachesPrimaryModelReferences() {
        DiverseSamplingOrchestrator sampler = mock(DiverseSamplingOrchestrator.class);
        EnsembleJudgeService judge = mock(EnsembleJudgeService.class);
        EnsembleFinalAnswerService service = new EnsembleFinalAnswerService(sampler, judge);
        ReflectionTestUtils.setField(service, "ensembleEnabled", true);
        PromptContext ctx = PromptContext.builder().userQuery("q").build();
        EnsembleEvidenceMatrix matrix = EnsembleEvidenceMatrix.from(List.of(
                new RagEvidenceMetadata("W1", "WEB", "public evidence", "https://official.example/a",
                        null, null, null, 1, 0.9d, "retrieval"),
                new RagEvidenceMetadata("W2", "WEB", "public evidence", "https://independent.example/b",
                        null, null, null, 1, 0.9d, "retrieval")));
        List<String> ids = matrix.evidenceIds().stream().sorted().toList();
        String falsifyDossier = """
                DIRECTION: FALSIFY
                CLAIM: the first record exposes a missing premise | EVIDENCE: %s | STATUS: SUPPORTED
                CLAIM: a contrary record limits that conclusion | EVIDENCE: %s | STATUS: CONTRADICTED
                CONCLUSION: the evidence-backed falsification case remains mixed
                """.formatted(ids.get(0), ids.get(1));
        DualHypothesisEvidenceScorer.Score score = DualHypothesisEvidenceScorer.score(
                falsifyDossier,
                SampledCandidate.HypothesisDirection.FALSIFY,
                matrix);
        SampledCandidate falsify = new SampledCandidate(
                "falsify",
                falsifyDossier,
                0.0d,
                0.40d,
                score.groundingScore(),
                0.1d,
                FinalSigmoidGate.GateResult.PASS,
                SampledCandidate.HypothesisDirection.FALSIFY,
                score.evidenceStatus(),
                score.evidenceRate(),
                score.sourceDiversity(),
                score.contradictionRate(),
                score.groundingScore());
        List<SampledCandidate> pair = List.of(
                validDualCandidate(SampledCandidate.HypothesisDirection.SUPPORT, 0.95d),
                falsify);
        when(sampler.refinementEvidenceReady(ctx)).thenReturn(true);
        when(sampler.sampleDualHypotheses(ctx, "session-7")).thenReturn(pair);

        List<SampledCandidate> candidates = service.sampleCandidatesForRefinement(ctx, 7L);

        assertEquals(SampledCandidate.EvidenceStatus.INSUFFICIENT_OR_CONTRADICTED,
                score.evidenceStatus());
        assertEquals(0.95d, score.groundingScore(), 0.000_001d);
        assertEquals(pair, candidates);
        PromptContext finalContext = ctx.toBuilder().ensembleCandidates(candidates).build();
        assertTrue(service.tryGenerate(finalContext, 7L).isEmpty());
        assertEquals("reference_only", TraceStore.get("ensemble.judge.skipped"));
        verify(judge, never()).judge(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void refinementKeepsBothCandidatesAndDoesNotForceWinnerBelowFivePointGap() {
        DiverseSamplingOrchestrator sampler = mock(DiverseSamplingOrchestrator.class);
        EnsembleJudgeService judge = mock(EnsembleJudgeService.class);
        EnsembleFinalAnswerService service = new EnsembleFinalAnswerService(sampler, judge);
        ReflectionTestUtils.setField(service, "ensembleEnabled", true);
        PromptContext ctx = PromptContext.builder().userQuery("q").build();
        List<SampledCandidate> pair = validDualPair(0.91d, 0.88d);
        when(sampler.refinementEvidenceReady(ctx)).thenReturn(true);
        when(sampler.sampleDualHypotheses(ctx, "session-7")).thenReturn(pair);

        List<SampledCandidate> candidates = service.sampleCandidatesForRefinement(ctx, 7L);

        assertEquals(pair, candidates);
        assertEquals("underdetermined", TraceStore.get("ensemble.refiner.selectionDecision"));
        assertEquals(0.03d, (Double) TraceStore.get("ensemble.refiner.scoreGap"), 0.000_001d);
        verify(judge, never()).judge(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void exactlyFivePointGapLabelsHigherGroundingDirectionForDiagnostics() {
        DiverseSamplingOrchestrator sampler = mock(DiverseSamplingOrchestrator.class);
        EnsembleJudgeService judge = mock(EnsembleJudgeService.class);
        EnsembleFinalAnswerService service = new EnsembleFinalAnswerService(sampler, judge);
        ReflectionTestUtils.setField(service, "ensembleEnabled", true);
        PromptContext ctx = PromptContext.builder().userQuery("q").build();
        List<SampledCandidate> pair = validDualPair(0.95d, 0.90d);
        when(sampler.refinementEvidenceReady(ctx)).thenReturn(true);
        when(sampler.sampleDualHypotheses(ctx, "session-7")).thenReturn(pair);

        assertEquals(pair, service.sampleCandidatesForRefinement(ctx, 7L));
        assertEquals("support", TraceStore.get("ensemble.refiner.selectionDecision"));
        assertEquals(0.05d, (Double) TraceStore.get("ensemble.refiner.scoreGap"), 0.000_001d);
    }

    @Test
    void falsifyDirectionCanLeadDiagnosticsWithoutBecomingFinalDecisionAuthority() {
        DiverseSamplingOrchestrator sampler = mock(DiverseSamplingOrchestrator.class);
        EnsembleJudgeService judge = mock(EnsembleJudgeService.class);
        EnsembleFinalAnswerService service = new EnsembleFinalAnswerService(sampler, judge);
        ReflectionTestUtils.setField(service, "ensembleEnabled", true);
        PromptContext ctx = PromptContext.builder().userQuery("q").build();
        List<SampledCandidate> pair = validDualPair(0.85d, 0.95d);
        when(sampler.refinementEvidenceReady(ctx)).thenReturn(true);
        when(sampler.sampleDualHypotheses(ctx, "session-7")).thenReturn(pair);

        assertEquals(pair, service.sampleCandidatesForRefinement(ctx, 7L));
        assertEquals("falsify", TraceStore.get("ensemble.refiner.selectionDecision"));
        assertEquals("probe_only", TraceStore.get("ensemble.refiner.decisionAuthority"));
        assertEquals(Boolean.FALSE, TraceStore.get("ensemble.refiner.mutationAllowed"));
        assertEquals(Boolean.FALSE, TraceStore.get("ensemble.refiner.verificationGatePassed"));
        verify(judge, never()).judge(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void dualRefinementPairRemainsReferenceOnlyIfLegacyJudgeEntryPointIsInvoked() {
        DiverseSamplingOrchestrator sampler = mock(DiverseSamplingOrchestrator.class);
        EnsembleJudgeService judge = mock(EnsembleJudgeService.class);
        EnsembleFinalAnswerService service = new EnsembleFinalAnswerService(sampler, judge);
        ReflectionTestUtils.setField(service, "ensembleEnabled", true);
        PromptContext seed = PromptContext.builder().userQuery("q").build();
        List<SampledCandidate> pair = validDualPair(0.95d, 0.85d);
        when(sampler.refinementEvidenceReady(seed)).thenReturn(true);
        when(sampler.sampleDualHypotheses(seed, "session-7")).thenReturn(pair);
        List<SampledCandidate> attached = service.sampleCandidatesForRefinement(seed, 7L);
        PromptContext finalContext = seed.toBuilder().ensembleCandidates(attached).build();

        assertTrue(service.tryGenerate(finalContext, 7L).isEmpty());
        assertEquals("reference_only", TraceStore.get("ensemble.judge.skipped"));
        assertEquals("prompt_context_refiner", TraceStore.get("ensemble.candidates.source"));
        verify(judge, never()).judge(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void refinementPreservesPrimaryAnswerReserveSkipReason() {
        DiverseSamplingOrchestrator sampler = mock(DiverseSamplingOrchestrator.class);
        EnsembleJudgeService judge = mock(EnsembleJudgeService.class);
        EnsembleFinalAnswerService service = new EnsembleFinalAnswerService(sampler, judge);
        ReflectionTestUtils.setField(service, "ensembleEnabled", true);
        PromptContext ctx = PromptContext.builder().userQuery("q").build();
        when(sampler.refinementEvidenceReady(ctx)).thenReturn(true);
        when(sampler.sampleDualHypotheses(ctx, "session-7")).thenAnswer(invocation -> {
            TraceStore.put("ensemble.sampling.skipped", "primary_answer_reserve");
            return List.of();
        });

        List<SampledCandidate> candidates = service.sampleCandidatesForRefinement(ctx, 7L);

        assertTrue(candidates.isEmpty());
        assertEquals("primary_answer_reserve", TraceStore.get("ensemble.refiner.disabledReason"));
        assertEquals(0, TraceStore.get("ensemble.refiner.candidateCount"));
        verify(judge, never()).judge(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void refinementSamplingRejectsAnIncompleteSafeHypothesisSet() {
        DiverseSamplingOrchestrator sampler = mock(DiverseSamplingOrchestrator.class);
        EnsembleJudgeService judge = mock(EnsembleJudgeService.class);
        EnsembleFinalAnswerService service = new EnsembleFinalAnswerService(sampler, judge);
        ReflectionTestUtils.setField(service, "ensembleEnabled", true);
        PromptContext ctx = PromptContext.builder().userQuery("q").build();
        List<SampledCandidate> incomplete = validDualPair(0.95d, 0.85d).subList(0, 1);
        when(sampler.refinementEvidenceReady(ctx)).thenReturn(true);
        when(sampler.sampleDualHypotheses(ctx, "session-7")).thenReturn(incomplete);

        List<SampledCandidate> candidates = service.sampleCandidatesForRefinement(ctx, 7L);

        assertTrue(candidates.isEmpty());
        assertEquals(1, TraceStore.get("ensemble.refiner.rawCandidateCount"));
        assertEquals(1, TraceStore.get("ensemble.refiner.safeCandidateCount"));
        assertEquals(0, TraceStore.get("ensemble.refiner.candidateCount"));
        assertEquals("unsafe_or_incomplete_hypothesis_set",
                TraceStore.get("ensemble.refiner.disabledReason"));
        assertFalse(Boolean.TRUE.equals(TraceStore.get("ensemble.refiner.used")));
        verify(judge, never()).judge(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void refinementSamplingDropsNonPassingOrHighRiskCandidates() {
        DiverseSamplingOrchestrator sampler = mock(DiverseSamplingOrchestrator.class);
        EnsembleJudgeService judge = mock(EnsembleJudgeService.class);
        EnsembleFinalAnswerService service = new EnsembleFinalAnswerService(sampler, judge);
        ReflectionTestUtils.setField(service, "ensembleEnabled", true);
        PromptContext ctx = PromptContext.builder().userQuery("q").build();
        SampledCandidate warning = new SampledCandidate(
                "warning",
                "candidate answer",
                0.7d,
                0.7d,
                0.9d,
                0.1d,
                FinalSigmoidGate.GateResult.WARN);
        SampledCandidate risky = new SampledCandidate(
                "risky",
                "candidate answer",
                0.7d,
                0.7d,
                0.9d,
                0.9d,
                FinalSigmoidGate.GateResult.PASS);
        when(sampler.refinementEvidenceReady(ctx)).thenReturn(true);
        when(sampler.sampleDualHypotheses(ctx, "session-7")).thenReturn(List.of(warning, risky));

        List<SampledCandidate> candidates = service.sampleCandidatesForRefinement(ctx, 7L);

        assertTrue(candidates.isEmpty());
        assertEquals(2, TraceStore.get("ensemble.refiner.rawCandidateCount"));
        assertEquals(0, TraceStore.get("ensemble.refiner.candidateCount"));
        assertEquals("model_unavailable", TraceStore.get("ensemble.refiner.disabledReason"));
        verify(judge, never()).judge(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void refinementWithoutCitationContextSkipsOutboundSampling() {
        DiverseSamplingOrchestrator sampler = mock(DiverseSamplingOrchestrator.class);
        EnsembleJudgeService judge = mock(EnsembleJudgeService.class);
        EnsembleFinalAnswerService service = new EnsembleFinalAnswerService(sampler, judge);
        ReflectionTestUtils.setField(service, "ensembleEnabled", true);
        PromptContext ctx = PromptContext.builder().userQuery("q").build();
        when(sampler.refinementEvidenceReady(ctx)).thenReturn(false);

        assertTrue(service.sampleCandidatesForRefinement(ctx, 7L).isEmpty());
        assertTrue(service.tryGenerate(ctx, 7L).isEmpty());

        assertEquals("citation_context_unavailable", TraceStore.get("ensemble.refiner.disabledReason"));
        assertEquals("citation_context_unavailable", TraceStore.get("ensemble.bypass.reason"));
        assertEquals(0, TraceStore.get("ensemble.refiner.candidateCount"));
        verify(sampler, never()).sample(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
        verify(sampler, never()).sampleDualHypotheses(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
        verify(judge, never()).judge(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void citationPreflightRejectionCannotReuseAnAttachedTriad() {
        DiverseSamplingOrchestrator sampler = mock(DiverseSamplingOrchestrator.class);
        EnsembleJudgeService judge = mock(EnsembleJudgeService.class);
        EnsembleFinalAnswerService service = new EnsembleFinalAnswerService(sampler, judge);
        ReflectionTestUtils.setField(service, "ensembleEnabled", true);
        PromptContext ctx = PromptContext.builder()
                .userQuery("q")
                .ensembleCandidates(validTriad())
                .build();
        when(sampler.refinementEvidenceReady(ctx)).thenReturn(false);

        assertTrue(service.sampleCandidatesForRefinement(ctx, 7L).isEmpty());
        assertTrue(service.tryGenerate(ctx, 7L).isEmpty());

        assertEquals("citation_preflight", TraceStore.get("ensemble.candidates.source"));
        assertEquals("citation_context_unavailable", TraceStore.get("ensemble.judge.skipped"));
        assertEquals("citation_context_unavailable", TraceStore.get("ensemble.bypass.reason"));
        verify(sampler, never()).sample(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
        verify(sampler, never()).sampleDualHypotheses(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
        verify(judge, never()).judge(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void tryGenerateReusesCompleteSafeAttachedTriadWithoutResampling() {
        DiverseSamplingOrchestrator sampler = mock(DiverseSamplingOrchestrator.class);
        EnsembleJudgeService judge = mock(EnsembleJudgeService.class);
        EnsembleFinalAnswerService service = new EnsembleFinalAnswerService(sampler, judge);
        ReflectionTestUtils.setField(service, "ensembleEnabled", true);
        List<SampledCandidate> attached = validTriad();
        PromptContext ctx = PromptContext.builder()
                .userQuery("q")
                .ensembleCandidates(attached)
                .build();
        TraceStore.putInternal("ensemble.refiner.samplingAttempted", true);
        when(judge.judge(attached, ctx, "session-7")).thenReturn("judge answer");

        assertEquals("judge answer", service.tryGenerate(ctx, 7L).orElseThrow());

        verify(sampler, never()).sample(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
        verify(judge).judge(attached, ctx, "session-7");
        assertEquals("prompt_context", TraceStore.get("ensemble.candidates.source"));
    }

    @Test
    void tryGenerateResamplesWhenAttachedCandidatesAreIncomplete() {
        DiverseSamplingOrchestrator sampler = mock(DiverseSamplingOrchestrator.class);
        EnsembleJudgeService judge = mock(EnsembleJudgeService.class);
        EnsembleFinalAnswerService service = new EnsembleFinalAnswerService(sampler, judge);
        ReflectionTestUtils.setField(service, "ensembleEnabled", true);
        List<SampledCandidate> complete = validTriad();
        PromptContext ctx = PromptContext.builder()
                .userQuery("q")
                .ensembleCandidates(complete.subList(0, 2))
                .build();
        when(sampler.sample(ctx, "session-7")).thenReturn(complete);
        when(judge.judge(complete, ctx, "session-7")).thenReturn("judge answer");

        assertEquals("judge answer", service.tryGenerate(ctx, 7L).orElseThrow());

        verify(sampler).sample(ctx, "session-7");
        verify(judge).judge(complete, ctx, "session-7");
        assertEquals("resampled", TraceStore.get("ensemble.candidates.source"));
        assertEquals("unsafe_or_incomplete", TraceStore.get("ensemble.candidates.attachedRejected"));
    }

    @Test
    void incompleteRefinerAttemptFallsBackWithoutASecondSamplingPass() {
        DiverseSamplingOrchestrator sampler = mock(DiverseSamplingOrchestrator.class);
        EnsembleJudgeService judge = mock(EnsembleJudgeService.class);
        EnsembleFinalAnswerService service = new EnsembleFinalAnswerService(sampler, judge);
        ReflectionTestUtils.setField(service, "ensembleEnabled", true);
        PromptContext seedCtx = PromptContext.builder().userQuery("q").build();
        List<SampledCandidate> sampled = validDualPair(0.95d, 0.85d);
        SampledCandidate risky = sampled.get(1);
        List<SampledCandidate> partiallySafe = List.of(
                sampled.get(0),
                new SampledCandidate(
                        risky.nodeId(), risky.text(), risky.temperature(), risky.topP(),
                        risky.citationScore(), 0.90d, risky.gateResult(),
                        risky.hypothesisDirection(), risky.evidenceStatus(), risky.evidenceRate(),
                        risky.sourceDiversity(), risky.contradictionRate(), risky.groundingScore()));
        when(sampler.refinementEvidenceReady(seedCtx)).thenReturn(true);
        when(sampler.sampleDualHypotheses(seedCtx, "session-7")).thenReturn(partiallySafe);

        List<SampledCandidate> attached = service.sampleCandidatesForRefinement(seedCtx, 7L);
        PromptContext finalCtx = seedCtx.toBuilder().ensembleCandidates(attached).build();

        assertTrue(service.tryGenerate(finalCtx, 7L).isEmpty());
        verify(sampler, times(1)).sampleDualHypotheses(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("session-7"));
        verify(sampler, never()).sample(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString());
        verify(judge, never()).judge(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString());
        assertEquals("refiner_attempted", TraceStore.get("ensemble.candidates.source"));
    }

    @Test
    void nonFiniteAttachedScoresAreNeverReused() {
        DiverseSamplingOrchestrator sampler = mock(DiverseSamplingOrchestrator.class);
        EnsembleJudgeService judge = mock(EnsembleJudgeService.class);
        EnsembleFinalAnswerService service = new EnsembleFinalAnswerService(sampler, judge);
        ReflectionTestUtils.setField(service, "ensembleEnabled", true);
        List<SampledCandidate> triad = validTriad();
        SampledCandidate baseRate = triad.get(1);
        List<SampledCandidate> unsafe = List.of(
                triad.get(0),
                new SampledCandidate(
                        baseRate.nodeId(), baseRate.text(), baseRate.temperature(), baseRate.topP(),
                        Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, baseRate.gateResult()),
                triad.get(2));
        PromptContext ctx = PromptContext.builder().userQuery("q").ensembleCandidates(unsafe).build();
        TraceStore.putInternal("ensemble.refiner.samplingAttempted", true);

        assertTrue(service.tryGenerate(ctx, 7L).isEmpty());

        verify(sampler, never()).sample(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
        verify(judge, never()).judge(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString());
        assertEquals("unsafe_or_incomplete", TraceStore.get("ensemble.candidates.attachedRejected"));
    }

    @Test
    void enabledFeatureSamplesCandidatesAndReturnsJudgeDraft() {
        DiverseSamplingOrchestrator sampler = mock(DiverseSamplingOrchestrator.class);
        EnsembleJudgeService judge = mock(EnsembleJudgeService.class);
        EnsembleFinalAnswerService service = new EnsembleFinalAnswerService(sampler, judge);
        ReflectionTestUtils.setField(service, "ensembleEnabled", true);
        PromptContext ctx = PromptContext.builder().userQuery("q").build();
        SampledCandidate candidate = new SampledCandidate(
                "deterministic",
                "candidate answer",
                0.4d,
                0.4d,
                0.9d,
                0.1d,
                FinalSigmoidGate.GateResult.PASS);
        when(sampler.sample(ctx, "session-7")).thenReturn(List.of(candidate));
        when(judge.judge(List.of(candidate), ctx, "session-7")).thenReturn("judge answer");

        assertEquals("judge answer", service.tryGenerate(ctx, 7L).orElseThrow());
        assertEquals(Boolean.TRUE, TraceStore.get("ensemble.used"));
        assertEquals(12, TraceStore.get("ensemble.resultLen"));
    }

    @Test
    void enabledFeatureReturnsEmptyWhenJudgeFailsClosedSoCallerCanUseSinglePath() {
        DiverseSamplingOrchestrator sampler = mock(DiverseSamplingOrchestrator.class);
        EnsembleJudgeService judge = mock(EnsembleJudgeService.class);
        EnsembleFinalAnswerService service = new EnsembleFinalAnswerService(sampler, judge);
        ReflectionTestUtils.setField(service, "ensembleEnabled", true);
        PromptContext ctx = PromptContext.builder().userQuery("q").build();
        SampledCandidate candidate = new SampledCandidate(
                "explore",
                "candidate answer",
                1.4d,
                0.9d,
                0.9d,
                0.1d,
                FinalSigmoidGate.GateResult.PASS);
        when(sampler.sample(ctx, "session-none")).thenReturn(List.of(candidate));
        when(judge.judge(List.of(candidate), ctx, "session-none")).thenReturn(null);

        assertTrue(service.tryGenerate(ctx, null).isEmpty());
        assertEquals("blank_result", TraceStore.get("ensemble.bypass.reason"));
    }

    @Test
    void enabledFeatureFailureRecordsStableBypassReason() {
        DiverseSamplingOrchestrator sampler = mock(DiverseSamplingOrchestrator.class);
        EnsembleJudgeService judge = mock(EnsembleJudgeService.class);
        EnsembleFinalAnswerService service = new EnsembleFinalAnswerService(sampler, judge);
        ReflectionTestUtils.setField(service, "ensembleEnabled", true);
        PromptContext ctx = PromptContext.builder().userQuery("q").build();
        when(sampler.sample(ctx, "session-7")).thenThrow(new UnsupportedOperationException("sampler down"));

        assertTrue(service.tryGenerate(ctx, 7L).isEmpty());

        assertEquals("ensemble_final_answer_failed", TraceStore.get("ensemble.bypass.reason"));
        assertFalse(String.valueOf(TraceStore.get("ensemble.bypass.reason")).contains("IllegalStateException"));
    }

    @Test
    void enabledFeaturePropagatesCancellationToChatWorkflow() {
        DiverseSamplingOrchestrator sampler = mock(DiverseSamplingOrchestrator.class);
        EnsembleJudgeService judge = mock(EnsembleJudgeService.class);
        EnsembleFinalAnswerService service = new EnsembleFinalAnswerService(sampler, judge);
        ReflectionTestUtils.setField(service, "ensembleEnabled", true);
        PromptContext ctx = PromptContext.builder().userQuery("q").build();
        when(sampler.sample(ctx, "session-7")).thenThrow(new CancellationException("cancelled"));

        assertThrows(CancellationException.class, () -> service.tryGenerate(ctx, 7L));
        assertTrue(TraceStore.get("ensemble.bypass.reason") == null);
    }

    private static List<SampledCandidate> validTriad() {
        return List.of(
                validCandidate("cooperative", "affirmative good-faith assistance"),
                validCandidate("base_rate", "routine process delay"),
                validCandidate("opportunistic", "strategic record shaping"));
    }

    private static GuardContext completeWildCreativeContext() {
        GuardContext context = new GuardContext();
        context.putPlanOverride("creative.emergence.active", true);
        context.putPlanOverride("creative.emergence.profile", "WILD");
        context.putPlanOverride("creative.emergence.search.temperature", 0.94d);
        context.putPlanOverride("creative.emergence.search.rate", 0.80d);
        context.putPlanOverride("creative.emergence.candidate.temperature", 1.36d);
        context.putPlanOverride("creative.emergence.candidate.topP", 0.98d);
        context.putPlanOverride("creative.emergence.final.temperature", 1.36d);
        context.putPlanOverride("creative.emergence.final.topP", 0.98d);
        context.putPlanOverride("creative.emergence.selfAsk.temperature", 0.93d);
        context.putPlanOverride("creative.emergence.requestedOptionsHash",
                SafeRedactor.hashValue("wild-profile"));
        context.putPlanOverride("promptPose.application.intentSlot", "explore");
        return context;
    }

    private static List<SampledCandidate> validDualPair(double supportScore, double falsifyScore) {
        return List.of(
                validDualCandidate(SampledCandidate.HypothesisDirection.SUPPORT, supportScore),
                validDualCandidate(SampledCandidate.HypothesisDirection.FALSIFY, falsifyScore));
    }

    private static List<SampledCandidate> validThreeRoleRefinementSet() {
        return List.of(
                validDualCandidate("support", SampledCandidate.HypothesisDirection.SUPPORT,
                        0.93d, "primary bounded claim"),
                validDualCandidate("support_alternative", SampledCandidate.HypothesisDirection.SUPPORT,
                        0.91d, "independent bounded claim"),
                validDualCandidate("falsify", SampledCandidate.HypothesisDirection.FALSIFY,
                        0.92d, "bounded counter claim"));
    }

    private static SampledCandidate validDualCandidate(
            SampledCandidate.HypothesisDirection direction,
            double groundingScore) {
        return validDualCandidate(
                direction.name().toLowerCase(java.util.Locale.ROOT),
                direction,
                groundingScore,
                "bounded claim");
    }

    private static SampledCandidate validDualCandidate(
            String nodeId,
            SampledCandidate.HypothesisDirection direction,
            double groundingScore,
            String claim) {
        String dossier = """
                DIRECTION: %s
                CLAIM: %s | EVIDENCE: ev1:111111111111 | STATUS: SUPPORTED
                CONCLUSION: untrusted directional hypothesis
                """.formatted(direction.name(), claim);
        return new SampledCandidate(
                nodeId,
                dossier,
                direction == SampledCandidate.HypothesisDirection.SUPPORT ? 0.85d : 0.0d,
                direction == SampledCandidate.HypothesisDirection.SUPPORT ? 0.90d : 0.40d,
                groundingScore,
                0.1d,
                FinalSigmoidGate.GateResult.PASS,
                direction,
                SampledCandidate.EvidenceStatus.SUFFICIENT,
                1.0d,
                Math.max(0.0d, Math.min(1.0d, (groundingScore - 0.8d) / 0.2d)),
                0.0d,
                groundingScore);
    }

    private static SampledCandidate validCandidate(String stance, String hypothesis) {
        String dossier = """
                STATUS: UNCONFIRMED
                STANCE: %s
                MODALITY: POSSIBLE
                OBSERVATIONS: the record is ambiguous
                REPORTED CLAIMS: the accounts differ
                HYPOTHESIS: %s
                SUPPORT: one reported fact
                CONFLICTS: the sources conflict
                MISSING EVIDENCE: contemporaneous records
                FALSIFIER: a verified contrary record
                DISCRIMINATING EVIDENCE: timestamped correspondence
                PROCEDURAL RESPONSE: PRESERVE_RECORDS; REQUEST_WRITTEN_CLARIFICATION
                """.formatted(stance.toUpperCase(), hypothesis);
        return new SampledCandidate(
                stance,
                dossier,
                0.9d,
                0.8d,
                0.85d,
                0.1d,
                FinalSigmoidGate.GateResult.PASS);
    }
}
