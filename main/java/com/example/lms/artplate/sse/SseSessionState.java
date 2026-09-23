package com.example.lms.artplate.sse;

public record SseSessionState(
        int iteration,
        double lastScore,
        int consecutivePenalty,
        SsePhase phase,
        double directionSign) {

    public SseSessionState(int iteration, double lastScore, int consecutivePenalty, SsePhase phase) {
        this(iteration, lastScore, consecutivePenalty, phase, 1.0d);
    }

    public SseSessionState {
        iteration = Math.max(0, iteration);
        consecutivePenalty = Math.max(0, consecutivePenalty);
        phase = phase == null ? SsePhase.EXPLOIT : phase;
        directionSign = normalizeDirection(directionSign);
        if (!Double.isFinite(lastScore)) {
            lastScore = 0.0d;
        }
    }

    public static SseSessionState initial() {
        return new SseSessionState(0, 0.0d, 0, SsePhase.EXPLOIT, 1.0d);
    }

    private static double normalizeDirection(double value) {
        if (!Double.isFinite(value) || value == 0.0d) {
            return 1.0d;
        }
        return value < 0.0d ? -1.0d : 1.0d;
    }
}
