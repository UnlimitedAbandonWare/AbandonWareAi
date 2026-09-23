
package com.abandonware.ai.agent.integrations.analyze;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class TileWeightStore {
    private final Map<String, Map<String, Double>> sessionWeights = new ConcurrentHashMap<>();

    public void update(String sessionId, List<WebEvidenceAdapter.AliasEvidence> ev) {
        Map<String, Double> w = sessionWeights.computeIfAbsent(sessionId, k-> new ConcurrentHashMap<>());
        for (WebEvidenceAdapter.AliasEvidence a : ev) {
            w.merge(a.alias, a.weight, (oldV, newV) -> oldV*0.8 + newV*0.2);
        }
    }

    public Map<String, Double> currentWeights(String sessionId) {
        return sessionWeights.getOrDefault(sessionId, Collections.emptyMap());
    }
}
