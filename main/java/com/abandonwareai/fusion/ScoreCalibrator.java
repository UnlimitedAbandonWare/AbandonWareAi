package com.abandonwareai.fusion;

import org.springframework.stereotype.Component;

/**
 * Stateless logistic calibrator as a safe fallback when no fitted model exists.
 * Maps arbitrary real-valued raw scores into (0,1) with optional temperature.
 */
@Component
public class ScoreCalibrator {
    private final double temperature = 1.0; // can be externalised later

    public double calibrate(double raw){
        double z = raw / Math.max(1e-9, temperature);
        double s = 1.0 / (1.0 + Math.exp(-z));
        // keep away from exact 0/1 for numerical stability
        double eps = 1e-6;
        return Math.min(1.0 - eps, Math.max(eps, s));
    }
}