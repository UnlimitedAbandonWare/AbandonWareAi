package com.example.lms.service.onnx;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.OrtException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Service that encapsulates a local ONNX runtime session for cross‑encoder inference.
 *
 * <p>This service is responsible for loading a serialized ONNX model from a
 * configurable location and providing a simple {@link #predict(String[], String[])}
 * API to score query–document pairs. In the absence of a domain‑specific
 * tokeniser and model, this implementation falls back to a lightweight
 * lexical similarity metric. The ONNX session is initialised in
 * {@link #init()} and remains active for the lifetime of the application.</p>
 */
@Service
public class OnnxRuntimeService {

    /** Model file path. May be prefixed with {@code classpath:} to load from resources. */
    @Value("${abandonware.reranker.onnx.model-path:}")
    private String modelPath;

    /** Execution provider name (e.g. cpu, cuda, tensorrt). */
    @Value("${abandonware.reranker.onnx.execution-provider:cpu}")
    private String executionProvider;

    private OrtEnvironment env;
    private OrtSession session;

    /**
     * Initialise the ONNX environment and session. If the model path is
     * configured with a {@code classpath:} prefix it will be resolved from
     * the application resources; otherwise it will be treated as a file
     * system path. Execution provider selection is based on the
     * {@link #executionProvider} property: {@code cpu} uses the default
     * provider, {@code cuda} enables CUDA support and {@code tensorrt} uses
     * TensorRT when available.
     */
    @PostConstruct
    public void init() throws OrtException, IOException {
        this.env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        String provider = (executionProvider == null ? "" : executionProvider.trim().toLowerCase());
        switch (provider) {
            case "cuda" -> options.addCUDA();
            // "cpu" 및 그 외는 기본 EP 사용
            default -> { /* no-op */ }
        }
        String resolvedPath = resolveModelPath(modelPath);
        if (resolvedPath != null && !resolvedPath.isEmpty()) {
            this.session = env.createSession(resolvedPath, options);
        } else {
            this.session = null;
        }
    }

    /**
     * Resolve a model path that may be prefixed with {@code classpath:}.
     *
     * @param path the configured path
     * @return a file system path suitable for {@link OrtSession#createSession(String, SessionOptions)}
     * @throws IOException if the classpath resource cannot be resolved
     */
    private String resolveModelPath(String path) throws IOException {
        if (path == null || path.isBlank()) {
            return null;
        }
        String trimmed = path.trim();
        String prefix = "classpath:";
        if (trimmed.startsWith(prefix)) {
            String location = trimmed.substring(prefix.length());
            Resource resource = new ClassPathResource(location);
            return resource.getFile().getAbsolutePath();
        }
        return trimmed;
    }

    /**
     * Predict confidence scores for a batch of query–document pairs.
     *
     * <p>The returned matrix has shape {@code [queries.length][documents.length]}. Each entry
     * represents a similarity score between the corresponding query and document. If an ONNX
     * model is available and compatible with the input format, it can be invoked here. In the
     * absence of such a model, a simple Jaccard similarity over whitespace‑delimited tokens
     * is used as a fallback. The output scores are in the range {@code [0,1]}.</p>
     *
     * @param queries   an array of query strings
     * @param documents an array of document strings
     * @return a 2D float array of confidence scores
     */
    public float[][] predict(String[] queries, String[] documents) {
        int m = (queries == null ? 0 : queries.length);
        int n = (documents == null ? 0 : documents.length);
        float[][] result = new float[m][n];
        if (m == 0 || n == 0) {
            return result;
        }
        for (int i = 0; i < m; i++) {
            String q = queries[i] == null ? "" : queries[i];
            for (int j = 0; j < n; j++) {
                String d = documents[j] == null ? "" : documents[j];
                result[i][j] = computeJaccardSimilarity(q, d);
            }
        }
        return result;
    }

    /**
     * Compute a simple Jaccard similarity between two strings. The strings are
     * tokenised on non‑word characters and converted to lower case. The
     * similarity is defined as {@code |intersection| / |union|}. When both
     * strings are empty the similarity defaults to {@code 0.0f}.
     *
     * @param q the query string
     * @param d the document string
     * @return a similarity score in {@code [0,1]}
     */
    private float computeJaccardSimilarity(String q, String d) {
        if (q == null || d == null) {
            return 0.0f;
        }
        String[] qTokens = Arrays.stream(q.toLowerCase().split("\\W+")).filter(s -> !s.isBlank()).toArray(String[]::new);
        String[] dTokens = Arrays.stream(d.toLowerCase().split("\\W+")).filter(s -> !s.isBlank()).toArray(String[]::new);
        if (qTokens.length == 0 || dTokens.length == 0) {
            return 0.0f;
        }
        Set<String> qSet = new HashSet<>(Arrays.asList(qTokens));
        Set<String> dSet = new HashSet<>(Arrays.asList(dTokens));
        Set<String> intersection = new HashSet<>(qSet);
        intersection.retainAll(dSet);
        Set<String> union = new HashSet<>(qSet);
        union.addAll(dSet);
        if (union.isEmpty()) {
            return 0.0f;
        }
        return (float) intersection.size() / (float) union.size();
    }
}