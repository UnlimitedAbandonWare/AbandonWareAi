package com.example.lms.agent.health;

public record HealthWeights(double wp, double wr, double wc, double wy, double wk) {
    public static HealthWeights defaults() { return new HealthWeights(0.2,0.25,0.25,0.2,0.1); }
}