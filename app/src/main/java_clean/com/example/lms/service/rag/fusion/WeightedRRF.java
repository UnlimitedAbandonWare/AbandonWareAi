package com.example.lms.service.rag.fusion;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class WeightedRRF {
    private ScoreCalibrator calibrator = ScoreCalibrator.identity();
    private RerankCanonicalizer canonicalizer = new RerankCanonicalizer();
    private final double k;
    private final Map<String, Double> sourceWeights = new HashMap<>();

    public WeightedRRF(double k) { this.k = k <= 0 ? 60.0 : k; }
    public void setSourceWeight(String source, double w) { sourceWeights.put(source, w); }
    public void setCalibrator(ScoreCalibrator c) { if (c != null) this.calibrator = c; }
    public void setCanonicalizer(RerankCanonicalizer c) { if (c != null) this.canonicalizer = c; }

    public double fuseScore(String source, int rank) {
        String key = canonicalizer.canonicalizeSource(source);
        double w = sourceWeights.getOrDefault(key, 1.0);
        double base = 1.0 / (k + Math.max(1, rank));
        return calibrator.apply(w * base);
    }

    // New: hybrid fusion using RRF + WPM + simple tail boost + delta projection (stub)
    public double fuse(List<Double> normalizedScores, int rank){
        double rrf = 1.0 / (k + Math.max(1, rank));
        double wpm = WeightedPowerMeanFuser.combine(normalizedScores, 2.0);
        double tail = CvarAggregator.tailMean(normalizedScores, 0.2);
        double delta = DeltaProjectionBooster.boost(wpm, tail);
        double sum = rrf + delta;
        return calibrator.apply(sum);
    }
}