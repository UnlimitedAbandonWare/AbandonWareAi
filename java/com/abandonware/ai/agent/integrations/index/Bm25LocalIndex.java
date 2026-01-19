package com.abandonware.ai.agent.integrations.index;


import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
/**
 * Minimal local BM25 index (lightweight, no Lucene).
 * Fields: id, title, text, url, ts
 * NOTE: For production use, replace with Lucene BM25. This is a simple DF/IDF toy to keep build green.
 *
 * Fail-soft: if index path missing, methods return empty results.
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

    public void loadFromTsv(Path path) {
        if (path == null || !Files.exists(path)) return;
        try {
            for (String line : Files.readAllLines(path)) {
                String[] a = line.split("\t");
                if (a.length < 5) continue;
                Doc d = new Doc(a[0], a[1], a[2], a[3], parseLong(a[4]));
                docs.put(d.id, d);
                N++;
                Set<String> unique = new HashSet<>(tokenize(d.title + " " + d.text));
                for (String t : unique) df.put(t, df.getOrDefault(t, 0) + 1);
            }
        } catch (Exception ignored) {}
    }
    private long parseLong(String s){ try { return Long.parseLong(s.trim()); } catch(Exception e){ return 0L; } }

    static List<String> tokenize(String s) {
        if (s == null) return Collections.emptyList();
        String norm = s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{Nd}\\s]", " ");
        String[] parts = norm.split("\\s+");
        List<String> out = new ArrayList<>();
        for (String p : parts) if (!p.isEmpty()) out.add(p);
        return out;
    }

    public List<Map.Entry<Doc, Double>> search(String query, int topK) {
        if (N == 0 || query == null || query.isBlank()) return Collections.emptyList();
        List<String> q = tokenize(query);
        Map<String, Double> scores = new HashMap<>();
        double k1 = 1.2, b = 0.75; // BM25 defaults
        Map<String, Integer> dl = new HashMap<>();
        double avgdl = 1.0;
        for (Doc d : docs.values()) {
            int len = tokenize(d.title + " " + d.text).size();
            dl.put(d.id, len);
            avgdl += len;
        }
        avgdl = avgdl / Math.max(1, N);
        for (Doc d : docs.values()) {
            double score = 0.0;
            int docLen = dl.getOrDefault(d.id, 1);
            for (String t : q) {
                int n_qi = df.getOrDefault(t, 0);
                if (n_qi == 0) continue;
                double idf = Math.log( (N - n_qi + 0.5) / (n_qi + 0.5) + 1e-9 );
                int f_qi = Collections.frequency(tokenize(d.title + " " + d.text), t);
                double denom = f_qi + k1 * (1 - b + b * (docLen / avgdl));
                score += idf * ( (f_qi * (k1 + 1)) / Math.max(1e-9, denom) );
            }
            scores.put(d.id, score);
        }
        List<Map.Entry<Doc, Double>> ranked = new ArrayList<>();
        for (Map.Entry<String, Double> e : scores.entrySet()) {
            ranked.add(new AbstractMap.SimpleEntry<>(docs.get(e.getKey()), e.getValue()));
        }
        ranked.sort((a, b2) -> Double.compare(b2.getValue(), a.getValue()));
        if (topK > 0 && ranked.size() > topK) return ranked.subList(0, topK);
        return ranked;
    }
}