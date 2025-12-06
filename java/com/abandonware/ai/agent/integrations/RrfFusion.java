package com.abandonware.ai.agent.integrations;

import java.util.*;

/**
 * Weighted Reciprocal Rank Fusion (RRF) for two sources: local + web.
 * <p>
 * This replacement fixes previous brace/return placement errors that caused
 * "illegal start of type" during compilation.
 * The implementation is intentionally self-contained and conservative.
 */
public final class RrfFusion {

    private RrfFusion() {}

    /**
     * Fuse two ranked lists using weighted RRF.
     * Each element is a Map with optional keys: id, url, title, snippet, source, score, rank.
     * Dedupe is performed by a canonicalized URL when present, otherwise by id.
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String,Object>> fuse(List<Map<String,Object>> local, List<Map<String,Object>> web) {
        if (local == null) local = List.of();
        if (web == null) web = List.of();

        double K = getEnvDouble("RRF_K", 60.0);
        double wLocal = getEnvDouble("RRF_W_LOCAL", 1.0);
        double wWeb   = getEnvDouble("RRF_W_WEB",   1.0);

        Map<String, Double> score = new HashMap<>();
        Map<String, Map<String,Object>> pick = new LinkedHashMap<>();

        // local
        int r = 1;
        for (Map<String,Object> m : local) {
            String key = keyOf(m);
            if (key == null) continue;
            pick.putIfAbsent(key, m);
            score.put(key, score.getOrDefault(key, 0.0) + (wLocal / (K + r)));
            r++;
        }
        // web
        r = 1;
        for (Map<String,Object> m : web) {
            String key = keyOf(m);
            if (key == null) continue;
            pick.putIfAbsent(key, m);
            score.put(key, score.getOrDefault(key, 0.0) + (wWeb / (K + r)));
            r++;
        }

        List<Map.Entry<String,Double>> sorted = new ArrayList<>(score.entrySet());
        sorted.sort((a,b) -> Double.compare(b.getValue(), a.getValue()));

        List<Map<String,Object>> out = new ArrayList<>(sorted.size());
        for (Map.Entry<String,Double> e : sorted) {
            Map<String,Object> m = pick.get(e.getKey());
            if (m == null) continue;
            // propagate fused score
            Map<String,Object> mm = new LinkedHashMap<>(m);
            mm.put("rrfScore", e.getValue());
            out.add(mm);
        }
        return out;
    }

    private static double getEnvDouble(String name, double def) {
        try {
            String s = System.getenv(name);
            return (s == null || s.isBlank()) ? def : Double.parseDouble(s);
        } catch (Exception ignore) {
            return def;
        }
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

    /** Normalize tracking params out of URLs for dedupe stability. */
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