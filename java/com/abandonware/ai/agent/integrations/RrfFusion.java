
package com.abandonware.ai.agent.integrations;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Weighted Reciprocal Rank Fusion (RRF).
 */
public final class RrfFusion {

    private RrfFusion() {}

    public static class Item {
        public String id;
        public String url; // optional
        public String title;
        public String snippet;
        public String source;
        public double baseScore;
        public int rank;
        public Item(String id, String url, String title, String snippet, String source, double baseScore, int rank) {
            this.id = id; this.url = url; this.title = title; this.snippet = snippet; this.source = source; this.baseScore = baseScore; this.rank = rank;
        }
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String,Object>> fuse(List<Map<String,Object>> local, List<Map<String,Object>> web) {
        double K = getEnvDouble("RRF_K", 60.0);
        double wLocal = getEnvDouble("RRF_W_LOCAL", 1.0);
        double wWeb = getEnvDouble("RRF_W_WEB", 1.0);

        // external weights JSON can override
        Map<String, Double> weights = loadWeightsJson();
        if (weights != null) {
            if (weights.containsKey("K")) K = weights.get("K");
            if (weights.containsKey("w_local")) wLocal = weights.get("w_local");
            if (weights.containsKey("w_web")) wWeb = weights.get("w_web");
        }

        Map<String, Map<String,Object>> byKey = new LinkedHashMap<>();
        Map<String, Double> score = new HashMap<>();

        int r = 1;
        for (Map<String,Object> m : local) {
            String key = keyOf(m);
            byKey.putIfAbsent(key, m);
            double s = wLocal / (K + r);
            score.put(key, score.getOrDefault(key, 0.0) + s);
            r++;
        }
        r = 1;
        for (Map<String,Object> m : web) {
            String key = keyOf(m);
            byKey.putIfAbsent(key, m);
            double s = wWeb / (K + r);
            score.put(key, score.getOrDefault(key, 0.0) + s);
            r++;
        }

        List<Map.Entry<String, Double>> entries = new ArrayList<>(score.entrySet());
        entries.sort((a,b)-> Double.compare(b.getValue(), a.getValue()));

        List<Map<String,Object>> out = new ArrayList<>();
        int rank = 1;
        for (var e : entries) {
            Map<String,Object> m = new HashMap<>(byKey.get(e.getKey()));
            m.put("score", e.getValue());
            m.put("rank", rank++);
            out.add(m);
        }
        return out;
    }

    private static String keyOf(Map<String,Object> m) {
        String url = str(m.get("source"));
        if (url != null && url.startsWith("http")) return url;
        String id = str(m.get("id"));
        if (id != null) return id;
        String title = str(m.get("title"));
        String snip = str(m.get("snippet"));
        return TextUtils.sha1((title == null ? "" : title) + "||" + (snip == null ? "" : snip));
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    private static double getEnvDouble(String k, double def) {
        try {
            String v = System.getenv(k);
            if (v == null) return def;
            return Double.parseDouble(v.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static Map<String, Double> loadWeightsJson() {
        String path = System.getenv("RRF_WEIGHTS_JSON");
        if (path == null || path.isBlank()) return null;
        try {
            String s = Files.readString(Paths.get(path));
            // very tiny parser: expecting {"K":60,"w_local":1.0,"w_web":1.0}
            Map<String, Double> out = new HashMap<>();
            for (String entry : s.replaceAll("[{}\\s\"]","").split(",")) {
                if (entry.isEmpty()) continue;
                String[] kv = entry.split(":");
                if (kv.length == 2) {
                    out.put(kv[0], Double.parseDouble(kv[1]));
                }
            }
            return out;
        } catch (IOException ignore) {
            return null;
        }
    }
}
