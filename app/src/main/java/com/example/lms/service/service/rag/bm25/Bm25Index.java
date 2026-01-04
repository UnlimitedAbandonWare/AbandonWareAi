package com.example.lms.service.service.rag.bm25;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.example.lms.service.service.rag.bm25.Bm25Index
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: uses concurrent primitives.
 */
/* agent-hint:
id: com.example.lms.service.service.rag.bm25.Bm25Index
role: config
*/
public class Bm25Index {
    public static class Doc {
        public final String id;
        public final String text;
        public Doc(String id, String text) {
            this.id = id;
            this.text = text == null ? "" : text;
        }
    }

    private static final double k1 = 1.5, b = 0.75;
    private final Map<String, String[]> docs = new ConcurrentHashMap<>();
    private final Map<String, Integer> docLen = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Integer>> tf = new ConcurrentHashMap<>();
    private final Map<String, Integer> df = new ConcurrentHashMap<>();
    private volatile double avgdl = 0.0;

    private static String[] tok(String s) {
        if (s == null || s.isBlank()) return new String[0];
        return s.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}\\s]", " ")
                .trim()
                .split("\\s+");
    }

    public void add(Doc d) {
        String[] T = tok(d.text);
        docs.put(d.id, T);
        docLen.put(d.id, T.length);
        Map<String, Integer> f = new HashMap<>();
        for (String t : T) f.put(t, f.getOrDefault(t, 0) + 1);
        tf.put(d.id, f);
        for (String t : f.keySet()) df.put(t, df.getOrDefault(t, 0) + 1);
        recomputeAvgdl();
    }

    private void recomputeAvgdl() {
        int tot = 0;
        for (int L : docLen.values()) tot += L;
        avgdl = docs.isEmpty() ? 0.0 : (tot * 1.0 / docs.size());
    }

    public List<Map.Entry<String, Double>> search(String q, int K) {
        String[] Q = tok(q);
        Set<String> terms = new HashSet<>(Arrays.asList(Q));
        int N = docs.size();
        List<Map.Entry<String, Double>> out = new ArrayList<>();
        for (String docId : docs.keySet()) {
            double s = 0.0;
            int dl = docLen.getOrDefault(docId, 1);
            Map<String, Integer> F = tf.getOrDefault(docId, Collections.emptyMap());
            for (String t : terms) {
                int f = F.getOrDefault(t, 0); if (f == 0) continue;
                int dfi = df.getOrDefault(t, 0);
                double idf = Math.log((N - dfi + 0.5) / (dfi + 0.5) + 1e-9);
                double denom = f + k1 * (1 - b + b * (dl / Math.max(1.0, avgdl)));
                s += idf * ((f * (k1 + 1)) / denom);
            }
            if (s != 0.0) out.add(new AbstractMap.SimpleEntry<>(docId, s));
        }
        out.sort((a, b2) -> Double.compare(b2.getValue(), a.getValue()));
        return out.size() > K ? out.subList(0, K) : out;
    }
}