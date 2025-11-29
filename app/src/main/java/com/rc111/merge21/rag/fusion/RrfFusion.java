package com.rc111.merge21.rag.fusion;

import com.rc111.merge21.rag.SearchDoc;
import java.util.*;

/**
 * Weighted Reciprocal Rank Fusion. See: Cormack et al., CIKM 2009.
 */
public class RrfFusion {
    private final WeightedRRF rrf;

    public RrfFusion(Map<String, Double> sourceWeights, int k) {
        this.rrf = new WeightedRRF(sourceWeights, k);
    }

    public List<SearchDoc> fuse(List<List<SearchDoc>> buckets) {
        Map<String, SearchDoc> byId = new HashMap<>();
        Map<String, Double> scores = new HashMap<>();

        for (List<SearchDoc> bucket : buckets) {
            for (int i = 0; i < bucket.size(); i++) {
                SearchDoc d = bucket.get(i);
                byId.putIfAbsent(d.id, d);
                double inc = rrf.score(d.source, i + 1);
                scores.merge(d.id, inc, Double::sum);
            }
        }
        List<Map.Entry<String, Double>> entries = new ArrayList<>(scores.entrySet());
        entries.sort((a,b) -> Double.compare(b.getValue(), a.getValue()));
        List<SearchDoc> out = new ArrayList<>();
        int rank = 1;
        for (Map.Entry<String, Double> e : entries) {
            SearchDoc d = byId.get(e.getKey());
            out.add(new SearchDoc(d.id, d.title, d.snippet, d.source, e.getValue(), rank++));
        }
        return out;
    }
}
