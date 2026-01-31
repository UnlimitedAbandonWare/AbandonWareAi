package com.example.lms.trace;

import com.example.lms.search.TraceStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * AblationContributionTracker
 *
 * <p>
 * Request-scoped "quality drop" tracker.
 * The goal is not a perfect metric, but a ranked attribution of which
 * step/guard most likely degraded the final output for debugging.
 * </p>
 *
 * <p>
 * It writes into {@link TraceStore}:
 * <ul>
 *   <li>ablation.penalties (list of events)</li>
 *   <li>ablation.temperature</li>
 *   <li>ablation.probabilities (top list)</li>
 *   <li>ablation.top (top-k list)</li>
 *   <li>ablation.sample.* (one sampled contributor)</li>
 *   <li>ablation.score.* (start/min/final)</li>
 * </ul>
 * </p>
 *
 * <p>
 * Fail-soft: never throws.
 * </p>
 */
public final class AblationContributionTracker {

    private static final String KEY_FINALIZED = "ablation.finalized";
    private static final String KEY_ONCE_PREFIX = "ablation.once.";
    private static final String KEY_PENALTIES = "ablation.penalties";
    private static final String KEY_SCORE_CUR = "ablation.score.current";

    private static final double START_SCORE = 1.0;
    private static final double MIN_TEMP = 0.05;
    private static final double BASE_TEMP = 1.0;

    private AblationContributionTracker() {
        // util
    }

    /**
     * Record one penalty event.
     *
     * @param step  step/stage label (ex: "qtx.bypass", "web.await", "faultmask")
     * @param guard guard/reason label (ex: "breaker-open", "missing_future", "chatDown")
     * @param delta score drop in [0..1] (values are clamped)
     * @param note  optional short note (no secrets; redacted best-effort)
     */
    public static void recordPenalty(String step, String guard, double delta, String note) {
        try {
            double before = ensureScoreInitialized();
            double d = clamp(delta, 0.0, 1.0);
            double after = Math.max(0.0, before - d);

            TraceStore.put(KEY_SCORE_CUR, after);

            // Track min score for quick debugging.
            try {
                Object minObj = TraceStore.get("ablation.score.min");
                double min = (minObj instanceof Number n) ? n.doubleValue() : before;
                if (after < min) {
                    TraceStore.put("ablation.score.min", after);
                }
            } catch (Throwable ignore) {
                // best-effort
            }

            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("eventId", TraceStore.nextSequence("ablation.penalty"));
            ev.put("ts", Instant.now().toString());
            ev.put("step", safeTrim(step, 90));
            ev.put("guard", safeTrim(guard, 140));
            ev.put("scoreBefore", before);
            ev.put("scoreAfter", after);
            ev.put("delta", before - after);

            if (note != null && !note.isBlank()) {
                ev.put("note", SafeRedactor.redact(safeTrim(note, 260)));
            }

            TraceStore.append(KEY_PENALTIES, ev);
        } catch (Throwable ignore) {
            // fail-soft
        }
    }

    /**
     * Record a penalty only once per request (dedupe key-based).
     */
    public static void recordPenaltyOnce(String onceKey, String step, String guard, double delta, String note) {
        if (onceKey == null || onceKey.isBlank()) {
            recordPenalty(step, guard, delta, note);
            return;
        }
        try {
            Object prev = TraceStore.putIfAbsent(KEY_ONCE_PREFIX + safeTrim(onceKey, 120), Boolean.TRUE);
            if (prev != null) {
                return;
            }
        } catch (Throwable ignore) {
            // if dedupe fails, still record
        }
        recordPenalty(step, guard, delta, note);
    }

    /**
     * Finalize trace:
     * - export final score
     * - compute temperature (log-annealed by number of penalty events)
     * - compute softmax probabilities over delta values
     * - store top list + one sampled contributor
     */
    @SuppressWarnings("unchecked")
    public static void finalizeTraceIfNeeded() {
        // Idempotent: first caller wins.
        try {
            Object prev = TraceStore.putIfAbsent(KEY_FINALIZED, Boolean.TRUE);
            if (prev != null) {
                return;
            }
        } catch (Throwable ignore) {
            // even if putIfAbsent fails, proceed fail-soft
        }

        try {
            double cur = ensureScoreInitialized();
            TraceStore.put("ablation.score.final", cur);

            Object obj = TraceStore.get(KEY_PENALTIES);
            if (!(obj instanceof List<?> list) || list.isEmpty()) {
                return;
            }

            ArrayList<Map<String, Object>> evs = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    try {
                        evs.add((Map<String, Object>) m);
                    } catch (Throwable ignore) {
                        // ignore broken entry
                    }
                }
            }
            if (evs.isEmpty()) return;

            final int n = evs.size();
            final double temperature = annealTemperatureLog(n);
            TraceStore.put("ablation.temperature", temperature);

            double[] logits = new double[n];
            double maxLogit = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < n; i++) {
                Map<String, Object> e = evs.get(i);
                double d = asDouble(e.get("delta"), 0.0);
                double logit = d / Math.max(MIN_TEMP, temperature);
                logits[i] = logit;
                if (logit > maxLogit) maxLogit = logit;
            }

            double sum = 0.0;
            double[] probs = new double[n];
            for (int i = 0; i < n; i++) {
                double w = Math.exp(logits[i] - maxLogit); // stable softmax
                probs[i] = w;
                sum += w;
            }
            if (sum <= 0.0 || Double.isNaN(sum) || Double.isInfinite(sum)) {
                return;
            }
            for (int i = 0; i < n; i++) {
                probs[i] = probs[i] / sum;
            }

            ArrayList<Ranked> ranked = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Map<String, Object> e = evs.get(i);
                ranked.add(new Ranked(i, probs[i], asDouble(e.get("delta"), 0.0)));
            }
            ranked.sort(Comparator
                    .comparingDouble((Ranked r) -> -r.p())
                    .thenComparingDouble(r -> -r.delta()));

            int maxOut = Math.min(12, ranked.size());
            List<Map<String, Object>> probOut = new ArrayList<>(maxOut);
            for (int i = 0; i < maxOut; i++) {
                Ranked r = ranked.get(i);
                Map<String, Object> e = evs.get(r.idx());
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("eventId", e.get("eventId"));
                row.put("step", safeTrim(String.valueOf(e.get("step")), 90));
                row.put("guard", safeTrim(String.valueOf(e.get("guard")), 140));
                row.put("delta", r.delta());
                row.put("p", round4(r.p()));
                probOut.add(row);
            }

            TraceStore.put("ablation.probabilities", probOut);


            // Aggregations (probability mass) for quick diagnosis in Trace UI
            TraceStore.put("ablation.events.count", evs.size());
            try {
                Map<String, Double> byGuardMass = new LinkedHashMap<>();
                Map<String, Double> byGuardDelta = new LinkedHashMap<>();
                Map<String, Double> byStepMass = new LinkedHashMap<>();
                Map<String, Double> byStepDelta = new LinkedHashMap<>();

                for (int i = 0; i < probs.length && i < evs.size(); i++) {
                    Map<String, Object> e = evs.get(i);
                    String guard = safeTrim(String.valueOf(e.get("guard")), 64);
                    String step = safeTrim(String.valueOf(e.get("step")), 64);
                    double p = probs[i];
                    double d = asDouble(e.get("delta"), 0.0);

                    byGuardMass.merge(guard, p, Double::sum);
                    byGuardDelta.merge(guard, p * d, Double::sum);

                    byStepMass.merge(step, p, Double::sum);
                    byStepDelta.merge(step, p * d, Double::sum);
                }

                List<Map<String, Object>> byGuard = new ArrayList<>();
                for (Map.Entry<String, Double> en : byGuardMass.entrySet()) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("guard", en.getKey());
                    r.put("p", round4(en.getValue()));
                    r.put("expectedDelta", round4(byGuardDelta.getOrDefault(en.getKey(), 0.0)));
                    byGuard.add(r);
                }
                byGuard.sort((a, b) -> Double.compare(asDouble(b.get("p"), 0.0), asDouble(a.get("p"), 0.0)));

                List<Map<String, Object>> byStep = new ArrayList<>();
                for (Map.Entry<String, Double> en : byStepMass.entrySet()) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("step", en.getKey());
                    r.put("p", round4(en.getValue()));
                    r.put("expectedDelta", round4(byStepDelta.getOrDefault(en.getKey(), 0.0)));
                    byStep.add(r);
                }
                byStep.sort((a, b) -> Double.compare(asDouble(b.get("p"), 0.0), asDouble(a.get("p"), 0.0)));

                TraceStore.put("ablation.byGuard", byGuard);
                TraceStore.put("ablation.byStep", byStep);

                Map<String, Object> summary = new LinkedHashMap<>();
                if (!byGuard.isEmpty()) summary.put("topGuard", byGuard.get(0));
                if (!byStep.isEmpty()) summary.put("topStep", byStep.get(0));
                summary.put("score", round4(cur));
                TraceStore.put("ablation.summary", summary);
            } catch (Exception ignore) {
            }
            int topK = Math.min(5, probOut.size());
            TraceStore.put("ablation.top", probOut.subList(0, topK));

            int sampled = sampleIndex(probs);
            if (sampled >= 0 && sampled < n) {
                Map<String, Object> e = evs.get(sampled);
                TraceStore.put("ablation.sample.eventId", e.get("eventId"));
                TraceStore.put("ablation.sample.step", e.get("step"));
                TraceStore.put("ablation.sample.guard", e.get("guard"));
                TraceStore.put("ablation.sample.delta", asDouble(e.get("delta"), 0.0));
                TraceStore.put("ablation.sample.p", round4(probs[sampled]));
            }
        } catch (Throwable ignore) {
            // fail-soft
        }
    }

    /* ------------------------ helpers ------------------------ */

    private static double ensureScoreInitialized() {
        Object curObj = null;
        try {
            curObj = TraceStore.get(KEY_SCORE_CUR);
        } catch (Throwable ignore) {
        }
        if (curObj instanceof Number n) {
            return n.doubleValue();
        }
        try {
            TraceStore.put("ablation.score.start", START_SCORE);
            TraceStore.put("ablation.score.min", START_SCORE);
            TraceStore.put(KEY_SCORE_CUR, START_SCORE);
        } catch (Throwable ignore) {
        }
        return START_SCORE;
    }

    private static double annealTemperatureLog(int n) {
        // Log-scale annealing: temperature decreases as N grows, but slowly.
        double denom = Math.log1p(Math.max(1, n));
        double t = (denom <= 0.0) ? BASE_TEMP : (BASE_TEMP / denom);
        return Math.max(MIN_TEMP, t);
    }

    private static int sampleIndex(double[] probs) {
        try {
            double r = ThreadLocalRandom.current().nextDouble();
            double acc = 0.0;
            for (int i = 0; i < probs.length; i++) {
                acc += probs[i];
                if (r <= acc) return i;
            }
            return probs.length - 1;
        } catch (Throwable ignore) {
            return -1;
        }
    }

    private static double asDouble(Object v, double dflt) {
        if (v instanceof Number n) return n.doubleValue();
        if (v == null) return dflt;
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (Exception ignore) {
            return dflt;
        }
    }

    private static double clamp(double v, double lo, double hi) {
        if (Double.isNaN(v)) return lo;
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    private static String safeTrim(String s, int max) {
        if (s == null) return "";
        String x = s.replace('\n', ' ').replace('\r', ' ').trim();
        if (x.length() <= max) return x;
        return x.substring(0, max) + "…";
    }

    private static double round4(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0.0;
        return Math.round(v * 10000.0) / 10000.0;
    }

    private record Ranked(int idx, double p, double delta) {}
}
