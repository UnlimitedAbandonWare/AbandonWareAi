package com.example.lms.transform;

import java.util.Locale;

public final class QueryTransformerBypassReason {

    private QueryTransformerBypassReason() {
    }

    public static String classify(Throwable error) {
        Throwable root = QueryTransformerFailureSupport.unwrap(error);
        String type = root == null ? "" : root.getClass().getName().toLowerCase(Locale.ROOT);
        String message = root == null || root.getMessage() == null
                ? ""
                : root.getMessage().toLowerCase(Locale.ROOT);
        if (root instanceof java.util.concurrent.CancellationException
                || type.contains("interrupted")
                || message.contains("cancelled")
                || message.contains("canceled")) {
            return "cancelled";
        }
        if (root instanceof java.util.concurrent.TimeoutException
                || type.contains("timeoutexception")
                || message.contains("timeout")
                || message.contains("timed out")) {
            return "timeout";
        }
        if (message.contains("breaker_open")
                || message.contains("breaker-open")
                || message.contains("circuit open")
                || message.contains("circuit_open")) {
            return "breaker_open";
        }
        if (root instanceof java.util.concurrent.RejectedExecutionException
                || message.contains("rejected")) {
            return "rejected";
        }
        return "transform_exception";
    }
}
