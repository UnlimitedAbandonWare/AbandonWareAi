package com.example.lms.artplate.sse;

public record SseScoreCard(
        double previousScore,
        double currentScore,
        int consecutivePenalty,
        int iteration) {

    public double delta() {
        return currentScore - previousScore;
    }

    public boolean isHighReward(double threshold) {
        return delta() > threshold;
    }

    public boolean isLowPenalty(double threshold) {
        return delta() < threshold;
    }
}
