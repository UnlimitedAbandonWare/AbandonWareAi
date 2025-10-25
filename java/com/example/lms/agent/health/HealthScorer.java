package com.example.lms.agent.health;

public class HealthScorer {
    private static double sigmoid(double x){ return 1.0/(1.0+Math.exp(-x)); }
    public double score(HealthSignals z, HealthWeights w){
        double s = w.wp()*sigmoid(z.p()) + w.wr()*sigmoid(z.r())
                + w.wc()*sigmoid(z.c()) + w.wy()*sigmoid(z.y())
                + w.wk()*sigmoid(z.k());
        return Math.max(0.0, Math.min(1.0, s));
    }
}