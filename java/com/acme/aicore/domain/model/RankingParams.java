package com.acme.aicore.domain.model;

import java.util.HashMap;
import java.util.Map;



/**
 * Parameters controlling fusion and ranking.  A simple implementation
 * provides weights per bundle type and the RRF parameter k.  These
 * parameters can be configured via the {@link QueryPlanner} or external
 * configuration.
 */
public class RankingParams {
    private int rrfK = 60;
    private Map<String, Double> weights = new HashMap<>();
    private int windowM = 50;

    public RankingParams() {
        weights.put("web", 1.0);
        weights.put("vector", 1.0);
    }

    public int rrfK() {
        return rrfK;
    }

    public Map<String, Double> weights() {
        return weights;
    }

    public double weightOf(String type) {
        return weights.getOrDefault(type, 1.0);
    }

    public int windowM() {
        return windowM;
    }

    public void setWindowM(int windowM) {
        this.windowM = windowM;
    }

    public static RankingParams defaults() {
        return new RankingParams();
    }
}