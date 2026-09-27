package com.example.lms.ensemble;

import com.example.lms.guard.FinalSigmoidGate;

/**
 * Immutable metadata for one ensemble sampling node output.
 */
public record SampledCandidate(
        String nodeId,
        String text,
        double temperature,
        double topP,
        double citationScore,
        double riskScore,
        FinalSigmoidGate.GateResult gateResult,
        HypothesisDirection hypothesisDirection,
        EvidenceStatus evidenceStatus,
        double evidenceRate,
        double sourceDiversity,
        double contradictionRate,
        double groundingScore) {

    public enum HypothesisDirection {
        LEGACY,
        SUPPORT,
        FALSIFY
    }

    public enum EvidenceStatus {
        UNKNOWN,
        SUFFICIENT,
        INSUFFICIENT_OR_CONTRADICTED
    }

    public SampledCandidate {
        hypothesisDirection = hypothesisDirection == null ? HypothesisDirection.LEGACY : hypothesisDirection;
        evidenceStatus = evidenceStatus == null ? EvidenceStatus.UNKNOWN : evidenceStatus;
        evidenceRate = clamp01(evidenceRate);
        sourceDiversity = clamp01(sourceDiversity);
        contradictionRate = clamp01(contradictionRate);
        groundingScore = clamp01(groundingScore);
    }

    /** Compatibility constructor for the legacy triad/judge rollback path. */
    public SampledCandidate(
            String nodeId,
            String text,
            double temperature,
            double topP,
            double citationScore,
            double riskScore,
            FinalSigmoidGate.GateResult gateResult) {
        this(
                nodeId,
                text,
                temperature,
                topP,
                citationScore,
                riskScore,
                gateResult,
                HypothesisDirection.LEGACY,
                EvidenceStatus.UNKNOWN,
                citationScore,
                0.0d,
                0.0d,
                citationScore);
    }

    public boolean dualHypothesis() {
        return hypothesisDirection == HypothesisDirection.SUPPORT
                || hypothesisDirection == HypothesisDirection.FALSIFY;
    }

    private static double clamp01(double value) {
        if (!Double.isFinite(value)) {
            return 0.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, value));
    }
}
