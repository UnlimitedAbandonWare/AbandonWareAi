package com.abandonware.ai.agent.integrations.service.rag.fusion;

import java.util.List;
public final class ScoreCalibrator {
    public enum Method { PLATT, ISOTONIC }
    private final Method method;
    public ScoreCalibrator(Method method) { this.method = method; }
    public double calibrate(double raw) {
        double x = Math.max(0.0, Math.min(1.0, raw));
        if (method == Method.PLATT) {
            double a = 1.2, b = -0.1;
            double z = a * x + b;
            return Math.max(0.0, Math.min(1.0, z));
        } else {
            if (x < 0.3) return x * 0.8;
            if (x < 0.7) return 0.24 + (x-0.3) * 0.9;
            return 0.6 + (x-0.7) * 0.6;
        }
    }
    public void calibrateInPlace(List<Double> scores) {
        if (scores == null) return;
        for (int i=0;i<scores.size();i++) {
            Double d = scores.get(i);
            scores.set(i, calibrate(d == null ? 0.0 : d.doubleValue()));
        }
    }
}