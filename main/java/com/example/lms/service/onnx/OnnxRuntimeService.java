package com.example.lms.service.onnx;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnExpression(
        "('${abandonware.reranker.backend:embedding-model}' == 'onnx-runtime' "
                + "or '${abandonware.reranker.backend:embedding-model}' == 'onnx') "
                + "and '${abandonware.reranker.onnx.runtime-enabled:false}' == 'true'")
public class OnnxRuntimeService {
    private static final Logger log = LoggerFactory.getLogger(OnnxRuntimeService.class);
    private static final int MIN_MODEL_BYTES = 1_000_000;

    @Value("${abandonware.reranker.onnx.execution-provider:${onnx.execution-provider:cpu}}")
    private String executionProvider;

    @Value("${abandonware.reranker.onnx.max-seq-len:${onnx.max-seq-len:256}}")
    private int maxSeqLen;

    @Value("${abandonware.reranker.onnx.fallback-enabled:true}")
    private boolean fallbackEnabled;

    @Value("${abandonware.reranker.onnx.normalize:true}")
    private boolean normalize;

    @Value("${abandonware.reranker.onnx.model-path:${onnx.model-path:}}")
    private String modelPath;

    private volatile String disabledReason = "onnxruntime_dependency_unavailable";

    @PostConstruct
    public void init() {
        if (isPlaceholderModelPath(modelPath)) {
            disablePlaceholderOrFail();
            return;
        }
        byte[] modelBytes = readModelBytes(modelPath);
        if (modelBytes.length > 0 && isPlaceholderOrTooSmallModel(modelBytes)) {
            disablePlaceholderOrFail();
            return;
        }
        if (modelBytes.length >= MIN_MODEL_BYTES) {
            disable("session_create_failed");
            log.debug("[ONNX] fail-soft stage={}", "session.create");
            TraceStore.put("rerank.onnx.sessionFailed", true);
            TraceStore.put("rerank.onnx.sessionFailureClass", "onnx_session_create_failed");
            TraceStore.put("rerank.onnx.modelBytes", modelBytes.length);
            RuntimeException t = new IllegalStateException("onnx_session_create_failed");
            log.warn("[AWX][onnx] action=initialisation_failed fallbackEnabled={} errorHash={} errorLength={}",
                    fallbackEnabled, SafeRedactor.hashValue(messageOf(t)), messageLength(t));
            if (!fallbackEnabled) {
                throw t;
            }
            return;
        }
        disable(disabledReason);
    }

    public boolean available() {
        return false;
    }

    public boolean active() {
        return false;
    }

    public float[][] predict(String[] queries, String[] documents) {
        int n = Math.min(length(queries), length(documents));
        float[][] out = new float[n][1];
        for (int i = 0; i < n; i++) {
            out[i][0] = (float) scorePair(valueAt(queries, i), valueAt(documents, i));
        }
        return out;
    }

    public double scorePair(String query, String document) {
        try {
            log.debug("[ONNX] fail-soft stage={}", "encodePair");
            log.debug("[ONNX] fail-soft stage={}", "score.inference");
            TraceStore.put("rerank.onnx.inferenceFallback", "lexical_inversion");
            TraceStore.put("rerank.onnx.disabledReason", this.disabledReason);
            TraceStore.put("rerank.onnx.queryHash12", SafeRedactor.hash12(query));
            TraceStore.put("rerank.onnx.queryLength", query == null ? 0 : query.length());
            return jaccard(query, document);
        } catch (RuntimeException t) {
            traceInferenceFallback(t, query);
            return 0.0d;
        }
    }

    public boolean isAvailable() {
        return available();
    }

    public String getDisabledReason() {
        return SafeRedactor.traceLabelOrFallback(disabledReason, "");
    }

    public boolean isFallbackEnabled() {
        return fallbackEnabled;
    }

    public boolean isNormalizeEnabled() {
        return normalize;
    }

    public String getExecutionProvider() {
        return executionProvider == null || executionProvider.isBlank() ? "cpu" : executionProvider;
    }

    public int getMaxSeqLen() {
        return Math.max(1, maxSeqLen);
    }

    public List<String> getInputNames() {
        return List.of();
    }

    public List<String> getOutputNames() {
        return List.of();
    }

    public static boolean isPlaceholderOrTooSmallModel(byte[] bytes) {
        if (bytes == null || bytes.length < MIN_MODEL_BYTES) {
            return true;
        }
        String prefix = new String(bytes, 0, Math.min(bytes.length, 64), java.nio.charset.StandardCharsets.UTF_8)
                .toUpperCase(Locale.ROOT);
        return prefix.contains("ONNXPLACEHOLDER") || prefix.contains("PLACEHOLDER");
    }

    private static boolean isPlaceholderModelPath(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String normalized = path.trim().replace('\\', '/').toLowerCase(Locale.ROOT);
        return normalized.equals("classpath:models/your-cross-encoder.onnx")
                || normalized.equals("models/your-cross-encoder.onnx")
                || normalized.endsWith("/models/your-cross-encoder.onnx")
                || normalized.endsWith("/your-cross-encoder.onnx")
                || normalized.contains("/placeholder/");
    }

    private void disablePlaceholderOrFail() {
        disable("placeholder_or_too_small_model");
        if (!fallbackEnabled) {
            throw new IllegalStateException("ONNX placeholder_or_too_small_model and fallback disabled");
        }
    }

    private void disable(String reason) {
        this.disabledReason = SafeRedactor.traceLabelOrFallback(reason, "unknown");
        TraceStore.put("rerank.onnx.ready", false);
        TraceStore.put("rerank.onnx.available", false);
        TraceStore.put("rerank.onnx.disabledReason", this.disabledReason);
        TraceStore.put("rerank.onnx.fallback", "lexical");
        TraceStore.put("onnx.status", "disabled");
        TraceStore.put("onnx.disabledReason", this.disabledReason);
    }

    private static byte[] readModelBytes(String path) {
        if (path == null || path.isBlank()) {
            return new byte[0];
        }
        String p = path.trim();
        try {
            if (p.startsWith("classpath:")) {
                String resource = p.substring("classpath:".length());
                if (resource.startsWith("/")) {
                    resource = resource.substring(1);
                }
                try (InputStream in = OnnxRuntimeService.class.getClassLoader().getResourceAsStream(resource)) {
                    return in == null ? new byte[0] : in.readAllBytes();
                }
            }
            Path file = Path.of(p);
            return Files.exists(file) ? Files.readAllBytes(file) : new byte[0];
        } catch (IOException | RuntimeException t) {
            log.debug("[ONNX] fail-soft stage={}", "open");
            log.warn("[AWX][onnx] action=initialisation_failed fallbackEnabled={} errorHash={} errorLength={}",
                    true, SafeRedactor.hashValue(messageOf(t)), messageLength(t));
            return new byte[0];
        }
    }

    private static void traceInferenceFallback(Throwable t, String query) {
        TraceStore.put("rerank.onnx.inferenceFailed", true);
        TraceStore.put("rerank.onnx.inferenceFallback", "lexical_inversion");
        TraceStore.put("rerank.onnx.inferenceFailureClass", SafeRedactor.traceLabelOrFallback(
                t == null ? null : t.getClass().getSimpleName(), "unknown"));
        TraceStore.put("rerank.onnx.queryHash12", SafeRedactor.hash12(query));
        TraceStore.put("rerank.onnx.queryLength", query == null ? 0 : query.length());
    }

    private static void logCpuFallback(Throwable t) {
        log.warn("[AWX][onnx] provider={} action=cpu_fallback errorHash={} errorLength={}",
                "onnxruntime", SafeRedactor.hashValue(messageOf(t)), messageLength(t));
    }

    private static String messageOf(Throwable t) {
        return t == null ? "" : String.valueOf(t.getMessage());
    }

    private static int messageLength(Throwable t) {
        return messageOf(t).length();
    }

    private static double jaccard(String left, String right) {
        Set<String> a = terms(left);
        Set<String> b = terms(right);
        if (a.isEmpty() && b.isEmpty()) {
            return 1.0d;
        }
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0d;
        }
        long intersection = a.stream().filter(b::contains).count();
        int union = a.size() + b.size() - (int) intersection;
        return union <= 0 ? 0.0d : Math.max(0.0d, Math.min(1.0d, (double) intersection / union));
    }

    private static Set<String> terms(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(value.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+"))
                .filter(s -> !s.isBlank())
                .collect(Collectors.toSet());
    }

    private static int length(String[] values) {
        return values == null ? 0 : values.length;
    }

    private static String valueAt(String[] values, int index) {
        return values == null || index < 0 || index >= values.length ? "" : values[index];
    }
}
