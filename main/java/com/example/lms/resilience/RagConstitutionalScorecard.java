package com.example.lms.resilience;

import com.example.lms.metrics.FaithfulnessMetricSnapshotStore;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class RagConstitutionalScorecard {

    private static final Logger LOG = LoggerFactory.getLogger(RagConstitutionalScorecard.class);

    private RagConstitutionalScorecard() {
    }

    static void projectTrace(Map<String, Object> trace, RagFailureBlackboxService.Snapshot snapshot) {
        writeTrace(project(trace, snapshot));
    }

    static Map<String, Object> project(Map<String, Object> trace, RagFailureBlackboxService.Snapshot snapshot) {
        Map<String, Object> safeTrace = trace == null ? Map.of() : trace;
        Map<String, Object> matrix = snapshot == null || snapshot.matrix() == null ? Map.of() : snapshot.matrix();
        double searchQuality = searchQualityChannel(safeTrace, matrix);
        double evidenceGrounding = evidenceGroundingChannel(safeTrace);
        double hallucinationLikelihood = hallucinationChannel(safeTrace, evidenceGrounding, searchQuality);
        double policyRisk = policyRiskChannel(safeTrace);
        double executionRisk = executionRiskChannel(safeTrace, matrix, snapshot);
        double searchRisk = 1.0d - searchQuality;
        double evidenceRisk = 1.0d - evidenceGrounding;
        double compositeRisk = round4(
                (searchRisk * 0.22d)
                        + (evidenceRisk * 0.24d)
                        + (hallucinationLikelihood * 0.24d)
                        + (policyRisk * 0.16d)
                        + (executionRisk * 0.14d));
        String dominantChannel = dominantChannel(searchRisk, evidenceRisk, hallucinationLikelihood, policyRisk, executionRisk);
        String routingDecision = routingDecision(compositeRisk, searchQuality, evidenceGrounding,
                hallucinationLikelihood, policyRisk, executionRisk, snapshot);

        Map<String, Object> channels = new LinkedHashMap<>();
        channels.put("searchQuality", searchQuality);
        channels.put("evidenceGrounding", evidenceGrounding);
        channels.put("hallucinationLikelihood", hallucinationLikelihood);
        channels.put("policyRisk", policyRisk);
        channels.put("executionRisk", executionRisk);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schemaVersion", "constitutional-scorecard-v1");
        out.put("channels", Collections.unmodifiableMap(channels));
        out.put("compositeRisk", compositeRisk);
        out.put("routingDecision", routingDecision);
        out.put("blockRecommended", "BLOCK".equals(routingDecision));
        out.put("dominantChannel", dominantChannel);
        out.put("reasonCode", reasonCode(dominantChannel, routingDecision, snapshot));
        out.put("restoreAction", snapshot == null ? "observe_only" : safeLabel(snapshot.restoreAction(), "observe_only"));
        return Collections.unmodifiableMap(out);
    }

    @SuppressWarnings("unchecked")
    private static void writeTrace(Map<String, Object> scorecard) {
        if (scorecard == null || scorecard.isEmpty()) {
            return;
        }
        writeMetricTrace(RagFailureBlackboxService.PREFIX, scorecard);
        writeMetricTrace("rag.blackbox.", scorecard);
    }

    @SuppressWarnings("unchecked")
    private static void writeMetricTrace(String prefix, Map<String, Object> scorecard) {
        TraceStore.put(prefix + "scorecard", scorecard);
        putMetricTrace(prefix + "scorecard.schemaVersion", scorecard.get("schemaVersion"));
        putMetricTrace(prefix + "compositeRisk", scorecard.get("compositeRisk"));
        putMetricTrace(prefix + "routingDecision", scorecard.get("routingDecision"));
        putMetricTrace(prefix + "blockRecommended", scorecard.get("blockRecommended"));
        putMetricTrace(prefix + "dominantChannel", scorecard.get("dominantChannel"));
        Object channels = scorecard.get("channels");
        if (channels instanceof Map<?, ?> map) {
            for (String key : List.of("searchQuality", "evidenceGrounding", "hallucinationLikelihood",
                    "policyRisk", "executionRisk")) {
                Object value = ((Map<String, Object>) map).get(key);
                if (value instanceof Number) {
                    putMetricTrace(prefix + "channel." + key, value);
                }
            }
        }
    }

    private static void putMetricTrace(String key, Object value) {
        TraceStore.put(key, value);
        FaithfulnessMetricSnapshotStore.put(key, value);
    }

    private static double searchQualityChannel(Map<String, Object> trace, Map<String, Object> matrix) {
        double normalized = Math.max(
                nestedDouble(trace, "rag.eval.normalized", "balancedScore"),
                maxDouble(trace, matrix, "rag.eval.normalized.balancedScore", "rag.normalized.balancedScore"));
        double hitRate = Math.max(
                nestedDouble(trace, "rag.eval.normalized", "retrievalHitRate"),
                maxDouble(trace, matrix, "rag.eval.normalized.retrievalHitRate"));
        double sourceTotal = maxDouble(trace, matrix, "source.web.count")
                + maxDouble(trace, matrix, "source.vector.count")
                + maxDouble(trace, matrix, "source.kg.count");
        double base = Math.max(Math.max(normalized, hitRate), sourceTotal <= 0.0d ? 0.0d : clamp01(sourceTotal / 4.0d));
        double pressure = Math.max(thresholdPressure(trace,
                        "retrieval_starvation", "source_collapse", "stage_drop", "zero_result",
                        "after_filter", "provider_disabled"),
                Math.max(maxDouble(trace, matrix, "provider.disabled.count") > 0.0d ? 0.55d : 0.0d,
                        maxDouble(trace, matrix, "after.filter.starvation.count") > 0.0d ? 0.70d : 0.0d));
        if (maxDouble(trace, matrix, "timeout.count", "rate.limit.count",
                "web.await.events.timeout.count", "web.await.events.rateLimit.count") > 0.0d
                || truthyAny(trace, "web.timeout", "web.naver.timeout", "web.brave.timeout",
                "web.serpapi.timeout", "web.tavily.timeout")) {
            pressure = Math.max(pressure, 0.35d);
        }
        return round4(base * (1.0d - (pressure * 0.45d)));
    }

    private static double evidenceGroundingChannel(Map<String, Object> trace) {
        double coverage = Math.max(
                nestedDouble(trace, "rag.eval.normalized", "evidenceCoverage"),
                maxDouble(trace, Map.of(), "rag.eval.normalized.evidenceCoverage", "rag.evidence.coverage"));
        if (coverage <= 0.0d) {
            long evidenceCount = maxLong(trace, "evidence.count", "citation.count", "guard.escalation.evidenceCount",
                    "guard.degradedToEvidence.evidenceCount");
            coverage = evidenceCount <= 0L ? 0.0d : clamp01(evidenceCount / 3.0d);
        }
        double pressure = Math.max(thresholdPressure(trace, "weak_evidence", "citation", "evidence_gate"),
                truthyAny(trace, "citation.gate.failed", "gate.final.failed", "finalSigmoidGate.failed") ? 0.65d : 0.0d);
        return round4(coverage * (1.0d - (pressure * 0.50d)));
    }

    private static double hallucinationChannel(Map<String, Object> trace, double evidenceGrounding, double searchQuality) {
        double risk = Math.max(1.0d - evidenceGrounding, maxDouble(trace, Map.of(),
                "overdrive.contradiction.mean",
                "extremez.risk.contradictionMean",
                "rag.contradiction.score",
                "answer.hallucinationRisk",
                "hallucinationRisk"));
        if (truthyAny(trace, "citation.gate.failed", "gate.final.failed", "finalSigmoidGate.failed")) {
            risk = Math.max(risk, 0.72d);
        }
        if (searchQuality < 0.25d && evidenceGrounding < 0.35d) {
            risk = Math.max(risk, 0.80d);
        }
        return round4(risk);
    }

    private static double policyRiskChannel(Map<String, Object> trace) {
        double risk = maxDouble(trace, Map.of(),
                "guard.policyRisk",
                "policyRisk",
                "rag.eval.policyRisk",
                "llm.model.policy.risk",
                "safety.policyRisk");
        risk = Math.max(risk, thresholdPressure(trace, "policy", "safety", "constitutional"));
        if (truthyAny(trace, "llm.model.policy.blocked", "guard.policy.blocked", "safety.policy.blocked")) {
            risk = Math.max(risk, 0.90d);
        }
        if (truthyAny(trace, "guard.sensitiveTopic", "query.sensitiveTopic")) {
            risk = Math.max(risk, 0.40d);
        }
        return round4(risk);
    }

    private static double executionRiskChannel(Map<String, Object> trace,
                                               Map<String, Object> matrix,
                                               RagFailureBlackboxService.Snapshot snapshot) {
        double risk = Math.max(
                countPressure(maxLong(trace, "web.await.events.timeout.count", "web.await.events.rateLimit.count",
                        "web.failsoft.rateLimitBackoff.skipped.cooldown.count")),
                maxDouble(trace, matrix, "q_gpu_gateway_pressure", "q_gpu_hardware_pressure", "q_kg_degradation_pressure",
                        "q_graph_dependency_pressure", "timeBudget.usage", "budget.usage"));
        if (truthyAny(trace, "web.timeout", "web.naver.timeout", "web.brave.timeout",
                "web.serpapi.timeout", "web.tavily.timeout")) {
            risk = Math.max(risk, countPressure(1));
        }
        if (truthyAny(trace, "web.await.missing_future.any", "aux.blocked", "aux.queryTransformer.blocked",
                "uaw.gpu-gateway.admission.blocked", "uaw.gpu-hardware.admission.blocked")) {
            risk = Math.max(risk, 0.70d);
        }
        String action = snapshot == null ? "" : safeLabel(snapshot.restoreAction(), "");
        if (List.of("web_await_bypass", "cooldown_reorder", "demote_heavy_work",
                "kg_dependency_fallback", "llm_route_degrade", "safe_path_bypass").contains(action)) {
            risk = Math.max(risk, Math.max(snapshot.riskScore(), snapshot.priorityScore()) * 0.85d);
        }
        return round4(risk);
    }

    private static String routingDecision(double compositeRisk,
                                          double searchQuality,
                                          double evidenceGrounding,
                                          double hallucinationRisk,
                                          double policyRisk,
                                          double executionRisk,
                                          RagFailureBlackboxService.Snapshot snapshot) {
        if (policyRisk >= 0.85d
                || (hallucinationRisk >= 0.85d && evidenceGrounding < 0.35d)
                || (compositeRisk >= 0.92d && Math.max(policyRisk, hallucinationRisk) >= 0.70d)) {
            return "BLOCK";
        }
        if (executionRisk >= 0.65d || searchQuality < 0.35d
                || (snapshot != null && "disable_provider_failsoft".equals(snapshot.restoreAction()))) {
            return "RECOVERY";
        }
        if (compositeRisk >= 0.65d || evidenceGrounding < 0.50d) {
            return "DEGRADE";
        }
        return "ALLOW";
    }

    private static String dominantChannel(double searchRisk,
                                          double evidenceRisk,
                                          double hallucinationRisk,
                                          double policyRisk,
                                          double executionRisk) {
        String label = "searchQuality";
        double max = searchRisk;
        if (evidenceRisk > max) {
            label = "evidenceGrounding";
            max = evidenceRisk;
        }
        if (hallucinationRisk > max) {
            label = "hallucinationLikelihood";
            max = hallucinationRisk;
        }
        if (policyRisk > max) {
            label = "policyRisk";
            max = policyRisk;
        }
        if (executionRisk > max) {
            label = "executionRisk";
        }
        return label;
    }

    private static String reasonCode(String dominantChannel,
                                     String routingDecision,
                                     RagFailureBlackboxService.Snapshot snapshot) {
        String action = snapshot == null ? "observe_only" : safeLabel(snapshot.restoreAction(), "observe_only");
        return safeLabel(routingDecision + "_" + dominantChannel + "_" + action, "scorecard");
    }

    private static double thresholdPressure(Map<String, Object> trace, String... needles) {
        Object raw = trace == null ? null : trace.get("rag.eval.thresholdBreaks");
        if (!(raw instanceof Iterable<?> rows) || needles == null || needles.length == 0) {
            return 0.0d;
        }
        double max = 0.0d;
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> map)) {
                continue;
            }
            String haystack = (asString(map.get("label")) + " " + asString(map.get("metric")) + " "
                    + asString(map.get("stage"))).toLowerCase(Locale.ROOT);
            for (String needle : needles) {
                if (needle != null && haystack.contains(needle.toLowerCase(Locale.ROOT))) {
                    max = Math.max(max, Math.max(asDouble(map.get("severity"), 0.0d),
                            asDouble(map.get("value"), 0.0d)));
                }
            }
        }
        return clamp01(max);
    }

    private static double countPressure(long count) {
        if (count <= 0L) {
            return 0.0d;
        }
        return clamp01(Math.log1p(count) / Math.log(4.0d));
    }

    private static double nestedDouble(Map<String, Object> trace, String mapKey, String valueKey) {
        if (trace == null || mapKey == null || valueKey == null) {
            return 0.0d;
        }
        Object nested = trace.get(mapKey);
        if (!(nested instanceof Map<?, ?> map)) {
            return 0.0d;
        }
        return asDouble(map.get(valueKey), 0.0d);
    }

    private static double maxDouble(Map<String, Object> trace, Map<String, Object> matrix, String... keys) {
        double max = 0.0d;
        if (keys == null) {
            return max;
        }
        Map<String, Object> safeTrace = trace == null ? Map.of() : trace;
        Map<String, Object> safeMatrix = matrix == null ? Map.of() : matrix;
        for (String key : keys) {
            max = Math.max(max, asDouble(safeTrace.get(key), 0.0d));
            max = Math.max(max, asDouble(safeMatrix.get(key), 0.0d));
        }
        return max;
    }

    private static long maxLong(Map<String, Object> trace, String... keys) {
        long max = 0L;
        if (trace == null || keys == null) {
            return max;
        }
        for (String key : keys) {
            max = Math.max(max, toLong(trace.get(key)));
        }
        return max;
    }

    private static boolean truthyAny(Map<String, Object> trace, String... keys) {
        if (trace == null || keys == null) {
            return false;
        }
        for (String key : keys) {
            if (truthy(trace.get(key))) {
                return true;
            }
        }
        return false;
    }

    private static boolean truthy(Object value) {
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.doubleValue() != 0.0d;
        String s = value == null ? "" : String.valueOf(value).trim();
        return "true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s) || "on".equalsIgnoreCase(s);
    }

    private static long toLong(Object value) {
        if (value instanceof Number n) {
            double numeric = n.doubleValue();
            return Double.isFinite(numeric) ? n.longValue() : 0L;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            traceSkipped("scorecard_long_parse", e);
            return 0L;
        }
    }

    private static double asDouble(Object value, double fallback) {
        if (value instanceof Number n) {
            double numeric = n.doubleValue();
            return Double.isFinite(numeric) ? numeric : fallback;
        }
        try {
            double parsed = Double.parseDouble(String.valueOf(value).trim());
            return Double.isFinite(parsed) ? parsed : fallback;
        } catch (NumberFormatException e) {
            traceSkipped("scorecard_double_parse", e);
            return fallback;
        }
    }

    private static String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String safeLabel(String value, String fallback) {
        String s = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (s.isBlank()) {
            s = fallback == null ? "" : fallback.trim().toLowerCase(Locale.ROOT);
        }
        s = s.replaceAll("[^a-z0-9_.:-]+", "_");
        if (s.length() > 96) {
            s = s.substring(0, 96);
        }
        return s.isBlank() ? "none" : s;
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return 0.0d;
        if (value < 0.0d) return 0.0d;
        if (value > 1.0d) return 1.0d;
        return value;
    }

    private static double round4(double value) {
        return Math.round(clamp01(value) * 10000.0d) / 10000.0d;
    }

    private static void traceSkipped(String stage, Throwable error) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String errorType = error == null ? "unknown" : error.getClass().getSimpleName();
        String safeErrorType = SafeRedactor.traceLabelOrFallback(errorType, "unknown");
        LOG.debug("[AWX][blackbox][scorecard] trace skipped stage={} errorType={}",
                safeStage,
                safeErrorType);
    }
}
