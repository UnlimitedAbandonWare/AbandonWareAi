package com.example.lms.llm;

public final class RequestedModelTimeoutPolicy {
    public static final int DEFAULT_REQUESTED_TIMEOUT_SECONDS = 180;

    private RequestedModelTimeoutPolicy() {
    }

    public static int timeoutSeconds(
            String requestedModel,
            String resolvedModel,
            int baseTimeoutSeconds,
            int requestedTimeoutSeconds) {
        int base = positiveOrDefault(baseTimeoutSeconds, 12);
        String requested = requestedModel == null ? "" : requestedModel.trim();
        String model = ModelCapabilities.canonicalModelName(requested.isBlank() ? resolvedModel : requested);
        if (model == null || model.isBlank()) {
            return base;
        }
        boolean chatCandidate = ModelCapabilities.isLocalChatModelId(model)
                || ModelCapabilities.isRemoteLookingModelId(model);
        if (!chatCandidate) {
            return base;
        }
        return Math.max(base, positiveOrDefault(
                requestedTimeoutSeconds,
                DEFAULT_REQUESTED_TIMEOUT_SECONDS));
    }

    private static int positiveOrDefault(int value, int fallback) {
        return value > 0 ? value : fallback;
    }
}
