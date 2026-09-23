package com.abandonware.ai.agent.integrations.guard;


/**
 * HealthScoreGate: 5D -> sigmoid -> threshold.
 */
public class HealthScoreGate {
    private final double threshold;
    public HealthScoreGate(double threshold){ this.threshold = threshold; }
    public boolean allow(double P,double R,double C,double Y,double K){
        double x = 0.2*(P+R+C+Y+K) - 2.0; // center baseline
        double s = 1.0 / (1.0 + Math.exp(-x));
        return s >= threshold;
    }
}