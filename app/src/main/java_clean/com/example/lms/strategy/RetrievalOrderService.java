package com.example.lms.strategy;

import java.util.EnumMap;
import java.util.Map;

/**
 * Risk-softmax allocator for per-source topK.
 * Keeps implementation dependency-free for clean builds.
 */
public class RetrievalOrderService {

    public enum Source { WEB, VECTOR, KG }

    /**
     * Allocate totalK across sources using a softmax over reliability.
     * Guarantees each source has at least 1 and the sum equals totalK.
     */
    public Map<Source, Integer> allocK(Map<Source, Double> reliability, int totalK, double temp) {
        EnumMap<Source, Integer> out = new EnumMap<>(Source.class);
        if (totalK <= 0) totalK = 6;
        temp = (temp <= 0.0) ? 0.7 : temp;

        if (reliability == null || reliability.isEmpty()) {
            out.put(Source.WEB, Math.max(2, totalK / 2));
            out.put(Source.VECTOR, Math.max(2, totalK / 3));
            out.put(Source.KG, Math.max(1, totalK - out.get(Source.WEB) - out.get(Source.VECTOR)));
            return out;
        }
        double Z = 0.0;
        for (double r : reliability.values()) Z += Math.exp(r / temp);
        int assigned = 0;
        for (Map.Entry<Source, Double> e : reliability.entrySet()) {
            int k = (int) Math.round(totalK * Math.exp(e.getValue() / temp) / Z);
            if (k < 1) k = 1;
            out.put(e.getKey(), k);
            assigned += k;
        }
        // adjust to match totalK exactly
        while (assigned > totalK) {
            Source s = out.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();
            out.put(s, out.get(s) - 1);
            assigned--;
        }
        while (assigned < totalK) {
            Source s = out.entrySet().stream().min(Map.Entry.comparingByValue()).get().getKey();
            out.put(s, out.get(s) + 1);
            assigned++;
        }
        return out;
    }
}