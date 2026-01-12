package com.example.lms.llm;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.ModelNotFoundException;

import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;

/**
 * Lightweight LLM error classifier used to decide whether an error is retryable.
 *
 * <p>Important: this is <b>heuristic</b> by design. It should never throw, and it
 * should avoid leaking sensitive information (messages are truncated).</p>
 */
public final class LlmErrorClassifier {

    private LlmErrorClassifier() {
    }

    public record Result(String code, boolean retryable, Integer statusCode, String shortMessage) {
    }

    public static Result classify(Throwable t) {
        try {
            // Walk the cause chain (do not assume the deepest cause keeps the useful message).
            java.util.ArrayList<Throwable> chain = new java.util.ArrayList<>();
            Throwable cur = (t == null) ? new RuntimeException("null") : t;
            int hops = 0;
            while (cur != null && hops++ < 20) {
                chain.add(cur);
                Throwable next = cur.getCause();
                if (next == cur) {
                    break;
                }
                cur = next;
            }

            // Explicit non-retryable types (can appear anywhere in the chain)
            for (Throwable x : chain) {
                if (x instanceof ModelNotFoundException) {
                    return new Result("MODEL_NOT_FOUND", false, null, shortMsg(x.getMessage()));
                }
                if (x instanceof CancellationException) {
                    return new Result("CANCELLED", false, null, shortMsg(x.getMessage()));
                }
            }

            // HTTP status-based classification (can appear anywhere)
            for (Throwable x : chain) {
                if (!(x instanceof HttpException he)) {
                    continue;
                }
                int sc = he.statusCode();
                String m = he.getMessage();
                String l = safeLower(m);

                if (sc == 400 && isModelRequiredMsg(l)) {
                    return new Result("MODEL_REQUIRED", false, sc, shortMsg(m));
                }
                if (sc == 401 || sc == 403) {
                    return new Result("AUTH", false, sc, shortMsg(m));
                }
                if (sc == 404 && (l.contains("model") && (l.contains("not found") || l.contains("does not exist") || l.contains("unknown")))) {
                    return new Result("MODEL_NOT_FOUND", false, sc, shortMsg(m));
                }
                if (sc == 429) {
                    return new Result("RATE_LIMIT", true, sc, shortMsg(m));
                }
                if (sc >= 500 && sc <= 599) {
                    return new Result("UPSTREAM_5XX", true, sc, shortMsg(m));
                }
                // Other 4xx are usually non-retryable (schema / validation / auth).
                if (sc >= 400 && sc <= 499) {
                    return new Result("HTTP_4XX", false, sc, shortMsg(m));
                }
            }

            // Keyword-based fallback across the entire chain.
            StringBuilder sb = new StringBuilder();
            for (Throwable x : chain) {
                String lm = safeLower(x.getMessage());
                if (!lm.isEmpty()) {
                    if (sb.length() > 0) {
                        sb.append(" | ");
                    }
                    sb.append(lm);
                }
            }
            String lowerAll = sb.toString();

            if (isModelRequiredMsg(lowerAll)) {
                return new Result("MODEL_REQUIRED", false, null, shortMsg(t == null ? null : t.getMessage()));
            }
            if (lowerAll.contains("rate") && lowerAll.contains("limit")) {
                return new Result("RATE_LIMIT", true, null, shortMsg(t == null ? null : t.getMessage()));
            }
            for (Throwable x : chain) {
                if (isTimeout(x, safeLower(x.getMessage()))) {
                    return new Result("TIMEOUT", true, null, shortMsg(x.getMessage()));
                }
            }
            if (lowerAll.contains("interrupted")) {
                return new Result("INTERRUPTED", false, null, shortMsg(t == null ? null : t.getMessage()));
            }

            Throwable root = chain.isEmpty() ? unwrap(t) : chain.get(chain.size() - 1);
            String msg = (root == null) ? null : root.getMessage();

            // Default: treat as retryable to preserve existing behavior.
            return new Result("UNKNOWN", true, null, shortMsg(msg));
        } catch (Throwable ignored) {
            Throwable root = unwrap(t);
            String msg = (root == null) ? null : root.getMessage();
            return new Result("UNKNOWN", true, null, shortMsg(msg));
        }
    }

    private static boolean isTimeout(Throwable root, String lowerMsg) {
        if (root instanceof TimeoutException) {
            return true;
        }
        // Common JVM/net/http timeout words
        return lowerMsg.contains("timed out") || lowerMsg.contains("timeout");
    }

    private static Throwable unwrap(Throwable t) {
        Throwable cur = (t == null) ? new RuntimeException("null") : t;
        int hops = 0;
        while (cur.getCause() != null && cur.getCause() != cur && hops++ < 20) {
            cur = cur.getCause();
        }
        return cur;
    }

    private static String safeLower(String s) {
        return (s == null) ? "" : s.toLowerCase(Locale.ROOT);
    }

    private static boolean isModelRequiredMsg(String lowerMsg) {
        if (lowerMsg == null || lowerMsg.isBlank()) {
            return false;
        }
        // Common OpenAI-compatible error bodies/messages
        if (lowerMsg.contains("model is required")) return true;
        if (lowerMsg.contains("must provide a model")) return true;
        if (lowerMsg.contains("model parameter") && lowerMsg.contains("required")) return true;
        if (lowerMsg.contains("missing required parameter") && lowerMsg.contains("model")) return true;
        return false;
    }

    private static String shortMsg(String s) {
        if (s == null) {
            return "";
        }
        // Collapse whitespace without regex for safety/perf.
        StringBuilder sb = new StringBuilder(Math.min(160, s.length()));
        boolean prevWs = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ws = Character.isWhitespace(c);
            if (ws) {
                if (!prevWs) {
                    sb.append(' ');
                    prevWs = true;
                }
            } else {
                sb.append(c);
                prevWs = false;
            }
            if (sb.length() >= 160) {
                break;
            }
        }
        String out = sb.toString().trim();
        if (s.length() > 160) {
            return out + "…";
        }
        return out;
    }
}
