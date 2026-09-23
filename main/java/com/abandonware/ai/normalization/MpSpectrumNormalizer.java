package com.abandonware.ai.normalization;

import java.util.*;

/** MpSpectrumNormalizer - placeholder for MP-Law based score de-spiking.
 *  Downweights extreme scores (top quantiles) to reduce hallucination risk.
 */
public class MpSpectrumNormalizer {
    public List<Double> normalize(List<Double> scores) {
        if (scores == null || scores.isEmpty()) return scores;
        List<Double> s = new ArrayList<>(scores);
        Collections.sort(s);
        double q90 = s.get((int)Math.floor(0.90*(s.size()-1)));
        double q50 = s.get((int)Math.floor(0.50*(s.size()-1)));
        double q10 = s.get((int)Math.floor(0.10*(s.size()-1)));
        List<Double> out = new ArrayList<>(scores.size());
        for (double v : scores) {
            double nv = v;
            if (v > q90) nv = q90 + 0.3*(v - q90); // clamp spikes
            if (v < q10) nv = q10 + 0.3*(v - q10); // lift tails
            // scale to around median
            if (q90 != q10) nv = (nv - q10) / (q90 - q10);
            out.add(nv);
        }
        return out;
    }
}