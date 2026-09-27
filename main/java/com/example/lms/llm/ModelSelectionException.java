package com.example.lms.llm;

import com.example.lms.llm.gateway.LlmGatewayFailureClassifier;
import java.util.Set;

/** Public-safe categorical failure. Does not retain upstream text or credentials. */
public final class ModelSelectionException extends IllegalArgumentException {
    private static final Set<String> CODES = Set.of("model_unavailable", "provider_not_configured",
            "provider_unauthorized", "protocol_unsupported", "rate_limited", "quota_exceeded",
            "backend_timeout", "backend_unavailable", "local_model_store_unavailable",
            "local_capacity_exceeded", "gpu_device_lost", "model_circuit_open",
            "model_request_invalid", "request_cancelled");
    private static final LlmGatewayFailureClassifier CLASSIFIER = new LlmGatewayFailureClassifier();
    private final String code;
    public ModelSelectionException(String code) {
        super(CODES.contains(code) ? code : "model_unavailable");
        this.code = CODES.contains(code) ? code : "model_unavailable";
    }
    public String code() { return code; }
    /** Categorize both worker and pre-worker SSE failures without exposing upstream text. */
    public static String streamFailureCode(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 20; depth++) {
            if (current instanceof LinkageError) return "backend_unavailable";
            if (current instanceof ModelSelectionException known) return known.code();
            if (current.getCause() == current) break;
            current = current.getCause();
        }
        return "stream_failed";
    }
    public static ModelSelectionException failure(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 20; depth++) {
            if (current instanceof ModelSelectionException known) return known;
            if (current.getCause() == current) break;
            current = current.getCause();
        }
        if (LlmGatewayFailureClassifier.hasQuotaFailure(failure)) {
            return new ModelSelectionException("quota_exceeded");
        }
        String code = switch (CLASSIFIER.classify(failure)) {
            case MODEL_MISSING -> "model_unavailable";
            case AUTH_MISSING -> "provider_unauthorized";
            case DISABLED -> "provider_not_configured";
            case TIMEOUT_SOFT -> "backend_timeout";
            case CANCELLED_NEUTRAL -> "request_cancelled";
            case RATE_LIMIT_COOLDOWN -> "rate_limited";
            case MODEL_STORE_UNAVAILABLE -> "local_model_store_unavailable";
            case GPU_DEVICE_LOST -> "gpu_device_lost";
            case VRAM_OOM -> "local_capacity_exceeded";
            case SOFT_CIRCUIT_OPEN -> "model_circuit_open";
            case BAD_REQUEST, CONTEXT_TOO_SMALL -> "model_request_invalid";
            case LOCAL_UNSUPPORTED_MANAGED_RAG, EMBEDDING_DIM_MISMATCH -> "protocol_unsupported";
            default -> "backend_unavailable";
        };
        return new ModelSelectionException(code);
    }
}
