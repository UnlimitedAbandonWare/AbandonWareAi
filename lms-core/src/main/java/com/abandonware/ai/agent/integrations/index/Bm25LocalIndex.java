
package com.abandonware.ai.agent.integrations.index;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.integrations.index.Bm25LocalIndex
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: unknown.
 */
/* agent-hint:
id: com.abandonware.ai.agent.integrations.index.Bm25LocalIndex
role: config
*/
public class Bm25LocalIndex {

    public static class Doc {
        public final String id, title, text, url;
        public final long ts;
        public Doc(String id, String title, String text, String url, long ts) {
            this.id = id; this.title = title; this.text = text; this.url = url; this.ts = ts;
        }
    }

    private final Map<String, Doc> docs = new HashMap<>();
    private final Map<String, Integer> df = new HashMap<>();
    private int N = 0;
    private double avgdl = 0.0;
    private final double k1 = 1.2;
    private final double b = 0.75;

    public void loadFromTsv(Path path) {
        try {
            if (path == null || !Files.exists(path)) return;
            double totalLen = 0.0;
            List<String> lines = Files.readAllLines(path);
            for (String line : lines) {
                String[] a = line.split("\\t");
                if (a.length < 5) continue;
                Doc d = new Doc(a[0], a[1], a[2], a[3], parseLongSafe(a[4]));
                docs.put(d.id, d);
                N++;
                List<String> tokens = tokenize(d.title + " " + d.text);
                totalLen += tokens.size();
                Set<String> uniq = new HashSet<>(tokens);
                for (String t : uniq) df.put(t, df.getOrDefault(t, 0) + 1);
            }
            avgdl = (N > 0) ? (totalLen / N) : 0.0;
        } catch (Exception ignored) { }
    }

    public List<Map.Entry<Doc, Double>> search(String query, int topK) {
        if (query == null || query.isBlank() || N == 0) return Collections.emptyList();
        List<String> qTokens = tokenize(query);
        Map<String, Double> scores = new HashMap<>();
        for (Doc d : docs.values()) {
            double score = 0.0;
            List<String> dTokens = tokenize(d.title + " " + d.text);
            Map<String, Integer> tf = new HashMap<>();
            for (String t : dTokens) tf.put(t, tf.getOrDefault(t, 0) + 1);
            double docLen = dTokens.size();
            for (String term : qTokens) {
                int f_qi = tf.getOrDefault(term, 0);
                if (f_qi == 0) continue;
                int n_qi = df.getOrDefault(term, 0);
                double idf = Math.log( (N - n_qi + 0.5) / (n_qi + 0.5) + 1e-9 );
                double denom = f_qi + k1 * (1 - b + b * (docLen / Math.max(1e-9, avgdl)));
                score += idf * ((f_qi * (k1 + 1)) / Math.max(1e-9, denom));
            }
            if (score != 0.0) scores.put(d.id, score);
        }
        List<Map.Entry<Doc, Double>> ranked = new ArrayList<>();
        for (Map.Entry<String, Double> e : scores.entrySet()) {
            ranked.add(new AbstractMap.SimpleEntry<>(docs.get(e.getKey()), e.getValue()));
        }
        ranked.sort((a, b2) -> Double.compare(b2.getValue(), a.getValue()));
        if (topK > 0 && ranked.size() > topK) return ranked.subList(0, topK);
        return ranked;
    }

    private static long parseLongSafe(String s) { try { return Long.parseLong(s.trim()); } catch(Exception e){ return 0L; } }
    private static List<String> tokenize(String s) {
        if (s == null) return Collections.emptyList();
        String[] arr = s.toLowerCase(Locale.ROOT).split("[^a-z0-9가-힣]+");
        List<String> out = new ArrayList<>();
        for (String t : arr) if (!t.isBlank()) out.add(t);
        return out;
    }
}