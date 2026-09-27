package com.example.lms.plugin.image.debug;

import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

@Service
@RequiredArgsConstructor
public class ImageJobDebugLedger {

    private static final double ATTEMPT_THRESHOLD = 0.15d;

    private final ObjectProvider<DebugEventStore> debugEventStore;
    private final Map<String, Deque<ImageJobDebugSignal>> byJob = new ConcurrentHashMap<>();

    @Value("${image.jobs.debug.enabled:true}")
    private boolean enabled = true;

    @Value("${image.jobs.debug.max-signals:2000}")
    private int maxSignals = 2000;

    @Value("${image.jobs.debug.session-ttl-ms:32400000}")
    private long sessionTtlMs = 32_400_000L;

    @Value("${image.jobs.debug.delta-threshold:0.35}")
    private double deltaThreshold = 0.35;

    @Value("${image.jobs.debug.negative-signal-threshold:0.55}")
    private double negativeThreshold = 0.55;

    public ImageJobDebugSignal record(String jobId,
                                      ImageJobDebugAgent agent,
                                      String stage,
                                      double severity,
                                      double expected,
                                      double observed,
                                      String reason,
                                      Map<String, Object> data) {
        if (!enabled) {
            return null;
        }
        String internalKey = clean(jobId);
        if (internalKey == null) {
            return null;
        }
        purgeExpired();
        double delta = (observed - expected) / (Math.abs(expected) + 1.0d);
        double negative = negativeScore(severity, delta, reason, data);
        boolean triggered = Math.abs(delta) >= deltaThreshold || negative >= negativeThreshold;
        ImageJobDebugSignal signal = new ImageJobDebugSignal(
                Instant.now(),
                SafeRedactor.hashValue(internalKey),
                agent == null ? ImageJobDebugAgent.VERDICT : agent,
                safeStage(stage),
                clamp01(severity),
                expected,
                observed,
                round4(delta),
                round4(negative),
                triggered,
                safeReason(reason),
                sanitizeData(data));

        Deque<ImageJobDebugSignal> signals = byJob.computeIfAbsent(internalKey, ignored -> new ConcurrentLinkedDeque<>());
        signals.addFirst(signal);
        while (signals.size() > Math.max(50, maxSignals)) {
            signals.pollLast();
        }
        emit(signal);
        return signal;
    }

    public Map<String, Object> recordAttemptScore(String jobId,
                                                  String attemptId,
                                                  String changeKey,
                                                  double beforeScore,
                                                  double afterScore,
                                                  double regressionPenalty,
                                                  String note) {
        String internalKey = clean(jobId);
        String attempt = clean(attemptId);
        String change = clean(changeKey);
        String memo = clean(note);
        double before = clamp01(beforeScore);
        double after = clamp01(afterScore);
        double penalty = clamp01(regressionPenalty);
        double reward = round4(clamp(after - before - penalty, -1.0d, 1.0d));
        String verdict = attemptVerdict(reward);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("attemptIdHash", SafeRedactor.hashValue(attempt));
        data.put("attemptIdLength", attempt == null ? 0 : attempt.length());
        data.put("changeKeyHash", SafeRedactor.hashValue(change));
        data.put("changeKeyLength", change == null ? 0 : change.length());
        data.put("noteHash", SafeRedactor.hashValue(memo));
        data.put("noteLength", memo == null ? 0 : memo.length());
        data.put("beforeScore", round4(before));
        data.put("afterScore", round4(after));
        data.put("regressionPenalty", round4(penalty));
        data.put("reward", reward);
        data.put("verdict", verdict);
        data.put("attemptThreshold", ATTEMPT_THRESHOLD);
        data.put("negativeAttempt", reward < -ATTEMPT_THRESHOLD);

        double severity = "ROLLBACK".equals(verdict)
                ? clamp01(Math.abs(reward) + penalty)
                : clamp01(penalty);
        ImageJobDebugSignal signal = record(internalKey, ImageJobDebugAgent.FAILURE_MEMORY,
                "attempt.score", severity, before, after, verdict, data);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jobIdHash", SafeRedactor.hashValue(internalKey));
        out.put("jobIdLength", internalKey == null ? 0 : internalKey.length());
        out.put("attemptIdHash", SafeRedactor.hashValue(attempt));
        out.put("attemptIdLength", attempt == null ? 0 : attempt.length());
        out.put("changeKeyHash", SafeRedactor.hashValue(change));
        out.put("changeKeyLength", change == null ? 0 : change.length());
        out.put("noteHash", SafeRedactor.hashValue(memo));
        out.put("noteLength", memo == null ? 0 : memo.length());
        out.put("beforeScore", round4(before));
        out.put("afterScore", round4(after));
        out.put("regressionPenalty", round4(penalty));
        out.put("reward", reward);
        out.put("verdict", verdict);
        out.put("triggered", signal != null && signal.triggered());
        out.put("nextAction", attemptNextAction(verdict));
        return out;
    }

    public Map<String, Object> snapshot(String jobId) {
        purgeExpired();
        String internalKey = clean(jobId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jobIdHash", SafeRedactor.hashValue(internalKey));
        out.put("jobIdLength", internalKey == null ? 0 : internalKey.length());
        if (internalKey == null) {
            out.put("signalCount", 0);
            out.put("suspicion", 0.0d);
            out.put("triggered", false);
            out.put("topAgents", Map.of());
            out.put("lastSignals", List.of());
            out.put("nextAction", "evidence_needed: missing job id");
            return out;
        }

        List<ImageJobDebugSignal> signals = new ArrayList<>(byJob.getOrDefault(internalKey, new ConcurrentLinkedDeque<>()));
        double suspicion = suspicion(signals);
        out.put("signalCount", signals.size());
        out.put("suspicion", round4(suspicion));
        out.put("triggered", suspicion >= negativeThreshold || signals.stream().anyMatch(ImageJobDebugSignal::triggered));
        out.put("topAgents", topAgents(signals));
        out.put("lastSignals", signals.stream().limit(30).toList());
        out.put("nextAction", nextAction(signals, suspicion));
        return out;
    }

    public double suspicion(List<ImageJobDebugSignal> signals) {
        if (signals == null || signals.isEmpty()) {
            return 0.0d;
        }
        List<Double> values = signals.stream()
                .map(ImageJobDebugSignal::negativeScore)
                .filter(v -> v != null && !v.isNaN() && !v.isInfinite())
                .sorted(Comparator.reverseOrder())
                .toList();
        if (values.isEmpty()) {
            return 0.0d;
        }
        double max = values.get(0);
        double median = values.get(values.size() / 2);
        double p = 2.0d + 3.0d * clamp01(max - median);
        int tailN = Math.max(1, (int) Math.ceil(values.size() * 0.30d));
        double sum = 0.0d;
        double weightSum = 0.0d;
        for (int i = 0; i < tailN; i++) {
            double weight = 1.0d + (tailN - i) * 0.15d;
            sum += weight * Math.pow(values.get(i), p);
            weightSum += weight;
        }
        double twpm = Math.pow(sum / Math.max(1.0d, weightSum), 1.0d / p);
        double trend = values.size() >= 2
                ? clamp01(values.get(0) - values.get(Math.min(values.size() - 1, 5)))
                : 0.0d;
        return clamp01(0.65d * twpm + 0.25d * max + 0.10d * trend);
    }

    private void emit(ImageJobDebugSignal signal) {
        if (signal == null) {
            return;
        }
        DebugEventStore store = debugEventStore.getIfAvailable();
        if (store == null) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("jobIdHash", signal.jobIdHash());
        data.put("agent", signal.agent().name());
        data.put("stage", signal.stage());
        data.put("severity", round4(signal.severity()));
        data.put("expected", signal.expected());
        data.put("observed", signal.observed());
        data.put("deltaRatio", round4(signal.deltaRatio()));
        data.put("negativeScore", round4(signal.negativeScore()));
        data.put("triggered", signal.triggered());
        data.put("reason", signal.reason());
        data.put("detail", signal.data());
        DebugEventLevel level = signal.negativeScore() >= 0.75d
                ? DebugEventLevel.ERROR
                : signal.triggered() ? DebugEventLevel.WARN : DebugEventLevel.INFO;
        store.emit(DebugProbeType.IMAGE_JOB, level,
                "image.job.debug." + signal.agent().name() + "." + signal.stage(),
                "[AWX][image-debug] stage=" + signal.stage()
                        + " agent=" + signal.agent().name()
                        + " severity=" + round4(signal.severity())
                        + " delta=" + round4(signal.deltaRatio())
                        + " suspicion=" + round4(signal.negativeScore())
                        + " trigger=" + signal.triggered()
                        + " reason=" + signal.reason(),
                "image.job.debug",
                data,
                null);
    }

    private Map<String, Object> topAgents(List<ImageJobDebugSignal> signals) {
        Map<String, Double> scores = new LinkedHashMap<>();
        if (signals != null) {
            for (ImageJobDebugSignal signal : signals) {
                if (signal == null || signal.agent() == null) {
                    continue;
                }
                scores.merge(signal.agent().name(), signal.negativeScore(), Math::max);
            }
        }
        List<Map.Entry<String, Double>> entries = new ArrayList<>(scores.entrySet());
        entries.sort(Map.Entry.<String, Double>comparingByValue().reversed());
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : entries) {
            if (out.size() >= 5) {
                break;
            }
            out.put(entry.getKey(), round4(entry.getValue()));
        }
        return out;
    }

    private String nextAction(List<ImageJobDebugSignal> signals, double suspicion) {
        if (signals == null || signals.isEmpty()) {
            return "observe: no image job debug signal yet";
        }
        ImageJobDebugSignal strongest = signals.stream()
                .max(Comparator.comparingDouble(ImageJobDebugSignal::negativeScore))
                .orElse(null);
        if (strongest == null) {
            return "observe: no ranked signal";
        }
        return switch (strongest.agent()) {
            case CONFIG_SENTINEL -> "check config: openai.image.enabled, api key presence, sync flag, relay delay";
            case QUEUE_TIME, UI_POLL -> "check queue/UI timing: compare etaSeconds, relay-delay-ms, and client wait";
            case PROVIDER -> "check provider: disabled reason, HTTP status class, returned count, and rate limit";
            case STORAGE, MANIFEST -> "check artifact storage/manifest: path hash, publicUrl presence, file size";
            case ACCESS -> "check access: session or owner-token mismatch without exposing token values";
            case FAILURE_MEMORY -> "compare repeated failure reasons before retrying";
            case VERDICT -> suspicion >= negativeThreshold
                    ? "triage highest image job failure reason before another retry"
                    : "keep observing until threshold is crossed";
        };
    }

    private double negativeScore(double severity, double delta, String reason, Map<String, Object> data) {
        double reasonPenalty = reasonPenalty(reason);
        double repeatedPenalty = booleanish(data == null ? null : data.get("repeated")) ? 1.0d : 0.0d;
        double stalePenalty = booleanish(data == null ? null : data.get("stale"))
                || number("waitingMs", data == null ? null : data.get("waitingMs")) > number("expectedUiPollMs", data == null ? null : data.get("expectedUiPollMs"))
                ? 1.0d : 0.0d;
        return clamp01(0.35d * clamp01(severity)
                + 0.25d * clamp01(Math.abs(delta))
                + 0.20d * reasonPenalty
                + 0.10d * repeatedPenalty
                + 0.10d * stalePenalty);
    }

    private double reasonPenalty(String reason) {
        String r = safeReason(reason).toUpperCase(java.util.Locale.ROOT);
        if (r.contains("ROLLBACK") || r.contains("REGRESSION")) {
            return 1.0d;
        }
        if (r.contains("SESSION_MISMATCH") || r.contains("NO_API_KEY") || r.contains("DISABLED")) {
            return 1.0d;
        }
        if (r.contains("429") || r.contains("RATE") || r.contains("TIMEOUT")) {
            return 0.90d;
        }
        if (r.contains("NO_RESULT") || r.contains("EMPTY") || r.contains("FAILED")) {
            return 0.75d;
        }
        if ("OK".equals(r) || "QUEUED".equals(r)) {
            return 0.05d;
        }
        return 0.35d;
    }

    private static String attemptVerdict(double reward) {
        if (reward >= ATTEMPT_THRESHOLD) {
            return "PROMOTE";
        }
        if (reward <= -ATTEMPT_THRESHOLD) {
            return "ROLLBACK";
        }
        return "KEEP_OBSERVING";
    }

    private static String attemptNextAction(String verdict) {
        return switch (verdict) {
            case "PROMOTE" -> "promote: keep observing image job debug signals for regressions";
            case "ROLLBACK" -> "rollback: revert or quarantine this attempt before another retry";
            default -> "keep observing: collect more image job signals before changing source";
        };
    }

    private Map<String, Object> sanitizeData(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            if (entry == null || entry.getKey() == null) {
                continue;
            }
            String key = SafeRedactor.traceLabelOrFallback(entry.getKey(), "field");
            out.put(key, SafeRedactor.diagnosticValue(entry.getKey(), entry.getValue(), 256));
        }
        return out;
    }

    private void purgeExpired() {
        if (sessionTtlMs <= 0) {
            return;
        }
        Instant cutoff = Instant.now().minus(Duration.ofMillis(sessionTtlMs));
        byJob.entrySet().removeIf(entry -> {
            Deque<ImageJobDebugSignal> signals = entry.getValue();
            if (signals == null) {
                return true;
            }
            signals.removeIf(signal -> signal == null || signal.at() == null || signal.at().isBefore(cutoff));
            return signals.isEmpty();
        });
    }

    private static boolean booleanish(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private static double number(String field, Object value) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value == null) {
            return 0.0d;
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception failure) {
            TraceStore.put("image.job.debug.metricCoercion.failed", true);
            traceMetricCoercion(field, value, failure);
            return 0.0d;
        }
    }

    private static void traceMetricCoercion(String field, Object value, Exception failure) {
        String safeField = SafeRedactor.traceLabelOrFallback(field, "metric");
        String raw = String.valueOf(value);
        TraceStore.put("image.job.debug.metricCoercion.failed", true);
        TraceStore.put("image.job.debug.metricCoercion.field", safeField);
        TraceStore.put("image.job.debug.metricCoercion.errorType", "invalid_number");
        TraceStore.put("image.job.debug.metricCoercion.exceptionType",
                SafeRedactor.traceLabelOrFallback(failure == null ? null : failure.getClass().getSimpleName(), "unknown"));
        TraceStore.put("image.job.debug.metricCoercion.valueHash", SafeRedactor.hashValue(raw));
        TraceStore.put("image.job.debug.metricCoercion.valueLength", raw.length());
    }

    private static String safeStage(String stage) {
        return SafeRedactor.traceLabelOrFallback(stage, "unknown");
    }

    private static String safeReason(String reason) {
        return SafeRedactor.traceLabelOrFallback(reason, "unknown");
    }

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.replace('\u0000', ' ').trim();
        return cleaned.isBlank() ? null : cleaned;
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private static double clamp(double value, double min, double max) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0d;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static double round4(double value) {
        return Math.round(value * 10_000.0d) / 10_000.0d;
    }
}
