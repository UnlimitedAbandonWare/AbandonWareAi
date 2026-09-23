package com.example.lms.ensemble;

import com.example.lms.prompt.PromptContext;
import com.example.lms.guard.ConversationFrameV1;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;

@Slf4j
@Service
@RequiredArgsConstructor
public class EnsembleFinalAnswerService {

    private static final long SCORE_PRECISION = 1_000_000L;
    private static final long NO_FORCED_WINNER_GAP_UNITS = 50_000L;
    private static final String REFINER_SAMPLING_ATTEMPTED = "ensemble.refiner.samplingAttempted";
    private static final String REFINER_CITATION_PREFLIGHT_REJECTED =
            "ensemble.refiner.citationPreflightRejected";

    private final DiverseSamplingOrchestrator samplingOrchestrator;
    private final EnsembleJudgeService judgeService;

    @Value("${ensemble.sampling.enabled:false}")
    private boolean ensembleEnabled;

    @Value("${ensemble.sampling.alternative-support.enabled:false}")
    private boolean alternativeSupportEnabled;

    public List<SampledCandidate> sampleCandidatesForRefinement(PromptContext ctx, Long sessionId) {
        return sampleCandidatesForRefinement(ctx, sessionId, null);
    }

    public List<SampledCandidate> sampleCandidatesForRefinement(
            PromptContext ctx,
            Long sessionId,
            Runnable cancellationCheck) {
        ConversationFrameV1 conversationFrame = ctx == null
                ? ConversationFrameV1.off(false)
                : ctx.conversationFrame();
        if (conversationFrame != null && !conversationFrame.allowsOptionalRefinement()) {
            TraceStore.put("ensemble.refiner.disabledReason", "conversation_stance");
            TraceStore.put("ensemble.refiner.candidateCount", 0);
            TraceStore.put("ensemble.refiner.modelCallCount", 0);
            TraceStore.put("ensemble.refiner.decisionAuthority", "primary_model");
            TraceStore.put("ensemble.refiner.mutationAllowed", false);
            return List.of();
        }
        if (!ensembleEnabled) {
            TraceStore.put("ensemble.sampling.skipped", "disabled");
            TraceStore.put("ensemble.refiner.disabledReason", "provider_disabled");
            TraceStore.put("ensemble.refiner.candidateCount", 0);
            return List.of();
        }
        boolean refinementEvidenceReady = samplingOrchestrator.refinementEvidenceReady(ctx);
        TraceStore.putInternal(REFINER_CITATION_PREFLIGHT_REJECTED, !refinementEvidenceReady);
        if (!refinementEvidenceReady) {
            TraceStore.put("ensemble.refiner.candidateCount", 0);
            TraceStore.put("ensemble.refiner.disabledReason", "citation_context_unavailable");
            return List.of();
        }
        TraceStore.putInternal(REFINER_SAMPLING_ATTEMPTED, true);
        String rid = sessionId == null ? "session-none" : "session-" + sessionId;
        boolean creativeTriad = !alternativeSupportEnabled && creativeEmergenceEligible();
        try {
            List<SampledCandidate> candidates;
            if (alternativeSupportEnabled) {
                candidates = cancellationCheck == null
                        ? samplingOrchestrator.sampleThreeRoleHypotheses(ctx, rid)
                        : samplingOrchestrator.sampleThreeRoleHypotheses(ctx, rid, cancellationCheck);
            } else if (creativeTriad) {
                candidates = cancellationCheck == null
                        ? samplingOrchestrator.sample(ctx, rid)
                        : samplingOrchestrator.sample(ctx, rid, cancellationCheck);
            } else {
                candidates = cancellationCheck == null
                        ? samplingOrchestrator.sampleDualHypotheses(ctx, rid)
                        : samplingOrchestrator.sampleDualHypotheses(ctx, rid, cancellationCheck);
            }
            if (candidates == null || candidates.isEmpty()) {
                String samplingSkip = String.valueOf(TraceStore.get("ensemble.sampling.skipped"));
                TraceStore.put("ensemble.refiner.candidateCount", 0);
                TraceStore.put("ensemble.refiner.disabledReason",
                        "primary_answer_reserve".equals(samplingSkip)
                                ? "primary_answer_reserve"
                                : "model_unavailable");
                return List.of();
            }
            List<SampledCandidate> safeCandidates = candidates.stream()
                    .filter(EnsembleFinalAnswerService::isPromptContextRefinementSafe)
                    .toList();
            TraceStore.put("ensemble.refiner.rawCandidateCount", candidates.size());
            TraceStore.put("ensemble.refiner.safeCandidateCount", safeCandidates.size());
            if (safeCandidates.isEmpty()) {
                TraceStore.put("ensemble.refiner.candidateCount", 0);
                TraceStore.put("ensemble.refiner.disabledReason", "model_unavailable");
                return List.of();
            }
            boolean validRefinementSet = creativeTriad
                    ? isReusableAttachedTriad(safeCandidates)
                    : alternativeSupportEnabled
                            ? isReusableAttachedThreeRoleSet(safeCandidates)
                            : isReusableAttachedDualPair(safeCandidates);
            if (!validRefinementSet) {
                TraceStore.put("ensemble.refiner.candidateCount", 0);
                TraceStore.put("ensemble.refiner.disabledReason", "unsafe_or_incomplete_hypothesis_set");
                return List.of();
            }
            if (creativeTriad) {
                TraceStore.put("ensemble.refiner.candidateCount", safeCandidates.size());
                TraceStore.put("ensemble.refiner.selectionDecision", "creative_synthesis");
                TraceStore.put("ensemble.refiner.selectedCandidate", "underdetermined");
                TraceStore.put("ensemble.refiner.decisionAuthority", "primary_model");
                TraceStore.put("ensemble.refiner.mutationAllowed", false);
                TraceStore.put("ensemble.refiner.maxAttempts", 1);
                TraceStore.put("ensemble.refiner.verificationGatePassed", false);
                TraceStore.put("ensemble.refiner.used", true);
                return List.copyOf(safeCandidates);
            }
            if (alternativeSupportEnabled) {
                List<SampledCandidate> orderedSet = orderedThreeRoleSet(safeCandidates);
                TraceStore.put("ensemble.refiner.candidateCount", orderedSet.size());
                TraceStore.put("ensemble.refiner.selectionDecision", "underdetermined");
                TraceStore.put("ensemble.refiner.selectedCandidate", "underdetermined");
                TraceStore.put("ensemble.refiner.decisionAuthority", "primary_model");
                TraceStore.put("ensemble.refiner.mutationAllowed", false);
                TraceStore.put("ensemble.refiner.maxAttempts", 1);
                TraceStore.put("ensemble.refiner.verificationGatePassed", false);
                TraceStore.put("ensemble.refiner.used", true);
                return orderedSet;
            }
            List<SampledCandidate> orderedPair = orderedDualPair(safeCandidates);
            long supportScoreUnits = scoreUnits(orderedPair.get(0).groundingScore());
            long falsifyScoreUnits = scoreUnits(orderedPair.get(1).groundingScore());
            long scoreGapUnits = Math.abs(supportScoreUnits - falsifyScoreUnits);
            double scoreGap = (double) scoreGapUnits / (double) SCORE_PRECISION;
            String selectionDecision = scoreGapUnits < NO_FORCED_WINNER_GAP_UNITS
                    ? "underdetermined"
                    : supportScoreUnits >= falsifyScoreUnits ? "support" : "falsify";
            TraceStore.put("ensemble.refiner.candidateCount", orderedPair.size());
            TraceStore.put("ensemble.refiner.scoreGap", scoreGap);
            TraceStore.put("ensemble.refiner.selectionDecision", selectionDecision);
            TraceStore.put("ensemble.refiner.selectedCandidate", selectionDecision);
            TraceStore.put("ensemble.refiner.decisionAuthority", "probe_only");
            TraceStore.put("ensemble.refiner.mutationAllowed", false);
            TraceStore.put("ensemble.refiner.maxAttempts", 1);
            TraceStore.put("ensemble.refiner.verificationGatePassed", false);
            TraceStore.put("ensemble.refiner.used", true);
            return orderedPair;
        } catch (CancellationException e) {
            throw e;
        } catch (RuntimeException e) {
            TraceStore.put("ensemble.refiner.candidateCount", 0);
            TraceStore.put("ensemble.refiner.disabledReason", "model_unavailable");
            log.warn("[ensemble] context-refiner sampling bypass type={}", "ensemble_refiner_sampling_failed");
            return List.of();
        }
    }

    private static boolean creativeEmergenceEligible() {
        GuardContext context = GuardContextHolder.get();
        return validCompleteCreativeProfile(context);
    }

    private static boolean validCompleteCreativeProfile(GuardContext context) {
        if (context == null || context.isSensitiveTopic()
                || context.planBool("privacy.boundary.enforce", false)
                || !context.planBool("creative.emergence.active", false)
                || !"explore".equals(context.getPlanOverride("promptPose.application.intentSlot"))) {
            return false;
        }
        String requestedHash = String.valueOf(
                context.getPlanOverride("creative.emergence.requestedOptionsHash")).toLowerCase(java.util.Locale.ROOT);
        if (!requestedHash.matches("hash:[0-9a-f]{12}")) {
            return false;
        }
        return switch (String.valueOf(context.getPlanOverride("creative.emergence.profile"))) {
            case "VIVID" -> creativeProfileValuesInRange(context,
                    0.85d, 0.90d, 0.70d, 0.76d,
                    1.10d, 1.25d, 0.95d, 0.97d,
                    1.05d, 1.20d, 0.95d, 0.97d,
                    0.80d, 0.88d);
            case "WILD" -> creativeProfileValuesInRange(context,
                    0.91d, 0.97d, 0.77d, 0.83d,
                    1.26d, 1.45d, 0.97d, 0.99d,
                    1.21d, 1.40d, 0.97d, 0.99d,
                    0.89d, 0.97d);
            case "FERAL" -> creativeProfileValuesInRange(context,
                    0.98d, 1.00d, 0.84d, 0.85d,
                    1.46d, 1.50d, 0.99d, 1.00d,
                    1.41d, 1.50d, 0.99d, 1.00d,
                    0.98d, 1.00d);
            default -> false;
        };
    }

    private static boolean creativeProfileValuesInRange(
            GuardContext context,
            double searchTempMin, double searchTempMax,
            double searchRateMin, double searchRateMax,
            double candidateTempMin, double candidateTempMax,
            double candidateTopPMin, double candidateTopPMax,
            double finalTempMin, double finalTempMax,
            double finalTopPMin, double finalTopPMax,
            double selfAskMin, double selfAskMax) {
        return creativeValueInRange(context, "creative.emergence.search.temperature", searchTempMin, searchTempMax)
                && creativeValueInRange(context, "creative.emergence.search.rate", searchRateMin, searchRateMax)
                && creativeValueInRange(context, "creative.emergence.candidate.temperature",
                        candidateTempMin, candidateTempMax)
                && creativeValueInRange(context, "creative.emergence.candidate.topP",
                        candidateTopPMin, candidateTopPMax)
                && creativeValueInRange(context, "creative.emergence.final.temperature",
                        finalTempMin, finalTempMax)
                && creativeValueInRange(context, "creative.emergence.final.topP",
                        finalTopPMin, finalTopPMax)
                && creativeValueInRange(context, "creative.emergence.selfAsk.temperature",
                        selfAskMin, selfAskMax);
    }

    private static boolean creativeValueInRange(
            GuardContext context, String key, double min, double max) {
        return inRange(context.planDouble(key, Double.NaN), min, max);
    }

    private static boolean inRange(double value, double min, double max) {
        return Double.isFinite(value) && value >= min && value <= max;
    }

    private static boolean isPromptContextRefinementSafe(SampledCandidate candidate) {
        return candidate != null
                && candidate.gateResult() == com.example.lms.guard.FinalSigmoidGate.GateResult.PASS
                && candidate.citationScore() >= 0.70d
                && candidate.citationScore() <= 1.0d
                && candidate.riskScore() <= 0.65d
                && candidate.riskScore() >= 0.0d
                && Double.isFinite(candidate.citationScore())
                && Double.isFinite(candidate.riskScore())
                && candidate.text() != null
                && !candidate.text().isBlank();
    }

    public Optional<String> tryGenerate(PromptContext ctx, Long sessionId) {
        if (!ensembleEnabled) {
            TraceStore.put("ensemble.sampling.skipped", "disabled");
            TraceStore.put("ensemble.bypass.reason", "disabled");
            return Optional.empty();
        }
        if (Boolean.TRUE.equals(TraceStore.get(REFINER_CITATION_PREFLIGHT_REJECTED))) {
            TraceStore.put("ensemble.candidates.source", "citation_preflight");
            TraceStore.put("ensemble.judge.skipped", "citation_context_unavailable");
            TraceStore.put("ensemble.bypass.reason", "citation_context_unavailable");
            return Optional.empty();
        }
        String rid = sessionId == null ? "session-none" : "session-" + sessionId;
        try {
            List<SampledCandidate> attachedCandidates = ctx == null ? List.of() : ctx.ensembleCandidates();
            List<SampledCandidate> candidates;
            boolean refinerAttempted = Boolean.TRUE.equals(TraceStore.get(REFINER_SAMPLING_ATTEMPTED));
            boolean attachedThreeRoleReferences = alternativeSupportEnabled
                    && isReusableAttachedThreeRoleSet(attachedCandidates);
            boolean attachedCreativeReferences = refinerAttempted
                    && "creative_synthesis".equals(TraceStore.get("ensemble.refiner.selectionDecision"))
                    && isReusableAttachedTriad(attachedCandidates);
            if (attachedCreativeReferences
                    || attachedThreeRoleReferences
                    || (refinerAttempted && isReusableAttachedDualPair(attachedCandidates))) {
                TraceStore.put("ensemble.candidates.source", "prompt_context_refiner");
                TraceStore.put("ensemble.judge.skipped", "reference_only");
                TraceStore.put("ensemble.bypass.reason", "reference_only");
                return Optional.empty();
            } else if (refinerAttempted && isReusableAttachedTriad(attachedCandidates)) {
                candidates = List.copyOf(attachedCandidates);
                TraceStore.put("ensemble.candidates.source", "prompt_context");
            } else if (refinerAttempted) {
                if (attachedCandidates != null && !attachedCandidates.isEmpty()) {
                    TraceStore.put("ensemble.candidates.attachedRejected", "unsafe_or_incomplete");
                }
                candidates = List.of();
                TraceStore.put("ensemble.candidates.source", "refiner_attempted");
            } else {
                if (attachedCandidates != null && !attachedCandidates.isEmpty()) {
                    TraceStore.put("ensemble.candidates.attachedRejected", "unsafe_or_incomplete");
                }
                candidates = samplingOrchestrator.sample(ctx, rid);
                TraceStore.put("ensemble.candidates.source", "resampled");
            }
            if (candidates == null || candidates.isEmpty()) {
                TraceStore.put("ensemble.judge.skipped", "no_candidates");
                TraceStore.put("ensemble.bypass.reason", "no_candidates");
                return Optional.empty();
            }
            String result = judgeService.judge(candidates, ctx, rid);
            if (result == null || result.isBlank()) {
                TraceStore.put("ensemble.bypass.reason", "blank_result");
                return Optional.empty();
            }
            TraceStore.put("ensemble.used", true);
            TraceStore.put("ensemble.resultLen", result.length());
            return Optional.of(result);
        } catch (CancellationException e) {
            throw e;
        } catch (RuntimeException e) {
            TraceStore.put("ensemble.bypass.reason", "ensemble_final_answer_failed");
            log.warn("[ensemble] bypass to single-path type={}", "ensemble_final_answer_failed");
            return Optional.empty();
        }
    }

    private static boolean isReusableAttachedTriad(List<SampledCandidate> candidates) {
        return EnsembleEvidenceContract.hasCompleteHypothesisSet(candidates)
                && EnsembleEvidenceContract.hasValidHypothesisDossiers(candidates)
                && EnsembleEvidenceContract.hasDistinctHypothesisDossiers(candidates)
                && candidates.stream().allMatch(EnsembleFinalAnswerService::isPromptContextRefinementSafe);
    }

    private static boolean isReusableAttachedDualPair(List<SampledCandidate> candidates) {
        if (candidates == null || candidates.size() != 2) {
            return false;
        }
        java.util.Set<SampledCandidate.HypothesisDirection> directions = candidates.stream()
                .filter(java.util.Objects::nonNull)
                .map(SampledCandidate::hypothesisDirection)
                .collect(java.util.stream.Collectors.toSet());
        return directions.equals(java.util.Set.of(
                        SampledCandidate.HypothesisDirection.SUPPORT,
                        SampledCandidate.HypothesisDirection.FALSIFY))
                && candidates.stream().allMatch(candidate -> candidate.dualHypothesis()
                        && candidate.evidenceStatus() != SampledCandidate.EvidenceStatus.UNKNOWN
                        && Double.isFinite(candidate.groundingScore())
                        && candidate.groundingScore() >= 0.70d
                        && Math.abs(candidate.citationScore() - candidate.groundingScore()) < 0.000_001d
                        && isPromptContextRefinementSafe(candidate));
    }

    private static boolean isReusableAttachedThreeRoleSet(List<SampledCandidate> candidates) {
        if (candidates == null || candidates.size() != 3) {
            return false;
        }
        java.util.Map<String, SampledCandidate> byNodeId = candidates.stream()
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toMap(
                        SampledCandidate::nodeId,
                        candidate -> candidate,
                        (first, ignored) -> first));
        if (!byNodeId.keySet().equals(java.util.Set.of("support", "support_alternative", "falsify"))) {
            return false;
        }
        return byNodeId.get("support").hypothesisDirection()
                        == SampledCandidate.HypothesisDirection.SUPPORT
                && byNodeId.get("support_alternative").hypothesisDirection()
                        == SampledCandidate.HypothesisDirection.SUPPORT
                && byNodeId.get("falsify").hypothesisDirection()
                        == SampledCandidate.HypothesisDirection.FALSIFY
                && candidates.stream().allMatch(candidate -> candidate.dualHypothesis()
                        && candidate.evidenceStatus() != SampledCandidate.EvidenceStatus.UNKNOWN
                        && Double.isFinite(candidate.groundingScore())
                        && candidate.groundingScore() >= 0.70d
                        && Math.abs(candidate.citationScore() - candidate.groundingScore()) < 0.000_001d
                        && isPromptContextRefinementSafe(candidate));
    }

    private static List<SampledCandidate> orderedDualPair(List<SampledCandidate> candidates) {
        return candidates.stream()
                .sorted(java.util.Comparator.comparingInt(candidate ->
                        candidate.hypothesisDirection() == SampledCandidate.HypothesisDirection.SUPPORT ? 0 : 1))
                .toList();
    }

    private static List<SampledCandidate> orderedThreeRoleSet(List<SampledCandidate> candidates) {
        java.util.Map<String, Integer> order = java.util.Map.of(
                "support", 0,
                "support_alternative", 1,
                "falsify", 2);
        return candidates.stream()
                .sorted(java.util.Comparator.comparingInt(candidate ->
                        order.getOrDefault(candidate.nodeId(), Integer.MAX_VALUE)))
                .toList();
    }

    private static long scoreUnits(double score) {
        return Math.round(score * SCORE_PRECISION);
    }
}
