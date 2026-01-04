
package com.abandonware.ai.agent.integrations.analyze;

import java.util.*;

public class NineTileAliasOverlay {
    private final TileWeightStore store;
    private final double minConfidence;

    public NineTileAliasOverlay(TileWeightStore store, double minConfidence) {
        this.store = store; this.minConfidence = minConfidence;
    }

    public Optional<String> correct(String raw, String sessionId) {
        Map<String, Double> w = store.currentWeights(sessionId);
        String best = pick(raw, w);
        double conf = confidence(best, w);
        if (conf >= minConfidence) return Optional.of(best);
        return Optional.empty();
    }

    private String pick(String raw, Map<String, Double> w) {
        if (w == null || w.isEmpty()) return raw;
        return w.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(raw);
    }

    private double confidence(String key, Map<String, Double> w) {
        if (w == null) return 0.0;
        return w.getOrDefault(key, 0.0);
    }
}
