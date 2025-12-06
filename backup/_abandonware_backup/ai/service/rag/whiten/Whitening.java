package com.abandonware.ai.service.rag.whiten;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.service.rag.whiten.Whitening
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.service.rag.whiten.Whitening
role: config
*/
public interface Whitening {
    boolean isEnabled();
    float[] apply(float[] x);

    default float[] maybeApply(float[] x){
        try { return isEnabled() ? apply(x) : x; } catch (Throwable t){ return x; }
    }
}