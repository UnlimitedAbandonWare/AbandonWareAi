package com.example.lms.uaw.autolearn;

import java.util.Collections;
import java.util.List;

/**
 * Deterministic validation metadata attached to one AutoLearn training sample.
 */
public record LearningSampleValidationMetadata(
        String questionType,
        List<String> selfAskLanes,
        double selfAskLaneCoverage,
        double refutabilityScore,
        double riskScore,
        double causalNeedScore,
        double contradictionScore,
        String contradictionCause,
        Requery requery,
        double contaminationScore,
        double legacyContextScore,
        double sampleScore,
        List<String> rejectReasons,
        List<String> evaluationCriteria,
        Thresholds thresholds,
        Runtime runtime,
        Anomalies anomalies,
        Feedback feedback,
        NeedleRoi needleRoi) {

    public LearningSampleValidationMetadata(
            String questionType,
            List<String> selfAskLanes,
            double selfAskLaneCoverage,
            double refutabilityScore,
            double riskScore,
            double causalNeedScore,
            Requery requery,
            double contaminationScore,
            double legacyContextScore,
            double sampleScore,
            List<String> rejectReasons,
            List<String> evaluationCriteria) {
        this(questionType,
                selfAskLanes,
                selfAskLaneCoverage,
                refutabilityScore,
                riskScore,
                causalNeedScore,
                0.0d,
                "unknown",
                requery,
                contaminationScore,
                legacyContextScore,
                sampleScore,
                rejectReasons,
                evaluationCriteria,
                Thresholds.defaults(),
                Runtime.defaults(),
                Anomalies.none(),
                Feedback.none(),
                NeedleRoi.none());
    }

    public LearningSampleValidationMetadata(
            String questionType,
            List<String> selfAskLanes,
            double selfAskLaneCoverage,
            double refutabilityScore,
            double riskScore,
            double causalNeedScore,
            Requery requery,
            double contaminationScore,
            double legacyContextScore,
            double sampleScore,
            List<String> rejectReasons,
            List<String> evaluationCriteria,
            Thresholds thresholds,
            Runtime runtime,
            Anomalies anomalies,
            Feedback feedback) {
        this(questionType,
                selfAskLanes,
                selfAskLaneCoverage,
                refutabilityScore,
                riskScore,
                causalNeedScore,
                0.0d,
                "unknown",
                requery,
                contaminationScore,
                legacyContextScore,
                sampleScore,
                rejectReasons,
                evaluationCriteria,
                thresholds,
                runtime,
                anomalies,
                feedback,
                NeedleRoi.none());
    }

    public LearningSampleValidationMetadata(
            String questionType,
            List<String> selfAskLanes,
            double selfAskLaneCoverage,
            double refutabilityScore,
            double riskScore,
            double causalNeedScore,
            Requery requery,
            double contaminationScore,
            double legacyContextScore,
            double sampleScore,
            List<String> rejectReasons,
            List<String> evaluationCriteria,
            Thresholds thresholds,
            Runtime runtime,
            Anomalies anomalies,
            Feedback feedback,
            NeedleRoi needleRoi) {
        this(questionType,
                selfAskLanes,
                selfAskLaneCoverage,
                refutabilityScore,
                riskScore,
                causalNeedScore,
                0.0d,
                "unknown",
                requery,
                contaminationScore,
                legacyContextScore,
                sampleScore,
                rejectReasons,
                evaluationCriteria,
                thresholds,
                runtime,
                anomalies,
                feedback,
                needleRoi);
    }

    public LearningSampleValidationMetadata(
            String questionType,
            List<String> selfAskLanes,
            double selfAskLaneCoverage,
            double refutabilityScore,
            double riskScore,
            double causalNeedScore,
            double contradictionScore,
            String contradictionCause,
            Requery requery,
            double contaminationScore,
            double legacyContextScore,
            double sampleScore,
            List<String> rejectReasons,
            List<String> evaluationCriteria,
            Thresholds thresholds,
            Runtime runtime,
            Anomalies anomalies,
            Feedback feedback) {
        this(questionType,
                selfAskLanes,
                selfAskLaneCoverage,
                refutabilityScore,
                riskScore,
                causalNeedScore,
                contradictionScore,
                contradictionCause,
                requery,
                contaminationScore,
                legacyContextScore,
                sampleScore,
                rejectReasons,
                evaluationCriteria,
                thresholds,
                runtime,
                anomalies,
                feedback,
                NeedleRoi.none());
    }

    public LearningSampleValidationMetadata {
        questionType = normalize(questionType, "unknown");
        selfAskLanes = selfAskLanes == null ? Collections.emptyList() : List.copyOf(selfAskLanes);
        selfAskLaneCoverage = clamp01(selfAskLaneCoverage);
        refutabilityScore = clamp01(refutabilityScore);
        riskScore = clamp01(riskScore);
        causalNeedScore = clamp01(causalNeedScore);
        contradictionScore = clamp01(contradictionScore);
        contradictionCause = normalizeCause(contradictionCause);
        requery = requery == null ? new Requery(false, false) : requery;
        contaminationScore = clamp01(contaminationScore);
        legacyContextScore = clamp01(legacyContextScore);
        sampleScore = clamp01(sampleScore);
        rejectReasons = rejectReasons == null ? Collections.emptyList() : List.copyOf(rejectReasons);
        evaluationCriteria = evaluationCriteria == null ? Collections.emptyList() : List.copyOf(evaluationCriteria);
        thresholds = thresholds == null ? Thresholds.defaults() : thresholds;
        runtime = runtime == null ? Runtime.defaults() : runtime;
        anomalies = anomalies == null ? Anomalies.none() : anomalies;
        feedback = feedback == null ? Feedback.none() : feedback;
        needleRoi = needleRoi == null ? NeedleRoi.none() : needleRoi;
    }

    public boolean accepted() {
        return rejectReasons.isEmpty()
                && (!needleRoi.needleSignalCandidate() || needleRoi.promoted());
    }

    public double contextContaminationScore() {
        return clamp01(Math.max(contaminationScore, legacyContextScore * 0.75d));
    }

    public LearningSampleValidationMetadata withRuntime(Runtime nextRuntime) {
        return new LearningSampleValidationMetadata(questionType, selfAskLanes, selfAskLaneCoverage,
                refutabilityScore, riskScore, causalNeedScore, contradictionScore, contradictionCause,
                requery, contaminationScore,
                legacyContextScore, sampleScore, rejectReasons, evaluationCriteria, thresholds,
                nextRuntime, anomalies, feedback, needleRoi);
    }

    public LearningSampleValidationMetadata withAnomalies(Anomalies nextAnomalies) {
        return new LearningSampleValidationMetadata(questionType, selfAskLanes, selfAskLaneCoverage,
                refutabilityScore, riskScore, causalNeedScore, contradictionScore, contradictionCause,
                requery, contaminationScore,
                legacyContextScore, sampleScore, rejectReasons, evaluationCriteria, thresholds,
                runtime, nextAnomalies, feedback, needleRoi);
    }

    public LearningSampleValidationMetadata withFeedback(Feedback nextFeedback) {
        return new LearningSampleValidationMetadata(questionType, selfAskLanes, selfAskLaneCoverage,
                refutabilityScore, riskScore, causalNeedScore, contradictionScore, contradictionCause,
                requery, contaminationScore,
                legacyContextScore, sampleScore, rejectReasons, evaluationCriteria, thresholds,
                runtime, anomalies, nextFeedback, needleRoi);
    }

    public LearningSampleValidationMetadata withNeedleRoi(NeedleRoi nextNeedleRoi) {
        return new LearningSampleValidationMetadata(questionType, selfAskLanes, selfAskLaneCoverage,
                refutabilityScore, riskScore, causalNeedScore, contradictionScore, contradictionCause,
                requery, contaminationScore,
                legacyContextScore, sampleScore, rejectReasons, evaluationCriteria, thresholds,
                runtime, anomalies, feedback, nextNeedleRoi);
    }

    public static LearningSampleValidationMetadata empty() {
        return new LearningSampleValidationMetadata(
                "unknown",
                List.of(),
                0.0d,
                0.0d,
                0.0d,
                0.0d,
                0.0d,
                "unknown",
                new Requery(false, false),
                0.0d,
                0.0d,
                1.0d,
                List.of(),
                List.of(),
                Thresholds.defaults(),
                Runtime.defaults(),
                Anomalies.none(),
                Feedback.none(),
                NeedleRoi.none());
    }

    public record Requery(boolean required, boolean confirmed) {
    }

    public record Thresholds(
            double sampleScoreMin,
            double contaminationMax,
            double contextContaminationMax,
            double contradictionMax,
            double requeryPenalty,
            String mode) {
        public Thresholds(
                double sampleScoreMin,
                double contaminationMax,
                double contextContaminationMax,
                double requeryPenalty,
                String mode) {
            this(sampleScoreMin, contaminationMax, contextContaminationMax, 0.60d, requeryPenalty, mode);
        }

        public Thresholds {
            sampleScoreMin = clamp01(sampleScoreMin);
            contaminationMax = clamp01(contaminationMax);
            contextContaminationMax = clamp01(contextContaminationMax);
            contradictionMax = clamp01(contradictionMax);
            requeryPenalty = clamp01(requeryPenalty);
            mode = normalize(mode, "static");
        }

        public static Thresholds defaults() {
            return new Thresholds(0.55d, 0.35d, 0.35d, 0.60d, 0.0d, "static");
        }
    }

    public record Runtime(
            int evidenceCount,
            int afterFilterCount,
            double contextDiversity,
            double laneCoverage,
            double errorRateWindow) {
        public Runtime {
            evidenceCount = Math.max(0, evidenceCount);
            afterFilterCount = Math.max(0, afterFilterCount);
            contextDiversity = clamp01(contextDiversity);
            laneCoverage = clamp01(laneCoverage);
            errorRateWindow = clamp01(errorRateWindow);
        }

        public static Runtime defaults() {
            return new Runtime(0, 0, 0.0d, 0.0d, 0.0d);
        }
    }

    public record Anomalies(List<String> flags, boolean spike, boolean drift) {
        public Anomalies {
            flags = flags == null ? Collections.emptyList() : List.copyOf(flags);
        }

        public static Anomalies none() {
            return new Anomalies(List.of(), false, false);
        }
    }

    public record Feedback(double cfvmReward, String vectorDecision) {
        public Feedback {
            cfvmReward = clamp01(cfvmReward);
            vectorDecision = normalize(vectorDecision, "SHADOW_REVIEW");
        }

        public static Feedback none() {
            return new Feedback(0.0d, "SHADOW_REVIEW");
        }
    }

    public record NeedleRoi(
            boolean needleSignalCandidate,
            double signalValueScore,
            boolean promoted,
            String rejectReason) {
        public NeedleRoi {
            signalValueScore = clamp01(signalValueScore);
            if (!needleSignalCandidate) {
                promoted = false;
            }
            rejectReason = normalizeRoiReason(rejectReason, promoted ? "accepted" : "not_candidate");
        }

        public static NeedleRoi none() {
            return new NeedleRoi(false, 0.0d, false, "not_candidate");
        }
    }

    static double clamp01(double value) {
        if (!Double.isFinite(value)) {
            return 0.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private static String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private static String normalizeCause(String value) {
        String v = normalize(value, "unknown").toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9_.:-]+", "_");
        if (v.isBlank()) {
            return "unknown";
        }
        if (v.contains("provider") || v.contains("disabled")) {
            return "provider_disabled";
        }
        if (v.contains("contradiction") || v.contains("conflict") || v.contains("evidence")) {
            return "evidence_conflict";
        }
        if (v.contains("starvation") || v.contains("after_filter") || v.contains("thin")) {
            return "evidence_starvation";
        }
        if (v.contains("retrieval") || v.contains("timeout") || v.contains("rate_limit") || v.contains("error_rate")) {
            return "retrieval_degraded";
        }
        if (v.contains("modelrequired") || v.contains("model_required") || v.contains("qtx.llm")) {
            return "model_required";
        }
        if (v.contains("final_gate")) {
            return "final_gate_failed";
        }
        if (v.contains("contamination") || v.contains("legacy_context")) {
            return "context_contamination";
        }
        if (v.contains("sample_score") || v.contains("threshold")) {
            return "threshold";
        }
        if (v.contains("requery")) {
            return "requery";
        }
        if (v.contains("writer") || v.contains("ingest")) {
            return "writer";
        }
        if (v.contains("validation")) {
            return "validation";
        }
        if ("unknown".equals(v) || "none".equals(v)) {
            return "unknown";
        }
        return "other";
    }

    private static String normalizeRoiReason(String value, String fallback) {
        String v = normalize(value, fallback).toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9_.:-]+", "_");
        if (v.isBlank()) {
            return fallback;
        }
        if (v.length() > 64) {
            return v.substring(0, 64);
        }
        return v;
    }
}
