
package com.abandonware.ai.service.rag.fusion;
import java.util.*;
import com.abandonware.ai.service.rag.model.ContextSlice;
import com.abandonware.ai.ml.SoftmaxUtils;
import com.abandonware.ai.service.rag.fusion.ScoreCalibrator;

/** Weighted Reciprocal Rank Fusion (RRF) with URL canonicalization and locale boost. */
public class WeightedRRF {
  private int k = 60; // RRF constant

    private final DocumentKeyNormalizer keyNorm = new DocumentKeyNormalizer();
    private final LocaleBoostPolicy localeBoost = new LocaleBoostPolicy();

    public WeightedRRF() {}

    /**
     * Fuse multiple ranked lists into a single ranking.
     * @param sources lists by source (e.g., web, vector, kg). Each ContextSlice must have source (String) and score (double).
     * @param k RRF constant
     * @param sourceWeights per-source weights (e.g., {"web":1.0, "vector":0.9})
     * @param calibrator score calibrator to normalize raw source scores to [0,1]
     * @param dedupByCanonical if true, deduplicate by canonical URL key (or id)
     * @return map keyed by canonical id with fused ContextSlice (rank and score set)
     */
    public Map<String, ContextSlice> fuse(List<List<ContextSlice>> sources,
                                          int k,
                                          Map<String, Double> sourceWeights,
                                          ScoreCalibrator calibrator,
                                          boolean dedupByCanonical) {
        this.k = k > 0 ? k : this.k;

        // 0) Preprocess each source: calibrate + locale boost
        List<List<ContextSlice>> pre = new ArrayList<>();
for (List<ContextSlice> list : sources){
            if (list == null) continue;
            List<ContextSlice> tmp = new ArrayList<>(list.size());
            for (int i=0;i<list.size();i++){
                ContextSlice c = list.get(i);
                String src = c.getSource()==null? "default" : c.getSource();
                double s = c.getScore();
                if (calibrator != null) s = calibrator.normalize(s, src);
                s *= localeBoost.multiplier(c);
                ContextSlice clone = new ContextSlice(c.getId(), c.getTitle(), c.getSnippet(), src, s, i+1);
                tmp.add(clone);
            }
            pre.add(tmp);
        }

        // 1) Optional dedup by canonical key (keep highest score per key per source list)
        if (dedupByCanonical){
            List<List<ContextSlice>> deduped = new ArrayList<>();
            for (List<ContextSlice> list : pre){
                Map<String, ContextSlice> merged = keyNorm.mergeByCanonicalKey(list);
                // preserve order by score desc
                List<ContextSlice> m = new ArrayList<>(merged.values());
                m.sort(Comparator.comparingDouble(ContextSlice::getScore).reversed());
                // reset rank within source
                for (int i=0;i<m.size();i++) m.get(i).setRank(i+1);
                deduped.add(m);
            }
            pre = deduped;
        }

        // 2) Fused scoring: score_RRF(d) = Σ_s w_s / (k + rank_s(d))
        Map<String, Double> weights = new HashMap<>(sourceWeights==null? Map.of(): sourceWeights);
        if (weights.isEmpty()){
            weights.put("web", 1.0);
            weights.put("vector", 1.0);
            weights.put("kg", 1.0);
        }
        Map<String, Double> fusedScore = new LinkedHashMap<>();
        Map<String, ContextSlice> repr = new LinkedHashMap<>();

        for (List<ContextSlice> list : pre){
            for (int i=0;i<list.size();i++){
                ContextSlice c = list.get(i);
                String key = keyNorm.keyOf(c);
                if (key == null) continue;
                double w = weights.getOrDefault(c.getSource(), 1.0);
                double rrf = w / (this.k + c.getRank());
                fusedScore.merge(key, rrf, Double::sum);
                // keep best representative (highest calibrated score) for metadata
                ContextSlice prev = repr.get(key);
                if (prev == null || c.getScore() > prev.getScore()){
                    repr.put(key, c);
                }
            }
        }

        // 3) Sort by fusedScore desc, then softmax-normalize final scores for stability
        List<Map.Entry<String, Double>> entries = new ArrayList<>(fusedScore.entrySet());
        entries.sort((a,b)->Double.compare(b.getValue(), a.getValue()));

        double[] logits = new double[entries.size()];
        for (int i=0;i<entries.size();i++) logits[i] = entries.get(i).getValue();
        double[] prob = SoftmaxUtils.stableSoftmax(logits, 1.0);

        Map<String, ContextSlice> out = new LinkedHashMap<>();
        for (int i=0;i<entries.size();i++){
            String key = entries.get(i).getKey();
            ContextSlice base = repr.get(key);
            ContextSlice copy = new ContextSlice(base.getId(), base.getTitle(), base.getSnippet(), base.getSource(), prob[i], i+1);
            out.put(key, copy);
        }
        return out;
    }

    /** Convenience overload: same as fuse(/* ... *&#47;), returns a sorted list */
    public List<ContextSlice> fuseToList(List<List<ContextSlice>> sources,
                                         int k,
                                         Map<String, Double> sourceWeights,
                                         ScoreCalibrator calibrator,
                                         boolean dedupByCanonical){
        Map<String, ContextSlice> m = fuse(sources, k, sourceWeights, calibrator, dedupByCanonical);
        return new ArrayList<>(m.values());
    }

    /** Legacy JSON-like output (kept for compatibility with some adapters). */
    public List<Map<String,Object>> fuse(List<List<ContextSlice>> sources,
                                         int topK,
                                         Map<String, Double> sourceWeights,
                                         ScoreCalibrator calibrator,
                                         boolean dedupByCanonical,
                                         boolean asJson){
        List<ContextSlice> list = fuseToList(sources, Math.max(1, topK), sourceWeights, calibrator, dedupByCanonical);
        List<Map<String,Object>> out = new ArrayList<>();
        int count = Math.max(1, Math.min(topK, list.size()));
        for (int i=0;i<count;i++){
            ContextSlice c = list.get(i);
            Map<String,Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("title", c.getTitle());
            m.put("snippet", c.getSnippet());
            m.put("source", c.getSource());
            m.put("score", c.getScore());
            m.put("rank", c.getRank());
            out.add(m);
        }
        return out;
    }
}