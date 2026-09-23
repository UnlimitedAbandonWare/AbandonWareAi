package com.abandonware.ai.addons.synthesis;


public class MoEGate {
    private final double mix;
    public MoEGate(double mix) { this.mix = probability(mix); }

    public double mix(double heuristic, double dynamic) {
        double heuristicScore = finiteOrZero(heuristic);
        double dynamicScore = finiteOrZero(dynamic);
        return (1.0 - mix) * heuristicScore + mix * dynamicScore;
    }

    private static double probability(double value) {
        if (!Double.isFinite(value)) {
            return 0.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private static double finiteOrZero(double value) {
        return Double.isFinite(value) ? value : 0.0d;
    }
}
