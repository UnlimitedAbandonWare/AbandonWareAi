
package com.abandonware.ai.agent.integrations;


public interface Embedder {
    /**
     * Returns a dense vector representation. Dimension may vary by backend; consumers should only use cosine.
     */
    float[] embed(String text);
}