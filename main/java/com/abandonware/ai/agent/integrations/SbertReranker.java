
package com.abandonware.ai.agent.integrations;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;

import java.util.*;



public class SbertReranker implements EmbeddingReranker {

    private final Embedder embedder;

    public SbertReranker(Embedder embedder) {
        this.embedder = embedder;
    }

    @Override
    public List<Map<String, Object>> rerank(String query, List<Map<String, Object>> items) {
        float[] q = embedder.embed(query);
        List<Scored> tmp = new ArrayList<>();
        for (Map<String,Object> m : items) {
            String title = String.valueOf(m.getOrDefault("title",""));
            String snippet = String.valueOf(m.getOrDefault("snippet",""));
            float[] d = embedder.embed(title + "\n" + snippet);
            double cos = cosine(q, d);
            double base = toDouble(m.get("score"));
            double finalScore = 0.7 * cos + 0.3 * Math.log1p(Math.max(0.0, base));
            tmp.add(new Scored(m, finalScore));
        }
        tmp.sort((a,b)-> Double.compare(b.s, a.s));
        List<Map<String,Object>> out = new ArrayList<>();
        int rank = 1;
        for (Scored s : tmp) {
            Map<String,Object> m = new LinkedHashMap<>(s.m);
            m.put("score", s.s);
            m.put("rank", rank++);
            out.add(m);
        }
        return out;
    }

    private static class Scored {
        Map<String,Object> m; double s; Scored(Map<String,Object> m, double s){this.m=m;this.s=s;}
    }

    private static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0.0;
        double dot = 0, na=0, nb=0;
        for (int i=0;i<a.length;i++) { dot+= a[i]*b[i]; na+=a[i]*a[i]; nb+=b[i]*b[i]; }
        if (na==0 || nb==0) return 0.0;
        return dot / Math.sqrt(na*nb);
    }

    private static double toDouble(Object o) {
        if (o == null) return 0.0;
        if (o instanceof Number) return finiteOrFallback(((Number)o).doubleValue(), o);
        try {
            return finiteOrFallback(Double.parseDouble(String.valueOf(o)), o);
        } catch (NumberFormatException e) {
            traceSuppressed("score.parseFallback", o, e);
            return 0.0;
        }
    }

    private static double finiteOrFallback(double value, Object raw) {
        if (Double.isFinite(value)) {
            return value;
        }
        traceSuppressed("score.parseFallback", raw, new NumberFormatException("non_finite"));
        return 0.0;
    }

    private static void traceSuppressed(String stage, Object value, Throwable error) {
        String key = "agent.sbert." + stage;
        String raw = String.valueOf(value);
        TraceStore.put(key, true);
        TraceStore.put(key + ".errorType", "invalid_number");
        TraceStore.put(key + ".valueHash", SafeRedactor.hashValue(raw));
        TraceStore.put(key + ".valueLength", raw.length());
    }
}
