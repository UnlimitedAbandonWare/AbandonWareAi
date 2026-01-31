
package com.abandonware.ai.agent.integrations;

import java.util.*;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.integrations.ColbertReranker
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.integrations.ColbertReranker
role: config
*/
public class ColbertReranker implements EmbeddingReranker {

    private final TokenEmbedder tokenEmbedder;

    public ColbertReranker(TokenEmbedder tokenEmbedder) {
        this.tokenEmbedder = tokenEmbedder;
    }

    @Override
    public List<Map<String, Object>> rerank(String query, List<Map<String, Object>> items) {
        float[][] q = tokenEmbedder.embedTokens(query);
        List<Scored> tmp = new ArrayList<>();
        for (Map<String,Object> m : items) {
            String title = String.valueOf(m.getOrDefault("title",""));
            String snippet = String.valueOf(m.getOrDefault("snippet",""));
            float[][] d = tokenEmbedder.embedTokens(title + "\n" + snippet);
            double score = lateInteraction(q, d);
            double base = toDouble(m.get("score"));
            double finalScore = 0.85 * score + 0.15 * Math.log1p(Math.max(0.0, base));
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

    private static double lateInteraction(float[][] q, float[][] d) {
        if (q == null || q.length == 0 || d == null || d.length == 0) return 0.0;
        double sum = 0.0;
        for (float[] qv : q) {
            double mx = 0.0;
            for (float[] dv : d) {
                mx = Math.max(mx, cosine(qv, dv));
            }
            sum += mx;
        }
        return sum / q.length;
    }

    private static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0.0;
        double dot=0,na=0,nb=0;
        for (int i=0;i<a.length;i++) { dot += a[i]*b[i]; na+=a[i]*a[i]; nb+=b[i]*b[i]; }
        if (na==0 || nb==0) return 0.0;
        return dot / Math.sqrt(na*nb);
    }

    private static class Scored { Map<String,Object> m; double s; Scored(Map<String,Object> m, double s){this.m=m;this.s=s;} }
    private static double toDouble(Object o){ if (o==null) return 0.0; if(o instanceof Number) return ((Number)o).doubleValue(); try{return Double.parseDouble(String.valueOf(o));}catch(Exception e){return 0.0;}}
}