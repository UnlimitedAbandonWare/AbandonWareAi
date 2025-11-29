
package com.abandonware.ai.agent.integrations;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.integrations.CrossEncoder
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.integrations.CrossEncoder
role: config
*/
public interface CrossEncoder {
    /**
     * Returns a relevance score in [0,1] for (query, title, content).
     */
    double score(String query, String title, String content);

    static CrossEncoder fromEnv() {
        String mode = System.getenv().getOrDefault("CROSS_ENCODER", "heuristic").toLowerCase();
        if ("onnx".equals(mode)) {
            try {
                return new OnnxCrossEncoder();
            } catch (Throwable t) {
                // fall back
                return new HeuristicCrossEncoder();
            }
        } else if ("off".equals(mode)) {
            return null;
        } else {
            return new HeuristicCrossEncoder();
        }
    }
}