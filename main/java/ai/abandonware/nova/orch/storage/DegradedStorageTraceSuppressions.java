package ai.abandonware.nova.orch.storage;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;

final class DegradedStorageTraceSuppressions {
    private DegradedStorageTraceSuppressions() {
    }

    static void trace(String stage, Throwable failure) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String errorType = errorType(failure);
        TraceStore.put("nova.degraded.storage.suppressed.stage", safeStage);
        TraceStore.put("nova.degraded.storage.suppressed.errorType", errorType);
        TraceStore.put("nova.degraded.storage.suppressed." + safeStage, true);
        TraceStore.put("nova.degraded.storage.suppressed." + safeStage + ".errorType", errorType);
    }

    private static String errorType(Throwable failure) {
        if (failure instanceof NumberFormatException) {
            return "invalid_number";
        }
        return failure == null ? "unknown" : SafeRedactor.traceLabelOrFallback(failure.getClass().getSimpleName(), "unknown");
    }
}
