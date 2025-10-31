
package com.example.moe;

import java.util.*;



/**
 * FeatureCollector: maps scattered metrics/signals to gate features r_j inputs.
 *
 * It accepts a Map<String, Object> per source where keys may vary ("authority", "baseWeight",
 * "novelty", "u", "correctionFactor", "F", "match", "m", "recentness", etc).
 * The collector normalizes them into a consistent Feature object.
 */
public class FeatureCollector {

    public static class Features {
        public double authority = 1.0;   // a_j > 0
        public double novelty = 0.5;     // u_j in [0,1]
        public double Fd = 1.0;          // correction factor > 0
        public double match = 0.0;       // m_j (any scalar relevance/alignment)
        public double[] extras = new double[0]; // optional scalars

        @Override public String toString() {
            return String.format(Locale.ROOT,
                "Features{a=%.3f, u=%.3f, Fd=%.3f, m=%.3f, extras=%s}",
                authority, novelty, Fd, match, Arrays.toString(extras));
        }
    }

    /**
     * Heuristic collector that searches for known aliases and clamps values
     * to safe ranges. This acts like "pseudocode" but is fully runnable.
     */
    public Features collect(Map<String, Object> srcMeta) {
        Features f = new Features();

        // --- Authority (aliases: authority, baseWeight, weight, a) ---
        f.authority = pickPositive(srcMeta, Arrays.asList("authority", "baseWeight", "weight", "a"), 1.0);

        // --- Novelty (aliases: novelty, u, noveltyFactor) ---
        double u = pickDouble(srcMeta, Arrays.asList("novelty", "u", "noveltyFactor"), 0.5);
        // If noveltyFactor in [0.5,1.0], map back to [0,1]
        if (u >= 0.5 && u <= 1.0) u = (u - 0.5) * 2.0;
        f.novelty = clamp(u, 0.0, 1.0);

        // --- Distance correction (aliases: correctionFactor, F, Fd) ---
        double Fd = pickPositive(srcMeta, Arrays.asList("correctionFactor", "F", "Fd"), 1.0);
        // safety clamp
        f.Fd = clamp(Fd, 1e-3, 1e3);

        // --- Matching score (aliases: match, m, alignmentScore) ---
        f.match = pickDouble(srcMeta, Arrays.asList("match", "m", "alignmentScore"), 0.0);

        // --- Extras (recentness, reliability, length, etc.) ---
        List<String> extraKeys = Arrays.asList(
            "recentness","recency","reliability","length","chunkCount","coverage","freshness"
        );
        ArrayList<Double> ex = new ArrayList<>();
        for (String k : extraKeys) if (srcMeta.containsKey(k)) {
            ex.add(asDouble(srcMeta.get(k)));
        }
        f.extras = ex.stream().mapToDouble(d -> d).toArray();
        return f;
    }

    private static double pickPositive(Map<String, Object> m, List<String> keys, double defv) {
        double v = defv;
        for (String k : keys) if (m.containsKey(k)) {
            v = asDouble(m.get(k));
            break;
        }
        if (v <= 0) v = defv;
        return v;
    }
    private static double pickDouble(Map<String, Object> m, List<String> keys, double defv) {
        for (String k : keys) if (m.containsKey(k)) return asDouble(m.get(k));
        return defv;
    }
    private static double asDouble(Object o) {
        if (o instanceof Number) return ((Number)o).doubleValue();
        try { return Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return 0.0; }
    }
    private static double clamp(double x, double lo, double hi) {
        return Math.max(lo, Math.min(hi, x));
    }
}