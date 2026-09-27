package com.example.lms.metrics;

import com.example.lms.harmony.HarmonyScoreEngine;
import com.example.lms.harmony.HarmonyScoreSnapshot;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/metrics")
public class FaithfulnessMetricController {

    private static final String P_EVAL = "rag.eval.normalized.";
    private static final String P_QUALITY = "rag.answerQuality.";
    private static final String P_SCORECARD = "rag.blackbox.";
    private static final String P_SOAK = "soak.";

    private final HarmonyScoreEngine harmonyScoreEngine;

    public FaithfulnessMetricController() {
        this(null);
    }

    @Autowired
    public FaithfulnessMetricController(HarmonyScoreEngine harmonyScoreEngine) {
        this.harmonyScoreEngine = harmonyScoreEngine;
    }

    @GetMapping(value = "/faithfulness", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> faithfulness() {
        Map<String, Object> out = new LinkedHashMap<>();
        double qualityScore = readDouble(P_QUALITY + "faithfulnessScore", -1.0d);
        String decision = safeLabel(readValue(P_QUALITY + "decision"), "UNKNOWN");
        String reason = safeLabel(readValue(P_QUALITY + "reason"), "UNKNOWN");
        long docCount = readLong(P_QUALITY + "docCount", -1L);
        long distinctSources = readLong(P_QUALITY + "distinctSources", -1L);

        out.put("schemaVersion", "faithfulness-v1");
        out.put("generatedAt", Instant.now().toString());

        out.put("rag.retrievalHitRate", readDouble(P_EVAL + "retrievalHitRate", -1.0d));
        out.put("rag.evidenceCoverage", readDouble(P_EVAL + "evidenceCoverage", -1.0d));
        out.put("rag.sourceDiversity", readDouble(P_EVAL + "sourceDiversity", -1.0d));
        out.put("rag.resultDepth", readDouble(P_EVAL + "resultDepth", -1.0d));
        out.put("rag.balancedScore", readDouble(P_EVAL + "balancedScore", -1.0d));
        out.put("rag.latencyCost", readDouble(P_EVAL + "latencyCost", -1.0d));
        out.put("rag.fallbackCost", readDouble(P_EVAL + "fallbackCost", -1.0d));

        out.put("quality.faithfulnessScore", clampMetric(qualityScore));
        out.put("quality.decision", decision);
        out.put("quality.reason", reason);
        out.put("quality.confidence", readDouble(P_QUALITY + "confidence", -1.0d));
        out.put("quality.docCount", docCount);
        out.put("quality.distinctSources", distinctSources);

        out.put("scorecard.compositeRisk", readDouble(P_SCORECARD + "compositeRisk", -1.0d));
        out.put("scorecard.evidenceGrounding", readDouble(P_SCORECARD + "channel.evidenceGrounding", -1.0d));
        out.put("scorecard.hallucinationLikelihood",
                readDouble(P_SCORECARD + "channel.hallucinationLikelihood", -1.0d));
        out.put("scorecard.routingDecision",
                safeLabel(readValue(P_SCORECARD + "routingDecision"), "UNKNOWN"));

        out.put("soak.fpFilterLegacyBypassCount", readLong(P_SOAK + "fpFilterLegacyBypassCount", -1L));
        out.put("soak.webCalls", readLong(P_SOAK + "webCalls", -1L));
        out.put("soak.webCallsWithNaver", readLong(P_SOAK + "webCallsWithNaver", -1L));
        out.put("soak.webMergedTotal", readLong(P_SOAK + "webMergedTotal", -1L));
        out.put("soak.webMergedFromNaver", readLong(P_SOAK + "webMergedFromNaver", -1L));
        out.put("soak.naverCallInclusionRate", readDouble(P_SOAK + "naverCallInclusionRate", -1.0d));
        out.put("soak.naverMergedShare", readDouble(P_SOAK + "naverMergedShare", -1.0d));

        putHarmonyScore(out);
        out.put("meta.traceSchemaVersion",
                safeLabel(readValue(P_EVAL + "schemaVersion"), "unknown"));

        // Legacy flat aliases kept for existing local dashboards.
        out.put("available", qualityScore >= 0.0d);
        out.put("faithfulnessScore", clampMetric(qualityScore));
        out.put("decision", decision);
        out.put("reason", reason);
        out.put("docCount", Math.max(0L, docCount));
        out.put("distinctSources", Math.max(0L, distinctSources));
        return ResponseEntity.ok(out);
    }

    private void putHarmonyScore(Map<String, Object> out) {
        double cached = readDouble("harmony.score.lastComputed", -1.0d);
        if (cached >= 0.0d) {
            out.put("harmony.score", cached);
            return;
        }
        if (harmonyScoreEngine == null) {
            out.put("harmony.score", "evidence_needed:harmony_engine_unavailable");
            return;
        }
        try {
            HarmonyScoreSnapshot snapshot = harmonyScoreEngine.compute();
            out.put("harmony.score", snapshot.harmonyScore());
            out.put("harmony.contaminationScore", snapshot.contaminationScore());
            out.put("harmony.achievementPct", snapshot.achievementPct());
            putMetricTrace("harmony.score.lastComputed", snapshot.harmonyScore());
        } catch (Exception ex) {
            traceSuppressed("harmonyScore", ex);
            out.put("harmony.score", "evidence_needed:" + ex.getClass().getSimpleName());
        }
    }

    private static double readDouble(String key, double fallback) {
        Object value = readValue(key);
        if (!(value instanceof Number n)) {
            return fallback;
        }
        double v = n.doubleValue();
        return Double.isFinite(v) ? v : fallback;
    }

    private static long readLong(String key, long fallback) {
        Object value = readValue(key);
        return value instanceof Number n ? Math.max(0L, n.longValue()) : fallback;
    }

    private static Object readValue(String key) {
        Object value = TraceStore.get(key);
        if (value == null && key != null && key.startsWith(P_EVAL)) {
            value = nestedNormalizedValue(key.substring(P_EVAL.length()));
        }
        return value == null ? FaithfulnessMetricSnapshotStore.get(key) : value;
    }

    private static Object nestedNormalizedValue(String childKey) {
        Object value = TraceStore.get("rag.eval.normalized");
        if (value instanceof Map<?, ?> map) {
            return map.get(childKey);
        }
        return null;
    }

    private static double clampMetric(double value) {
        if (!Double.isFinite(value) || value <= 0.0d) {
            return 0.0d;
        }
        return Math.min(1.0d, value);
    }

    private static void putMetricTrace(String key, Object value) {
        TraceStore.put(key, value);
        FaithfulnessMetricSnapshotStore.put(key, value);
    }

    private static String safeLabel(Object value, String fallback) {
        return SafeRedactor.traceLabelOrFallback(value == null ? "" : String.valueOf(value), fallback);
    }

    private static void traceSuppressed(String stage, Throwable failure) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String errorType = failure == null ? "unknown" : failure.getClass().getSimpleName();
        TraceStore.put("faithfulness.metric.suppressed.stage", safeStage);
        TraceStore.put("faithfulness.metric.suppressed.errorType", errorType);
        TraceStore.put("faithfulness.metric.suppressed." + safeStage, true);
        TraceStore.put("faithfulness.metric.suppressed." + safeStage + ".errorType", errorType);
    }
}
