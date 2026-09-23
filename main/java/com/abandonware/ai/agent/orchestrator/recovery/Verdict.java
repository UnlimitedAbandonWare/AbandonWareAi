package com.abandonware.ai.agent.orchestrator.recovery;

import java.util.Map;

/**
 * Single judge decision emitted by CriticNode after a step finishes.
 */
public record Verdict(
        Decision decision,
        FailureClass failureClass,
        double confidence,
        String reason,
        Map<String, Object> signals) {

    public enum Decision {
        ACCEPT,
        RECOVER,
        ABORT
    }

    public Verdict {
        decision = decision == null ? Decision.RECOVER : decision;
        failureClass = failureClass == null ? FailureClass.UNKNOWN : failureClass;
        confidence = confidence(confidence);
        reason = reason == null ? "" : reason;
        signals = signals == null ? Map.of() : Map.copyOf(signals);
    }

    public static Verdict accept(double confidence, String reason) {
        return new Verdict(Decision.ACCEPT, FailureClass.NONE, confidence, reason, Map.of());
    }

    private static double confidence(double value) {
        if (Double.isNaN(value) || value == Double.NEGATIVE_INFINITY) {
            return 0.0d;
        }
        if (value == Double.POSITIVE_INFINITY) {
            return 1.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, value));
    }
}
