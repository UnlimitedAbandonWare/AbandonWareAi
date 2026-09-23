package com.example.lms.uaw.autolearn;

import java.util.Map;

/**
 * Result of a single UAW autolearn cycle.
 */
public record AutoLearnCycleResult(
        int attempted,
        int acceptedCount,
        boolean abortedByUser,
        String datasetPath,
        double errorRateWindow,
        double thresholdTuningDelta,
        boolean trainAllowed,
        String topProblem,
        String trainDecision,
        int errorCount,
        double errorRate,
        String dominantFailure,
        String diagnosis,
        Map<String, Integer> phaseFailures
) {
    public AutoLearnCycleResult(int attempted, int acceptedCount, boolean abortedByUser, String datasetPath) {
        this(attempted, acceptedCount, abortedByUser, datasetPath,
                0.0d, 0.0d, true, "none", "ALLOW_RETRAIN");
    }

    public AutoLearnCycleResult(int attempted,
            int acceptedCount,
            boolean abortedByUser,
            String datasetPath,
            double errorRateWindow,
            double thresholdTuningDelta,
            boolean trainAllowed,
            String topProblem,
            String trainDecision) {
        this(attempted,
                acceptedCount,
                abortedByUser,
                datasetPath,
                errorRateWindow,
                thresholdTuningDelta,
                trainAllowed,
                topProblem,
                trainDecision,
                Math.max(0, attempted - acceptedCount),
                attempted <= 0 ? 0.0d : clamp01(Math.max(0, attempted - acceptedCount) / (double) attempted),
                normalize(topProblem, "none"),
                diagnose(topProblem),
                Map.of());
    }

    public AutoLearnCycleResult {
        attempted = Math.max(0, attempted);
        acceptedCount = Math.max(0, acceptedCount);
        errorRateWindow = clamp01(errorRateWindow);
        thresholdTuningDelta = finiteOrZero(thresholdTuningDelta);
        topProblem = normalize(topProblem, "none");
        trainDecision = normalize(trainDecision, "UNKNOWN");
        errorCount = Math.max(0, errorCount);
        errorRate = clamp01(errorRate);
        dominantFailure = normalize(dominantFailure, "none");
        diagnosis = normalize(diagnosis, "");
        phaseFailures = phaseFailures == null ? Map.of() : Map.copyOf(phaseFailures);
    }

    private static String diagnose(String failure) {
        return switch (normalize(failure, "")) {
            case "chat_service_exception" -> "chat_service_or_guard_context_error";
            case "empty_result" -> "llm_answer_generation_empty";
            case "insufficient_evidence" -> "evidence_search_gap";
            case "low_context_diversity" -> "retrieval_context_collapse";
            case "provider_disabled" -> "provider_or_api_key_disabled";
            case "final_gate_failed" -> "evidence_final_gate_failed";
            case "sample_score_threshold", "sample_score_below_threshold" -> "sample_quality_below_dynamic_threshold";
            case "context_contamination_threshold", "contamination_risk" -> "context_contamination_guard_rejected";
            case "legacy_context_risk" -> "legacy_context_contamination_guard_rejected";
            case "requery_unconfirmed", "unconfirmed_high_risk_requery" -> "requery_confirmation_missing";
            case "validation_rejected" -> "sample_threshold_or_contamination_rejected";
            case "writer_failed" -> "dataset_writer_or_training_filter_rejected";
            case "user_preempted" -> "user_returned_preemption";
            case "", "none" -> "";
            default -> "autolearn_phase_failure:" + normalize(failure, "unknown");
        };
    }

    private static double clamp01(double value) {
        if (!Double.isFinite(value)) {
            return 0.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private static double finiteOrZero(double value) {
        return Double.isFinite(value) ? value : 0.0d;
    }

    private static String normalize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
