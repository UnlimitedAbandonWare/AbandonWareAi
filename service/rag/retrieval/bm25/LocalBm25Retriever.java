package com.abandonware.ai.normalization.service.rag.retrieval.bm25;

import java.util.*;
import java.util.regex.Pattern;

/** Minimal BM25 implementation placeholder (non-Lucene). */
public class LocalBm25Retriever {
    static class Doc { String id; String text; }
    private final Map<String, Doc> docs = new HashMap<>();
    private final Map<String, Map<String, Integer>> tf = new HashMap<>();
    private final Map<String, Integer> df = new HashMap<>();
    private double avgLen = 0;
    private static final Pattern TOKEN = Pattern.compile("[^a-z0-9]+");
    public void addDoc(String id, String text) {
        Doc d = new Doc(); d.id=id; d.text=text==null? "": text.toLowerCase();
        docs.put(id, d);
        String[] toks = TOKEN.split(d.text);
        avgLen = (avgLen*(docs.size()-1) + toks.length)/docs.size();
        Map<String,Integer> f = new HashMap<>();
        for (String t: toks) if (!t.isEmpty()) f.put(t, f.getOrDefault(t,0)+1);
        tf.put(id, f);
        for (String t: f.keySet()) df.put(t, df.getOrDefault(t,0)+1);
    }
    public List<String> search(String query, int k) {
        String[] q = TOKEN.split(query.toLowerCase());
        int N = docs.size();
        double k1=1.5, b=0.75;
        List<Map.Entry<String,Double>> scores = new ArrayList<>();
        for (String id: docs.keySet()) {
            double s=0; Map<String,Integer> f=tf.get(id);
            int dl = f.values().stream().mapToInt(i->i).sum();
            for (String term: q) {
                if (term.isEmpty()) continue;
                int fi = f.getOrDefault(term,0);
                int dfi = df.getOrDefault(term,0);
                if (dfi==0) continue;
                double idf = Math.log( (N - dfi + 0.5) / (dfi + 0.5) + 1 );
                double denom = fi + k1*(1 - b + b*dl/Math.max(1.0, avgLen));
                s += idf * (fi * (k1+1)) / Math.max(1e-9, denom);
            }
            scores.add(new AbstractMap.SimpleEntry<>(id,s));
        }
        scores.sort((a,b2)->Double.compare(b2.getValue(), a.getValue()));
        List<String> out = new ArrayList<>();
        for (int i=0;i<Math.min(k,scores.size());i++) out.add(scores.get(i).getKey());
        return out;
    }
}