package com.example.lms.moe;

import com.example.lms.artplate.ArtPlateEvolver;
import com.example.lms.artplate.ArtPlateRegistry;
import com.example.lms.artplate.ArtPlateSpec;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static com.example.lms.moe.RgbLogSignalParser.*;

/**
 * Offline strategy selector.
 *
 * <p>
 * Purpose: pick a conservative combination of experts for TrainingJobRunner.
 * </p>
 */
@Component
public class RgbStrategySelector {
    private static final Logger log = LoggerFactory.getLogger(RgbStrategySelector.class);

    private final ArtPlateEvolver artPlateEvolver;
    private final ArtPlateRegistry artPlateRegistry;
    private volatile Decision lastDecision;

    public RgbStrategySelector() {
        this((ArtPlateEvolver) null, (ArtPlateRegistry) null);
    }

    @Autowired
    public RgbStrategySelector(ObjectProvider<ArtPlateEvolver> artPlateEvolverProvider,
                               ObjectProvider<ArtPlateRegistry> artPlateRegistryProvider) {
        this(
                artPlateEvolverProvider == null ? null : artPlateEvolverProvider.getIfAvailable(),
                artPlateRegistryProvider == null ? null : artPlateRegistryProvider.getIfAvailable());
    }

    RgbStrategySelector(ArtPlateEvolver artPlateEvolver, ArtPlateRegistry artPlateRegistry) {
        this.artPlateEvolver = artPlateEvolver;
        this.artPlateRegistry = artPlateRegistry;
    }

    public Decision getLastDecision() {
        return lastDecision;
    }

    public void publishHarmonyTrace() {
        boolean available = artPlateEvolver != null && artPlateRegistry != null;
        TraceStore.put("moe.evolverPlateRegistered", available);
        TraceStore.put("moe.artplate.evolver.available", available);
        if (!available) {
            traceArtPlateEvolverSkipped("evolver_unavailable");
        }
    }

    public Decision select(RgbLogSignalParser.Features f, RgbResourceProbe.Snapshot rInput) {
        if (f == null)
            f = RgbLogSignalParser.Features.empty();
        // effectively final 처리: 람다에서 안전하게 참조 가능
        final RgbResourceProbe.Snapshot r = (rInput == null)
                ? new RgbResourceProbe.Snapshot(false, false, false, List.of(), null, null, 0L)
                : rInput;

        // base expert scores
        final int baseR = r.redHealthy() ? 100 : -1000;
        final int baseG = r.greenHealthy() ? 85 : -1000;
        final int baseB = r.blueHealthy() ? 55 : -1000;

        int sR = baseR;
        int sG = baseG;
        int sB = baseB;

        List<Reason> reasons = new ArrayList<>();
        List<ScoreEvent> scoreEvents = new ArrayList<>();

        // baseline health reasons (useful in preview/status)
        if (!r.redHealthy())
            reasons.add(new Reason("red_unhealthy", 100, AppliesTo.RED));
        if (!r.greenHealthy())
            reasons.add(new Reason("green_unhealthy", 100, AppliesTo.GREEN));
        if (!r.blueHealthy())
            reasons.add(new Reason("blue_unhealthy", 100, AppliesTo.BLUE));
        if (r.blueCooldownRemainingMs() > 0)
            reasons.add(new Reason("blue_cooldown", 70, AppliesTo.BLUE));

        // --- signals -> score deltas (keep this small + explainable) ---
        if (f.has(SIG_VECTOR_POISON_FILTERED) || f.has(SIG_LOW_EVIDENCE)) {
            sR += 40;
            scoreEvents.add(new ScoreEvent("vector_poison_or_low_evidence", 40, 0, 0));
            reasons.add(new Reason("need_red_refit", 80, AppliesTo.RED));
        }
        if (f.has(SIG_AUX_DOWN_HARD) || f.has(SIG_QT_OPEN)) {
            sG -= 60;
            scoreEvents.add(new ScoreEvent("aux_down_or_qt_open", 0, -60, 0));
            reasons.add(new Reason("aux_unstable", 75, AppliesTo.GREEN));
        }
        if (f.has(SIG_REMOTE_429)) {
            sB -= 250;
            scoreEvents.add(new ScoreEvent("remote_429_or_rate_limit", 0, 0, -250));
            reasons.add(new Reason("rate_limited", 90, AppliesTo.BLUE));
        }
        if (f.has(SIG_AFTER_RETRIES)) {
            // external instability -> prefer local.
            sR += 10;
            sB -= 40;
            scoreEvents.add(new ScoreEvent("after_retries", 10, 0, -40));
            reasons.add(new Reason("external_instability", 60, AppliesTo.GLOBAL));
        }
        if (f.has(SIG_BREAKER_OPEN)) {
            sB -= 150;
            sG -= 10;
            scoreEvents.add(new ScoreEvent("breaker_open", 0, -10, -150));
            reasons.add(new Reason("breaker_open", 85, AppliesTo.GLOBAL));
        }
        if (f.has(SIG_PENDING_ACQUIRE_TIMEOUT) || f.has(SIG_TIMEOUT)) {
            sG -= 20;
            scoreEvents.add(new ScoreEvent("backpressure_or_timeout", 0, -20, 0));
            reasons.add(new Reason("backpressure", 40, AppliesTo.GREEN));
        }
        if (f.has(SIG_INVALID_KEY)) {
            // treat as very bad for BLUE.
            sB -= 400;
            scoreEvents.add(new ScoreEvent("invalid_key", 0, 0, -400));
            reasons.add(new Reason("invalid_key", 100, AppliesTo.BLUE));
        }
        if (f.has(SIG_MODEL_REQUIRED)) {
            // should be eliminated by ModelGuard at startup, but keep fail-soft.
            sR -= 1000;
            sG -= 1000;
            sB -= 1000;
            scoreEvents.add(new ScoreEvent("config_error_model_required", -1000, -1000, -1000));
            reasons.add(new Reason("config_error_model_required", 100, AppliesTo.GLOBAL));
        }

        Map<String, Integer> strategyScores = computeStrategyScores(sR, sG, sB);
        List<Candidate> ranked = buildRankedCandidates(strategyScores, reasons, r, 6);
        ScoreCard scoreCard = new ScoreCard(baseR, baseG, baseB, sR, sG, sB, scoreEvents, strategyScores, ranked);

        Strategy primary = choosePrimary(ranked, r, sR, sG, sB);

        List<Strategy> fallbacks = new ArrayList<>();
        // conservative ordered fallback: RG -> R -> G -> B
        for (Strategy s : Arrays.asList(
                Strategy.RG_ENSEMBLE,
                Strategy.R_ONLY,
                Strategy.G_ONLY,
                Strategy.GB_FALLBACK,
                Strategy.RB_ENSEMBLE,
                Strategy.B_ONLY,
                Strategy.RGB_ENSEMBLE)) {
            if (s != primary && isUsable(s, r)) {
                fallbacks.add(s);
            }
        }

        List<Reason> primaryReasons = ranked.stream()
                .filter(c -> c.strategy() == primary)
                .findFirst()
                .map(Candidate::reasons)
                .orElseGet(() -> topReasonsFor(primary, reasons, r, 6));

        Decision decision = new Decision(primary, fallbacks, primaryReasons, scoreCard);
        lastDecision = decision;
        TraceStore.put("moe.strategy.primary", decision.primaryStrategy().name());
        TraceStore.put("moe.strategy.fallbackCount", decision.fallbackStrategies().size());
        TraceStore.put("moe.strategy.redScore", scoreCard.redScore());
        TraceStore.put("moe.strategy.greenScore", scoreCard.greenScore());
        TraceStore.put("moe.strategy.blueScore", scoreCard.blueScore());
        if (!decision.reasons().isEmpty()) {
            TraceStore.put("moe.strategy.topReason", decision.reasons().get(0).tag());
        }
        traceArtPlateEvolverSuggestion(decision);
        return decision;
    }

    private static Strategy choosePrimary(List<Candidate> ranked, RgbResourceProbe.Snapshot r, int sR, int sG, int sB) {
        if (ranked != null) {
            for (Candidate candidate : ranked) {
                if (candidate != null && candidate.usable()) {
                    return candidate.strategy();
                }
            }
        }
        if (sR >= sG && sR >= sB) {
            return (sG > 0 && r != null && r.greenHealthy()) ? Strategy.RG_ENSEMBLE : Strategy.R_ONLY;
        }
        if (sG >= sR && sG >= sB) {
            return (sR > 0 && r != null && r.redHealthy()) ? Strategy.RG_ENSEMBLE : Strategy.G_ONLY;
        }
        return Strategy.B_ONLY;
    }

    private void traceArtPlateEvolverSuggestion(Decision decision) {
        if (artPlateEvolver == null || artPlateRegistry == null || decision == null || decision.primaryStrategy() == null) {
            TraceStore.put("moe.evolverPlateRegistered", false);
            TraceStore.put("moe.artplate.evolver.available", false);
            traceArtPlateEvolverSkipped("evolver_unavailable");
            return;
        }

        TraceStore.put("moe.evolverPlateRegistered", true);
        String baseId = basePlateIdFor(decision.primaryStrategy());
        ArtPlateSpec base = artPlateRegistry.get(baseId).orElse(null);
        TraceStore.put("moe.artplate.evolver.available", base != null);
        TraceStore.put("moe.artplate.base", SafeRedactor.traceLabelOrFallback(baseId, "unknown"));
        if (base == null) {
            TraceStore.put("moe.artplate.evolver.bucket", "base_plate_missing");
            TraceStore.put("moe.artplate.evolver.suggested", "base_plate_missing");
            return;
        }

        ArtPlateEvolver.PlateFailureBucket bucket = failureBucketFor(decision.reasons());
        TraceStore.put("moe.artplate.evolver.bucket", bucket.name());
        String suggested = artPlateEvolver.propose(bucket, base)
                .map(candidate -> SafeRedactor.traceLabelOrFallback(candidate.id(), "unknown"))
                .orElse("no_candidate");
        TraceStore.put("moe.artplate.evolver.suggested", suggested);
    }

    private static void traceArtPlateEvolverSkipped(String reason) {
        TraceStore.put("moe.artplate.base", reason);
        TraceStore.put("moe.artplate.evolver.bucket", reason);
        TraceStore.put("moe.artplate.evolver.suggested", reason);
    }

    private static String basePlateIdFor(Strategy strategy) {
        return strategy.artPlateAlias();
    }

    private static ArtPlateEvolver.PlateFailureBucket failureBucketFor(List<Reason> reasons) {
        if (reasons == null || reasons.isEmpty()) {
            return ArtPlateEvolver.PlateFailureBucket.NO_EVIDENCE;
        }
        for (Reason reason : reasons) {
            String tag = reason == null || reason.tag() == null ? "" : reason.tag().toLowerCase();
            if (tag.contains("timeout") || tag.contains("backpressure")) {
                return ArtPlateEvolver.PlateFailureBucket.TIMEOUT;
            }
            if (tag.contains("contradiction")) {
                return ArtPlateEvolver.PlateFailureBucket.CONTRADICTION;
            }
            if (tag.contains("low_evidence") || tag.contains("vector_poison") || tag.contains("need_red_refit")) {
                return ArtPlateEvolver.PlateFailureBucket.NO_EVIDENCE;
            }
        }
        return ArtPlateEvolver.PlateFailureBucket.LOW_AUTHORITY;
    }

    private static Map<String, Integer> computeStrategyScores(int sR, int sG, int sB) {
        LinkedHashMap<String, Integer> m = new LinkedHashMap<>();
        // Single
        m.put(Strategy.R_ONLY.name(), sR);
        m.put(Strategy.G_ONLY.name(), sG);
        m.put(Strategy.B_ONLY.name(), sB);
        // Combos (cheap heuristics; decision logic is still expert-first)
        m.put(Strategy.RG_ENSEMBLE.name(), (sR + sG) - 20);
        // BLUE 포함 전략은 "보수적" 운영을 위해 점수상 불리하게(디버그 노출용)
        m.put(Strategy.GB_FALLBACK.name(), (sG + sB) - 150);
        m.put(Strategy.RB_ENSEMBLE.name(), (sR + sB) - 150);
        int rgbPenalty = 70
                + Math.max(0, sR - 100)
                + Math.max(0, 85 - sG)
                + Math.max(0, 55 - sB);
        m.put(Strategy.RGB_ENSEMBLE.name(), (sR + sG + sB) - rgbPenalty);
        return m;
    }

    private static List<Candidate> buildRankedCandidates(Map<String, Integer> strategyScores,
            List<Reason> allReasons,
            RgbResourceProbe.Snapshot r,
            int topKReasons) {
        if (strategyScores == null || strategyScores.isEmpty())
            return List.of();

        List<Candidate> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : strategyScores.entrySet()) {
            Strategy s;
            try {
                s = Strategy.valueOf(e.getKey());
            } catch (Exception ignore) {
                String errorType = traceInvalidStrategyNameSuppressed(ignore);
                if (log.isDebugEnabled()) {
                    log.debug("[AWX][moe.rgb] invalid strategy suppressed errorType={}", errorType);
                }
                continue;
            }

            int score = e.getValue() == null ? Integer.MIN_VALUE : e.getValue();
            boolean usable = isUsable(s, r);

            List<Reason> reasons = topReasonsFor(s, allReasons, r, topKReasons);
            if (!usable) {
                // strategy-level unusable tag for status UIs
                List<Reason> tmp = new ArrayList<>(reasons);
                tmp.add(new Reason("strategy_unusable", 100, AppliesTo.STRATEGY));
                reasons = topN(tmp, topKReasons);
            }
            out.add(new Candidate(s, score, usable, reasons));
        }

        out.sort(Comparator.comparingInt(Candidate::score).reversed());
        return out;
    }

    private static String traceInvalidStrategyNameSuppressed(Exception ignore) {
        String errorType = SafeRedactor.traceLabelOrFallback(ignore.getClass().getSimpleName(), "unknown");
        TraceStore.put("moe.strategy.suppressed.stage", "invalidStrategyName");
        TraceStore.put("moe.strategy.suppressed.errorType", errorType);
        TraceStore.put("moe.strategy.suppressed.invalidStrategyName", true);
        TraceStore.put("moe.strategy.suppressed.invalidStrategyName.errorType", errorType);
        return errorType;
    }

    private static List<Reason> topReasonsFor(Strategy s,
            List<Reason> allReasons,
            RgbResourceProbe.Snapshot r,
            int k) {
        if (allReasons == null || allReasons.isEmpty())
            return List.of();

        boolean usesR = switch (s) {
            case R_ONLY, RG_ENSEMBLE, RB_ENSEMBLE, RGB_ENSEMBLE -> true;
            default -> false;
        };
        boolean usesG = switch (s) {
            case G_ONLY, RG_ENSEMBLE, GB_FALLBACK, RGB_ENSEMBLE -> true;
            default -> false;
        };
        boolean usesB = switch (s) {
            case B_ONLY, GB_FALLBACK, RB_ENSEMBLE, RGB_ENSEMBLE -> true;
            default -> false;
        };

        List<Reason> filtered = new ArrayList<>();
        for (Reason reason : allReasons) {
            if (reason == null)
                continue;
            if (reason.appliesTo() == AppliesTo.GLOBAL) {
                filtered.add(reason);
                continue;
            }
            if (reason.appliesTo() == AppliesTo.RED && usesR)
                filtered.add(reason);
            if (reason.appliesTo() == AppliesTo.GREEN && usesG)
                filtered.add(reason);
            if (reason.appliesTo() == AppliesTo.BLUE && usesB)
                filtered.add(reason);
        }
        return topN(filtered, k);
    }

    private static List<Reason> topN(List<Reason> reasons, int k) {
        if (reasons == null || reasons.isEmpty())
            return List.of();
        int lim = Math.max(0, k);
        // de-duplicate by tag while preserving priority ordering
        List<Reason> sorted = new ArrayList<>(reasons);
        sorted.sort(Comparator.comparingInt(Reason::priority).reversed());
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<Reason> out = new ArrayList<>();
        for (Reason r : sorted) {
            if (r == null)
                continue;
            String t = r.tag();
            if (t != null && !seen.add(t))
                continue;
            out.add(r);
            if (out.size() >= lim)
                break;
        }
        return out;
    }

    private static boolean isUsable(Strategy s, RgbResourceProbe.Snapshot r) {
        if (r == null)
            return false;
        return switch (s) {
            case R_ONLY -> r.redHealthy();
            case G_ONLY -> r.greenHealthy();
            case B_ONLY -> r.blueHealthy();
            case RG_ENSEMBLE -> r.redHealthy() && r.greenHealthy();
            case GB_FALLBACK -> r.greenHealthy() && r.blueHealthy();
            case RB_ENSEMBLE -> r.redHealthy() && r.blueHealthy();
            case RGB_ENSEMBLE -> r.redHealthy() && r.greenHealthy() && r.blueHealthy();
        };
    }

    public enum Strategy {
        R_ONLY("AP3_VEC_DENSE"),
        G_ONLY("AP9_COST_SAVER"),
        B_ONLY("AP1_AUTH_WEB"),
        RG_ENSEMBLE("AP3_VEC_DENSE"),
        GB_FALLBACK("AP1_AUTH_WEB"),
        RB_ENSEMBLE("AP1_AUTH_WEB"),
        RGB_ENSEMBLE("AP1_AUTH_WEB");

        private final String artPlateAlias;

        Strategy(String artPlateAlias) {
            this.artPlateAlias = artPlateAlias;
        }

        public String artPlateAlias() {
            return artPlateAlias;
        }
    }

    /** Explains score adjustments applied during selection (debug/ops). */
    public record ScoreEvent(String rule, int redDelta, int greenDelta, int blueDelta) {
    }

    /** Structured reason tag for selection (status/preview friendly). */
    public record Reason(String tag, int priority, AppliesTo appliesTo) {
    }

    /** Where a reason applies (expert, strategy or global). */
    public enum AppliesTo {
        GLOBAL,
        RED,
        GREEN,
        BLUE,
        STRATEGY
    }

    /** Ranked strategy candidate for UI/debugging. */
    public record Candidate(Strategy strategy, int score, boolean usable, List<Reason> reasons) {
    }

    /** Scorecard is returned for debugging endpoints (status/preview). */
    public record ScoreCard(
            int redBase,
            int greenBase,
            int blueBase,
            int redScore,
            int greenScore,
            int blueScore,
            List<ScoreEvent> scoreEvents,
            Map<String, Integer> strategyScores,
            List<Candidate> rankedCandidates) {
    }

    public record Decision(
            Strategy primaryStrategy,
            List<Strategy> fallbackStrategies,
            List<Reason> reasons,
            ScoreCard scoreCard) {
    }
}
