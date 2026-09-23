
package com.abandonware.ai.agent.integrations;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;


public interface CrossEncoder {
    /**
     * Returns a relevance score in [0,1] for (query, title, content).
     */
    double score(String query, String title, String content);

    static CrossEncoder fromEnv() {
        String mode = System.getenv().getOrDefault("CROSS_ENCODER", "heuristic").toLowerCase();
        return fromMode(mode, OnnxCrossEncoder::new);
    }

    static CrossEncoder fromMode(String mode, CrossEncoderFactory onnxFactory) {
        String safeMode = mode == null ? "heuristic" : mode.toLowerCase();
        if ("onnx".equals(safeMode)) {
            try {
                return onnxFactory.create();
            } catch (Throwable t) {
                traceSuppressed("onnx.factory", safeMode, t);
                return new HeuristicCrossEncoder();
            }
        } else if ("off".equals(safeMode)) {
            return null;
        } else {
            return new HeuristicCrossEncoder();
        }
    }

    private static void traceSuppressed(String stage, String mode, Throwable error) {
        TraceStore.put("agent.crossEncoder.suppressed", true);
        TraceStore.put("agent.crossEncoder.suppressed.stage", stage);
        TraceStore.put("agent.crossEncoder.suppressed.errorType",
                error == null ? "unknown" : error.getClass().getSimpleName());
        TraceStore.put("agent.crossEncoder.suppressed.modeHash", SafeRedactor.hashValue(mode));
        TraceStore.put("agent.crossEncoder.suppressed.modeLength", mode == null ? 0 : mode.length());
    }

    @FunctionalInterface
    interface CrossEncoderFactory {
        CrossEncoder create() throws Exception;
    }
}
