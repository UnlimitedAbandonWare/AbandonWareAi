package com.example.lms.uaw.autolearn;

import com.example.lms.search.TraceStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds sample-level validation metadata without making extra model/API calls.
 */
@Component
public class LearningSampleValidationMetadataBuilder {

    private static final System.Logger LOG = System.getLogger(LearningSampleValidationMetadataBuilder.class.getName());

    private static final double MIN_SAMPLE_SCORE = 0.55d;
    private static final double MAX_CONTAMINATION_SCORE = 0.35d;
    private static final double MAX_LEGACY_CONTEXT_SCORE = 0.40d;
    private static final double HIGH_RISK_SCORE = 0.70d;
    private final UawAutolearnProperties props;

    public LearningSampleValidationMetadataBuilder() {
        this(null);
    }

    @Autowired
    public LearningSampleValidationMetadataBuilder(UawAutolearnProperties props) {
        this.props = props == null ? new UawAutolearnProperties() : props;
    }

    public LearningSampleValidationMetadata build(
            String question,
            String answer,
            String modelUsed,
            int evidenceCount,
            int afterFilterCount,
            boolean finalGate,
            double contextDiversity,
            String disabledReason) {

        String q = safe(question);
        String a = safe(answer);
        String questionType = questionType(q);
        List<String> lanes = selfAskLanes();
        double laneCoverage = lanes.isEmpty() ? 0.0d : Math.min(1.0d, lanes.size() / 3.0d);

        double refutabilityScore = refutabilityScore(questionType, q);
        double causalNeedScore = causalNeedScore(questionType, q);
        double riskScore = riskScore(q);
        double contradictionScore = contradictionScore();
        double contaminationScore = contaminationScore(q, a, modelUsed);
        double legacyContextScore = legacyContextScore(q, a);
        double historyContaminationScore = historyContaminationScore();
        double contextContaminationScore = clamp01(Math.max(
                Math.max(contaminationScore, legacyContextScore * 0.75d),
                historyContaminationScore));

        boolean requeryRequired = riskScore >= HIGH_RISK_SCORE
                || refutabilityScore >= 0.70d
                || causalNeedScore >= 0.60d
                || "recency".equals(questionType)
                || "comparison".equals(questionType)
                || historyContaminationScore >= MAX_CONTAMINATION_SCORE;
        boolean explicitRequeryConfirmed = readBoolean("selfask.3way.requery.confirmed", false);
        boolean requeryConfirmed = !requeryRequired
                || (explicitRequeryConfirmed && laneCoverage >= 0.66d);
        double requeryPenalty = requeryRequired && !requeryConfirmed
                ? clamp(0.08d + riskScore * 0.10d, 0.08d, 0.20d)
                : 0.0d;
        double tuningDelta = clamp(readDouble("learning.threshold.tuningDelta", 0.0d), -0.10d, 0.10d);
        double sampleScoreMin = clamp(
                MIN_SAMPLE_SCORE + 0.20d * contextContaminationScore + requeryPenalty - 0.08d * laneCoverage
                        + tuningDelta,
                0.45d,
                0.78d);
        double contaminationMax = clamp(
                MAX_CONTAMINATION_SCORE - 0.10d * riskScore - requeryPenalty * 0.50d - tuningDelta,
                0.18d,
                0.40d);
        double contextContaminationMax = clamp(contaminationMax + 0.05d, 0.20d, 0.45d);
        double contradictionMax = contradictionThreshold(tuningDelta);
        String contradictionCause = contradictionCause(contradictionScore, contradictionMax);

        double sampleScore = sampleScore(
                evidenceCount,
                afterFilterCount,
                contextDiversity,
                laneCoverage,
                riskScore,
                contradictionScore,
                contaminationScore,
                legacyContextScore,
                requeryRequired,
                requeryConfirmed);

        List<String> rejectReasons = new ArrayList<>();
        if (disabledReason != null && !disabledReason.isBlank()) {
            rejectReasons.add("provider_disabled");
        }
        if (evidenceCount > 0 && afterFilterCount == 0) {
            rejectReasons.add("after_filter_starvation");
        }
        if (!finalGate) {
            rejectReasons.add("final_gate_failed");
        }
        if (sampleScore < sampleScoreMin) {
            rejectReasons.add("sample_score_below_threshold");
        }
        if (contaminationScore > contaminationMax || contextContaminationScore > contextContaminationMax) {
            rejectReasons.add("contamination_risk");
        }
        if (legacyContextScore > MAX_LEGACY_CONTEXT_SCORE) {
            rejectReasons.add("legacy_context_risk");
        }
        if (contradictionScore >= contradictionMax) {
            rejectReasons.add("contradiction_risk");
        }
        if (riskScore >= HIGH_RISK_SCORE && requeryRequired && !requeryConfirmed) {
            rejectReasons.add("unconfirmed_high_risk_requery");
        }
        if (historyContaminationScore >= MAX_CONTAMINATION_SCORE && requeryRequired && !requeryConfirmed) {
            rejectReasons.add("unconfirmed_history_contamination_requery");
        }
        LearningSampleValidationMetadata.NeedleRoi needleRoi = needleRoi(
                sampleScore,
                sampleScoreMin,
                laneCoverage,
                requeryRequired,
                requeryConfirmed,
                finalGate,
                disabledReason,
                evidenceCount,
                afterFilterCount,
                contradictionScore,
                contradictionMax,
                contextContaminationScore,
                contextContaminationMax,
                rejectReasons);
        if (needleRoi.needleSignalCandidate() && !needleRoi.promoted()) {
            addNeedleRoiRejectReason(rejectReasons, needleRoi.rejectReason());
        }

        LearningSampleValidationMetadata meta = new LearningSampleValidationMetadata(
                questionType,
                lanes,
                laneCoverage,
                refutabilityScore,
                riskScore,
                causalNeedScore,
                contradictionScore,
                contradictionCause,
                new LearningSampleValidationMetadata.Requery(requeryRequired, requeryConfirmed),
                contaminationScore,
                legacyContextScore,
                sampleScore,
                rejectReasons,
                evaluationCriteria(questionType, requeryRequired),
                new LearningSampleValidationMetadata.Thresholds(
                        sampleScoreMin,
                        contaminationMax,
                        contextContaminationMax,
                        contradictionMax,
                        requeryPenalty,
                        "dynamic"),
                new LearningSampleValidationMetadata.Runtime(
                        evidenceCount,
                        afterFilterCount,
                        contextDiversity,
                        laneCoverage,
                        readDouble("learning.metrics.errorRateWindow", 0.0d)),
                LearningSampleValidationMetadata.Anomalies.none(),
                new LearningSampleValidationMetadata.Feedback(0.0d, vectorDecision(rejectReasons)),
                needleRoi);
        trace(meta);
        return meta;
    }

    private static String questionType(String query) {
        String q = lower(query);
        if (containsAny(q, "latest", "recent", "news", "release", "today", "2026",
                "\ucd5c\uc2e0", "\ucd9c\uc2dc", "\uadfc\ud669", "\uc624\ub298", "\ub274\uc2a4")) {
            return "recency";
        }
        if (containsAny(q, "compare", "comparison", "difference", " vs ", "versus",
                "\ube44\uad50", "\ucc28\uc774", "\ub300\ube44")) {
            return "comparison";
        }
        if (containsAny(q, "why", "cause", "effect", "impact", "because", "reason",
                "\uc6d0\uc778", "\uacb0\uacfc", "\uc601\ud5a5", "\uc778\uacfc", "\uc774\uc720")) {
            return "causal";
        }
        if (containsAny(q, "who is", "what is", "define", "definition",
                "\ub204\uad6c", "\ubb34\uc5c7", "\ubb50\uc57c", "\uc815\uc758")) {
            return "factual_entity";
        }
        if (query != null && query.length() >= 90) {
            return "long_tail";
        }
        return "factual";
    }

    private static double refutabilityScore(String questionType, String query) {
        double score = switch (questionType) {
            case "comparison" -> 0.78d;
            case "causal" -> 0.74d;
            case "recency" -> 0.70d;
            case "long_tail" -> 0.62d;
            default -> 0.45d;
        };
        String q = lower(query);
        if (containsAny(q, "counterexample", "exception", "contradict", "\ubc18\ubc15", "\ubc18\ub840", "\uc608\uc678")) {
            score += 0.12d;
        }
        return clamp01(score);
    }

    private static double causalNeedScore(String questionType, String query) {
        double score = "causal".equals(questionType) ? 0.78d : 0.20d;
        String q = lower(query);
        if (containsAny(q, "root cause", "tradeoff", "side effect", "\uadfc\ubcf8 \uc6d0\uc778", "\ud2b8\ub808\uc774\ub4dc\uc624\ud504")) {
            score += 0.12d;
        }
        return clamp01(score);
    }

    private static double riskScore(String query) {
        double tracedValue = readDouble("resource.valueScore", Double.NaN);
        double tracedOptimism = readDouble("resource.optimismScore", Double.NaN);
        double score = Double.isFinite(tracedValue) ? tracedValue : 0.30d;
        String q = lower(query);
        if (containsAny(q, "medical", "legal", "finance", "investment", "contract", "lawsuit",
                "diagnosis", "prescription", "\uc758\ub8cc", "\ubc95\ub960", "\uae08\uc735", "\ud22c\uc790",
                "\uacc4\uc57d", "\uc18c\uc1a1", "\uc9c4\ub2e8", "\ucc98\ubc29")) {
            score = Math.max(score, 0.76d);
        }
        if (Double.isFinite(tracedOptimism)) {
            score += clamp01(tracedOptimism) * 0.12d;
        }
        return clamp01(score);
    }

    private static double contaminationScore(String question, String answer, String modelUsed) {
        String text = lower(question + "\n" + answer + "\n" + safe(modelUsed));
        double score = 0.0d;
        if (containsAny(text, "[degraded mode]", "fallback:evidence", "[no_evidence]", "search trace",
                "raw snippets", "orchestration state", "[src:web]", "[src:rag]")) {
            score += 0.45d;
        }
        if (containsAny(text, "stacktrace", "exception:", " at com.", " org.hibernate.sql ", "build failed")) {
            score += 0.35d;
        }
        if (containsAny(text, "api_key", "apikey", "client-secret", "ownertoken", "bearer ")) {
            score += 0.35d;
        }
        return clamp01(score);
    }

    private static double legacyContextScore(String question, String answer) {
        String text = lower(question + "\n" + answer);
        double score = 0.0d;
        if (containsAny(text, "app/src/main/java", "src/main/java_clean", "java_clean")) {
            score += 0.30d;
        }
        if (containsAny(text, "demo-1/src/main/java", "lms-core/src/main/java", "tool_b",
                "abandonwaretool_v1", "backupsxs", "uaw.txt", "pro2.txt")) {
            score += 0.30d;
        }
        if (containsAny(text, "legacy duplicate", "shadow helper", "duplicate wrapper")) {
            score += 0.20d;
        }
        return clamp01(score);
    }

    private static double historyContaminationScore() {
        double score = Math.max(
                readDouble("blackbox.risk.historyContaminationScore", 0.0d),
                readDouble("prompt.memory.compressor.contaminationScore", 0.0d));
        String memoryReason = TraceStore.getString("prompt.memory.compressor.reason");
        if (readBoolean("prompt.builder.contaminationFlag", false)
                || (readBoolean("prompt.memory.compressor.activated", false)
                && memoryReason != null
                && memoryReason.toLowerCase(Locale.ROOT).contains("contamination"))) {
            score = Math.max(score, MAX_CONTAMINATION_SCORE);
        }
        return clamp01(score);
    }

    private static double sampleScore(
            int evidenceCount,
            int afterFilterCount,
            double contextDiversity,
            double laneCoverage,
            double riskScore,
            double contradictionScore,
            double contaminationScore,
            double legacyContextScore,
            boolean requeryRequired,
            boolean requeryConfirmed) {
        double score = 0.25d;
        score += Math.min(1.0d, Math.max(0, evidenceCount) / 5.0d) * 0.25d;
        score += clamp01(contextDiversity) * 0.20d;
        score += laneCoverage * 0.15d;
        score += Math.min(1.0d, Math.max(afterFilterCount, 0) / 5.0d) * 0.10d;
        if (requeryRequired && !requeryConfirmed) {
            score -= Math.max(0.10d, riskScore * 0.18d);
        }
        score -= contradictionScore * 0.18d;
        score -= contaminationScore * 0.35d;
        score -= legacyContextScore * 0.25d;
        return clamp01(score);
    }

    private static LearningSampleValidationMetadata.NeedleRoi needleRoi(
            double sampleScore,
            double sampleScoreMin,
            double laneCoverage,
            boolean requeryRequired,
            boolean requeryConfirmed,
            boolean finalGate,
            String disabledReason,
            int evidenceCount,
            int afterFilterCount,
            double contradictionScore,
            double contradictionMax,
            double contextContaminationScore,
            double contextContaminationMax,
            List<String> rejectReasons) {
        boolean candidate = readBoolean("needle.triggered", false);
        if (!candidate) {
            return LearningSampleValidationMetadata.NeedleRoi.none();
        }
        double signalValueScore = needleSignalValueScore(laneCoverage);
        String runtimeFailure = runtimeFailureReason(disabledReason, evidenceCount, afterFilterCount);
        String rejectReason = "";
        if (!runtimeFailure.isBlank()) {
            rejectReason = "runtime_failure_" + runtimeFailure.replace('-', '_');
        } else if (!finalGate || containsReason(rejectReasons, "final_gate_failed")) {
            rejectReason = "final_gate_failed";
        } else if (signalValueScore < sampleScoreMin || sampleScore < sampleScoreMin) {
            rejectReason = "signal_below_threshold";
        } else if (laneCoverage <= 0.0d) {
            rejectReason = "selfask_lane_coverage_missing";
        } else if (requeryRequired && !requeryConfirmed) {
            rejectReason = "requery_unconfirmed";
        } else if (contradictionScore >= contradictionMax) {
            rejectReason = "contradiction_risk";
        } else if (contextContaminationScore >= contextContaminationMax) {
            rejectReason = "context_contamination";
        } else if (rejectReasons != null && !rejectReasons.isEmpty()) {
            rejectReason = firstRejectReason(rejectReasons, "validation_rejected");
        }
        boolean promoted = rejectReason.isBlank();
        return new LearningSampleValidationMetadata.NeedleRoi(
                true,
                signalValueScore,
                promoted,
                promoted ? "accepted" : rejectReason);
    }

    private static double needleSignalValueScore(double laneCoverage) {
        int docs = (int) Math.max(0, Math.round(readDouble("needle.docs.count", 0.0d)));
        int urls = (int) Math.max(0, Math.round(readDouble("needle.urls.count", 0.0d)));
        double authority = readDouble("needle.quality.authorityAvg", 0.0d);
        double coverage = readDouble("needle.quality.coverage", 0.0d);
        double duplicate = readDouble("needle.quality.duplicateRatio", 1.0d);
        double docsNorm = clamp01(docs / 4.0d);
        double urlsNorm = clamp01(urls / 3.0d);
        return round4(clamp01(
                (0.22d * authority)
                        + (0.20d * coverage)
                        + (0.18d * (1.0d - duplicate))
                        + (0.14d * docsNorm)
                        + (0.11d * urlsNorm)
                        + (0.15d * laneCoverage)));
    }

    private static void addNeedleRoiRejectReason(List<String> rejectReasons, String roiReason) {
        if (rejectReasons == null) {
            return;
        }
        String reason = switch (roiReason == null ? "" : roiReason) {
            case "signal_below_threshold" -> "needle_roi_below_threshold";
            case "requery_unconfirmed" -> "needle_roi_requery_unconfirmed";
            default -> roiReason != null && roiReason.startsWith("runtime_failure_")
                    ? "needle_roi_runtime_failure"
                    : "needle_roi_rejected";
        };
        if (!rejectReasons.contains(reason)) {
            rejectReasons.add(reason);
        }
    }

    private static String runtimeFailureReason(String disabledReason, int evidenceCount, int afterFilterCount) {
        if (disabledReason != null && !disabledReason.isBlank()) {
            return "provider_disabled";
        }
        if (evidenceCount > 0 && afterFilterCount == 0) {
            return "after_filter_starvation";
        }
        String fromAttempts = runtimeFailureFromObject(TraceStore.get("selfask.requery.attempts"));
        if (!fromAttempts.isBlank()) {
            return fromAttempts;
        }
        return firstRuntimeFailureLabel(
                TraceStore.get("web.search.failureClass"),
                TraceStore.get("web.failureClass"),
                TraceStore.get("web.naver.failureClass"),
                TraceStore.get("web.brave.failureClass"),
                TraceStore.get("web.serpapi.failureClass"));
    }

    private static String runtimeFailureFromObject(Object raw) {
        if (raw instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                String value = runtimeFailureFromObject(item);
                if (!value.isBlank()) {
                    return value;
                }
            }
            return "";
        }
        if (raw instanceof Map<?, ?> map) {
            return firstRuntimeFailureLabel(
                    map.get("failureClass"),
                    map.get("failure"),
                    map.get("reason"));
        }
        return firstRuntimeFailureLabel(raw);
    }

    private static String firstRuntimeFailureLabel(Object... values) {
        if (values == null) {
            return "";
        }
        for (Object value : values) {
            String label = runtimeFailureLabel(value);
            if (!label.isBlank()) {
                return label;
            }
        }
        return "";
    }

    private static String runtimeFailureLabel(Object raw) {
        if (raw == null) {
            return "";
        }
        String v = String.valueOf(raw).trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if (v.isBlank() || "none".equals(v) || "ok".equals(v)) {
            return "";
        }
        if (v.contains("timeout")) {
            return "timeout";
        }
        if (v.contains("rate-limit") || v.contains("ratelimit") || v.contains("429")) {
            return "rate_limit";
        }
        if (v.contains("provider-disabled") || v.contains("missing-key") || v.contains("unauthorized")) {
            return "provider_disabled";
        }
        if (v.contains("after-filter") || v.contains("starvation")) {
            return "after_filter_starvation";
        }
        return "";
    }

    private static boolean containsReason(List<String> rejectReasons, String expected) {
        if (rejectReasons == null || expected == null) {
            return false;
        }
        for (String reason : rejectReasons) {
            if (expected.equals(reason)) {
                return true;
            }
        }
        return false;
    }

    private static String firstRejectReason(List<String> rejectReasons, String fallback) {
        if (rejectReasons == null) {
            return fallback;
        }
        for (String reason : rejectReasons) {
            if (reason != null && !reason.isBlank()) {
                return reason.trim();
            }
        }
        return fallback;
    }

    private static List<String> evaluationCriteria(String questionType, boolean requeryRequired) {
        List<String> out = new ArrayList<>();
        switch (questionType) {
            case "factual_entity", "factual" -> {
                out.add("citation_or_entity_match");
                out.add("answer_supported_by_evidence");
            }
            case "causal" -> {
                out.add("cause_effect_support");
                out.add("alternative_cause_checked");
            }
            case "comparison" -> {
                out.add("both_sides_covered");
                out.add("comparison_axis_explicit");
            }
            case "recency" -> {
                out.add("freshness_or_source_date_checked");
                out.add("official_or_primary_source_preferred");
            }
            default -> {
                out.add("bq_er_rc_lane_coverage");
                out.add("long_tail_context_supported");
            }
        }
        if (requeryRequired) {
            out.add("requery_confirmation_required");
        }
        return List.copyOf(out);
    }

    private static List<String> selfAskLanes() {
        Object events = TraceStore.get("selfask.3way.events");
        LinkedHashSet<String> lanes = new LinkedHashSet<>();
        if (events instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                String lane = laneFromEvent(item);
                if (!lane.isBlank()) {
                    lanes.add(lane);
                }
            }
        } else {
            String lane = laneFromEvent(events);
            if (!lane.isBlank()) {
                lanes.add(lane);
            }
        }
        return List.copyOf(lanes);
    }

    private static String laneFromEvent(Object event) {
        if (event instanceof Map<?, ?> map) {
            Object lane = map.get("lane");
            return lane == null ? "" : String.valueOf(lane).trim().toUpperCase(Locale.ROOT);
        }
        if (event == null) {
            return "";
        }
        String text = String.valueOf(event).toUpperCase(Locale.ROOT);
        for (String lane : List.of("BQ", "ER", "RC")) {
            if (text.contains(lane)) {
                return lane;
            }
        }
        return "";
    }

    private static void trace(LearningSampleValidationMetadata meta) {
        try {
            TraceStore.put("learning.validation.questionType", meta.questionType());
            TraceStore.put("learning.validation.selfAskLaneCoverage", meta.selfAskLaneCoverage());
            TraceStore.put("learning.validation.refutabilityScore", meta.refutabilityScore());
            TraceStore.put("learning.validation.riskScore", meta.riskScore());
            TraceStore.put("learning.validation.causalNeedScore", meta.causalNeedScore());
            TraceStore.put("learning.validation.evidenceCount", meta.runtime().evidenceCount());
            TraceStore.put("learning.validation.afterFilterCount", meta.runtime().afterFilterCount());
            TraceStore.put("learning.validation.contradictionScore", meta.contradictionScore());
            TraceStore.put("learning.validation.contradictionCause", meta.contradictionCause());
            TraceStore.put("selfask.3way.requery.required", meta.requery().required());
            TraceStore.put("selfask.3way.requery.confirmed", meta.requery().confirmed());
            TraceStore.put("learning.validation.requeryRequired", meta.requery().required());
            TraceStore.put("learning.validation.requeryConfirmed", meta.requery().confirmed());
            TraceStore.put("learning.validation.contaminationScore", meta.contaminationScore());
            TraceStore.put("learning.validation.legacyContextScore", meta.legacyContextScore());
            TraceStore.put("learning.validation.sampleScore", meta.sampleScore());
            TraceStore.put("learning.validation.decision", meta.accepted() ? "accepted" : "rejected");
            TraceStore.put("learning.validation.rejectReasons", meta.rejectReasons());
            TraceStore.put("learning.validation.contextContaminationScore", meta.contextContaminationScore());
            TraceStore.put("learning.roi.needleSignalCandidate", meta.needleRoi().needleSignalCandidate());
            TraceStore.put("learning.roi.signalValueScore", meta.needleRoi().signalValueScore());
            TraceStore.put("learning.roi.promoted", meta.needleRoi().promoted());
            TraceStore.put("learning.roi.rejectReason", meta.needleRoi().rejectReason());
            TraceStore.put("learning.threshold.sampleScoreMin", meta.thresholds().sampleScoreMin());
            TraceStore.put("learning.threshold.contaminationMax", meta.thresholds().contaminationMax());
            TraceStore.put("learning.threshold.contextContaminationMax", meta.thresholds().contextContaminationMax());
            TraceStore.put("learning.threshold.contradictionMax", meta.thresholds().contradictionMax());
            TraceStore.put("learning.threshold.requeryPenalty", meta.thresholds().requeryPenalty());
            TraceStore.put("learning.validation.thresholdBreaks", thresholdBreaks(meta));
            TraceStore.put("learning.validation.contaminationSignals", contaminationSignals(meta));
        } catch (Exception ignore) {
            LOG.log(System.Logger.Level.DEBUG,
                    "[AWX][uaw][learning] validation trace emission skipped");
        }
    }

    private static List<Map<String, Object>> thresholdBreaks(LearningSampleValidationMetadata meta) {
        if (meta == null) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        addMinBreak(out, "sample_score_below_threshold", "sampleScore",
                meta.sampleScore(), meta.thresholds().sampleScoreMin(), "autolearn.validation");
        addMaxBreak(out, "contamination_risk", "contaminationScore",
                meta.contaminationScore(), meta.thresholds().contaminationMax(), "autolearn.validation");
        addMaxBreak(out, "context_contamination_threshold", "contextContaminationScore",
                meta.contextContaminationScore(), meta.thresholds().contextContaminationMax(), "autolearn.validation");
        addMaxBreak(out, "legacy_context_risk", "legacyContextScore",
                meta.legacyContextScore(), MAX_LEGACY_CONTEXT_SCORE, "autolearn.validation");
        addMaxBreakInclusive(out, "contradiction_risk", "contradictionScore",
                meta.contradictionScore(), meta.thresholds().contradictionMax(), "evidence_gate");
        if (meta.requery().required() && !meta.requery().confirmed()) {
            out.add(thresholdBreak(
                    meta.rejectReasons().contains("unconfirmed_history_contamination_requery")
                            ? "unconfirmed_history_contamination_requery"
                            : "unconfirmed_high_risk_requery",
                    "requeryConfirmed",
                    0.0d,
                    1.0d,
                    "==",
                    Math.max(0.01d, meta.thresholds().requeryPenalty()),
                    "autolearn.requery"));
        }
        if (meta.rejectReasons().contains("provider_disabled")) {
            out.add(thresholdBreak("provider_disabled", "providerDisabled", 1.0d,
                    0.0d, "<=", 1.0d, "provider"));
        }
        if (meta.rejectReasons().contains("after_filter_starvation")) {
            out.add(thresholdBreak("after_filter_starvation", "afterFilterCount", 0.0d,
                    1.0d, ">=", Math.max(1.0d, meta.runtime().evidenceCount()), "evidence_gate"));
        }
        if (meta.needleRoi().needleSignalCandidate() && !meta.needleRoi().promoted()) {
            out.add(thresholdBreak(meta.needleRoi().rejectReason(),
                    "needleRoi.signalValueScore",
                    meta.needleRoi().signalValueScore(),
                    meta.thresholds().sampleScoreMin(),
                    ">=",
                    Math.max(0.01d, meta.thresholds().sampleScoreMin() - meta.needleRoi().signalValueScore()),
                    "needle_roi"));
        }
        return List.copyOf(out);
    }

    private static List<String> contaminationSignals(LearningSampleValidationMetadata meta) {
        if (meta == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        if (meta.contaminationScore() >= meta.thresholds().contaminationMax()) {
            out.add("raw_trace_or_secret_like_text");
        }
        if (meta.legacyContextScore() > MAX_LEGACY_CONTEXT_SCORE) {
            out.add("legacy_source_root");
        }
        if (meta.runtime().contextDiversity() < 0.30d) {
            out.add("low_diversity");
        }
        if (meta.contextContaminationScore() > meta.thresholds().contextContaminationMax()
                || meta.rejectReasons().contains("unconfirmed_history_contamination_requery")) {
            out.add("history_context_contamination");
        }
        if (meta.rejectReasons().contains("provider_disabled")) {
            out.add("provider_disabled_fallback");
        }
        if (meta.rejectReasons().contains("after_filter_starvation")) {
            out.add("after_filter_starvation");
        }
        if (meta.rejectReasons().contains("contradiction_risk")) {
            out.add("evidence_conflict");
        }
        if ("QUARANTINE".equalsIgnoreCase(meta.feedback().vectorDecision())) {
            out.add("quarantine_vector_decision");
        }
        if (meta.needleRoi().needleSignalCandidate() && !meta.needleRoi().promoted()) {
            out.add("needle_roi_not_promoted");
        }
        return out.stream().distinct().toList();
    }

    private static void addMinBreak(List<Map<String, Object>> out,
                                    String label,
                                    String metric,
                                    double value,
                                    double threshold,
                                    String stage) {
        if (value < threshold) {
            out.add(thresholdBreak(label, metric, value, threshold, ">=", threshold - value, stage));
        }
    }

    private static void addMaxBreak(List<Map<String, Object>> out,
                                    String label,
                                    String metric,
                                    double value,
                                    double threshold,
                                    String stage) {
        if (value > threshold) {
            out.add(thresholdBreak(label, metric, value, threshold, "<=", value - threshold, stage));
        }
    }

    private static void addMaxBreakInclusive(List<Map<String, Object>> out,
                                             String label,
                                             String metric,
                                             double value,
                                             double threshold,
                                             String stage) {
        if (value >= threshold) {
            out.add(thresholdBreak(label, metric, value, threshold, "<=", value - threshold, stage));
        }
    }

    private static Map<String, Object> thresholdBreak(String label,
                                                      String metric,
                                                      double value,
                                                      double threshold,
                                                      String comparator,
                                                      double severity,
                                                      String stage) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("label", label);
        row.put("metric", metric);
        row.put("value", round4(value));
        row.put("threshold", round4(threshold));
        row.put("comparator", comparator);
        row.put("severity", round4(Math.max(0.0d, severity)));
        row.put("stage", stage);
        return row;
    }

    private static double round4(double value) {
        if (!Double.isFinite(value)) {
            return 0.0d;
        }
        return Math.round(value * 10_000.0d) / 10_000.0d;
    }

    private static boolean containsAny(String text, String... needles) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (String n : needles) {
            if (n != null && !n.isBlank() && text.contains(n.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String lower(String value) {
        return safe(value).toLowerCase(Locale.ROOT);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static double readDouble(String key, double def) {
        Object value = TraceStore.get(key);
        if (value instanceof Number n) {
            double parsed = n.doubleValue();
            if (Double.isFinite(parsed)) {
                return parsed;
            }
            traceReadDoubleSuppressed();
            return def;
        }
        if (value instanceof String s) {
            try {
                double parsed = Double.parseDouble(s.trim());
                if (Double.isFinite(parsed)) {
                    return parsed;
                }
                traceReadDoubleSuppressed();
                return def;
            } catch (NumberFormatException ignore) {
                traceReadDoubleSuppressed();
                return def;
            }
        }
        return def;
    }

    private static void traceReadDoubleSuppressed() {
        TraceStore.put("learning.validation.suppressed.stage", "readDouble");
        TraceStore.put("learning.validation.suppressed.errorType", "invalid_number");
        TraceStore.put("learning.validation.suppressed.readDouble", true);
        TraceStore.put("learning.validation.suppressed.readDouble.errorType", "invalid_number");
    }

    private static double contradictionScore() {
        return clamp01(maxTraceDouble(
                "overdrive.contradiction.mean",
                "extremez.risk.contradictionScore",
                "extremez.risk.contradictionMean",
                "rag.contradiction.score"));
    }

    private static double maxTraceDouble(String... keys) {
        double best = 0.0d;
        if (keys == null) {
            return best;
        }
        for (String key : keys) {
            best = Math.max(best, readDouble(key, 0.0d));
        }
        return best;
    }

    private static String contradictionCause(double contradictionScore, double contradictionThreshold) {
        String cause = firstTraceString(
                "extremez.risk.primaryCause",
                "extremez.activation.reason",
                "overdrive.reason",
                "learning.error.hotspot");
        if ((cause == null || cause.isBlank()) && contradictionScore >= contradictionThreshold) {
            return "evidence_conflict";
        }
        return safeCause(cause);
    }

    private static String firstTraceString(String... keys) {
        if (keys == null) {
            return "";
        }
        for (String key : keys) {
            Object value = TraceStore.get(key);
            if (value != null) {
                String s = String.valueOf(value).trim();
                if (!s.isBlank()) {
                    return s;
                }
            }
        }
        return "";
    }

    private static String safeCause(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        String s = value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.:-]+", "_");
        if (s.isBlank()) {
            return "unknown";
        }
        if (s.contains("provider") || s.contains("disabled")) {
            return "provider_disabled";
        }
        if (s.contains("contradiction") || s.contains("conflict") || s.contains("evidence")) {
            return "evidence_conflict";
        }
        if (s.contains("starvation") || s.contains("after_filter") || s.contains("thin")) {
            return "evidence_starvation";
        }
        if (s.contains("retrieval") || s.contains("timeout") || s.contains("rate_limit") || s.contains("error_rate")) {
            return "retrieval_degraded";
        }
        if (s.contains("modelrequired") || s.contains("model_required") || s.contains("qtx.llm")) {
            return "model_required";
        }
        if (s.contains("final_gate")) {
            return "final_gate_failed";
        }
        if (s.contains("contamination") || s.contains("legacy_context")) {
            return "context_contamination";
        }
        if (s.contains("sample_score") || s.contains("threshold")) {
            return "threshold";
        }
        if (s.contains("requery")) {
            return "requery";
        }
        if (s.contains("writer") || s.contains("ingest")) {
            return "writer";
        }
        if (s.contains("validation")) {
            return "validation";
        }
        if ("unknown".equals(s) || "none".equals(s)) {
            return "unknown";
        }
        return "other";
    }

    private static boolean readBoolean(String key, boolean def) {
        Object value = TraceStore.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value == null) {
            return def;
        }
        String s = String.valueOf(value).trim();
        if (s.isBlank()) {
            return def;
        }
        if ("true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s)) {
            return true;
        }
        if ("false".equalsIgnoreCase(s) || "0".equals(s) || "no".equalsIgnoreCase(s)) {
            return false;
        }
        return def;
    }

    private static double clamp01(double value) {
        return LearningSampleValidationMetadata.clamp01(value);
    }

    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    private double contradictionThreshold(double tuningDelta) {
        UawAutolearnProperties.Validation validation = validationProps();
        double base = clamp01(validation.getContradictionThreshold());
        double min = clamp01(validation.getContradictionThresholdMin());
        double max = clamp01(validation.getContradictionThresholdMax());
        double threshold = validation.isDynamicThresholdEnabled() ? base - tuningDelta : base;
        return clamp(threshold, min, max);
    }

    private UawAutolearnProperties.Validation validationProps() {
        UawAutolearnProperties.Validation validation = props.getValidation();
        return validation == null ? new UawAutolearnProperties.Validation() : validation;
    }

    private static String vectorDecision(List<String> rejectReasons) {
        return rejectReasons == null || rejectReasons.isEmpty() ? "SHADOW_REVIEW" : "QUARANTINE";
    }
}
