
package com.abandonware.ai.agent.integrations;

import java.util.*;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.integrations.ColbertLiteReranker
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.integrations.ColbertLiteReranker
role: config
*/
public class ColbertLiteReranker implements EmbeddingReranker {

    private final Embedder embedder;

    public ColbertLiteReranker(Embedder embedder) {
        this.embedder = embedder;
    }

    @Override
    public List<Map<String, Object>> rerank(String query, List<Map<String, Object>> items) {
        // naive late interaction: token-embedding max-sim average approximated via unigram hashing
        List<String> qToks = TextUtils.tokenize(query);
        float[][] qVecs = new float[qToks.size()][];
        for (int i=0;i<qToks.size();i++) qVecs[i] = embedder.embed(qToks.get(i));

        List<Scored> tmp = new ArrayList<>();
        for (Map<String,Object> m : items) {
            String title = String.valueOf(m.getOrDefault("title",""));
            String snippet = String.valueOf(m.getOrDefault("snippet",""));
            String text = title + "\n" + snippet;
            List<String> dToks = TextUtils.tokenize(text);
            float[][] dVecs = new float[Math.min(64, dToks.size())][];
            int lim = Math.min(64, dToks.size());
            for (int i=0;i<lim;i++) dVecs[i] = embedder.embed(dToks.get(i));
            double score = 0.0;
            for (float[] qv : qVecs) {
                double mx = 0.0;
                for (int j=0;j<lim;j++) {
                    mx = Math.max(mx, cosine(qv, dVecs[j]));
                }
                score += mx;
            }
            score /= Math.max(1, qVecs.length);
            double base = toDouble(m.get("score"));
            double finalScore = 0.7 * score + 0.3 * Math.log1p(Math.max(0.0, base));
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
        if (o instanceof Number) return ((Number)o).doubleValue();
        try { return Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return 0.0; }
    }
}