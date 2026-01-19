package com.example.lms.support;

import com.example.lms.entity.TranslationMemory;
import java.util.concurrent.atomic.AtomicReference;




/**
 * A service that dynamically adjusts reward policy weights based on user feedback.
 */
public class WeightTuningService {
    private final AtomicReference<double[]> weights = new AtomicReference<>(new double[] {0.55, 0.30, 0.15});

    public void positiveFeedback(TranslationMemory mem) {
        // shim: nudge all weights slightly upwards.
        adjustWeights(0.01, 0.01, 0.01);
    }

    public void negativeFeedback(TranslationMemory mem) {
        // shim: nudge all weights slightly downwards.
        adjustWeights(-0.01, -0.01, -0.01);
    }

    public double[] getWeights() {
        double[] arr = weights.get();
        return new double[] { arr[0], arr[1], arr[2] };
    }

    private void adjustWeights(double dwSim, double dwHit, double dwRec) {
        weights.updateAndGet(current -> {
            double sim = clamp(current[0] + dwSim, 0.0, 1.0);
            double hit = clamp(current[1] + dwHit, 0.0, 1.0);
            double rec = clamp(current[2] + dwRec, 0.0, 1.0);
            return new double[] { sim, hit, rec };
        });
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}