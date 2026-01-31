package com.example.lms.service.rag.whiten;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.example.lms.service.rag.whiten.LegacyLowRankWhiteningAdapter
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.example.lms.service.rag.whiten.LegacyLowRankWhiteningAdapter
role: config
*/
public class LegacyLowRankWhiteningAdapter implements Whitening {
    private final int rank;
    public LegacyLowRankWhiteningAdapter(int rank){ this.rank=rank; }
    public double[] apply(double[] v){
        return v==null? new double[0] : v.clone();
    }
}