package com.example.lms.service.service.rag.bm25;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class Bm25Index {
    public static class Doc {
        public final String id;
        public final String text;

        public Doc(String id, String text) {
            this.id = id;
            this.text = text == null ? "" : text;
        }
    }

    private static final double K1 = 1.5;
    private static final double B = 0.75;

    private final Map<String, String[]> docs = new ConcurrentHashMap<>();
    private final Map<String, Integer> docLen = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Integer>> tf = new ConcurrentHashMap<>();
    private final Map<String, Integer> df = new ConcurrentHashMap<>();
    private volatile double avgdl = 0.0;

    public synchronized void put(Doc doc) {
        String[] terms = tokenize(doc == null ? "" : doc.text);
        String id = doc == null || doc.id == null ? "" : doc.id;
        docs.put(id, terms);
        docLen.put(id, terms.length);
        Map<String, Integer> freq = new HashMap<>();
        for (String term : terms) {
            freq.put(term, 1 + freq.getOrDefault(term, 0));
        }
        tf.put(id, freq);
        rebuildDocumentFrequency();
    }

    public List<Map.Entry<String, Double>> search(String query, int topK) {
        Set<String> queryTerms = new HashSet<>(Arrays.asList(tokenize(query)));
        int totalDocs = docs.size();
        List<Map.Entry<String, Double>> out = new ArrayList<>();
        for (String docId : docs.keySet()) {
            double score = 0.0;
            int dl = docLen.getOrDefault(docId, 1);
            Map<String, Integer> freq = tf.getOrDefault(docId, Collections.emptyMap());
            for (String term : queryTerms) {
                int f = freq.getOrDefault(term, 0);
                if (f == 0) {
                    continue;
                }
                int dfi = df.getOrDefault(term, 0);
                double idf = Math.log((totalDocs - dfi + 0.5) / (dfi + 0.5) + 1e-9);
                double denom = f + K1 * (1 - B + B * (dl / Math.max(1.0, avgdl)));
                score += idf * ((f * (K1 + 1)) / denom);
            }
            if (score != 0.0) {
                out.add(new AbstractMap.SimpleEntry<>(docId, score));
            }
        }
        out.sort((left, right) -> Double.compare(right.getValue(), left.getValue()));
        int limit = Math.max(0, topK);
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    private synchronized void rebuildDocumentFrequency() {
        df.clear();
        for (Map<String, Integer> value : tf.values()) {
            for (String term : value.keySet()) {
                df.put(term, 1 + df.getOrDefault(term, 0));
            }
        }
        int totalLength = 0;
        for (int length : docLen.values()) {
            totalLength += length;
        }
        avgdl = docs.isEmpty() ? 0.0 : totalLength / (double) docs.size();
    }

    private static String[] tokenize(String value) {
        if (value == null) {
            return new String[0];
        }
        String normalized = value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{Nd}]+", " ").trim();
        return normalized.isEmpty() ? new String[0] : normalized.split("\\s+");
    }
}
