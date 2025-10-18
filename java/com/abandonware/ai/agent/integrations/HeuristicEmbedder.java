
package com.abandonware.ai.agent.integrations;

import java.util.List;
import java.util.Random;



/**
 * Very small heuristic embedder hashing tokens into a fixed 256-dim vector.
 */
public class HeuristicEmbedder implements Embedder {
    private static final int D = 256;

    @Override
    public float[] embed(String text) {
        float[] v = new float[D];
        List<String> toks = TextUtils.tokenize(text);
        for (String t : toks) {
            int h = Math.abs(t.hashCode());
            int idx = h % D;
            v[idx] += 1.0f;
        }
        // L2 normalize
        double norm = 0.0;
        for (float x : v) norm += x * x;
        norm = Math.sqrt(Math.max(1e-9, norm));
        for (int i = 0; i < v.length; i++) v[i] /= (float) norm;
        return v;
    }
}