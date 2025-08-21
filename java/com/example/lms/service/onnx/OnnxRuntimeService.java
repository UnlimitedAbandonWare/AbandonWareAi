

        package com.example.lms.service.onnx;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

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
// 개선: onnx 백엔드일 때에만 서비스 생성(선택)
@ConditionalOnProperty(
        prefix = "abandonware.reranker", name = "backend", havingValue = "onnx-runtime"
)
public class OnnxRuntimeService {

    // 개선: 로거 추가
    private static final Logger log = LoggerFactory.getLogger(OnnxRuntimeService.class);

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
    public void init() {
        try {
            this.env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            String provider = (executionProvider == null ? "" : executionProvider.trim().toLowerCase());
            if ("cuda".equals(provider)) options.addCUDA(); // 수정: switch→단순 분기

            String resolvedPath = resolveModelPath(modelPath);
            if (resolvedPath != null && !resolvedPath.isBlank()) {
                this.session = env.createSession(resolvedPath, options);
                log.info("[ONNX] model loaded: {}", resolvedPath);
            } else {
                this.session = null; // 개선: 모델 없으면 폴백(예외 미던짐)
                log.warn("[ONNX] model-path not set or not resolvable. Falling back to lexical scorer.");
            }
        } catch (Throwable t) {
            // 개선: 어떤 예외도 앱 기동을 막지 않음
            this.session = null;
            log.warn("[ONNX] initialisation failed, falling back (reason: {})", t.toString());
        }
    }

    /**
     * Resolve a model path that may be prefixed with {@code classpath:}.
     *
     * @param path the configured path
     * @return a file system path suitable for {@link OrtSession#createSession(String, OrtSession.SessionOptions)}
     */
    // 수정: throws 제거, 내부에서 안전 처리
    private String resolveModelPath(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String trimmed = path.trim();
        String prefix = "classpath:";
        if (trimmed.startsWith(prefix)) {
            String location = trimmed.substring(prefix.length());
            Resource resource = new ClassPathResource(location);
            if (!resource.exists()) {
                log.warn("[ONNX] classpath resource not found: {}", location);
                return null;
            }
            try {
                // 개발환경(Exploded)에서는 파일 경로가 나옴
                return resource.getFile().getAbsolutePath();
            } catch (IOException e) {
                // 패키징(JAR)된 경우 getFile() 불가 → 여기서는 로드 건너뛰고 폴백
                log.warn("[ONNX] resource is not a file (likely inside JAR). Skipping ONNX load.");
                return null;
            }
        }
        return trimmed;
    }

    // 개선: 상위 레이어에서 ONNX 세션 가용성 체크용
    public boolean available() {
        return session != null;
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
        // TODO: Add actual ONNX inference logic here if `available()` is true
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