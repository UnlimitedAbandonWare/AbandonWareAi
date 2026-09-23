package ai.abandonware.nova.orch.aop;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class WebFailSoftTraceSuppressions {
    private static final Logger log = LoggerFactory.getLogger(WebFailSoftTraceSuppressions.class);

    private WebFailSoftTraceSuppressions() {
    }

    static void trace(String stage, Throwable failure) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String errorType = errorType(failure);
        try {
            TraceStore.put("web.failsoft.suppressed.stage", safeStage);
            TraceStore.put("web.failsoft.suppressed.errorType", errorType);
            TraceStore.put("web.failsoft.suppressed." + safeStage, true);
            TraceStore.put("web.failsoft.suppressed." + safeStage + ".errorType", errorType);
        } catch (RuntimeException traceFailure) {
            log.debug("[WebFailSoft] suppressed trace failed stage={} errorType={}",
                    safeStage, errorType(traceFailure));
        }
    }

    private static String errorType(Throwable failure) {
        if (failure == null) {
            return "unknown";
        }
        if (failure instanceof NumberFormatException) {
            return "invalid_number";
        }
        return SafeRedactor.traceLabelOrFallback(failure.getClass().getSimpleName(), "unknown");
    }
}
