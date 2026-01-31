package com.abandonware.ai.agent.integrations.math;


/**
 * Bode clamp (placeholder; shape to be aligned with legacy).
 */
public class BodeClamp {
    public double clamp(double x, double w){
        double k = Math.max(1e-6, w);
        return x / (1.0 + Math.abs(x)/k);
    }
}