package com.example.lms.artplate;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.artplate.sse.SseScoreCard;
import com.example.lms.artplate.sse.SseSessionState;
import com.example.lms.artplate.sse.StochasticTransformerEvolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.lang.reflect.RecordComponent;
import java.util.Objects;
import java.util.Optional;



/**
 * Deterministic ArtPlate evolver for small, traceable runtime tuning proposals.
 */
@Component
public class ArtPlateEvolver {

    private final ObjectProvider<ArtPlateEvolutionLogRepository> evolutionLogRepositoryProvider;

    public ArtPlateEvolver() {
        this(null);
    }

    @Autowired
    public ArtPlateEvolver(ObjectProvider<ArtPlateEvolutionLogRepository> evolutionLogRepositoryProvider) {
        this.evolutionLogRepositoryProvider = evolutionLogRepositoryProvider;
    }

    public enum PlateFailureBucket { NO_EVIDENCE, LOW_AUTHORITY, TIMEOUT, CONTRADICTION }

    public record ScoreCard(
            double authority,
            double novelty,
            double fusionDiversity,
            double match,
            double latencyPenalty,
            double errorPenalty,
            int samples) {

        public double composite() {
            double score = 0.30d * clamp01(authority)
                    + 0.20d * clamp01(novelty)
                    + 0.20d * clamp01(fusionDiversity)
                    + 0.30d * clamp01(match)
                    - 0.10d * clamp01(latencyPenalty)
                    - 0.20d * clamp01(errorPenalty);
            return clamp01(score);
        }

        public static ScoreCard neutral() {
            return new ScoreCard(0.55d, 0.50d, 0.50d, 0.55d, 0.10d, 0.05d, 0);
        }
    }

    public record RolloutDecision(
            ArtPlateSpec candidate,
            double score,
            int rolloutPercent,
            boolean promote,
            String reason) {
    }

    public record SseProposal(Optional<ArtPlateSpec> candidate, SseSessionState nextState) {
    }

    public Optional<ArtPlateSpec> propose(PlateFailureBucket bucket, ArtPlateSpec base) {
        if (base == null) {
            TraceStore.put("artplate.propose.skipReason", "missing_base");
            return Optional.empty();
        }
        if (hasPromptTemplateMutationSurface(base)) {
            TraceStore.put("artplate.propose.promptTemplateBlocked", true);
            TraceStore.put("artplate.propose.skipReason", "prompt_template_mutation_blocked");
            return Optional.empty();
        }
        TraceStore.put("artplate.propose.promptTemplateBlocked", false);
        PlateFailureBucket effectiveBucket = bucket == null ? PlateFailureBucket.NO_EVIDENCE : bucket;
        int webTopK = base.webTopK();
        int vecTopK = base.vecTopK();
        boolean allowMemory = base.allowMemory();
        boolean kgOn = base.kgOn();
        int webBudget = base.webBudgetMs();
        int vecBudget = base.vecBudgetMs();
        double noveltyFloor = base.noveltyFloor();
        double authorityFloor = base.authorityFloor();
        boolean includeHistory = base.includeHistory();
        boolean includeDraft = base.includeDraft();
        boolean includePrevAnswer = base.includePrevAnswer();
        boolean crossEnc = base.crossEncoderOn();
        int minEvidence = base.minEvidence();
        int minDistinctSources = base.minDistinctSources();

        switch (effectiveBucket) {
            case NO_EVIDENCE -> {
                webTopK = clamp(base.webTopK() + 3, 1, 16);
                vecTopK = clamp(base.vecTopK() + 4, 1, 32);
                webBudget = clamp(base.webBudgetMs() + 350, 300, 5000);
                vecBudget = clamp(base.vecBudgetMs() + 350, 300, 5000);
                noveltyFloor = Math.max(0.20d, base.noveltyFloor());
                authorityFloor = Math.max(0.45d, base.authorityFloor());
                crossEnc = true;
                minEvidence = clamp(base.minEvidence() + 1, 1, 8);
                minDistinctSources = clamp(base.minDistinctSources() + 1, 1, 6);
            }
            case LOW_AUTHORITY -> {
                webTopK = clamp(base.webTopK() + 2, 1, 16);
                vecTopK = clamp(base.vecTopK() + 1, 1, 32);
                webBudget = clamp(base.webBudgetMs() + 250, 300, 5000);
                vecBudget = clamp(base.vecBudgetMs() + 250, 300, 5000);
                authorityFloor = Math.max(0.65d, clamp01(base.authorityFloor() + 0.10d));
                crossEnc = true;
                minEvidence = clamp(base.minEvidence() + 1, 1, 8);
                minDistinctSources = Math.max(2, base.minDistinctSources());
            }
            case TIMEOUT -> {
                webTopK = clamp(base.webTopK() - 3, 1, 16);
                vecTopK = clamp(base.vecTopK() - 1, 1, 32);
                webBudget = clamp(base.webBudgetMs() + 600, 300, 5000);
                vecBudget = clamp(base.vecBudgetMs() + 400, 300, 5000);
                crossEnc = true;
                minEvidence = Math.max(2, base.minEvidence());
                minDistinctSources = Math.max(1, base.minDistinctSources());
            }
            case CONTRADICTION -> {
                webTopK = clamp(base.webTopK() + 1, 1, 16);
                vecTopK = clamp(base.vecTopK() + 2, 1, 32);
                kgOn = true;
                webBudget = clamp(base.webBudgetMs() + 300, 300, 5000);
                vecBudget = clamp(base.vecBudgetMs() + 300, 300, 5000);
                authorityFloor = Math.max(0.65d, clamp01(base.authorityFloor() + 0.08d));
                crossEnc = true;
                minEvidence = clamp(base.minEvidence() + 1, 1, 8);
                minDistinctSources = clamp(base.minDistinctSources() + 2, 1, 6);
                includePrevAnswer = false;
            }
        }

        ArtPlateSpec mutated = new ArtPlateSpec(
            candidateId(base, effectiveBucket), base.intent(),
            webTopK, vecTopK, allowMemory, kgOn,
            webBudget, vecBudget,
            base.domainAllow(), noveltyFloor, authorityFloor,
            includeHistory, includeDraft, includePrevAnswer,
            base.modelCandidates(),
            crossEnc, minEvidence, minDistinctSources,
            base.wAuthority(), base.wNovelty(), base.wFd(), base.wMatch()
        );
        traceProposal(effectiveBucket, base, mutated);
        return Optional.of(mutated);
    }

    public SseProposal proposeWithSse(
            PlateFailureBucket bucket,
            ArtPlateSpec base,
            StochasticTransformerEvolver sseEvolver,
            SseSessionState sseState,
            SseScoreCard sseScoreCard) {
        SseSessionState safeState = sseState == null ? SseSessionState.initial() : sseState;
        if (sseEvolver == null || !sseEvolver.isEnabled()) {
            TraceStore.put("sse.enabled", false);
            TraceStore.put("sse.source", "fallback_to_deterministic");
            return deterministicFallback(bucket, base, safeState);
        }
        if (base == null) {
            TraceStore.put("sse.enabled", true);
            TraceStore.put("sse.source", "sse_null_fallback");
            TraceStore.put("sse.skipReason", "null_base");
            return new SseProposal(Optional.empty(), safeState);
        }
        try {
            StochasticTransformerEvolver.SseResult result = sseEvolver.perturb(base, safeState, sseScoreCard);
            SseSessionState nextState = result.nextState() == null ? safeState : result.nextState();
            ArtPlateSpec mutated = result.mutatedSpec();
            if (mutated == null) {
                TraceStore.put("sse.source", "sse_null_fallback");
                return deterministicFallback(bucket, base, nextState);
            }
            if (mutated == base) {
                TraceStore.put("sse.source", "fallback_to_deterministic");
                return deterministicFallback(bucket, base, nextState);
            }
            if (!sameSurface(base, mutated)) {
                TraceStore.put("sse.guard.accepted", false);
                TraceStore.put("sse.bypassReason", "guard_rejected_candidate");
                TraceStore.put("artplate.propose.skipReason", "guard_rejected_candidate");
                return deterministicFallback(bucket, base, nextState);
            }
            TraceStore.put("sse.source", "sse_block");
            TraceStore.put("sse.guard.accepted", true);
            traceProposal(bucket == null ? PlateFailureBucket.NO_EVIDENCE : bucket, base, mutated);
            return new SseProposal(Optional.of(mutated), nextState);
        } catch (RuntimeException ex) {
            TraceStore.put("sse.source", "sse_exception_fallback");
            TraceStore.put("sse.bypassReason", "proposeWithSse_exception");
            TraceStore.put("sse.bypassErrorHash", SafeRedactor.hashValue(messageOf(ex)));
            return deterministicFallback(bucket, base, safeState);
        }
    }

    private SseProposal deterministicFallback(
            PlateFailureBucket bucket,
            ArtPlateSpec base,
            SseSessionState state) {
        try {
            return new SseProposal(propose(bucket, base), state);
        } catch (RuntimeException ex) {
            TraceStore.put("sse.bypassReason", "deterministic_propose_exception");
            TraceStore.put("sse.bypassErrorHash", SafeRedactor.hashValue(messageOf(ex)));
            TraceStore.put("artplate.propose.skipReason", "deterministic_propose_exception");
            TraceStore.put("artplate.propose.errorHash", SafeRedactor.hashValue(messageOf(ex)));
            return new SseProposal(Optional.ofNullable(base), state);
        }
    }

    public void abTest(ArtPlateSpec candidate) {
        abTest(candidate, ScoreCard.neutral());
    }

    public RolloutDecision abTest(ArtPlateSpec candidate, ScoreCard scoreCard) {
        ScoreCard card = scoreCard == null ? ScoreCard.neutral() : scoreCard;
        if (candidate == null) {
            TraceStore.put("moe.evolver.candidateNull", true);
            RolloutDecision decision = new RolloutDecision(null, 0.0d, 0, false, "candidate_null");
            traceRollout(decision, card);
            traceEvolutionSkipped("candidate_null");
            return decision;
        }
        TraceStore.put("moe.evolver.candidateNull", false);
        double score = card.composite();
        int percent;
        String reason;
        String gateReason = adoptionGateReason(candidate, card);
        if (!gateReason.isBlank()) {
            percent = 0;
            reason = gateReason;
        } else if (score >= 0.80d && card.samples() >= 10) {
            percent = 50;
            reason = "scorecard_promote_50";
        } else if (score >= 0.62d && card.samples() >= 8) {
            percent = 15;
            reason = "scorecard_promote_15";
        } else if (score >= 0.50d) {
            percent = 5;
            reason = "scorecard_canary_5";
        } else {
            percent = 0;
            reason = "scorecard_below_rollout_floor";
        }
        RolloutDecision decision = new RolloutDecision(candidate, score, percent, percent > 0, reason);
        traceRollout(decision, card);
        persistEvolution(decision, card);
        return decision;
    }

    public boolean shouldRouteToCandidate(RolloutDecision decision) {
        String key = decision == null || decision.candidate() == null
                ? "missing"
                : safeLabel(decision.candidate().id(), "unknown");
        return shouldRouteToCandidate(decision, key);
    }

    public boolean shouldRouteToCandidate(RolloutDecision decision, String routeKey) {
        if (decision == null || !decision.promote() || decision.rolloutPercent() <= 0) {
            traceRouting(decision, false, 100, decision == null ? "missing_decision" : "not_promoted");
            return false;
        }
        int percent = Math.max(0, Math.min(100, decision.rolloutPercent()));
        int bucket = Math.floorMod((routeKey == null ? "" : routeKey).hashCode(), 100);
        boolean routed = bucket < percent;
        traceRouting(decision, routed, bucket, routed ? "rollout_bucket_match" : "rollout_bucket_hold");
        return routed;
    }

    private int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private static String candidateId(ArtPlateSpec base, PlateFailureBucket bucket) {
        String baseId = base == null || base.id() == null || base.id().isBlank() ? "AP_UNKNOWN" : base.id();
        PlateFailureBucket safeBucket = bucket == null ? PlateFailureBucket.NO_EVIDENCE : bucket;
        return baseId + "_M_" + safeBucket.name();
    }

    private static void traceProposal(PlateFailureBucket bucket, ArtPlateSpec base, ArtPlateSpec candidate) {
        String bucketName = bucket == null ? PlateFailureBucket.NO_EVIDENCE.name() : bucket.name();
        TraceStore.put("artplate.propose.bucket", bucketName);
        TraceStore.put("artplate.propose.base", safeLabel(base == null ? "" : base.id(), "unknown"));
        TraceStore.put("artplate.propose.candidate", safeLabel(candidate == null ? "" : candidate.id(), "unknown"));
        TraceStore.put("moe.evolver.plateRegistered", candidate != null);
        TraceStore.put("moe.evolverPlateRegistered", candidate != null);
        TraceStore.put("moe.evolver.failureBucket", bucketName);
        TraceStore.put("moe.evolver.skipReason", candidate == null ? "candidate_null" : "");
        TraceStore.put("moe.evolverCandidatePlateId", safeLabel(candidate == null ? "" : candidate.id(), ""));
        if (candidate != null) {
            String safeCandidateId = safeLabel(candidate.id(), "unknown");
            TraceStore.put("moe.evolver.plateId", safeCandidateId);
            TraceStore.put("moe.evolver.candidate.id", safeCandidateId);
        }
        TraceStore.put("artplate.propose.webTopK", candidate == null ? 0 : candidate.webTopK());
        TraceStore.put("artplate.propose.vecTopK", candidate == null ? 0 : candidate.vecTopK());
        TraceStore.put("artplate.propose.webBudgetMs", candidate == null ? 0 : candidate.webBudgetMs());
        TraceStore.put("artplate.propose.vecBudgetMs", candidate == null ? 0 : candidate.vecBudgetMs());
        TraceStore.put("artplate.propose.kgOn", candidate != null && candidate.kgOn());
        TraceStore.put("artplate.propose.crossEncoder", candidate != null && candidate.crossEncoderOn());
        TraceStore.put("artplate.propose.minEvidence", candidate == null ? 0 : candidate.minEvidence());
        TraceStore.put("artplate.propose.minDistinctSources", candidate == null ? 0 : candidate.minDistinctSources());
    }

    private static void traceRollout(RolloutDecision decision, ScoreCard card) {
        ArtPlateSpec candidate = decision == null ? null : decision.candidate();
        String reason = safeLabel(decision == null ? "missing_decision" : decision.reason(), "unknown");
        TraceStore.put("artplate.rollout.candidate", safeLabel(candidate == null ? "" : candidate.id(), ""));
        TraceStore.put("artplate.rollout.score", decision == null ? 0.0d : decision.score());
        TraceStore.put("artplate.rollout.percent", decision == null ? 0 : decision.rolloutPercent());
        TraceStore.put("artplate.rollout.promote", decision != null && decision.promote());
        TraceStore.put("artplate.rollout.reason", reason);
        TraceStore.put("moe.evolver.rolloutPercent", decision == null ? 0 : decision.rolloutPercent());
        TraceStore.put("moe.evolver.promoted", decision != null && decision.promote());
        TraceStore.put("moe.evolver.skipReason", decision != null && decision.promote() ? "" : reason);
        TraceStore.put("moe.evolverCandidatePlateId", safeLabel(candidate == null ? "" : candidate.id(), ""));
        if (candidate != null) {
            TraceStore.put("moe.evolver.candidate.id", safeLabel(candidate.id(), "unknown"));
        }
        TraceStore.put("artplate.scorecard.samples", card == null ? 0 : Math.max(0, card.samples()));
        TraceStore.put("artplate.scorecard.authority", card == null ? 0.0d : clamp01(card.authority()));
        TraceStore.put("artplate.scorecard.match", card == null ? 0.0d : clamp01(card.match()));
        String guardReason = adoptionGateReason(candidate, card);
        TraceStore.put("artplate.rollout.guard.accepted", candidate != null && guardReason.isBlank());
        TraceStore.put("artplate.rollout.guard.reason", safeLabel(guardReason, ""));
    }

    private static void traceRouting(RolloutDecision decision, boolean routed, int bucket, String reason) {
        ArtPlateSpec candidate = decision == null ? null : decision.candidate();
        TraceStore.put("artplate.routing.rolloutPercent", decision == null ? 0 : decision.rolloutPercent());
        TraceStore.put("artplate.routing.bucket", Math.max(0, Math.min(100, bucket)));
        TraceStore.put("artplate.routing.routed", routed);
        TraceStore.put("artplate.routing.reason", safeLabel(reason, "unknown"));
        TraceStore.put("artplate.routing.candidate", safeLabel(candidate == null ? "" : candidate.id(), "unknown"));
    }

    private void persistEvolution(RolloutDecision decision, ScoreCard card) {
        if (evolutionLogRepositoryProvider == null) {
            traceEvolutionSkipped("no_repository");
            return;
        }
        ArtPlateEvolutionLogRepository repository;
        try {
            repository = evolutionLogRepositoryProvider.getIfAvailable();
        } catch (RuntimeException ex) {
            traceSuppressedEvolutionFailure("repositoryProvider", "repository_unavailable", ex);
            return;
        }
        if (repository == null) {
            traceEvolutionSkipped("no_repository");
            return;
        }
        try {
            repository.save(ArtPlateEvolutionLog.from(decision, card));
            TraceStore.put("artplate.evolution.persisted", true);
            TraceStore.put("artplate.evolution.persist.reason", "saved");
        } catch (RuntimeException ex) {
            traceSuppressedEvolutionFailure("repositorySave", "repository_error", ex);
        }
    }

    private static void traceEvolutionSkipped(String reason) {
        TraceStore.put("artplate.evolution.persisted", false);
        TraceStore.put("artplate.evolution.persist.reason", safeLabel(reason, "unknown"));
    }

    private static void traceSuppressedEvolutionFailure(String stage, String reason, RuntimeException ex) {
        TraceStore.put("artplate.evolution.persisted", false);
        TraceStore.put("artplate.evolution.persist.reason", safeLabel(reason, "unknown"));
        TraceStore.put("artplate.evolution.persist.stage", safeLabel(stage, "unknown"));
        TraceStore.put("artplate.evolution.persist.errorHash", SafeRedactor.hashValue(messageOf(ex)));
        TraceStore.put("artplate.evolution.persist.errorLength", messageLength(ex));
    }

    private static boolean hasPromptTemplateMutationSurface(ArtPlateSpec spec) {
        if (spec == null) {
            return false;
        }
        RecordComponent[] components = spec.getClass().getRecordComponents();
        if (components == null) {
            return false;
        }
        for (RecordComponent component : components) {
            if (isPromptTemplateName(component == null ? null : component.getName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPromptTemplateName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String normalized = name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "");
        return "prompttemplate".equals(normalized);
    }

    private static boolean sameSurface(ArtPlateSpec base, ArtPlateSpec mutated) {
        if (base == null || mutated == null) {
            return false;
        }
        return Objects.equals(base.intent(), mutated.intent())
                && Objects.equals(base.domainAllow(), mutated.domainAllow())
                && Objects.equals(base.modelCandidates(), mutated.modelCandidates())
                && base.includeHistory() == mutated.includeHistory()
                && base.includeDraft() == mutated.includeDraft()
                && base.includePrevAnswer() == mutated.includePrevAnswer()
                && within(mutated.webTopK(), 1, 16)
                && within(mutated.vecTopK(), 1, 32)
                && within(mutated.webBudgetMs(), 300, 5000)
                && within(mutated.vecBudgetMs(), 300, 5000);
    }

    private static String adoptionGateReason(ArtPlateSpec candidate, ScoreCard card) {
        if (candidate == null) {
            return "candidate_null";
        }
        ScoreCard safeCard = card == null ? ScoreCard.neutral() : card;
        if (Math.max(0, safeCard.samples()) < Math.max(1, candidate.minEvidence())) {
            return "evidence_gate_failed";
        }
        if (clamp01(safeCard.authority()) < clamp01(candidate.authorityFloor())) {
            return "authority_gate_failed";
        }
        if (clamp01(safeCard.match()) < 0.50d) {
            return "citation_gate_failed";
        }
        return "";
    }

    private static boolean within(int value, int low, int high) {
        return value >= low && value <= high;
    }

    private static String safeLabel(String value, String fallback) {
        return SafeRedactor.traceLabelOrFallback(value, fallback);
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private static String messageOf(Throwable t) {
        return t == null ? null : t.getMessage();
    }

    private static int messageLength(Throwable t) {
        String message = messageOf(t);
        return message == null ? 0 : message.length();
    }
}
