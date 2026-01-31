package com.abandonware.ai.addons.synthesis;


public class MoEGate {
    private final double mix;
    public MoEGate(double mix) { this.mix = Math.max(0, Math.min(1, mix)); }
    public double mix(double heuristic, double dynamic) {
        return (1.0 - mix) * heuristic + mix * dynamic;
    }
}