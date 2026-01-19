
package com.abandonware.ai.agent.integrations;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.integrations.Embedder
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.integrations.Embedder
role: config
*/
public interface Embedder {
    /**
     * Returns a dense vector representation. Dimension may vary by backend; consumers should only use cosine.
     */
    float[] embed(String text);
}