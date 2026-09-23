package com.example.lms.service.rag.fusion;

import com.example.lms.search.TraceStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Tail-weighted variant of {@link WeightedPowerMeanFuser}.
 *
 * <p>Lower-tail boosting makes weak evidence contribute more strongly to the
 * final fused score, which is useful for risk-aware HYPERNOVA-style routing
 * without replacing the existing power-mean implementation.
 *
 * <p><b>Design note:</b> this implementation exposes both modes explicitly:
 * lower-tail stabilization for weak-evidence risk and upper-tail sharpening for
 * high-confidence HYPERNOVA signals.
 */
@Component
public class TailWeightedPowerMeanFuser {

    private final WeightedPowerMeanFuser delegate;

    public TailWeightedPowerMeanFuser(WeightedPowerMeanFuser delegate) {
        this.delegate = delegate == null ? new WeightedPowerMeanFuser() : delegate;
    }

    public double fuseLowerTail(List<Double> scores,
                                double p,
                                List<Double> weights,
                                 double tailFraction,
                                 double tailBoost) {
        if (scores == null || scores.isEmpty()) {
            traceFusion(0, 0, 1.0d, 0.0d);
            return 0.0d;
        }
        int n = scores.size();
        List<Double> normalizedScores = new ArrayList<>(n);
        List<Double> adjustedWeights = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            normalizedScores.add(clamp01(valueAt(scores, i, 0.0d)));
            adjustedWeights.add(safeWeight(weights, i));
        }

        int tailCount = (int) Math.ceil(clampFinite(tailFraction, 0.0d, 1.0d) * n);
        tailCount = Math.max(1, Math.min(n, tailCount));
        double boost = clampFinite(tailBoost, 1.0d, 100.0d);

        List<Integer> byScore = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            byScore.add(i);
        }
        byScore.sort(Comparator.comparingDouble(normalizedScores::get));

        for (int rank = 0; rank < tailCount; rank++) {
            int index = byScore.get(rank);
            adjustedWeights.set(index, adjustedWeights.get(index) * boost);
        }
        double result = clamp01(delegate.fuse(normalizedScores, p, adjustedWeights));
        traceFusion(n, tailCount, boost, result);
        return result;
    }

    public double fuseUpperTail(List<Double> scores,
                                double p,
                                List<Double> weights,
                                double tailFraction,
                                double tailBoost) {
        if (scores == null || scores.isEmpty()) {
            traceUpperFusion(0, 0, 1.0d, p, 0.0d, 0.0d);
            return 0.0d;
        }
        int n = scores.size();
        List<Double> normalizedScores = new ArrayList<>(n);
        List<Double> adjustedWeights = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            normalizedScores.add(clamp01(valueAt(scores, i, 0.0d)));
            adjustedWeights.add(safeWeight(weights, i));
        }

        int tailCount = (int) Math.ceil(clampFinite(tailFraction, 0.0d, 1.0d) * n);
        tailCount = Math.max(1, Math.min(n, tailCount));
        double boost = clampFinite(tailBoost, 1.0d, 100.0d);

        List<Integer> byScore = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            byScore.add(i);
        }
        byScore.sort(Comparator.comparingDouble(normalizedScores::get).reversed());

        for (int rank = 0; rank < tailCount; rank++) {
            int index = byScore.get(rank);
            adjustedWeights.set(index, adjustedWeights.get(index) * boost);
        }
        double result = clamp01(delegate.fuse(normalizedScores, p, adjustedWeights));
        traceUpperFusion(n, tailCount, boost, p, clampFinite(tailFraction, 0.0d, 1.0d), result);
        return result;
    }

    private static void traceFusion(int inputCount, int tailCount, double tailBoost, double result) {
        TraceStore.put("rag.fusion.twpm.inputCount", Math.max(0, inputCount));
        TraceStore.put("rag.fusion.twpm.tailCount", Math.max(0, tailCount));
        TraceStore.put("rag.fusion.twpm.tailBoost", tailBoost);
        TraceStore.put("rag.fusion.twpm.result", result);
    }

    private static void traceUpperFusion(int inputCount, int tailCount, double tailBoost, double p, double tailFraction, double result) {
        TraceStore.put("rag.fusion.twpm.upper.inputCount", Math.max(0, inputCount));
        TraceStore.put("rag.fusion.twpm.upper.tailCount", Math.max(0, tailCount));
        TraceStore.put("rag.fusion.twpm.upper.tailBoost", tailBoost);
        TraceStore.put("rag.fusion.twpm.upper.p", p);
        TraceStore.put("rag.fusion.twpm.upper.result", result);
        TraceStore.put("hypernova.twpm.mode", "upper");
        TraceStore.put("hypernova.twpm.p", p);
        TraceStore.put("hypernova.twpmP", p);
        TraceStore.put("hypernova.twpmInputCount", Math.max(0, inputCount));
        TraceStore.put("hypernova.twpmResult", result);
        TraceStore.put("hypernova.twpm.tailFraction", tailFraction);
    }

    private static double safeWeight(List<Double> weights, int index) {
        if (weights == null || weights.size() <= index) {
            return 1.0d;
        }
        double value = valueAt(weights, index, 1.0d);
        return value <= 0.0d ? 1.0d : value;
    }

    private static double valueAt(List<Double> values, int index, double fallback) {
        Double value = values == null || values.size() <= index ? null : values.get(index);
        return value == null || !Double.isFinite(value) ? fallback : value;
    }

    private static double clampFinite(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp01(double value) {
        return Math.max(0.0d, Math.min(1.0d, value));
    }
}
