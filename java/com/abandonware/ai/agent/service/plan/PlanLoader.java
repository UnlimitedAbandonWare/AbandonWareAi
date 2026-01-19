package com.abandonware.ai.agent.service.plan;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@Component
public class PlanLoader {

    public Map<String, RetrievalPlan> loadAll(String locationPattern) {
        try {
            Map<String, RetrievalPlan> map = new HashMap<>();
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] res = resolver.getResources(locationPattern);
            Yaml yaml = new Yaml();
            for (Resource r : res) {
                try (InputStream in = r.getInputStream()) {
                    String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    Map<String, Object> data = yaml.load(text);
                    RetrievalPlan p = toPlan(data);
                    map.put(p.id(), p);
                }
            }
            return map;
        } catch (Exception e) {
            return java.util.Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private RetrievalPlan toPlan(Map<String, Object> y) {
        RetrievalPlan p = new RetrievalPlan();
        p.setId((String) y.get("id"));
        p.setDesc((String) y.get("desc"));
        p.setK((Map<String, Integer>) y.getOrDefault("k", java.util.Map.of()));
        Map<String, Object> cal = (Map<String, Object>) y.getOrDefault("calibration", java.util.Map.of());
        RetrievalPlan.Calibration c = new RetrievalPlan.Calibration();
        Map<String, Object> rec = (Map<String, Object>) cal.getOrDefault("recency", java.util.Map.of());
        c.recency.halfLifeDays = ((Number) rec.getOrDefault("halfLifeDays", 21)).intValue();
        c.recency.maxBoost = ((Number) rec.getOrDefault("maxBoost", 0.25)).doubleValue();
        Map<String, Object> auth = (Map<String, Object>) cal.getOrDefault("authority", java.util.Map.of());
        c.authority.profile = (String) auth.getOrDefault("profile", "");
        c.authority.maxBoost = ((Number) auth.getOrDefault("maxBoost", 0.20)).doubleValue();
        Map<String, Object> scale = (Map<String, Object>) cal.getOrDefault("scale", java.util.Map.of());
        c.scale.method = (String) scale.getOrDefault("method", "isotonic");
        p.setCalibration(c);

        Map<String, Object> fuse = (Map<String, Object>) y.getOrDefault("fuse", java.util.Map.of());
        Map<String, Object> rrf = (Map<String, Object>) fuse.getOrDefault("rrf", java.util.Map.of());
        RetrievalPlan.Rrf r = new RetrievalPlan.Rrf();
        r.k = ((Number) rrf.getOrDefault("k", 60)).intValue();
        r.weight = (Map<String, Double>) rrf.getOrDefault("weight", java.util.Map.of());
        p.setRrf(r);

        Map<String, Object> g = (Map<String, Object>) y.getOrDefault("guard", java.util.Map.of());
        Map<String, Object> onnx = (Map<String, Object>) ((Map<String, Object>) g.getOrDefault("onnx", java.util.Map.of()));
        RetrievalPlan.Guard guard = new RetrievalPlan.Guard();
        guard.onnx.maxConcurrent = ((Number) onnx.getOrDefault("maxConcurrent", 4)).intValue();
        guard.onnx.budgetMs = ((Number) onnx.getOrDefault("budgetMs", 800)).intValue();
        p.setGuard(guard);

        p.setOverride((Map<String, Object>) y.getOrDefault("override", java.util.Map.of()));
        return p;
    }
}