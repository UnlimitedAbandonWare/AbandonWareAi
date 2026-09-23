package ai.abandonware.nova.orch.timebudget;

import com.example.lms.search.TraceStore;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Low-cardinality budget math for Zero-100 routing and expensive rerank gates.
 */
public final class TimeBudgetGuard {

    public static final double DEFAULT_ROUTING_TEMPERATURE = 0.65d;
    public static final double DEFAULT_FORCE_FALLBACK_PENALTY = 0.65d;
    public static final double DEFAULT_FORCE_FALLBACK_OVERRUN = 0.45d;
    private static final long MIN_EXPENSIVE_STAGE_REMAINING_MS = 50L;

    private TimeBudgetGuard() {
    }

    public static Decision evaluate(
            String lane,
            long elapsedMs,
            long expectedMs,
            long budgetMs,
            double qualityScore,
            Signals signals) {
        String safeLane = normalizeLane(lane);
        long safeElapsed = Math.max(0L, elapsedMs);
        long safeExpected = Math.max(1L, expectedMs);
        long safeBudget = Math.max(1L, budgetMs);
        Signals safeSignals = signals == null ? Signals.builder().build() : signals;

        double overrunLogit = round4((safeElapsed - safeExpected) / (double) safeBudget);
        double overrunPressure = Math.max(0.0d, overrunLogit);
        double qualityPenalty = Math.max(0.0d, 1.0d - clamp01(qualityScore));
        double signalPenalty = Math.min(0.35d,
                (0.12d * Math.max(0, safeSignals.timeoutCount()))
                        + (0.10d * Math.max(0, safeSignals.rateLimitCount()))
                        + (safeSignals.qtxSoftCooldownActive() ? 0.10d : 0.0d)
                        + (safeSignals.providerCoolingDown() ? 0.08d : 0.0d)
                        + (safeSignals.breakerOpen() ? 0.10d : 0.0d));
        double remainingPenalty = safeSignals.remainingMs() > 0L
                && safeSignals.remainingMs() < MIN_EXPENSIVE_STAGE_REMAINING_MS ? 0.12d : 0.0d;
        double latencyPenalty = clamp01((0.80d * overrunPressure)
                + (0.25d * qualityPenalty)
                + signalPenalty
                + remainingPenalty);

        long overrunMs = Math.max(0L, safeElapsed - safeExpected);
        long budgetDebitMs = overrunMs <= 0L
                ? 0L
                : Math.min(safeBudget, Math.round(overrunMs * (0.50d + latencyPenalty)));
        double routeMultiplier = round4(Math.exp(-latencyPenalty / DEFAULT_ROUTING_TEMPERATURE));
        boolean forceFallback = latencyPenalty >= DEFAULT_FORCE_FALLBACK_PENALTY
                || overrunLogit >= DEFAULT_FORCE_FALLBACK_OVERRUN
                || remainingPenalty > 0.0d;
        String reason = forceFallback
                ? "time_budget_force_fallback"
                : latencyPenalty > 0.0d ? "time_budget_penalty" : "time_budget_ok";

        Decision decision = new Decision(
                safeLane,
                safeElapsed,
                safeExpected,
                safeBudget,
                round4(clamp01(qualityScore)),
                overrunLogit,
                round4(latencyPenalty),
                budgetDebitMs,
                routeMultiplier,
                forceFallback,
                reason);
        traceEvaluation(decision, safeSignals);
        return decision;
    }

    public static Map<String, Double> adjustLaneWeights(
            Map<String, Double> laneWeights,
            Map<String, Double> latencyPenalties) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (String lane : lanes()) {
            double base = value(laneWeights, lane, 1.0d);
            double penalty = clamp01(value(latencyPenalties, lane, 0.0d));
            out.put(lane, round4(clamp(base * Math.exp(-penalty / DEFAULT_ROUTING_TEMPERATURE), 0.05d, 1.25d)));
        }
        return Map.copyOf(out);
    }

    public static Map<String, Double> adjustCallRatios(
            Map<String, Double> callRatios,
            Map<String, Double> latencyPenalties) {
        Map<String, Double> raw = new LinkedHashMap<>();
        for (String lane : lanes()) {
            double base = value(callRatios, lane, 1.0d / 3.0d);
            double penalty = clamp01(value(latencyPenalties, lane, 0.0d));
            raw.put(lane, Math.max(0.0001d, base * (1.0d - Math.min(0.75d, penalty))));
        }
        return normalize(raw);
    }

    public static Map<String, Long> adjustTimeboxes(
            Map<String, Long> timeboxesMs,
            Map<String, Long> budgetDebitsMs) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (String lane : lanes()) {
            long base = longValue(timeboxesMs, lane, 250L);
            long debit = Math.max(0L, longValue(budgetDebitsMs, lane, 0L));
            out.put(lane, Math.max(100L, base - debit));
        }
        return Map.copyOf(out);
    }

    public static Map<String, Double> routingProbabilities(Map<String, Double> laneWeights, double temperature) {
        double temp = clamp(temperature, 0.15d, 3.0d);
        Map<String, Double> logits = new LinkedHashMap<>();
        double max = Double.NEGATIVE_INFINITY;
        for (String lane : lanes()) {
            double weight = Math.max(0.0001d, value(laneWeights, lane, 1.0d));
            double logit = Math.log(weight) / temp;
            logits.put(lane, logit);
            max = Math.max(max, logit);
        }
        Map<String, Double> exp = new LinkedHashMap<>();
        double sum = 0.0d;
        for (String lane : lanes()) {
            double value = Math.exp(logits.get(lane) - max);
            exp.put(lane, value);
            sum += value;
        }
        if (!Double.isFinite(sum) || sum <= 0.0d) {
            return normalize(Map.of("BQ", 1.0d, "ER", 1.0d, "RC", 1.0d));
        }
        Map<String, Double> out = new LinkedHashMap<>();
        for (String lane : lanes()) {
            out.put(lane, round4(exp.get(lane) / sum));
        }
        return Map.copyOf(out);
    }

    public static String normalizeLane(String lane) {
        String value = lane == null ? "" : lane.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "BQ", "STRICT" -> "BQ";
            case "ER", "RELAXED" -> "ER";
            case "RC", "EXPLORE" -> "RC";
            default -> "BQ";
        };
    }

    private static String[] lanes() {
        return new String[]{"BQ", "ER", "RC"};
    }

    private static Map<String, Double> normalize(Map<String, Double> raw) {
        Map<String, Double> out = new LinkedHashMap<>();
        double sum = 0.0d;
        for (String lane : lanes()) {
            double value = Math.max(0.0d, value(raw, lane, 0.0d));
            out.put(lane, value);
            sum += value;
        }
        if (!Double.isFinite(sum) || sum <= 0.0d) {
            out.put("BQ", 0.34d);
            out.put("ER", 0.33d);
            out.put("RC", 0.33d);
            return Map.copyOf(out);
        }
        double denominator = sum;
        out.replaceAll((lane, value) -> round4(value / denominator));
        return Map.copyOf(out);
    }

    private static double value(Map<String, Double> map, String lane, double fallback) {
        if (map == null || map.isEmpty()) {
            return fallback;
        }
        Double value = map.get(normalizeLane(lane));
        return value == null || !Double.isFinite(value) ? fallback : value;
    }

    private static long longValue(Map<String, Long> map, String lane, long fallback) {
        if (map == null || map.isEmpty()) {
            return fallback;
        }
        Long value = map.get(normalizeLane(lane));
        return value == null ? fallback : value;
    }

    private static double clamp01(double value) {
        return clamp(value, 0.0d, 1.0d);
    }

    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static double round4(double value) {
        if (!Double.isFinite(value)) {
            return 0.0d;
        }
        return Math.round(value * 10_000.0d) / 10_000.0d;
    }

    private static void traceEvaluation(Decision decision, Signals signals) {
        TraceStore.put("timeBudget.lane", decision.lane());
        TraceStore.put("timeBudget.forceFallback", decision.forceFallback());
        TraceStore.put("timeBudget.latencyPenalty", decision.latencyPenalty());
        TraceStore.put("timeBudget.routeMultiplier", decision.routeMultiplier());
        TraceStore.put("timeBudget.budgetMs", decision.budgetMs());
        TraceStore.put("timeBudget.elapsedMs", decision.elapsedMs());
        TraceStore.put("timeBudget.reason", decision.reason());
        TraceStore.put("zero100.timeBudget.guard.lane", decision.lane());
        TraceStore.put("zero100.timeBudget.guard.elapsedMs", decision.elapsedMs());
        TraceStore.put("zero100.timeBudget.guard.expectedMs", decision.expectedMs());
        TraceStore.put("zero100.timeBudget.guard.budgetMs", decision.budgetMs());
        TraceStore.put("zero100.timeBudget.guard.remainingMs", signals.remainingMs());
        TraceStore.put("zero100.timeBudget.guard.timeoutCount", signals.timeoutCount());
        TraceStore.put("zero100.timeBudget.guard.rateLimitCount", signals.rateLimitCount());
        TraceStore.put("zero100.timeBudget.guard.qtxSoftCooldownActive", signals.qtxSoftCooldownActive());
        TraceStore.put("zero100.timeBudget.guard.providerCoolingDown", signals.providerCoolingDown());
        TraceStore.put("zero100.timeBudget.guard.breakerOpen", signals.breakerOpen());
        TraceStore.put("zero100.timeBudget.guard.qualityScore", decision.qualityScore());
        TraceStore.put("zero100.timeBudget.guard.overrunLogit", decision.overrunLogit());
        TraceStore.put("zero100.timeBudget.guard.latencyPenalty", decision.latencyPenalty());
        TraceStore.put("zero100.timeBudget.guard.budgetDebitMs", decision.budgetDebitMs());
        TraceStore.put("zero100.timeBudget.guard.routeMultiplier", decision.routeMultiplier());
        TraceStore.put("zero100.timeBudget.guard.forceFallback", decision.forceFallback());
        TraceStore.put("zero100.timeBudget.guard.reason", decision.reason());
    }

    public record Decision(
            String lane,
            long elapsedMs,
            long expectedMs,
            long budgetMs,
            double qualityScore,
            double overrunLogit,
            double latencyPenalty,
            long budgetDebitMs,
            double routeMultiplier,
            boolean forceFallback,
            String reason) {
    }

    public record Signals(
            int timeoutCount,
            int rateLimitCount,
            boolean qtxSoftCooldownActive,
            long qtxSoftCooldownRemainingMs,
            long remainingMs,
            boolean providerCoolingDown,
            boolean breakerOpen) {
        public static Builder builder() {
            return new Builder();
        }

        public static final class Builder {
            private int timeoutCount;
            private int rateLimitCount;
            private boolean qtxSoftCooldownActive;
            private long qtxSoftCooldownRemainingMs;
            private long remainingMs;
            private boolean providerCoolingDown;
            private boolean breakerOpen;

            public Builder timeoutCount(int timeoutCount) {
                this.timeoutCount = Math.max(0, timeoutCount);
                return this;
            }

            public Builder rateLimitCount(int rateLimitCount) {
                this.rateLimitCount = Math.max(0, rateLimitCount);
                return this;
            }

            public Builder qtxSoftCooldownActive(boolean qtxSoftCooldownActive) {
                this.qtxSoftCooldownActive = qtxSoftCooldownActive;
                return this;
            }

            public Builder qtxSoftCooldownRemainingMs(long qtxSoftCooldownRemainingMs) {
                this.qtxSoftCooldownRemainingMs = Math.max(0L, qtxSoftCooldownRemainingMs);
                return this;
            }

            public Builder remainingMs(long remainingMs) {
                this.remainingMs = Math.max(0L, remainingMs);
                return this;
            }

            public Builder providerCoolingDown(boolean providerCoolingDown) {
                this.providerCoolingDown = providerCoolingDown;
                return this;
            }

            public Builder breakerOpen(boolean breakerOpen) {
                this.breakerOpen = breakerOpen;
                return this;
            }

            public Signals build() {
                return new Signals(timeoutCount, rateLimitCount, qtxSoftCooldownActive,
                        qtxSoftCooldownRemainingMs, remainingMs, providerCoolingDown, breakerOpen);
            }
        }
    }
}
