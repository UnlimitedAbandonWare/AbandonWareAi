package com.abandonware.ai.rag.planner;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.rag.planner.PlanLoader
 * Role: config
 * Feature Flags: whitelist, kg
 * Observability: propagates trace headers if present.
 * Thread-Safety: uses concurrent primitives.
 */
/* agent-hint:
id: com.abandonware.ai.rag.planner.PlanLoader
role: config
flags: [whitelist, kg]
*/
public class PlanLoader {
    private final Map<String, PlanDefinition> plans = new ConcurrentHashMap<>();

    public PlanLoader() {
        loadAll("classpath*:plans/{default,brave,rulebreak,zero_break,hypernova}*.yaml");
    }

    public PlanDefinition get(String id) {
        return plans.get(id);
    }

    public Collection<PlanDefinition> all() {
        return plans.values();
    }

    private void loadAll(String pattern) {
        try {
            PathMatchingResourcePatternResolver r = new PathMatchingResourcePatternResolver();
            Resource[] resources = r.getResources(pattern);
            Yaml yaml = new Yaml();
            for (Resource res : resources) {
                try (InputStream in = res.getInputStream()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = yaml.load(in);
                    if (m == null) continue;
                    PlanDefinition p = mapToPlan(m);
                    plans.put(p.getId(), p);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load plans", e);
        }
    }

    @SuppressWarnings("unchecked")
    private PlanDefinition mapToPlan(Map<String, Object> m) {
        PlanDefinition p = new PlanDefinition();
        // Support both 'id: default.v1' and legacy ids like 'default'
        String id = (String) m.getOrDefault("id", "default.v1");
        if (!id.contains(".")) id = id + ".v1";
        p.setId(id);
        p.setDescription((String) m.getOrDefault("description", ""));

        Map<String, Object> r = (Map<String, Object>) m.getOrDefault("retrieval", Collections.emptyMap());
        p.setWebTopK(((Number) r.getOrDefault("webTopK", 10)).intValue());
        p.setVectorTopK(((Number) r.getOrDefault("vectorTopK", 8)).intValue());
        p.setKgTopK(((Number) r.getOrDefault("kgTopK", 0)).intValue());

        Map<String, Object> g = (Map<String, Object>) m.getOrDefault("gates", Collections.emptyMap());
        p.setWhitelistRequired((Boolean) g.getOrDefault("whitelistRequired", Boolean.TRUE));
        p.setMinCitations(((Number) g.getOrDefault("minCitations", 2)).intValue());
        p.setDppEnabled((Boolean) g.getOrDefault("dppEnabled", Boolean.TRUE));
        p.setCalibrator((String) g.getOrDefault("calibrator", "minmax"));
        p.setMpLawEnabled((Boolean) g.getOrDefault("mpLawEnabled", Boolean.TRUE));

        Map<String, Object> c = (Map<String, Object>) m.getOrDefault("constraints", Collections.emptyMap());
        p.setOnnxMaxConcurrency(((Number) c.getOrDefault("onnxMaxConcurrency", 4)).intValue());
        p.setTimeBudgetMs(((Number) c.getOrDefault("timeBudgetMs", 6000)).longValue());

        p.setParams((Map<String, Object>) m.getOrDefault("params", Collections.emptyMap()));
        return p;
    }
}