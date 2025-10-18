package com.abandonware.ai.service.rag.fusion;

import com.abandonware.ai.service.rag.model.ContextSlice;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class RrfFusion {



    private final WeightedRRF weightedRRF;
    private final ScoreCalibrator calibrator;

    @Value("${retrieval.fusion.rrf.k:60}")
    private int k;

    @Value("${retrieval.fusion.rrf.dedupe-by-canonical-key:true}")
    private boolean dedupe;

    // weights in format "web=0.5,vector=0.4,kg=0.1"
    @Value("${retrieval.fusion.weights:web=0.5,vector=0.4,kg=0.1}")
    private String weightsProp;

    public RrfFusion(WeightedRRF weightedRRF, ScoreCalibrator calibrator) {
        this.weightedRRF = weightedRRF;
        this.calibrator = calibrator;
    }

    public List<ContextSlice> fuse(List<List<ContextSlice>> sources) {
        Map<String, Double> weights = parseWeights(weightsProp);
        Map<String, ContextSlice> map = weightedRRF.fuse(sources, k, weights, calibrator, dedupe);
        return new ArrayList<>(map.values());
    }

    private Map<String, Double> parseWeights(String prop) {
        Map<String, Double> w = new HashMap<>();
        if (prop == null || prop.isBlank()) return w;
        for (String pair : prop.split(",")) {
            String[] kv = pair.trim().split("=");
            if (kv.length == 2) {
                try { w.put(kv[0].trim(), Double.parseDouble(kv[1].trim())); } catch (Exception ignore) {}
            }
        }
        return w;
    }
}