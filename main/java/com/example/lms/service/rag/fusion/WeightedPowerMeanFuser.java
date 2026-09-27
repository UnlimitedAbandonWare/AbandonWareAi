package com.example.lms.service.rag.fusion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class WeightedPowerMeanFuser {

    @Value("${rag.fusion.weights.web:1.0}")
    private double webWeight = 1.0;

    @Value("${rag.fusion.weights.vector:0.8}")
    private double vectorWeight = 0.8;

    @Value("${rag.fusion.weights.memory:0.6}")
    private double memoryWeight = 0.6;

    public double fuse(List<Double> scores, double p, List<Double> weights) {
        if (scores == null || scores.isEmpty()) {
            return 0.0;
        }
        int n = scores.size();
        List<Double> localWeights = weights == null || weights.size() != n
                ? new ArrayList<>(Collections.nCopies(n, 1.0))
                : new ArrayList<>(weights);

        // Scale finite nonnegative scores before pow to preserve small magnitudes.
        boolean scalePositive = Double.isFinite(p) && p > 0.0;
        double scoreScale = 0.0;
        if (scalePositive) {
            for (int i = 0; i < n; i++) {
                double s = scores.get(i) == null ? 0.0 : scores.get(i);
                double w = localWeights.get(i) == null ? 1.0 : localWeights.get(i);
                if (!Double.isFinite(s) || s < 0.0 || !Double.isFinite(w) || w < 0.0) {
                    scalePositive = false;
                    break;
                }
                if (w > 0.0) scoreScale = Math.max(scoreScale, s);
            }
        }
        if (!scalePositive || scoreScale == 0.0) scoreScale = 1.0;

        double num = 0.0;
        double den = 0.0;
        for (int i = 0; i < n; i++) {
            double w = localWeights.get(i) == null ? 1.0 : localWeights.get(i);
            double s = scores.get(i) == null ? 0.0 : scores.get(i);
            if (scalePositive && w == 0.0) continue;
            den += w;
            num += p == 0.0 ? w * Math.log(Math.max(1e-9, s)) : w * Math.pow(s / scoreScale, p);
        }
        if (den == 0.0) {
            return 0.0;
        }
        return p == 0.0 ? Math.exp(num / den) : scoreScale * Math.pow(num / den, 1.0 / p);
    }

    public double fuseWithSourceKinds(List<Double> scores, double p, List<String> sourceKinds) {
        if (scores == null || scores.isEmpty()) {
            return 0.0;
        }
        List<Double> weights = new ArrayList<>(scores.size());
        if (sourceKinds == null || sourceKinds.size() != scores.size()) {
            for (int i = 0; i < scores.size(); i++) {
                weights.add(1.0);
            }
        } else {
            for (String sourceKind : sourceKinds) {
                weights.add(resolveWeight(sourceKind));
            }
        }
        return fuse(scores, p, weights);
    }

    public double getWebWeight() {
        return webWeight;
    }

    public double getVectorWeight() {
        return vectorWeight;
    }

    public double getMemoryWeight() {
        return memoryWeight;
    }

    private double resolveWeight(String kind) {
        if (kind == null) {
            return 1.0;
        }
        String normalized = kind.toLowerCase(Locale.ROOT);
        if (normalized.contains("web")) {
            return webWeight;
        }
        if (normalized.contains("vector") || normalized.contains("embed")) {
            return vectorWeight;
        }
        if (normalized.contains("mem")) {
            return memoryWeight;
        }
        return 1.0;
    }
}
