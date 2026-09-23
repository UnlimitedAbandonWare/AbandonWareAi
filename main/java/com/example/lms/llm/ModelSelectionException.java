package com.example.lms.llm;

import java.util.Set;

/** Public-safe categorical failure. Does not retain upstream text or credentials. */
public final class ModelSelectionException extends IllegalArgumentException {
    private static final Set<String> CODES = Set.of("model_unavailable", "provider_not_configured",
            "provider_unauthorized", "protocol_unsupported", "rate_limited", "quota_exceeded");
    private final String code;
    public ModelSelectionException(String code) {
        super(CODES.contains(code) ? code : "model_unavailable");
        this.code = CODES.contains(code) ? code : "model_unavailable";
    }
    public String code() { return code; }
    public static ModelSelectionException failure(Throwable failure) {
        if (failure instanceof ModelSelectionException known) return known;
        return new ModelSelectionException("model_unavailable");
    }
}
