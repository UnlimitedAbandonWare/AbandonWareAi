package ai.abandonware.nova.orch.zero100;

import ai.abandonware.nova.orch.timebudget.TimeBudgetGuard;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.service.guard.GuardContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pure Zero-100 timebox scheduler.
 *
 * <p>This class intentionally has no Spring annotations. It only projects the
 * current session slice into branch/phase hints consumed by existing RAG seams.</p>
 */
public final class Zero100BranchScheduler {

    private static final int CALIBRATE_END_PCT = 15;
    private static final int DEFAULT_CROSS_VERIFY_START_PCT = 55;
    private static final int DEFAULT_CONSENSUS_START_PCT = 80;
    private static final int DEFAULT_QUERY_BURST_MAX = 9;
    private static final double DEFAULT_RISK_PENALTY_LAMBDA = 0.45d;

    private Zero100BranchScheduler() {
    }

    public enum Phase {
        CALIBRATE,
        DIVERGE,
        CROSS_VERIFY,
        CONSENSUS
    }

    public enum Branch {
        STRICT("BQ"),
        RELAXED("ER"),
        EXPLORE("RC");

        private final String lane;

        Branch(String lane) {
            this.lane = lane;
        }

        public String lane() {
            return lane;
        }
    }

    public record Schedule(
            int progressPct,
            Phase phase,
            Branch activeBranch,
            String activeLane,
            Map<String, Double> laneWeights,
            Map<String, Double> callBudgetRatios,
            Map<String, Long> laneTimeboxesMs,
            Map<String, Double> latencyPenalties,
            Map<String, Long> budgetDebitsMs,
            Map<String, Double> routingProbabilities,
            int queryBurstMax,
            boolean crossVerifyRequired,
            boolean consensusEnabled,
            boolean riskConsensusEnabled,
            int minLaneCoverage,
            double riskPenaltyLambda,
            double minRrfWeight,
            double maxRrfWeight,
            boolean forceFallback) {
    }

    public static Schedule schedule(Zero100SessionRegistry.Slice slice, GuardContext ctx) {
        int crossStart = clampInt(planInt(ctx, "search.zero100.crossVerifyStartPct",
                DEFAULT_CROSS_VERIFY_START_PCT), CALIBRATE_END_PCT, 99);
        int consensusStart = clampInt(planInt(ctx, "search.zero100.consensusStartPct",
                DEFAULT_CONSENSUS_START_PCT), crossStart + 1, 100);
        int queryBurstMax = clampInt(planInt(ctx, "search.zero100.queryBurstMax",
                DEFAULT_QUERY_BURST_MAX), 0, 12);

        int progress = progressPct(slice);
        Phase phase = phaseFor(progress, crossStart, consensusStart);
        Branch active = activeBranch(slice);

        Map<String, Double> penalties = latencyPenalties(slice, ctx);
        Map<String, Long> debits = budgetDebitsMs(slice, ctx);
        double routingTemperature = clampDouble(
                planDouble(ctx, "search.zero100.routing.temperature", TimeBudgetGuard.DEFAULT_ROUTING_TEMPERATURE),
                0.15d,
                3.0d);
        Map<String, Double> weights = TimeBudgetGuard.adjustLaneWeights(laneWeights(ctx, phase, active.lane()), penalties);
        Map<String, Double> callRatios = TimeBudgetGuard.adjustCallRatios(callBudgetRatios(ctx, phase, active.lane()), penalties);
        Map<String, Long> timeboxesMs = TimeBudgetGuard.adjustTimeboxes(
                laneTimeboxesMs(slice, timeboxRatios(ctx, phase, active.lane())), debits);
        Map<String, Double> routingProbabilities = TimeBudgetGuard.routingProbabilities(weights, routingTemperature);
        boolean crossVerify = phase == Phase.CROSS_VERIFY || phase == Phase.CONSENSUS;
        boolean consensus = phase == Phase.CONSENSUS;
        boolean forceFallback = forceFallback(slice, ctx);
        boolean riskConsensus = planBool(ctx, "search.zero100.riskConsensus.enabled", true);
        int minLaneCoverage = clampInt(planInt(ctx, "search.zero100.riskConsensus.minLaneCoverage", 2), 1, 3);
        double riskPenaltyLambda = clampDouble(
                planDouble(ctx, "search.zero100.riskConsensus.riskPenaltyLambda", DEFAULT_RISK_PENALTY_LAMBDA),
                0.0d,
                1.0d);
        double minRrfWeight = clampDouble(
                planDouble(ctx, "search.zero100.riskConsensus.minRrfWeight", 0.05d),
                0.01d,
                1.25d);
        double maxRrfWeight = clampDouble(
                planDouble(ctx, "search.zero100.riskConsensus.maxRrfWeight", 1.25d),
                minRrfWeight,
                2.5d);
        return new Schedule(progress, phase, active, active.lane(), weights,
                callRatios, timeboxesMs, penalties, debits, routingProbabilities,
                queryBurstMax, crossVerify, consensus,
                riskConsensus, minLaneCoverage, riskPenaltyLambda, minRrfWeight, maxRrfWeight, forceFallback);
    }

    private static int progressPct(Zero100SessionRegistry.Slice slice) {
        if (slice == null) {
            return 0;
        }
        long total = slice.getDeadlineMs() - slice.getStartedMs();
        if (total <= 0L) {
            return 100;
        }
        long elapsed = Math.max(0L, slice.getNowMs() - slice.getStartedMs());
        return clampInt((int) Math.floor((elapsed * 100.0d) / total), 0, 100);
    }

    private static Phase phaseFor(int progressPct, int crossStartPct, int consensusStartPct) {
        if (progressPct < CALIBRATE_END_PCT) {
            return Phase.CALIBRATE;
        }
        if (progressPct < crossStartPct) {
            return Phase.DIVERGE;
        }
        if (progressPct < consensusStartPct) {
            return Phase.CROSS_VERIFY;
        }
        return Phase.CONSENSUS;
    }

    private static Branch activeBranch(Zero100SessionRegistry.Slice slice) {
        long idx = slice == null ? 0L : Math.max(0L, slice.getSliceIndex());
        int turn = (int) (idx % 3L);
        return switch (turn) {
            case 1 -> Branch.RELAXED;
            case 2 -> Branch.EXPLORE;
            default -> Branch.STRICT;
        };
    }

    private static Map<String, Double> laneWeights(GuardContext ctx, Phase phase, String activeLane) {
        Map<String, Double> out = new LinkedHashMap<>();
        out.put("BQ", clampWeight(planDouble(ctx, "search.zero100.strictWeight", 1.0d)));
        out.put("ER", clampWeight(planDouble(ctx, "search.zero100.relaxedWeight", 1.0d)));
        out.put("RC", clampWeight(planDouble(ctx, "search.zero100.exploreWeight", 1.0d)));

        if (phase == Phase.DIVERGE) {
            out.computeIfPresent(activeLane, (lane, value) -> clampWeight(value * 1.15d));
        } else if (phase == Phase.CROSS_VERIFY) {
            out.replaceAll((lane, value) -> clampWeight(value * (lane.equals(activeLane) ? 1.05d : 0.95d)));
        } else if (phase == Phase.CONSENSUS) {
            out.replaceAll((lane, value) -> clampWeight((value * 0.90d) + 0.10d));
        }
        return Map.copyOf(out);
    }

    private static Map<String, Double> latencyPenalties(Zero100SessionRegistry.Slice slice, GuardContext ctx) {
        Map<String, Double> out = new LinkedHashMap<>();
        Map<String, Double> fromSlice = slice == null ? Map.of() : slice.getLaneLatencyPenalties();
        for (String lane : new String[]{"BQ", "ER", "RC"}) {
            double value = mapDouble(fromSlice, lane, 0.0d);
            value = Math.max(value, planDouble(ctx, "search.zero100.latencyPenalty." + lane, 0.0d));
            if (value > 0.0d) {
                out.put(lane, clampDouble(value, 0.0d, 1.0d));
            }
        }
        return Map.copyOf(out);
    }

    private static Map<String, Long> budgetDebitsMs(Zero100SessionRegistry.Slice slice, GuardContext ctx) {
        Map<String, Long> out = new LinkedHashMap<>();
        Map<String, Long> fromSlice = slice == null ? Map.of() : slice.getLaneBudgetDebitsMs();
        for (String lane : new String[]{"BQ", "ER", "RC"}) {
            long value = mapLong(fromSlice, lane, 0L);
            value = Math.max(value, planLong(ctx, "search.zero100.budgetDebitMs." + lane, 0L));
            if (value > 0L) {
                out.put(lane, value);
            }
        }
        return Map.copyOf(out);
    }

    private static boolean forceFallback(Zero100SessionRegistry.Slice slice, GuardContext ctx) {
        return (slice != null && slice.isForceFallback())
                || planBool(ctx, "search.zero100.timeBudget.forceFallback", false);
    }

    private static Map<String, Double> callBudgetRatios(GuardContext ctx, Phase phase, String activeLane) {
        Map<String, Double> out = new LinkedHashMap<>();
        out.put("BQ", ratio(ctx, "search.zero100.strictCallBudgetRatio", 0.34d));
        out.put("ER", ratio(ctx, "search.zero100.relaxedCallBudgetRatio", 0.33d));
        out.put("RC", ratio(ctx, "search.zero100.exploreCallBudgetRatio", 0.33d));
        if (phase == Phase.DIVERGE) {
            out.computeIfPresent(activeLane, (lane, value) -> value * 1.10d);
        } else if (phase == Phase.CROSS_VERIFY) {
            out.computeIfPresent(activeLane, (lane, value) -> value * 1.05d);
        } else if (phase == Phase.CONSENSUS) {
            out.replaceAll((lane, value) -> (value * 0.85d) + (1.0d / 3.0d * 0.15d));
        }
        return normalizeRatios(out, 0.34d, 0.33d, 0.33d);
    }

    private static Map<String, Long> laneTimeboxesMs(Zero100SessionRegistry.Slice slice, Map<String, Double> timeboxRatios) {
        long webTimebox = slice == null ? 2_500L : Math.max(250L, slice.getWebTimeboxMs());
        Map<String, Double> ratios = timeboxRatios == null || timeboxRatios.isEmpty()
                ? normalizeRatios(null, 0.38d, 0.32d, 0.30d)
                : timeboxRatios;
        Map<String, Long> out = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : ratios.entrySet()) {
            long value = Math.round(webTimebox * entry.getValue());
            out.put(entry.getKey(), clampLong(value, 250L, webTimebox));
        }
        return Map.copyOf(out);
    }

    private static double ratio(GuardContext ctx, String key, double defaultValue) {
        double value = key == null || key.isBlank() ? defaultValue : planDouble(ctx, key, defaultValue);
        if (!Double.isFinite(value) || value < 0.0d) {
            return defaultValue;
        }
        return value;
    }

    private static Map<String, Double> normalizeRatios(Map<String, Double> raw, double bq, double er, double rc) {
        Map<String, Double> safe = new LinkedHashMap<>();
        safe.put("BQ", validRatio(raw == null ? null : raw.get("BQ"), bq));
        safe.put("ER", validRatio(raw == null ? null : raw.get("ER"), er));
        safe.put("RC", validRatio(raw == null ? null : raw.get("RC"), rc));
        double sum = safe.values().stream().mapToDouble(Double::doubleValue).sum();
        if (!Double.isFinite(sum) || sum <= 0.0d) {
            safe.put("BQ", bq);
            safe.put("ER", er);
            safe.put("RC", rc);
            sum = bq + er + rc;
        }
        double denominator = sum <= 0.0d ? 1.0d : sum;
        safe.replaceAll((lane, value) -> Math.round((value / denominator) * 10_000.0d) / 10_000.0d);
        return Map.copyOf(safe);
    }

    private static double validRatio(Double value, double fallback) {
        return value == null || !Double.isFinite(value) || value < 0.0d ? fallback : value;
    }

    private static Map<String, Double> timeboxRatios(GuardContext ctx, Phase phase, String activeLane) {
        Map<String, Double> out = new LinkedHashMap<>();
        out.put("BQ", ratio(ctx, "search.zero100.strictTimeboxRatio", 0.38d));
        out.put("ER", ratio(ctx, "search.zero100.relaxedTimeboxRatio", 0.32d));
        out.put("RC", ratio(ctx, "search.zero100.exploreTimeboxRatio", 0.30d));
        if (phase == Phase.DIVERGE) {
            out.computeIfPresent(activeLane, (lane, value) -> value * 1.10d);
        } else if (phase == Phase.CROSS_VERIFY) {
            out.computeIfPresent(activeLane, (lane, value) -> value * 1.05d);
        } else if (phase == Phase.CONSENSUS) {
            out.replaceAll((lane, value) -> (value * 0.85d) + (1.0d / 3.0d * 0.15d));
        }
        return normalizeRatios(out, 0.38d, 0.32d, 0.30d);
    }

    private static int planInt(GuardContext ctx, String key, int defaultValue) {
        if (ctx == null) {
            return defaultValue;
        }
        try {
            return ctx.planInt(key, defaultValue);
        } catch (Exception e) {
            traceSuppressed("planInt", e);
            return defaultValue;
        }
    }

    private static double planDouble(GuardContext ctx, String key, double defaultValue) {
        if (ctx == null) {
            return defaultValue;
        }
        try {
            return ctx.planDouble(key, defaultValue);
        } catch (Exception e) {
            traceSuppressed("planDouble", e);
            return defaultValue;
        }
    }

    private static boolean planBool(GuardContext ctx, String key, boolean defaultValue) {
        if (ctx == null) {
            return defaultValue;
        }
        try {
            return ctx.planBool(key, defaultValue);
        } catch (Exception e) {
            traceSuppressed("planBool", e);
            return defaultValue;
        }
    }

    private static long planLong(GuardContext ctx, String key, long defaultValue) {
        if (ctx == null) {
            return defaultValue;
        }
        try {
            return ctx.planLong(key, defaultValue);
        } catch (Exception e) {
            traceSuppressed("planLong", e);
            return defaultValue;
        }
    }

    private static void traceSuppressed(String stage, Throwable error) {
        TraceStore.put("zero100.branchScheduler.suppressed", true);
        TraceStore.put("zero100.branchScheduler.suppressed.stage", stage);
        TraceStore.put("zero100.branchScheduler.suppressed.errorClass",
                error == null ? "unknown" : error.getClass().getSimpleName());
        TraceStore.put("zero100.branchScheduler.suppressed.errorType",
                SafeRedactor.traceLabelOrFallback(error == null ? null : error.getClass().getSimpleName(), "unknown"));
    }

    private static double mapDouble(Map<String, Double> map, String key, double defaultValue) {
        if (map == null || key == null) {
            return defaultValue;
        }
        Double value = map.get(key);
        return value == null || !Double.isFinite(value) ? defaultValue : value;
    }

    private static long mapLong(Map<String, Long> map, String key, long defaultValue) {
        if (map == null || key == null) {
            return defaultValue;
        }
        Long value = map.get(key);
        return value == null ? defaultValue : value;
    }

    private static int clampInt(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    private static double clampWeight(double value) {
        if (!Double.isFinite(value)) {
            return 1.0d;
        }
        if (value < 0.05d) {
            return 0.05d;
        }
        if (value > 1.25d) {
            return 1.25d;
        }
        return Math.round(value * 10_000.0d) / 10_000.0d;
    }

    private static double clampDouble(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static long clampLong(long value, long min, long max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }
}
