package com.abandonware.ai.stable.rag.fusion;

import java.util.*;
import com.abandonware.ai.stable.rag.model.ContextSlice;

/**
 * Weighted Reciprocal Rank Fusion with per-source calibration and optional dedup.
 */
public final class WeightedRRF {
    private final int k;
    private final ScoreCalibrator calibrator;
    private final RerankCanonicalizer canonicalizer;

    public WeightedRRF(int k){
        this(k, new ScoreCalibrator(), new RerankCanonicalizer());
    }
    public WeightedRRF(int k, ScoreCalibrator calibrator, RerankCanonicalizer canonicalizer){
        this.k = k<=0? 60: k;
        this.calibrator = calibrator==null? new ScoreCalibrator(): calibrator;
        this.canonicalizer = canonicalizer==null? new RerankCanonicalizer(): canonicalizer;
    }

    /**
     * @param sources list of ranked lists from different sources
     * @param sourceWeights per-source weights (same order as sources; default 1.0)
     * @param dedup whether to deduplicate by canonical key
     */
    public List<ContextSlice> fuseToList(List<List<ContextSlice>> sources, List<Double> sourceWeights, boolean dedup){
        if(sources==null || sources.isEmpty()) return Collections.emptyList();
        int n = sources.size();
        double[] w = new double[n];
        for(int i=0;i<n;i++) w[i] = (sourceWeights!=null && i<sourceWeights.size() && sourceWeights.get(i)!=null)? sourceWeights.get(i):1.0;

        Map<String, Double> fused = new HashMap<>();
        Map<String, ContextSlice> repr = new HashMap<>();

        for(int si=0; si<n; si++){
            List<ContextSlice> src = sources.get(si);
            if(src==null) continue;
            // calibrate and (optionally) dedup inside each source
            List<ContextSlice> cal = calibrator.apply(src, (src.isEmpty()? "": src.get(0).getSource()));
            if(dedup) cal = canonicalizer.normalize(cal);
            int rank=1;
            for(ContextSlice c: cal){
                double contrib = w[si] * (1.0 / (k + rank));
                String key = canonicalizer.canonicalKey(c.getId());
                fused.put(key, fused.getOrDefault(key, 0.0) + contrib);
                ContextSlice rep = repr.get(key);
                if(rep==null || c.getScore()>rep.getScore()){
                    repr.put(key, new ContextSlice(key, c.getTitle(), c.getSnippet(), c.getSource(), c.getScore(), rank));
                }
                rank++;
            }
        }

        // sort by fused score desc
        List<Map.Entry<String, Double>> entries = new ArrayList<>(fused.entrySet());
        entries.sort((a,b)-> Double.compare(b.getValue(), a.getValue()));
        List<ContextSlice> out = new ArrayList<>(entries.size());
        int r=1;
        for(Map.Entry<String, Double> e: entries){
            ContextSlice c = repr.get(e.getKey());
            out.add(new ContextSlice(c.getId(), c.getTitle(), c.getSnippet(), c.getSource(), e.getValue(), r++));
        }
        return out;
    }
}