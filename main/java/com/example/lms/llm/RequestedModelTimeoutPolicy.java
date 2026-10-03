package com.example.lms.llm;

public final class RequestedModelTimeoutPolicy {
    public static final int DEFAULT_CHAT_RUN_MAX_DURATION_SECONDS = 600;
    private static final java.util.concurrent.atomic.AtomicBoolean LEGACY_RUN_CAP_REPORTED =
            new java.util.concurrent.atomic.AtomicBoolean();

    private RequestedModelTimeoutPolicy() {
    }

    public static int timeoutSeconds(
            String requestedModel,
            String resolvedModel,
            int baseTimeoutSeconds,
            int requestedTimeoutSeconds,
            int maxRunDurationSeconds) {
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
        if (com.example.lms.service.chat.ChatRunExecutionContext.isAcceptedExecution()) {
            if (LEGACY_RUN_CAP_REPORTED.compareAndSet(false, true)) {
                org.slf4j.LoggerFactory.getLogger(RequestedModelTimeoutPolicy.class).info(
                        "[AWX] chat.run.max-duration-seconds deprecated for accepted execution; llm timeout retained as positive transport policy");
            }
            return positiveOrDefault(requestedTimeoutSeconds, base);
        }
        // 선택 모델 대기는 단일 chat-run 상한(chat.run.max-duration-seconds)으로만
        // 제한한다. 명시적으로 더 짧은 제한은 계속 우선한다.
        return Math.min(positiveOrDefault(maxRunDurationSeconds, DEFAULT_CHAT_RUN_MAX_DURATION_SECONDS),
                positiveOrDefault(requestedTimeoutSeconds, base));
    }

    private static int positiveOrDefault(int value, int fallback) {
        return value > 0 ? value : fallback;
    }
}
