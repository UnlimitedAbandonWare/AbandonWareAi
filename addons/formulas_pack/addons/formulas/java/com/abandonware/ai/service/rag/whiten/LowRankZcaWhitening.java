/* 
 * Extracted formula module for orchestration
 * Source zip: src111_merge15 - 2025-10-20T134617.846.zip
 * Source path: app/src/main/java/com/abandonware/ai/service/rag/whiten/LowRankZcaWhitening.java
 * Extracted: 2025-10-20T15:26:37.265324Z
 */
package com.abandonware.ai.service.rag.whiten;

import java.util.concurrent.atomic.AtomicReference;
/**
 * [GPT-PRO-AGENT v2] — concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.service.rag.whiten.LowRankZcaWhitening
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: uses concurrent primitives.
 */
/* agent-hint:
id: com.abandonware.ai.service.rag.whiten.LowRankZcaWhitening
role: config
*/
public class LowRankZcaWhitening implements Whitening {
    private final AtomicReference<LowRankWhiteningStats> ref = new AtomicReference<>();

    public void refit(LowRankWhiteningStats s){ ref.set(s); }

    @Override public boolean isEnabled(){ return ref.get() != null; }

    @Override
    public float[] apply(float[] x){
        LowRankWhiteningStats s = ref.get();
        if (s == null || x == null || s.mean.length == 0 || s.eigVals.length == 0) return x;
        int d = Math.min(x.length, s.mean.length);
        float[] xc = new float[d];
        for (int i=0;i<d;i++) xc[i] = x[i] - s.mean[i];
        // y = V^T (x - mu)
        int r = Math.min(s.eigVals.length, s.eigVecs.length);
        float[] y = new float[r];
        for (int i=0;i<r;i++){
            float acc = 0f;
            float[] vi = s.eigVecs[i];
            for (int j=0;j<d && j<vi.length;j++) acc += vi[j] * xc[j];
            y[i] = (float)(acc / Math.sqrt(s.eigVals[i] + s.epsilon));
        }
        // back-project: V y
        float[] out = new float[d];
        for (int j=0;j<d;j++){
            double acc = 0.0;
            for (int i=0;i<r;i++){
                float[] vi = s.eigVecs[i];
                float vij = j < vi.length ? vi[j] : 0f;
                acc += vij * y[i];
            }
            out[j] = (float)acc;
        }
        return out;
    }
}