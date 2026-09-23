package com.example.lms.service;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;

final class LlmRetryBudgetTrace {

    private LlmRetryBudgetTrace() {
    }

    static void emit(long elapsedMs, long budgetMs, int attempt, int maxAttempts, String code) {
        try {
            String safeCode = SafeRedactor.traceLabelOrFallback(code, "unknown");
            TraceStore.put("llm.retryBudget.exceeded", true);
            TraceStore.put("llm.retryBudget.exceeded.count", TraceStore.nextSequence("llm.retryBudget.exceeded"));
            TraceStore.put("llm.retryBudget.elapsedMs", Math.max(0L, elapsedMs));
            TraceStore.put("llm.retryBudget.budgetMs", Math.max(0L, budgetMs));
            TraceStore.put("llm.retryBudget.attempt", Math.max(0, attempt));
            TraceStore.put("llm.retryBudget.maxAttempts", Math.max(0, maxAttempts));
            TraceStore.put("llm.retryBudget.code", safeCode);
        } catch (Throwable ignore) {
            ChatWorkflowTraceSuppressions.traceSuppressed("llm.retryBudgetTrace", ignore);
        }
    }
}
