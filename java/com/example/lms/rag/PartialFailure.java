// src/main/java/com/example/lms/rag/PartialFailure.java
package com.example.lms.rag;


/**
 * Represents a partial failure encountered during the retrieval or
 * verification phases.  Instead of bubbling an exception up the call
 * stack (which would abort the entire chain), handlers can populate
 * instances of this class and attach them to a {@code SearchContext}
 * warnings list.  The agent can then decide whether to proceed with
 * degraded results or surface a warning to the user.
 */
public class PartialFailure {
    /** The stage in which the failure occurred (e.g. "Web", "Vector"). */
    private final String stage;
    /** The exception class name (for logging/debugging). */
    private final String exception;
    /** A user-friendly message describing the error. */
    private final String message;
    /** Whether the failure is likely transient and could succeed on retry. */
    private final boolean retryable;

    public PartialFailure(String stage, String exception, String message, boolean retryable) {
        this.stage = stage;
        this.exception = exception;
        this.message = message;
        this.retryable = retryable;
    }

    public String getStage() {
        return stage;
    }

    public String getException() {
        return exception;
    }

    public String getMessage() {
        return message;
    }

    public boolean isRetryable() {
        return retryable;
    }

    @Override
    public String toString() {
        return "PartialFailure{" +
                "stage='" + stage + '\'' +
                ", exception='" + exception + '\'' +
                ", message='" + message + '\'' +
                ", retryable=" + retryable +
                '}';
    }
}