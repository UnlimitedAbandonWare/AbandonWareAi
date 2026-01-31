package com.abandonware.ai.agent.integrations.service.alloc;


/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.integrations.service.alloc.KAllocator
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.integrations.service.alloc.KAllocator
role: config
*/
public class KAllocator {
    public int[] allocate(double[] logits, int budget){
        if (logits == null || logits.length == 0 || budget <= 0) return new int[]{0,0,0};
        double max = Double.NEGATIVE_INFINITY;
        for (double v: logits) if (v > max) max = v;
        double sum = 0.0;
        double[] e = new double[logits.length];
        for (int i=0;i<logits.length;i++){ e[i] = Math.exp(logits[i] - max); sum += e[i]; }
        int[] ks = new int[logits.length];
        int used = 0;
        for (int i=0;i<logits.length;i++){
            ks[i] = (int)Math.floor((e[i]/sum)*budget);
            used += ks[i];
        }
        // distribute remainder
        int rem = budget - used;
        for (int i=0; i<rem; i++){ ks[i % ks.length]++; }
        return ks;
    }
}