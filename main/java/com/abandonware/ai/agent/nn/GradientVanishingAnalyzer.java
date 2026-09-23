package com.abandonware.ai.agent.nn;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;



/**
 * Gradient vanishing risk estimator.
 *
 * p_vanish = sigmoid(alpha * (log10(threshold) - log10(norm + eps)) + beta)
 * where norm is L2 norm of the gradient tensor for a layer.
 */
public final class GradientVanishingAnalyzer {

    private final double threshold;
    private final double alpha;
    private final double beta;
    private final double eps;

    public record LayerHealth(String layer, double l2norm, double vanishProb, boolean flag) {}

    public GradientVanishingAnalyzer(double threshold, double alpha, double beta, double eps) {
        this.threshold = threshold;
        this.alpha = alpha;
        this.beta = beta;
        this.eps = eps;
    }

    private static double l2(double[] g) {
        if (g == null || g.length == 0) return 0.0;
        double s = 0.0;
        for (double v : g) s += v * v;
        return Math.sqrt(s);
    }

    private double vanishProb(double norm) {
        // sigmoid(alpha * (log10(threshold) - log10(norm + eps)) + beta)
        double z = alpha * (Math.log10(threshold) - Math.log10(norm + eps)) + beta;
        return 1.0 / (1.0 + Math.exp(-z));
    }

    /** Assess from raw gradient arrays; useful for batch diagnostics. */
    public List<LayerHealth> assessRaw(List<String> layers, List<double[]> grads) {
        if (layers == null || grads == null || layers.size() != grads.size()) {
            return Collections.emptyList();
        }
        List<LayerHealth> out = new ArrayList<>(layers.size());
        for (int i = 0; i < layers.size(); i++) {
            double n = l2(grads.get(i));
            double p = vanishProb(n);
            out.add(new LayerHealth(layers.get(i), n, p, p >= 0.5));
        }
        return out;
    }

    /** Assess when L2 norms are already precomputed. */
    public List<LayerHealth> assess(List<String> layers, List<Double> norms) {
        if (layers == null || norms == null || layers.size() != norms.size()) {
            return Collections.emptyList();
        }
        List<LayerHealth> out = new ArrayList<>(layers.size());
        for (int i = 0; i < layers.size(); i++) {
            double n = norms.get(i);
            double p = vanishProb(n);
            out.add(new LayerHealth(layers.get(i), n, p, p >= 0.5));
        }
        return out;
    }
}