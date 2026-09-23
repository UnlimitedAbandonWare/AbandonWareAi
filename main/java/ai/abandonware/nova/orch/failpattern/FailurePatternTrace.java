package ai.abandonware.nova.orch.failpattern;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;

public final class FailurePatternTrace {

    private static final System.Logger LOG = System.getLogger(FailurePatternTrace.class.getName());

    private FailurePatternTrace() {
    }

    public static void traceSkipped(String stage, Throwable failure) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String errorType = errorType(failure);
        try {
            TraceStore.put("failpattern.suppressed.stage", safeStage);
            TraceStore.put("failpattern.suppressed.errorType", errorType);
            TraceStore.put("failpattern.suppressed." + safeStage, true);
            TraceStore.put("failpattern.suppressed." + safeStage + ".errorType", errorType);
        } catch (RuntimeException traceFailure) {
            LOG.log(System.Logger.Level.DEBUG,
                    "Failure pattern trace skipped stage=" + safeStage
                            + " errorType=" + errorType(traceFailure));
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
