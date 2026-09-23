package com.example.lms.service.rag;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;

import java.util.LinkedHashMap;
import java.util.Map;

public final class AnalyzeSearchTimeoutTrace {
    private AnalyzeSearchTimeoutTrace() {
    }

    public static void recordCancelSuppressed(
            String engine,
            long timeoutMs,
            int cancelAttempted,
            int cancelSucceeded,
            String query) {
        if (cancelAttempted <= 0) {
            return;
        }
        long safeTimeoutMs = Math.max(0L, timeoutMs);
        TraceStore.inc("web.await.cancelSuppressed", cancelAttempted);
        TraceStore.put("web.await.cancelSuppressed.reason", "timeout_hard");
        TraceStore.put("web.await.analyze.cancelAttempted", cancelAttempted);
        TraceStore.put("web.await.analyze.cancelSucceeded", Math.max(0, cancelSucceeded));
        TraceStore.put("web.await.analyze.timeoutMs", safeTimeoutMs);
        TraceStore.put("web.await.analyze.queryHash12", SafeRedactor.hash12(query));

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("seq", TraceStore.inc("web.await.events.seq"));
        event.put("stage", "hard");
        event.put("engine", SafeRedactor.traceLabelOrFallback(engine, "Analyze"));
        event.put("step", "planner_search");
        event.put("cause", "timeout_hard");
        event.put("timeoutMs", safeTimeoutMs);
        event.put("waitedMs", safeTimeoutMs);
        event.put("skip", true);
        event.put("timeout", true);
        event.put("softTimeout", false);
        event.put("hardTimeout", true);
        event.put("nonOk", true);
        event.put("cancelSuppressed", true);
        event.put("cancelAttempted", cancelAttempted);
        event.put("cancelSucceeded", Math.max(0, cancelSucceeded));
        event.put("queryHash12", SafeRedactor.hash12(query));
        TraceStore.append("web.await.events", event);
        TraceStore.put("web.await.events.count", TraceStore.getLong("web.await.events.seq"));
    }
}
