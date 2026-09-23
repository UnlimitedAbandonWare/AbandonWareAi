package com.example.lms.service.rag;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import dev.langchain4j.rag.content.Content;

import java.util.List;

final class HybridRetrieverTraceSuppressions {
    private static final List<String> WEB_PROVIDER_NAMES = List.of("brave", "naver", "serpapi", "tavily");

    private HybridRetrieverTraceSuppressions() {
    }

    static void trace(String stage, Throwable failure) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String errorType = errorType(failure);
        TraceStore.put("hybrid.retriever.suppressed.stage", safeStage);
        TraceStore.put("hybrid.retriever.suppressed.errorType", errorType);
        TraceStore.put("hybrid.retriever.suppressed." + safeStage, true);
        TraceStore.put("hybrid.retriever.suppressed." + safeStage + ".errorType", errorType);
    }

    static void traceHybridWebRollup(List<Content> webResults) {
        int outCount = webResults == null ? 0 : webResults.size();
        TraceStore.put("hybrid.web.outCount", outCount);
        TraceStore.put("hybrid.web.starvation", outCount == 0);
        for (String provider : WEB_PROVIDER_NAMES) {
            traceHybridWebProvider(provider);
        }
    }

    private static void traceHybridWebProvider(String provider) {
        String safeProvider = SafeRedactor.traceLabelOrFallback(provider, "unknown");
        String prefix = "web." + safeProvider + ".";
        Object skippedValue = TraceStore.get(prefix + "skipped");
        String reason = SafeRedactor.traceLabelOrFallback(TraceStore.get(prefix + "skipped.reason"), "");
        boolean skipped = Boolean.TRUE.equals(skippedValue) || !reason.isBlank();
        TraceStore.put("hybrid.web.provider." + safeProvider + ".skipped", skipped);
        TraceStore.put("hybrid.web.provider." + safeProvider + ".skipReason", skipped ? reason : "");
    }

    private static String errorType(Throwable failure) {
        if (failure instanceof NumberFormatException) {
            return "invalid_number";
        }
        return failure == null ? "unknown" : SafeRedactor.traceLabelOrFallback(failure.getClass().getSimpleName(), "unknown");
    }
}
