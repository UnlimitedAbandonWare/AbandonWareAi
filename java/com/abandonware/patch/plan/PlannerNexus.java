package com.abandonware.patch.plan;

import org.yaml.snakeyaml.Yaml;
import java.io.InputStream;
import java.util.Collections;
import java.util.Map;

public class PlannerNexus {

    public Plan load(String name) {
        String path = "/plans/" + name + ".yaml";
        try (InputStream is = getClass().getResourceAsStream(path)) {
            if (is == null) return defaultPlan();
            Object root = new Yaml().load(is);
            if (!(root instanceof Map)) return defaultPlan();

            @SuppressWarnings("unchecked")
            Map<Object,Object> m = (Map<Object,Object>) root;

            Object planObj = m.get("plan");
            @SuppressWarnings("unchecked")
            Map<Object,Object> plan = (planObj instanceof Map) ? (Map<Object,Object>) planObj : Collections.emptyMap();

            Plan p = new Plan();
            p.webTopK = asInt(plan.get("webTopK"), 10);
            p.officialSourcesOnly = asBool(plan.get("officialSourcesOnly"), false);
            p.minCitations = asInt(plan.get("minCitations"), 2);
            p.onnxEnabled = asBool(plan.get("onnxEnabled"), true);
            p.dppTopK = asInt(plan.get("dppTopK"), 8);
            return p;
        } catch (Exception e) {
            return defaultPlan();
        }
    }

    private Plan defaultPlan() { return new Plan(); }

    private int asInt(Object v, int def) {
        return (v instanceof Number) ? ((Number) v).intValue() : def;
    }

    private boolean asBool(Object v, boolean def) {
        return (v instanceof Boolean) ? (Boolean) v : def;
    }
}
