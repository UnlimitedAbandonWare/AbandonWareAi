package com.example.lms.llm.gateway;

public class LlmGatewayException extends IllegalStateException {

    private final LlmFailureClass failureClass;
    private final String reasonCode;

    public LlmGatewayException(String message, LlmFailureClass failureClass) {
        this(message, failureClass, "gateway_failure");
    }

    public LlmGatewayException(String message,
                               LlmFailureClass failureClass,
                               String reasonCode) {
        super(message);
        this.failureClass = failureClass == null ? LlmFailureClass.UNKNOWN : failureClass;
        this.reasonCode = safeReasonCode(reasonCode);
    }

    public LlmFailureClass failureClass() {
        return failureClass;
    }

    /** Allowlisted categorical reason only; raw upstream text is never retained here. */
    public String reasonCode() {
        return reasonCode;
    }

    private static String safeReasonCode(String raw) {
        if (raw == null || !raw.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
            return "gateway_failure";
        }
        return raw;
    }
}
