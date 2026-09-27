package com.nova.protocol.fusion;

import com.example.lms.search.TraceStore;
import java.util.ArrayList;
import java.util.List;

public class CvarAggregator {

    public double cvar(double[] scores, double tailFraction) {
        double result = upperTailMean(scores, tailFraction);
        traceCvar(tailFraction, result);
        return result;
    }

    public double cvarAtQuantile(List<Double> scores, double quantile) {
        double result = upperTailMeanAtQuantile(scores, quantile);
        TraceStore.put("hypernova.cvarQuantile", round6(clamp(quantile, 0.0d, 1.0d)));
        TraceStore.put("hypernova.cvarValue", round6(result));
        return result;
    }

    public static double upperTailMean(double[] scores, double tailFraction) {
        if (scores == null || scores.length == 0) {
            return 0.0d;
        }
        List<Double> values = new ArrayList<>(scores.length);
        for (double score : scores) {
            values.add(score);
        }
        return upperTailMean(values, tailFraction);
    }

    public static double upperTailMean(List<Double> scores, double tailFraction) {
        List<Double> sorted = sortedFinite(scores);
        if (sorted.isEmpty()) {
            return 0.0d;
        }
        int count = tailCount(sorted.size(), tailFraction);
        double sum = 0.0d;
        for (int i = sorted.size() - count; i < sorted.size(); i++) {
            sum += sorted.get(i);
        }
        return sum / count;
    }

    public static double upperTailMeanAtQuantile(List<Double> scores, double quantile) {
        List<Double> sorted = sortedFinite(scores);
        if (sorted.isEmpty()) {
            return 0.0d;
        }
        double cut = percentileSorted(sorted, quantile);
        double sum = 0.0d;
        int count = 0;
        for (double score : sorted) {
            if (score >= cut) {
                sum += score;
                count++;
            }
        }
        return count == 0 ? sorted.get(sorted.size() - 1) : sum / count;
    }

    public static double lowerTailMean(List<Double> scores, double tailFraction) {
        List<Double> sorted = sortedClampedIncludingInvalidZero(scores);
        if (sorted.isEmpty()) {
            return 0.0d;
        }
        int count = tailCount(sorted.size(), tailFraction);
        double sum = 0.0d;
        for (int i = 0; i < count; i++) {
            sum += sorted.get(i);
        }
        return sum / count;
    }

    private static List<Double> sortedFinite(List<Double> scores) {
        if (scores == null || scores.isEmpty()) {
            return List.of();
        }
        List<Double> out = new ArrayList<>(scores.size());
        for (Double score : scores) {
            if (score != null && Double.isFinite(score)) {
                out.add(score);
            }
        }
        out.sort(Double::compareTo);
        return out;
    }

    private static List<Double> sortedClampedIncludingInvalidZero(List<Double> scores) {
        if (scores == null || scores.isEmpty()) {
            return List.of();
        }
        List<Double> out = new ArrayList<>(scores.size());
        for (Double score : scores) {
            out.add(clamp01(score == null || !Double.isFinite(score) ? 0.0d : score));
        }
        out.sort(Double::compareTo);
        return out;
    }

    private static int tailCount(int size, double tailFraction) {
        double fraction = clamp(tailFraction, 0.0d, 1.0d);
        return Math.max(1, Math.min(size, (int) Math.ceil(fraction * size)));
    }

    private static double percentileSorted(List<Double> sorted, double q) {
        if (sorted.size() == 1) {
            return sorted.get(0);
        }
        double pos = clamp(q, 0.0d, 1.0d) * (sorted.size() - 1);
        int lo = (int) Math.floor(pos);
        int hi = (int) Math.ceil(pos);
        if (lo == hi) {
            return sorted.get(lo);
        }
        double t = pos - lo;
        return sorted.get(lo) * (1.0d - t) + sorted.get(hi) * t;
    }

    private static double clamp01(double value) {
        return clamp(value, 0.0d, 1.0d);
    }

    private static double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static void traceCvar(double tailFraction, double value) {
        TraceStore.put("hypernova.cvarAlpha", round6(clamp(tailFraction, 0.0d, 1.0d)));
        TraceStore.put("hypernova.cvarValue", round6(value));
    }

    private static double round6(double value) {
        if (!Double.isFinite(value)) {
            return 0.0d;
        }
        return Math.round(value * 1_000_000.0d) / 1_000_000.0d;
    }
}
