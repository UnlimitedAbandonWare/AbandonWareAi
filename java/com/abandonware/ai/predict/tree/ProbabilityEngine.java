package com.abandonware.ai.predict.tree;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Deterministic probability scoring.
 *
 * <p>Given the same (seed, label, index) this will always return the same probability.
 */
public class ProbabilityEngine {

    /**
     * Legacy signature kept for compatibility.
     */
    public double score(Object evidence) {
        String s = evidence == null ? "" : evidence.toString();
        return score(s, "evidence", 0);
    }

    public double score(String seed, String label) {
        return score(seed, label, 0);
    }

    public double score(String seed, String label, int index) {
        String in = (seed == null ? "" : seed) + "|" + (label == null ? "" : label) + "|" + index;
        long h = sha256ToLong(in);
        long nonNeg = h >>> 1;
        double u = nonNeg / (double) Long.MAX_VALUE; // [0,1]
        double p = 0.05 + (u * 0.90);                // [0.05, 0.95]
        // stable rounding: keeps output readable + consistent
        return Math.round(p * 10_000.0) / 10_000.0;
    }

    private static long sha256ToLong(String in) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(in.getBytes(StandardCharsets.UTF_8));
            // take first 8 bytes
            long v = 0L;
            for (int i = 0; i < 8; i++) {
                v = (v << 8) | (d[i] & 0xFFL);
            }
            return v;
        } catch (Exception e) {
            // fallback: still deterministic
            return in.hashCode();
        }
    }
}
