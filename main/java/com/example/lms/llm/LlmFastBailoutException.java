package com.example.lms.llm;

/**
 * Marker exception for "fail-soft" fast-bail scenarios.
 *
 * <p>Used to avoid noisy ERROR stacktraces when we intentionally degrade to evidence-only
 * fallbacks after repeated LLM timeouts while evidence is already present.</p>
 */
public class LlmFastBailoutException extends RuntimeException {

    private final int timeoutHits;
    private final int attempt;
    private final int maxAttempts;

    public LlmFastBailoutException(String message, Throwable cause, int timeoutHits, int attempt, int maxAttempts) {
        super(message, cause);
        this.timeoutHits = timeoutHits;
        this.attempt = attempt;
        this.maxAttempts = maxAttempts;
    }

    public int getTimeoutHits() {
        return timeoutHits;
    }

    public int getAttempt() {
        return attempt;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }
}
