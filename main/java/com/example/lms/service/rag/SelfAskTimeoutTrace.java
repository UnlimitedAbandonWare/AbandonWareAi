package com.example.lms.service.rag;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;

final class SelfAskTimeoutTrace {
    private SelfAskTimeoutTrace() {
    }

    static void recordCancellationRequested(
            String stage,
            long timeoutMs,
            String keyword,
            boolean callerInterrupted,
            boolean cancelAccepted) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        TraceStore.inc("selfask.timeout.cancelRequested.count");
        TraceStore.put("selfask.timeout.stage", safeStage);
        TraceStore.put("selfask.timeout.errorType", safeStage);
        TraceStore.put("selfask.timeout.cancelRequested", true);
        TraceStore.put("selfask.timeout.cancelInterrupt", true);
        TraceStore.put("selfask.timeout.cancelAccepted", cancelAccepted);
        TraceStore.put("selfask.timeout.callerInterrupted", callerInterrupted);
        TraceStore.put("selfask.timeout.timeoutMs", Math.max(0L, timeoutMs));
        TraceStore.put("selfask.timeout.queryHash12", SafeRedactor.hash12(keyword));
    }
}
