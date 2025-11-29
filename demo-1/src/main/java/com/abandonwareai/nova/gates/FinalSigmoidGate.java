package com.abandonwareai.nova.gates;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Placeholder calibration gate. Scores answer based on length and presence of citations.
 */
@Component
public class FinalSigmoidGate {
    private final double minScore;

    public FinalSigmoidGate(@Value("${idle.gates.minSigmoid:0.50}") double minScore) {
        this.minScore = minScore;
    }

    public double getMinScore() {
        return minScore;
    }

    public double score(String answer) {
        if (answer == null || answer.isBlank()) return 0.0;
        double base = Math.tanh(answer.length() / 200.0);
        double bonus = answer.contains("[1]") || answer.contains("[2]") || answer.contains("[3]") ? 0.2 : 0.0;
        return Math.max(0.0, Math.min(1.0, base + bonus));
    }
}
