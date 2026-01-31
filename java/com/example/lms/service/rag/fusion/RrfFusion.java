package com.example.lms.service.rag.fusion;

import java.util.*;

/**
 * Lightweight RRF util kept intentionally framework-free.
 * Provides a simple combine method used by tests or adapters.
 */
public final class RrfFusion {

    private RrfFusion() {}

    public static <T> List<T> fuse(List<List<T>> sources, int topK) {
        if (sources == null || sources.isEmpty()) return List.of();
        // If T is Map, compute RRF over "url"/"id" keys; otherwise fall back to stable order.
        boolean isMap = sources.stream().flatMap(List::stream).anyMatch(o -> o instanceof Map);

        if (!isMap) {
            List<T> out = new ArrayList<>();
            for (List<T> l : sources) if (l != null) out.addAll(l);
            if (topK > 0 && out.size() > topK) return out.subList(0, topK);
            return out;
        }

        Map<String, Double> score = new HashMap<>();
        Map<String, T> pick = new LinkedHashMap<>();
        double K = 60.0;

        for (List<T> list : sources) {
            if (list == null) continue;
            int r = 1;
            for (T t : list) {
                @SuppressWarnings("unchecked")
                Map<String,Object> m = (Map<String,Object>) t;
                String key = keyOf(m);
                if (key == null) continue;
                pick.putIfAbsent(key, t);
                score.put(key, score.getOrDefault(key, 0.0) + (1.0 / (K + r)));
                r++;
            }
        }
        List<Map.Entry<String,Double>> sorted = new ArrayList<>(score.entrySet());
        sorted.sort((a,b) -> Double.compare(b.getValue(), a.getValue()));

        List<T> out = new ArrayList<>();
        for (Map.Entry<String,Double> e : sorted) {
            T t = pick.get(e.getKey());
            if (t != null) out.add(t);
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