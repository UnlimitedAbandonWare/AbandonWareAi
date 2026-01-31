
package com.abandonware.ai.agent.integrations;


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