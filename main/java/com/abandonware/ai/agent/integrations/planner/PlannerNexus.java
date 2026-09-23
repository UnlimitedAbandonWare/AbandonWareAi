package com.abandonware.ai.agent.integrations.planner;

import java.util.Map;
import java.util.HashMap;
public final class PlannerNexus {
    public static final class PlanSpec {
        public String id;
        public int webTopK = 12;
        public boolean onnxEnabled = true;
        public boolean officialSourcesOnly = false;
        public String strategy = "balanced";
    }
    private final Map<String, PlanSpec> plans = new HashMap<>();
    public PlannerNexus() {
        PlanSpec brave = new PlanSpec();
        brave.id = "brave.v1";
        brave.webTopK = 48;
        brave.strategy = "overdrive";
        plans.put(brave.id, brave);
        PlanSpec recency = new PlanSpec();
        recency.id = "recency_first.v1";
        recency.webTopK = 16;
        recency.strategy = "recency";
        plans.put(recency.id, recency);
    }
    public PlanSpec resolve(String planId) {
        return plans.getOrDefault(planId, plans.get("recency_first.v1"));
    }
}