/* 
 * Extracted formula module for orchestration
 * Source zip: src111_merge15 - 2025-10-20T134617.846.zip
 * Source path: app/src/main/java/com/abandonware/ai/agent/service/rag/rerank/DppDiversityReranker.java
 * Extracted: 2025-10-20T15:26:37.373859Z
 */
package com.abandonware.ai.agent.service.rag.rerank;


import java.util.*;
/**
 * [GPT-PRO-AGENT v2] — concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.service.rag.rerank.DppDiversityReranker
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.service.rag.rerank.DppDiversityReranker
role: config
*/
public class DppDiversityReranker {
    public static class Candidate {
        public final String id;
        public final double score;
        public final double[] vec;
        public Candidate(String id, double score, double[] vec){ this.id=id; this.score=score; this.vec=vec; }
    }
    public List<Candidate> rerank(List<Candidate> in, int k, double simTh){
        if (in==null) return Collections.emptyList();
        in.sort((a,b)->Double.compare(b.score,a.score));
        List<Candidate> out = new ArrayList<>();
        for (Candidate c : in) {
            boolean ok = true;
            for (Candidate d : out) {
                if (cos(c.vec, d.vec) >= simTh) { ok=false; break; }
            }
            if (ok) out.add(c);
            if (out.size()>=k) break;
        }
        return out;
    }
    private double cos(double[] a, double[] b) {
        if (a==null||b==null||a.length!=b.length) return 0.0;
        double dot=0,na=0,nb=0;
        for (int i=0;i<a.length;i++){ dot+=a[i]*b[i]; na+=a[i]*a[i]; nb+=b[i]*b[i]; }
        if (na==0||nb==0) return 0.0;
        return dot/Math.sqrt(na*nb);
    }
}