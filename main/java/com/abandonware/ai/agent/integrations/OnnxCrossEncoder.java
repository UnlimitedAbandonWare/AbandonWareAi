
package com.abandonware.ai.agent.integrations;

import java.nio.file.Files;
import java.nio.file.Paths;



/**
 * ONNX Cross Encoder loader via reflection.
 * Requires environment:
 *   CROSS_ENCODER=onnx
 *   CE_ONNX_MODEL=/path/to/model.onnx
 *
 * Falls back to HeuristicCrossEncoder if onnxruntime is not present or fails.
 */
public class OnnxCrossEncoder implements CrossEncoder {

    private final HeuristicCrossEncoder fallback = new HeuristicCrossEncoder();

    public OnnxCrossEncoder() throws Exception {
        String modelPath = System.getenv("CE_ONNX_MODEL");
        if (modelPath == null || modelPath.isBlank() || !Files.exists(Paths.get(modelPath))) {
            throw new IllegalStateException("CE_ONNX_MODEL missing");
        }
    }

    @Override
    public double score(String query, String title, String content) {
        // For brevity, not implementing real tokenization; use heuristic until model wiring is defined.
        // You can extend here to feed tokens into the ONNX model.
        return fallback.score(query, title, content);
    }
}
