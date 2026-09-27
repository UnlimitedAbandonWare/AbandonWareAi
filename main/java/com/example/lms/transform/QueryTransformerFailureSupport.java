package com.example.lms.transform;

import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;

import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;

final class QueryTransformerFailureSupport {

    private QueryTransformerFailureSupport() {
    }

    static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    static String buildCurlHint(String baseUrl, String model) {
        String b = (baseUrl == null || baseUrl.isBlank()) ? "<baseUrl>" : baseUrl.trim();
        String m = (model == null || model.isBlank()) ? "<model>" : model.trim();

        String endpoint = b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
        endpoint = endpoint + "/chat/completions";

        return "curl -sS -X POST '" + endpoint + "' -H 'Content-Type: application/json' "
                + "-d '{\"model\":\"" + escapeJson(m)
                + "\",\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}],\"max_tokens\":1}'";
    }

    static NightmareBreaker.FailureKind classifyLlmFailure(Throwable t) {
        Throwable root = unwrap(t);

        if (root instanceof InterruptedException
                || root instanceof InterruptedIOException
                || root instanceof CancellationException) {
            return NightmareBreaker.FailureKind.INTERRUPTED;
        }

        if (root instanceof HttpTimeoutException
                || root instanceof SocketTimeoutException
                || root instanceof java.util.concurrent.TimeoutException) {
            return NightmareBreaker.FailureKind.TIMEOUT;
        }

        if (root instanceof RejectedExecutionException) {
            return NightmareBreaker.FailureKind.REJECTED;
        }

        try {
            NightmareBreaker.FailureKind shared = NightmareBreaker.classify(root);
            if (shared != null && shared != NightmareBreaker.FailureKind.UNKNOWN) {
                return shared;
            }
        } catch (Throwable ignore) {
            traceSuppressed("nightmareClassify", ignore);
        }

        String cn = root.getClass().getName();
        if (cn.endsWith("RateLimitException") || cn.toLowerCase().contains("ratelimit")) {
            return NightmareBreaker.FailureKind.RATE_LIMIT;
        }
        if (cn.endsWith("TimeoutException") && !cn.startsWith("java.")) {
            return NightmareBreaker.FailureKind.TIMEOUT;
        }

        String msg = root.getMessage();
        if (msg != null) {
            String lower = msg.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("model is required")
                    || lower.contains("must provide a model")
                    || (lower.contains("model parameter") && lower.contains("required"))
                    || (lower.contains("missing required parameter") && lower.contains("model"))) {
                return NightmareBreaker.FailureKind.CONFIG;
            }
            if (msg.contains("429") || lower.contains("too many requests")) {
                return NightmareBreaker.FailureKind.RATE_LIMIT;
            }
            if (lower.contains("timed out")) {
                return NightmareBreaker.FailureKind.TIMEOUT;
            }
        }

        return NightmareBreaker.FailureKind.UNKNOWN;
    }

    static Throwable unwrap(Throwable t) {
        Throwable cur = t;
        for (int i = 0; i < 16 && cur != null; i++) {
            if (cur instanceof ExecutionException || cur instanceof CompletionException) {
                cur = cur.getCause();
                continue;
            }
            if (cur instanceof RuntimeException && cur.getCause() != null) {
                cur = cur.getCause();
                continue;
            }
            break;
        }
        return (cur != null) ? cur : t;
    }

    static boolean looksLikeModelLoading(Throwable e) {
        Throwable root = unwrap(e);
        String msg = null;
        try {
            msg = (root != null ? root.getMessage() : (e != null ? e.getMessage() : null));
        } catch (Throwable ignore) {
            traceSuppressed("messageRead", ignore);
            msg = null;
        }
        if (msg == null) {
            return false;
        }
        String m = msg.toLowerCase(java.util.Locale.ROOT);
        return (m.contains("loading") && m.contains("model"))
                || m.contains("model is loading")
                || m.contains("server loading model")
                || m.contains("warming up")
                || (m.contains("initializing") && m.contains("model"))
                || m.contains("not ready");
    }

    static boolean isSoftTransientForQtx(NightmareBreaker.FailureKind kind, boolean modelLoading) {
        if (modelLoading || kind == null) {
            return true;
        }
        return kind == NightmareBreaker.FailureKind.TIMEOUT
                || kind == NightmareBreaker.FailureKind.HTTP_5XX
                || kind == NightmareBreaker.FailureKind.REJECTED
                || kind == NightmareBreaker.FailureKind.RATE_LIMIT;
    }

    static long qtxSoftCooldownMsFor(
            NightmareBreaker.FailureKind kind,
            boolean modelLoading,
            long qtxSoftCooldownBaseMs,
            long llmTimeoutOpenHintMs) {
        long base = Math.max(0L, (qtxSoftCooldownBaseMs > 0L ? qtxSoftCooldownBaseMs : llmTimeoutOpenHintMs));
        if (base <= 0L) {
            base = 3000L;
        }
        if (kind == NightmareBreaker.FailureKind.RATE_LIMIT) {
            return Math.max(1500L, Math.min(base, 10000L));
        }
        if (modelLoading || kind == NightmareBreaker.FailureKind.TIMEOUT) {
            return Math.min(4000L, Math.max(1200L, base));
        }
        return base;
    }

    private static void traceSuppressed(String stage, Throwable failure) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String safeErrorType = errorType(failure);
        TraceStore.put("qtx.failureSupport.suppressed.stage", safeStage);
        TraceStore.put("qtx.failureSupport.suppressed.errorType", safeErrorType);
        TraceStore.put("qtx.failureSupport.suppressed." + safeStage, true);
        TraceStore.put("qtx.failureSupport.suppressed." + safeStage + ".errorType", safeErrorType);
    }

    private static String errorType(Throwable failure) {
        if (failure == null) {
            return "unknown";
        }
        return SafeRedactor.traceLabelOrFallback(failure.getClass().getSimpleName(), "unknown");
    }

    private static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
