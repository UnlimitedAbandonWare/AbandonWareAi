package com.example.lms.service.rag.fusion;

import java.util.*;
import java.util.function.Function;

/** Minimal weighted RRF with canonicalizer support for portability. */
public final class WeightedRrfRanking {

    /** Entry with id and rank for each source. */
    public static final class ScoredDoc {
        public final String id; public final int rank; public final double weight;
        public ScoredDoc(String id, int rank, double weight) {
            this.id = id; this.rank = rank; this.weight = weight;
        }
    }

    /** Fuse scores after applying a canonical key function. */
    public Map<String, Double> fuseWithCanonicalizer(Map<String, List<ScoredDoc>> bySource,
                                                     Function<String,String> keyFn) {
        final int RRF_K = 60;
        Map<String, Double> out = new HashMap<>();
        for (List<ScoredDoc> L : bySource.values()) {
            for (ScoredDoc d : L) {
                String key = keyFn.apply(d.id);
                double s = d.weight * (1.0 / (RRF_K + Math.max(1, d.rank)));
                out.merge(key, s, Double::sum);
            }
        }
        return out;
    }
}