package com.abandonware.ai.service.rag.fusion;

import org.springframework.stereotype.Component;
import java.util.*;

/**
 * Minimal, dependency-free RRF fuser bean.
 * Provides a stable signature used via reflection: fuse(List<List<Map>>, int).
 */
@Component
public class RrfFusion {

    /**
     * Fuse N provider lists using uniform weights and return topK items.
     * Input element type is Map<String,Object> in a standard context shape.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String,Object>> fuse(List<List<Map<String,Object>>> sources, int topK) {
        if (sources == null || sources.isEmpty()) return List.of();
        double K = 60.0;

        Map<String, Double> score = new HashMap<>();
        Map<String, Map<String,Object>> pick = new LinkedHashMap<>();

        int idx = 0;
        for (List<Map<String,Object>> list : sources) {
            if (list == null) continue;
            double w = 1.0; // uniform
            int r = 1;
            for (Map<String,Object> m : list) {
                String key = keyOf(m);
                if (key == null) continue;
                pick.putIfAbsent(key, m);
                score.put(key, score.getOrDefault(key, 0.0) + (w / (K + r)));
                r++;
            }
            idx++;
        }

        List<Map.Entry<String,Double>> sorted = new ArrayList<>(score.entrySet());
        sorted.sort((a,b) -> Double.compare(b.getValue(), a.getValue()));
        List<Map<String,Object>> out = new ArrayList<>();
        for (Map.Entry<String,Double> e : sorted) {
            Map<String,Object> m = pick.get(e.getKey());
            if (m == null) continue;
            Map<String,Object> mm = new LinkedHashMap<>(m);
            mm.put("rrfScore", e.getValue());
            out.add(mm);
            if (topK > 0 && out.size() >= topK) break;
        }
        return out;
    }

    private static String keyOf(Map<String,Object> m) {
        Object url = m.get("url");
        if (url instanceof String) {
            String u = __canonicalUrl((String) url);
            if (u != null && !u.isBlank()) return u;
        }
        Object id = m.get("id");
        return (id == null) ? null : String.valueOf(id);
    }

    private static String __canonicalUrl(String url) {
        if (url == null || url.isBlank()) return url;
        try {
            java.net.URI u = new java.net.URI(url);
            String q = u.getQuery();
            String filtered = null;
            if (q != null && !q.isBlank()) {
                String[] parts = q.split("&");
                StringBuilder sb = new StringBuilder();
                for (String s : parts) {
                    if (s.startsWith("utm_") || s.startsWith("fbclid")) continue;
                    if (sb.length() > 0) sb.append("&");
                    sb.append(s);
                }
                filtered = (sb.length() == 0) ? null : sb.toString();
            }
            return new java.net.URI(u.getScheme(), u.getAuthority(), u.getPath(), filtered, null).toString();
        } catch (Exception e) {
            return url;
        }
    }
}