package com.example.lms.llm;

/**
 * A configuration-level LLM failure that should be surfaced to the user
 * (instead of falling back to silent/empty outputs).
 */
public class LlmConfigurationException extends RuntimeException {

    private final String code;
    private final String userMessage;
    private final String model;
    private final String endpoint;

    public LlmConfigurationException(String code,
            String userMessage,
            String model,
            String endpoint,
            Throwable cause) {
        super(code + ": " + (userMessage == null ? "" : userMessage), cause);
        this.code = code;
        this.userMessage = userMessage;
        this.model = model;
        this.endpoint = endpoint;
    }

    public String getCode() {
        return code;
    }

    public String getUserMessage() {
        return userMessage;
    }

    public String getModel() {
        return model;
    }

    public String getEndpoint() {
        return endpoint;
    }
}
