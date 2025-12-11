package com.abandonware.ai.normalization.service.rag.fusion.score;

import java.util.*;

/** Simple per-source score calibrator with Platt scaling (logistic). */
public class ScoreCalibrator {
    public enum Mode { PLATT, ISOTONIC }
    private final Mode mode;
    private final Map<String, double[]> plattParams = new HashMap<>(); // source -> [A,B]
    public ScoreCalibrator(Mode mode) { this.mode = mode; }
    public void setPlatt(String source, double A, double B) { plattParams.put(source, new double[]{A,B}); }
    public double calibrate(String source, double raw) {
        if (mode == Mode.PLATT) {
            double[] p = plattParams.getOrDefault(source, new double[]{1.0, 0.0});
            double z = p[0]*raw + p[1];
            return 1.0/(1.0+Math.exp(-z));
        } else {
            // Isotonic: backoff to identity, actual model updated offline
            return raw;
        }
    }
}