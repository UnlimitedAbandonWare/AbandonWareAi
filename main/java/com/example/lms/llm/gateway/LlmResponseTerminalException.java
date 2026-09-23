package com.example.lms.llm.gateway;

import dev.langchain4j.model.chat.response.ChatResponseMetadata;

/** A received Responses result that must not be replayed or promoted to a completed answer. */
public final class LlmResponseTerminalException extends LlmGatewayException {
    private final String partialText;
    private final ChatResponseMetadata metadata;
    private final String status;
    private final String incompleteReason;
    private final String providerCode;

    public LlmResponseTerminalException(String reason, LlmFailureClass failureClass,
            String partialText, ChatResponseMetadata metadata, String status,
            String incompleteReason, String providerCode) {
        super("Responses terminal: " + reason, failureClass, reason);
        this.partialText = partialText;
        this.metadata = metadata;
        this.status = status;
        this.incompleteReason = incompleteReason;
        this.providerCode = providerCode;
    }

    public String partialText() { return partialText; }
    public ChatResponseMetadata metadata() { return metadata; }
    public String status() { return status; }
    public String incompleteReason() { return incompleteReason; }
    public String providerCode() { return providerCode; }

    public static LlmResponseTerminalException find(Throwable failure) {
        for (int depth = 0; failure != null && depth < 16; depth++, failure = failure.getCause()) {
            if (failure instanceof LlmResponseTerminalException terminal) return terminal;
            if (failure == failure.getCause()) break;
        }
        return null;
    }

    public static void rethrowIfPresent(Throwable failure) {
        LlmResponseTerminalException terminal = find(failure);
        if (terminal != null) throw terminal;
    }
}
