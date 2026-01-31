package com.example.lms.service.rag.fusion;

import java.util.ArrayList;
import java.util.List;

/**
 * A light‑weight normalizer implementing a simplified version of the
 * mixed power (MP) law.  The input scores are transformed into a
 * bounded [0,1] scale using z‑score normalisation followed by a
 * hyperbolic tangent clamp.  This reduces the influence of extreme
 * outliers while preserving relative ordering.  It is intended to be
 * used prior to combining heterogeneous scores in RRF fusion.
 */
public class MpLawNormalizer {
    /**
     * Normalise the given list of scores.  Null or empty lists return
     * an empty result.  Each output element lies in [0,1].
     *
     * @param xs raw scores
     * @return normalised scores
     */
    public List<Double> normalize(List<Double> xs) {
        if (xs == null || xs.isEmpty()) {
            return List.of();
        }
        double mean = xs.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double variance = xs.stream().mapToDouble(d -> (d - mean) * (d - mean)).average().orElse(1e-9);
        double sd = Math.sqrt(variance);
        List<Double> out = new ArrayList<>(xs.size());
        for (double v : xs) {
            double z = (v - mean) / (sd + 1e-9);
            double clamped = Math.tanh(z);
            out.add(0.5 + 0.5 * clamped);
        }
        return out;
    }
}