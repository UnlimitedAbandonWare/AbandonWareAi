package com.example.lms.trace;

import com.example.lms.search.TraceStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

    private static final System.Logger LOG = System.getLogger(AblationContributionTracker.class.getName());
    private static final String KEY_FINALIZED = "ablation.finalized";
    private static final String KEY_ONCE_PREFIX = "ablation.once.";
    private static final String KEY_PENALTIES = "ablation.penalties";
    private static final String KEY_SCORE_CUR = "ablation.score.current";
    private static final String KEY_SCORE_DELTA_PREV = "ablation.scoreDelta.prev";

    private static final double START_SCORE = 1.0;
    private static final double MIN_TEMP = 0.05;
    private static final double BASE_TEMP = 1.0;
    private static final double BODE_ALPHA = 0.45;
    private static final double BODE_TAU = 0.20;
    private static final String CLAMP_NAME = "bode-tanh-v1";

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
            double rawScoreDelta = clamp(delta, 0.0, 1.0);
            double prevScoreDelta = clamp(asDouble(TraceStore.get(KEY_SCORE_DELTA_PREV), 0.0), 0.0, 1.0);
            double scoreDelta = bodeLikeClamp(prevScoreDelta, rawScoreDelta);
            double after = Math.max(0.0, before - scoreDelta);
            double dropRatio = clamp(scoreDelta / Math.max(before, 1e-9), 0.0, 1.0);

            TraceStore.put(KEY_SCORE_CUR, after);
            TraceStore.put(KEY_SCORE_DELTA_PREV, scoreDelta);

            // Track min score for quick debugging.
            double minScore = after;
            try {
                Object minObj = TraceStore.get("ablation.score.min");
                double min = (minObj instanceof Number n) ? n.doubleValue() : before;
                if (after < min) {
                    TraceStore.put("ablation.score.min", after);
                    minScore = after;
                } else {
                    minScore = min;
                }
            } catch (Throwable ignore) {
                TraceStore.put("ablation.suppressed.minScoreRead", true);
                TraceStore.put("ablation.suppressed.minScoreRead.errorType", "trace_read_failed");
                // best-effort
            }
            double maxDrawdown = updateMaxDrawdown(minScore);

            Map<String, Object> ev = new LinkedHashMap<>();
            ev.put("eventId", TraceStore.nextSequence("ablation.penalty"));
            ev.put("ts", Instant.now().toString());
            ev.put("step", safeLabel(step, 90));
            ev.put("guard", safeLabel(guard, 140));
            ev.put("scoreBefore", before);
            ev.put("scoreAfter", after);
            ev.put("delta", rawScoreDelta);
            ev.put("rawScoreDelta", rawScoreDelta);
            ev.put("scoreDelta", scoreDelta);
            ev.put("dropRatio", dropRatio);
            ev.put("maxDrawdown", maxDrawdown);
            ev.put("expectedDelta", scoreDelta);
            ev.put("clampName", CLAMP_NAME);

            if (note != null && !note.isBlank()) {
                ev.put("note", redactDiagnosticNote(note));
            }

            TraceStore.append(KEY_PENALTIES, ev);
            TraceStore.put("ablation.scoreDelta.latest", new LinkedHashMap<>(ev));
            TraceStore.append("ablation.scoreDelta.events", new LinkedHashMap<>(ev));
            TraceStore.put("ablation.dropRatio.latest", dropRatio);
            TraceStore.put("ablation.expectedDelta.max",
                    Math.max(asDouble(TraceStore.get("ablation.expectedDelta.max"), 0.0), scoreDelta));
            writeScoreSummary(after, minScore, scoreDelta, rawScoreDelta, dropRatio, maxDrawdown,
                    asDouble(TraceStore.get("ablation.expectedDelta.max"), scoreDelta));
        } catch (Throwable ignore) {
            TraceStore.put("ablation.suppressed.recordPenalty", true);
            TraceStore.put("ablation.suppressed.recordPenalty.errorType", "record_penalty_failed");
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
            Object prev = TraceStore.putIfAbsent(KEY_ONCE_PREFIX + safeOnceKey(onceKey), Boolean.TRUE);
            if (prev != null) {
                return;
            }
        } catch (Throwable ignore) {
            TraceStore.put("ablation.suppressed.onceDedupe", true);
            TraceStore.put("ablation.suppressed.onceDedupe.errorType", "dedupe_failed");
            // if dedupe fails, still record
        }
        recordPenalty(step, guard, delta, note);
    }

    private static String safeOnceKey(String onceKey) {
        String hash = SafeRedactor.hash12(onceKey);
        return hash == null ? "empty" : "hash." + hash;
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
            TraceStore.put("ablation.suppressed.finalizationDedupe", true);
            TraceStore.put("ablation.suppressed.finalizationDedupe.errorType", "finalization_dedupe_failed");
            // even if putIfAbsent fails, proceed fail-soft
        }

        try {
            double cur = ensureScoreInitialized();
            TraceStore.put("ablation.score.final", cur);
            double minScore = asDouble(TraceStore.get("ablation.score.min"), cur);
            double maxDrawdown = updateMaxDrawdown(minScore);
            writeScoreSummary(cur, minScore,
                    asDouble(TraceStore.get(KEY_SCORE_DELTA_PREV), 0.0),
                    asDouble(TraceStore.get("ablation.scoreDelta.latest.rawScoreDelta"), 0.0),
                    asDouble(TraceStore.get("ablation.dropRatio.latest"), 0.0),
                    maxDrawdown,
                    asDouble(TraceStore.get("ablation.expectedDelta.max"), 0.0));

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
                        TraceStore.put("ablation.suppressed.finalizationEventCast", true);
                        TraceStore.put("ablation.suppressed.finalizationEventCast.errorType", "event_cast_failed");
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
                double d = eventScoreDelta(e);
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
                ranked.add(new Ranked(i, probs[i], eventScoreDelta(e)));
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
                row.put("step", safeLabel(String.valueOf(e.get("step")), 90));
                row.put("guard", safeLabel(String.valueOf(e.get("guard")), 140));
                double scoreDelta = eventScoreDelta(e);
                double expectedDelta = round4(scoreDelta * r.p());
                e.put("expectedDelta", expectedDelta);
                row.put("delta", eventRawScoreDelta(e));
                row.put("rawScoreDelta", eventRawScoreDelta(e));
                row.put("scoreDelta", round4(scoreDelta));
                row.put("dropRatio", round4(asDouble(e.get("dropRatio"), 0.0)));
                row.put("maxDrawdown", round4(asDouble(e.get("maxDrawdown"), maxDrawdown)));
                row.put("expectedDelta", expectedDelta);
                row.put("clampName", String.valueOf(e.getOrDefault("clampName", CLAMP_NAME)));
                row.put("p", round4(r.p()));
                probOut.add(row);
            }

            TraceStore.put("ablation.probabilities", probOut);
            if (!probOut.isEmpty()) {
                Map<String, Object> top = new LinkedHashMap<>(probOut.get(0));
                TraceStore.put("ablation.scoreDelta.latest", top);
                TraceStore.put("ablation.expectedDelta.max", asDouble(top.get("expectedDelta"), 0.0));
                TraceStore.put("ablation.dropRatio.latest", asDouble(top.get("dropRatio"), 0.0));
                writeScoreSummary(cur, minScore,
                        asDouble(top.get("scoreDelta"), 0.0),
                        asDouble(top.get("rawScoreDelta"), 0.0),
                        asDouble(top.get("dropRatio"), 0.0),
                        maxDrawdown,
                        asDouble(top.get("expectedDelta"), 0.0));
            }
            writeAnchorStacks(evs, probs);


            // Aggregations (probability mass) for quick diagnosis in Trace UI
            TraceStore.put("ablation.events.count", evs.size());
            try {
                Map<String, Double> byGuardMass = new LinkedHashMap<>();
                Map<String, Double> byGuardDelta = new LinkedHashMap<>();
                Map<String, Double> byStepMass = new LinkedHashMap<>();
                Map<String, Double> byStepDelta = new LinkedHashMap<>();

                for (int i = 0; i < probs.length && i < evs.size(); i++) {
                    Map<String, Object> e = evs.get(i);
                    String guard = safeLabel(String.valueOf(e.get("guard")), 64);
                    String step = safeLabel(String.valueOf(e.get("step")), 64);
                    double p = probs[i];
                    double d = eventScoreDelta(e);

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
                    double expected = round4(byGuardDelta.getOrDefault(en.getKey(), 0.0));
                    r.put("scoreDelta", expected);
                    r.put("dropRatio", expected);
                    r.put("maxDrawdown", round4(maxDrawdown));
                    r.put("expectedDelta", expected);
                    byGuard.add(r);
                }
                byGuard.sort((a, b) -> Double.compare(asDouble(b.get("p"), 0.0), asDouble(a.get("p"), 0.0)));

                List<Map<String, Object>> byStep = new ArrayList<>();
                for (Map.Entry<String, Double> en : byStepMass.entrySet()) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("step", en.getKey());
                    r.put("p", round4(en.getValue()));
                    double expected = round4(byStepDelta.getOrDefault(en.getKey(), 0.0));
                    r.put("scoreDelta", expected);
                    r.put("dropRatio", expected);
                    r.put("maxDrawdown", round4(maxDrawdown));
                    r.put("expectedDelta", expected);
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
                logSuppressed("summary.projection");
            }
            int topK = Math.min(5, probOut.size());
            TraceStore.put("ablation.top", probOut.subList(0, topK));

            int sampled = sampleIndex(probs);
            if (sampled >= 0 && sampled < n) {
                Map<String, Object> e = evs.get(sampled);
                TraceStore.put("ablation.sample.eventId", e.get("eventId"));
                TraceStore.put("ablation.sample.step", e.get("step"));
                TraceStore.put("ablation.sample.guard", e.get("guard"));
                TraceStore.put("ablation.sample.delta", eventRawScoreDelta(e));
                TraceStore.put("ablation.sample.scoreDelta", eventScoreDelta(e));
                TraceStore.put("ablation.sample.dropRatio", asDouble(e.get("dropRatio"), 0.0));
                TraceStore.put("ablation.sample.expectedDelta", round4(eventScoreDelta(e) * probs[sampled]));
                TraceStore.put("ablation.sample.p", round4(probs[sampled]));
            }
        } catch (Throwable ignore) {
            TraceStore.put("ablation.suppressed.finalization", true);
            TraceStore.put("ablation.suppressed.finalization.errorType", "finalization_failed");
            // fail-soft
        }
    }

    /* ------------------------ helpers ------------------------ */

    private static double ensureScoreInitialized() {
        Object curObj = null;
        try {
            curObj = TraceStore.get(KEY_SCORE_CUR);
        } catch (Throwable ignore) {
            logSuppressed("score.current.read");
        }
        if (curObj instanceof Number n) {
            return n.doubleValue();
        }
        try {
            TraceStore.put("ablation.score.start", START_SCORE);
            TraceStore.put("ablation.score.min", START_SCORE);
            TraceStore.put(KEY_SCORE_CUR, START_SCORE);
        } catch (Throwable ignore) {
            logSuppressed("score.current.initialize");
        }
        return START_SCORE;
    }

    private static double bodeLikeClamp(double previous, double rawScoreDelta) {
        double prev = clamp(previous, 0.0, 1.0);
        double raw = clamp(rawScoreDelta, 0.0, 1.0);
        double damped = prev + (BODE_ALPHA * BODE_TAU * Math.tanh((raw - prev) / BODE_TAU));
        return round4(clamp(damped, 0.0, 1.0));
    }

    private static double updateMaxDrawdown(double minScore) {
        double drawdown = clamp(1.0 - clamp(minScore, 0.0, 1.0), 0.0, 1.0);
        double prev = asDouble(TraceStore.get("ablation.maxDrawdown"), 0.0);
        double max = round4(Math.max(prev, drawdown));
        TraceStore.put("ablation.maxDrawdown", max);
        return max;
    }

    private static void writeScoreSummary(double current,
                                          double minScore,
                                          double scoreDelta,
                                          double rawScoreDelta,
                                          double dropRatio,
                                          double maxDrawdown,
                                          double expectedDelta) {
        try {
            Map<String, Object> score = new LinkedHashMap<>();
            score.put("start", round4(START_SCORE));
            score.put("current", round4(clamp(current, 0.0, 1.0)));
            score.put("min", round4(clamp(minScore, 0.0, 1.0)));
            score.put("final", round4(clamp(current, 0.0, 1.0)));
            score.put("rawScoreDelta", round4(clamp(rawScoreDelta, 0.0, 1.0)));
            score.put("scoreDelta", round4(clamp(scoreDelta, 0.0, 1.0)));
            score.put("dropRatio", round4(clamp(dropRatio, 0.0, 1.0)));
            score.put("maxDrawdown", round4(clamp(maxDrawdown, 0.0, 1.0)));
            score.put("expectedDelta", round4(clamp(expectedDelta, 0.0, 1.0)));
            score.put("clampName", CLAMP_NAME);
            TraceStore.put("ablation.score", score);
        } catch (Throwable ignore) {
            TraceStore.put("ablation.suppressed.scoreSummary", true);
            TraceStore.put("ablation.suppressed.scoreSummary.errorType", "score_summary_failed");
            // summary is diagnostic only
        }
    }

    private static double eventScoreDelta(Map<String, Object> event) {
        if (event == null) {
            return 0.0;
        }
        return clamp(asDouble(event.get("scoreDelta"), eventRawScoreDelta(event)), 0.0, 1.0);
    }

    private static double eventRawScoreDelta(Map<String, Object> event) {
        if (event == null) {
            return 0.0;
        }
        return clamp(asDouble(event.get("rawScoreDelta"), asDouble(event.get("delta"), 0.0)), 0.0, 1.0);
    }

    private static String redactDiagnosticNote(String note) {
        String redacted = SafeRedactor.redact(safeTrim(note, 260));
        if (redacted == null) {
            return "";
        }
        return redacted.replaceAll("(?i)(ownerToken|authorization|api[-_]?key|client[-_]?secret|secret|token)\\s*[:=]\\s*[^\\s,;]+",
                "$1=<redacted>");
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
            TraceStore.put("ablation.suppressed.sampleIndex", true);
            TraceStore.put("ablation.suppressed.sampleIndex.errorType", "sample_failed");
            return -1;
        }
    }

    private static void writeAnchorStacks(List<Map<String, Object>> evs, double[] probs) {
        if (evs == null || evs.isEmpty() || probs == null || probs.length == 0) {
            return;
        }
        try {
            Map<String, AnchorAgg> primary = new LinkedHashMap<>();
            List<Map<String, Object>> secondary = new ArrayList<>();
            List<Map<String, Object>> traceAnchors = new ArrayList<>();
            String traceIdHash = idHashFromTrace("traceId", "trace.id", "trace");
            String requestIdHash = idHashFromTrace("requestId", "x-request-id");
            String sessionIdHash = idHashFromTrace("sessionId", "sid");
            int n = Math.min(evs.size(), probs.length);
            for (int i = 0; i < n; i++) {
                Map<String, Object> e = evs.get(i);
                String step = safeLabel(String.valueOf(e.get("step")), 90);
                String guard = safeLabel(String.valueOf(e.get("guard")), 140);
                double rawScoreDelta = eventRawScoreDelta(e);
                double delta = eventScoreDelta(e);
                double dropRatio = clamp(asDouble(e.get("dropRatio"), 0.0), 0.0, 1.0);
                double maxDrawdown = clamp(asDouble(e.get("maxDrawdown"), 0.0), 0.0, 1.0);
                double p = clamp(probs[i], 0.0, 1.0);
                double expected = round4(delta * p);
                e.put("expectedDelta", expected);
                String component = componentFor(step, guard);
                String lane = laneFor(step, guard, component);
                String stage = safeLabel(step.isBlank() ? component : step, 64);
                String anchorHash = SafeRedactor.hash12(component + "|" + lane + "|" + stage + "|" + guard);

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("eventId", e.get("eventId"));
                row.put("step", stage);
                row.put("guard", safeLabel(guard, 96));
                row.put("delta", round4(rawScoreDelta));
                row.put("rawScoreDelta", round4(rawScoreDelta));
                row.put("scoreDelta", round4(delta));
                row.put("dropRatio", round4(dropRatio));
                row.put("maxDrawdown", round4(maxDrawdown));
                row.put("p", round4(p));
                row.put("expectedDelta", expected);
                row.put("clampName", String.valueOf(e.getOrDefault("clampName", CLAMP_NAME)));
                row.put("component", component);
                row.put("lane", lane);
                row.put("anchorHash", anchorHash);
                secondary.add(row);

                String routeHint = routeHintFor(component, stage, guard);
                Map<String, Object> traceAnchor = new LinkedHashMap<>();
                traceAnchor.put("eventId", e.get("eventId"));
                traceAnchor.put("traceIdHash", traceIdHash);
                traceAnchor.put("requestIdHash", requestIdHash);
                traceAnchor.put("sessionIdHash", sessionIdHash);
                traceAnchor.put("anchorHash", anchorHash);
                traceAnchor.put("anchorLen", Math.min(128, Math.max(0, guard.length())));
                traceAnchor.put("evidenceDigestHash", SafeRedactor.hash12(
                        component + "|" + lane + "|" + stage + "|" + routeHint + "|" + expected));
                traceAnchor.put("matrixTile", matrixTileFor(component));
                traceAnchor.put("delta", round4(rawScoreDelta));
                traceAnchor.put("rawScoreDelta", round4(rawScoreDelta));
                traceAnchor.put("scoreDelta", round4(delta));
                traceAnchor.put("dropRatio", round4(dropRatio));
                traceAnchor.put("maxDrawdown", round4(maxDrawdown));
                traceAnchor.put("p", round4(p));
                traceAnchor.put("expectedDelta", expected);
                traceAnchor.put("clampName", String.valueOf(e.getOrDefault("clampName", CLAMP_NAME)));
                traceAnchor.put("component", component);
                traceAnchor.put("stage", stage);
                traceAnchor.put("lane", lane);
                traceAnchor.put("routeHint", routeHint);
                traceAnchors.add(traceAnchor);

                String key = component + "|" + lane + "|" + stage;
                primary.computeIfAbsent(key, ignored -> new AnchorAgg(component, stage, lane))
                        .add(p, expected, guard, anchorHash);
            }

            secondary.sort(Comparator
                    .comparingDouble((Map<String, Object> row) -> -asDouble(row.get("expectedDelta"), 0.0))
                    .thenComparing(row -> String.valueOf(row.get("component")))
                    .thenComparing(row -> String.valueOf(row.get("step"))));
            int secondaryLimit = Math.min(12, secondary.size());

            List<Map<String, Object>> primaryRows = new ArrayList<>();
            for (AnchorAgg agg : primary.values()) {
                primaryRows.add(agg.toMap());
            }
            primaryRows.sort(Comparator
                    .comparingDouble((Map<String, Object> row) -> -asDouble(row.get("expectedDelta"), 0.0))
                    .thenComparing(row -> String.valueOf(row.get("component")))
                    .thenComparing(row -> String.valueOf(row.get("stage"))));
            int primaryLimit = Math.min(9, primaryRows.size());

            TraceStore.put("ablation.anchor.version", "anchor-stack-v1");
            TraceStore.put("ablation.anchor.primaryStack", List.copyOf(primaryRows.subList(0, primaryLimit)));
            TraceStore.put("ablation.anchor.secondaryStack", List.copyOf(secondary.subList(0, secondaryLimit)));
            if (!secondary.isEmpty()) {
                TraceStore.put("ablation.anchor.topHash", secondary.get(0).get("anchorHash"));
            }

            traceAnchors.sort(Comparator
                    .comparingDouble((Map<String, Object> row) -> -asDouble(row.get("expectedDelta"), 0.0))
                    .thenComparing(row -> String.valueOf(row.get("component")))
                    .thenComparing(row -> String.valueOf(row.get("stage"))));
            int traceAnchorLimit = Math.min(12, traceAnchors.size());
            List<Map<String, Object>> traceAnchorRows = List.copyOf(traceAnchors.subList(0, traceAnchorLimit));
            TraceStore.put("ablation.traceAnchor.version", "trace-anchor-v1");
            TraceStore.put("ablation.traceAnchor.rows", traceAnchorRows);
            if (!traceAnchorRows.isEmpty()) {
                Map<String, Object> top = traceAnchorRows.get(0);
                TraceStore.put("ablation.traceAnchor.top", top);
                TraceStore.put("ablation.traceAnchor.topHash", top.get("anchorHash"));
                TraceStore.put("ablation.traceAnchor.topRouteHint", top.get("routeHint"));
                TraceStore.put("ablation.traceAnchor.topMatrixTile", top.get("matrixTile"));
                TraceStore.put("ablation.traceAnchor.maxExpectedDelta", maxExpectedDelta(traceAnchorRows));
                TraceStore.put("ablation.traceAnchor.maxP", maxProbability(traceAnchorRows));
                TraceStore.put("ablation.traceAnchor.drop.max", maxExpectedDelta(traceAnchorRows));
                TraceStore.put("ablation.traceAnchor.routeCorrectionNeed", maxExpectedDelta(traceAnchorRows));
            }
        } catch (Throwable ignore) {
            TraceStore.put("ablation.suppressed.traceAnchorProjection", true);
            TraceStore.put("ablation.suppressed.traceAnchorProjection.errorType", "trace_anchor_projection_failed");
            // anchor projection is diagnostic only
        }
    }

    private static String idHashFromTrace(String... keys) {
        if (keys == null) {
            return "";
        }
        for (String key : keys) {
            try {
                Object raw = TraceStore.get(key);
                if (raw == null) {
                    continue;
                }
                String value = String.valueOf(raw).trim();
                if (value.isBlank()) {
                    continue;
                }
                if (value.startsWith("hash:")) {
                    return value;
                }
                return "hash:" + SafeRedactor.hash12(value);
            } catch (Throwable ignore) {
                TraceStore.put("ablation.suppressed.idHashFromTrace", true);
                TraceStore.put("ablation.suppressed.idHashFromTrace.errorType", "trace_id_hash_failed");
                // best-effort correlation only
            }
        }
        return "";
    }

    private static double maxExpectedDelta(List<Map<String, Object>> rows) {
        double max = 0.0;
        if (rows == null) {
            return max;
        }
        for (Map<String, Object> row : rows) {
            max = Math.max(max, asDouble(row.get("expectedDelta"), 0.0));
        }
        return round4(clamp(max, 0.0, 1.0));
    }

    private static double maxProbability(List<Map<String, Object>> rows) {
        double max = 0.0;
        if (rows == null) {
            return max;
        }
        for (Map<String, Object> row : rows) {
            max = Math.max(max, asDouble(row.get("p"), 0.0));
        }
        return round4(clamp(max, 0.0, 1.0));
    }

    private static int matrixTileFor(String component) {
        return switch (safeTrim(component, 32)) {
            case "web" -> 1;
            case "vector" -> 2;
            case "kg" -> 3;
            case "rerank" -> 4;
            case "gate" -> 5;
            case "selfask" -> 6;
            case "qtx", "llm" -> 7;
            case "memory" -> 8;
            default -> 9;
        };
    }

    private static String routeHintFor(String component, String stage, String guard) {
        String c = component == null ? "" : component.toLowerCase(Locale.ROOT);
        String s = ((stage == null ? "" : stage) + " " + (guard == null ? "" : guard))
                .toLowerCase(Locale.ROOT);
        if (s.contains("provider") || s.contains("disabled") || s.contains("timeout")
                || s.contains("rate") || s.contains("missing_future") || s.contains("silent")
                || s.contains("model_required") || s.contains("blank_model")) {
            return "fail_soft_fallback";
        }
        if ("gate".equals(c) || "rerank".equals(c) || s.contains("citation") || s.contains("evidence")
                || s.contains("final") || s.contains("grandas") || s.contains("dpp") || s.contains("drop")) {
            return "brave_mode";
        }
        return "recovery";
    }

    private static String componentFor(String step, String guard) {
        String s = ((step == null ? "" : step) + " " + (guard == null ? "" : guard))
                .toLowerCase(Locale.ROOT);
        if (s.contains("naver") || s.contains("brave") || s.contains("serp") || s.contains("tavily")
                || s.contains("web") || s.contains("search")
                || ((s.contains("provider") || s.contains("disabled") || s.contains("missing_future"))
                && !s.contains("llm") && !s.contains("model"))) {
            return "web";
        }
        if (s.contains("vector") || s.contains("pinecone") || s.contains("embedding")) {
            return "vector";
        }
        if (s.contains("neo4j") || s.contains("kg") || s.contains("graph")) {
            return "kg";
        }
        if (s.contains("citation") || s.contains("evidence") || s.contains("final")
                || s.contains("gate") || s.contains("model_guard")) {
            return "gate";
        }
        if (s.contains("selfask") || s.contains("self-ask")) {
            return "selfask";
        }
        if (s.contains("qtx") || s.contains("query") || s.contains("rewrite")
                || s.contains("transform")) {
            return "qtx";
        }
        if (s.contains("llm") || s.contains("model")) {
            return "llm";
        }
        if (s.contains("memory") || s.contains("history")) {
            return "memory";
        }
        if (s.contains("rerank") || s.contains("dpp") || s.contains("grandas")) {
            return "rerank";
        }
        return "orchestration";
    }

    private static String laneFor(String step, String guard, String component) {
        String s = ((step == null ? "" : step) + " " + (guard == null ? "" : guard))
                .toLowerCase(Locale.ROOT);
        if (containsAxisToken(s, "bq") || s.contains("domain_definition")) {
            return "BQ";
        }
        if (containsAxisToken(s, "er") || s.contains("alias_synonym")) {
            return "ER";
        }
        if (containsAxisToken(s, "rc") || s.contains("relation_hypothesis")) {
            return "RC";
        }
        return safeTrim(String.valueOf(component).toUpperCase(Locale.ROOT), 32);
    }

    private static boolean containsAxisToken(String s, String token) {
        if (s == null || token == null || token.isBlank()) {
            return false;
        }
        return s.matches(".*(^|[^a-z0-9])" + token + "($|[^a-z0-9]).*");
    }

    private static double asDouble(Object v, double dflt) {
        if (v instanceof Number n) return n.doubleValue();
        if (v == null) return dflt;
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException ignore) {
            TraceStore.put("ablation.suppressed.asDouble", true);
            TraceStore.put("ablation.suppressed.asDouble.errorType", "invalid_number");
            return dflt;
        }
    }

    private static double clamp(double v, double lo, double hi) {
        if (!Double.isFinite(v)) return lo;
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }

    private static String safeTrim(String s, int max) {
        if (s == null) return "";
        String x = s.replace('\n', ' ').replace('\r', ' ').trim();
        if (x.length() <= max) return x;
        return x.substring(0, max) + "...";
    }

    private static String safeLabel(String s, int max) {
        String label = SafeRedactor.traceLabel(s);
        return safeTrim(label == null ? "" : label, max);
    }

    private static void logSuppressed(String stage) {
        LOG.log(System.Logger.Level.DEBUG, "[ablation] suppressed stage={0}", safeTrim(stage, 80));
    }

    private static double round4(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0.0;
        return Math.round(v * 10000.0) / 10000.0;
    }

    private record Ranked(int idx, double p, double delta) {}

    private static final class AnchorAgg {
        private final String component;
        private final String stage;
        private final String lane;
        private int eventCount;
        private double p;
        private double expectedDelta;
        private String topGuard = "";
        private String topAnchorHash = "";
        private double topExpectedDelta = -1.0;

        private AnchorAgg(String component, String stage, String lane) {
            this.component = component;
            this.stage = stage;
            this.lane = lane;
        }

        private void add(double eventP, double eventExpectedDelta, String guard, String anchorHash) {
            eventCount++;
            p += eventP;
            expectedDelta += eventExpectedDelta;
            if (eventExpectedDelta > topExpectedDelta) {
                topExpectedDelta = eventExpectedDelta;
                topGuard = safeLabel(guard, 96);
                topAnchorHash = safeTrim(anchorHash, 32);
            }
        }

        private Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("component", component);
            out.put("stage", stage);
            out.put("lane", lane);
            out.put("eventCount", eventCount);
            out.put("p", round4(p));
            out.put("expectedDelta", round4(expectedDelta));
            out.put("topGuard", topGuard);
            out.put("topAnchorHash", topAnchorHash);
            return out;
        }
    }
}
