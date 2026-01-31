package com.example.lms.moe;

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

        Strategy primary;
        if (sR >= sG && sR >= sB) {
            primary = (sG > 0) ? Strategy.RG_ENSEMBLE : Strategy.R_ONLY;
        } else if (sG >= sR && sG >= sB) {
            primary = (sR > 0) ? Strategy.RG_ENSEMBLE : Strategy.G_ONLY;
        } else {
            primary = Strategy.B_ONLY;
        }

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

        return new Decision(primary, fallbacks, primaryReasons, scoreCard);
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
        m.put(Strategy.RGB_ENSEMBLE.name(), (sR + sG + sB) - 300);
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
        R_ONLY,
        G_ONLY,
        B_ONLY,
        RG_ENSEMBLE,
        GB_FALLBACK,
        RB_ENSEMBLE,
        RGB_ENSEMBLE
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
