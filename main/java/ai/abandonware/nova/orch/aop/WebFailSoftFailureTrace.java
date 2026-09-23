package ai.abandonware.nova.orch.aop;

import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.SocketTimeoutException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;

final class WebFailSoftFailureTrace {

    private WebFailSoftFailureTrace() {
    }

    static void record(String prefix, Throwable failure, String query) {
        if (prefix == null || prefix.isBlank() || failure == null) {
            return;
        }
        TraceStore.inc(prefix + ".failed.count");
        TraceStore.put(prefix + ".failed", true);
        TraceStore.put(prefix + ".failureReason", String.valueOf(NightmareBreaker.classify(failure)));
        TraceStore.put(prefix + ".errorType", errorType(failure));
        if (query != null && !query.isBlank()) {
            TraceStore.put(prefix + ".queryHash", SafeRedactor.hashValue(query));
            TraceStore.put(prefix + ".queryLength", query.length());
        }
    }

    static void recordInitialHybridFailure(String stage, RuntimeException failure, String query) {
        if (failure == null) {
            return;
        }
        String reason = classifyInitialHybridFailure(failure);
        TraceStore.put("web.failsoft.error", reason);
        TraceStore.put("web.failsoft.error.stage", SafeRedactor.traceLabelOrFallback(stage, "unknown"));
        TraceStore.put("web.hybrid.skipped", true);
        TraceStore.put("web.hybrid.skipped.reason", reason);
        if (query != null && !query.isBlank()) {
            TraceStore.put("web.failsoft.error.queryHash", SafeRedactor.hashValue(query));
            TraceStore.put("web.failsoft.error.queryLength", query.length());
        }
        if ("rate_limited".equals(reason)) {
            TraceStore.put("web.hybrid.rateLimited", true);
        } else if ("timeout".equals(reason)) {
            TraceStore.put("web.hybrid.timeout", true);
        } else if ("cancelled".equals(reason)) {
            TraceStore.put("web.hybrid.cancelled", true);
        }
    }

    private static String errorType(Throwable failure) {
        if (failure instanceof NumberFormatException) {
            return "invalid_number";
        }
        return SafeRedactor.traceLabelOrFallback(failure.getClass().getSimpleName(), "unknown");
    }

    private static String classifyInitialHybridFailure(Throwable failure) {
        Throwable cur = failure;
        int depth = 0;
        while (cur != null && depth++ < 8) {
            if (cur instanceof WebClientResponseException wce && wce.getStatusCode().value() == 429) {
                return "rate_limited";
            }
            if (cur instanceof CancellationException || cur instanceof InterruptedException) {
                return "cancelled";
            }
            if (cur instanceof TimeoutException || cur instanceof SocketTimeoutException) {
                return "timeout";
            }
            String className = cur.getClass().getName();
            if (className.endsWith("HttpTimeoutException") || className.endsWith("TimeoutException")) {
                return "timeout";
            }
            cur = cur.getCause();
        }
        return "provider_error";
    }
}
