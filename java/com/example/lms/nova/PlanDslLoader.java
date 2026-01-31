package com.example.lms.nova;

import org.yaml.snakeyaml.Yaml;
import org.springframework.core.io.ClassPathResource;
import java.io.InputStream;
import java.util.Map;




public class PlanDslLoader {
    public BravePlan load(String id) {
        String path = "plans/" + id + ".yaml";
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            Map<String, Object> y = new Yaml().load(in);
            Map<String, Object> retrieval = (Map<String, Object>) y.get("retrieval");
            Map<String, Object> k = (Map<String, Object>) retrieval.get("k");
            Map<String, Object> burst = (Map<String, Object>) y.get("burst");
            Map<String, Object> gates = (Map<String, Object>) y.get("gates");

            int web = (int) toNumber(k.getOrDefault("web", 12));
            int vector = (int) toNumber(k.getOrDefault("vector", 8));
            int kg = (int) toNumber(k.getOrDefault("kg", 4));
            boolean burstEnabled = burst != null && Boolean.TRUE.equals(burst.getOrDefault("enabled", Boolean.FALSE));
            int bmin = 0, bmax = 0;
            if (burst != null && burst.containsKey("subqueries")) {
                Map<String, Object> sub = (Map<String, Object>) burst.get("subqueries");
                bmin = (int) toNumber(sub.getOrDefault("min", 0));
                bmax = (int) toNumber(sub.getOrDefault("max", 0));
            }
            int minCitations = gates == null ? 2 : (int) toNumber(gates.getOrDefault("minCitations", 2));
            String order = retrieval == null ? "web_then_vector_then_kg" : (String) retrieval.getOrDefault("order", "web_then_vector_then_kg");
            String tier = gates == null ? "trusted" : (String) gates.getOrDefault("authorityTier", "trusted");

            return new BravePlan(true, web, vector, kg, burstEnabled, bmin, bmax, minCitations, order, tier);
        } catch (Exception e) {
            return new BravePlan(false, 0,0,0,false,0,0,0,"","");
        }
    }

    private Number toNumber(Object o) {
        if (o instanceof Number) return (Number)o;
        if (o == null) return 0;
        return Integer.parseInt(String.valueOf(o));
    }
}