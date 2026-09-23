package com.example.lms.llm.gateway;

public enum LlmFailureClass {
    NONE,
    AUTH_MISSING,
    BAD_REQUEST,
    HEALTH_DOWN,
    GPU_DEVICE_LOST,
    MODEL_MISSING,
    VRAM_OOM,
    TIMEOUT_SOFT,
    SOFT_CIRCUIT_OPEN,
    RATE_LIMIT_COOLDOWN,
    CANCELLED_NEUTRAL,
    PROVIDER_ERROR,
    CONTEXT_TOO_SMALL,
    EMBEDDING_DIM_MISMATCH,
    LOCAL_UNSUPPORTED_MANAGED_RAG,
    STREAM_ERROR,
    RESPONSE_MODEL_UNVERIFIED,
    DISABLED,
    UNKNOWN;

    public boolean routeBlocking() {
        return this == AUTH_MISSING
                || this == HEALTH_DOWN
                || this == GPU_DEVICE_LOST
                || this == MODEL_MISSING
                || this == VRAM_OOM
                || this == CONTEXT_TOO_SMALL
                || this == EMBEDDING_DIM_MISMATCH
                || this == LOCAL_UNSUPPORTED_MANAGED_RAG
                || this == DISABLED;
    }

    public boolean hardBreakerFailure() {
        return routeBlocking() || this == PROVIDER_ERROR || this == STREAM_ERROR || this == UNKNOWN;
    }
}
