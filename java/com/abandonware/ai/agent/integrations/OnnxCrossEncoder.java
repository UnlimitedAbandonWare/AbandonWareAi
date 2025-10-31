
package com.abandonware.ai.agent.integrations;

import java.io.File;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;



/**
 * ONNX Cross Encoder loader via reflection.
 * Requires environment:
 *   CROSS_ENCODER=onnx
 *   CE_ONNX_MODEL=/path/to/model.onnx
 *
 * Falls back to HeuristicCrossEncoder if onnxruntime is not present or fails.
 */
public class OnnxCrossEncoder implements CrossEncoder {

    private final Object session; // ai.onnxruntime.OrtSession
    private final Object env;     // ai.onnxruntime.OrtEnvironment
    private final Method runMethod;
    private final HeuristicCrossEncoder fallback = new HeuristicCrossEncoder();

    public OnnxCrossEncoder() throws Exception {
        String modelPath = System.getenv("CE_ONNX_MODEL");
        if (modelPath == null || modelPath.isBlank() || !Files.exists(Paths.get(modelPath))) {
            throw new IllegalStateException("CE_ONNX_MODEL missing");
        }
        Class<?> envClz = Class.forName("ai.onnxruntime.OrtEnvironment");
        Method getEnv = envClz.getMethod("getEnvironment");
        this.env = getEnv.invoke(null);
        Method create = envClz.getMethod("createSession", String.class, Class.forName("ai.onnxruntime.SessionOptions"));
        Class<?> optsClz = Class.forName("ai.onnxruntime.SessionOptions");
        Object opts = optsClz.getConstructor().newInstance();
        this.session = create.invoke(env, modelPath, opts);
        this.runMethod = session.getClass().getMethod("run", java.util.Map.class);
    }

    @Override
    public double score(String query, String title, String content) {
        // For brevity, not implementing real tokenization; use heuristic until model wiring is defined.
        // You can extend here to feed tokens into the ONNX model.
        return fallback.score(query, title, content);
    }
}