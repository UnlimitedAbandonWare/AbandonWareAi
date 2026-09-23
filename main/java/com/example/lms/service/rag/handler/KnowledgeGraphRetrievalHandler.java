package com.example.lms.service.rag.handler;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Chain-safe adapter for the KG content retriever used by the fixed retrieval chain.
 */
public final class KnowledgeGraphRetrievalHandler extends AbstractRetrievalHandler {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeGraphRetrievalHandler.class);

    private final KnowledgeGraphHandler delegate;

    public KnowledgeGraphRetrievalHandler(KnowledgeGraphHandler delegate) {
        this.delegate = delegate;
    }

    @Override
    protected boolean doHandle(Query query, List<Content> accumulator) {
        long started = System.nanoTime();
        int before = accumulator == null ? -1 : accumulator.size();
        String queryHash12 = query == null ? "" : blankToDefault(SafeRedactor.hash12(query.text()), "");
        try {
            if (delegate == null) {
                recordKgFixedChain("skipped", false, 0, 0, "delegate_unavailable", "",
                        elapsedMs(started), queryHash12);
                return true;
            }
            if (accumulator == null) {
                recordKgFixedChain("skipped", true, 0, 0, "accumulator_unavailable", "",
                        elapsedMs(started), queryHash12);
                return true;
            }
            List<Content> results = delegate.retrieve(query);
            int returnedCount = results == null ? 0 : results.size();
            int addedCount = 0;
            if (results != null && !results.isEmpty()) {
                accumulator.addAll(results);
                addedCount = Math.max(0, accumulator.size() - before);
            }
            recordKgFixedChain(returnedCount > 0 ? "success" : "empty", true, returnedCount, addedCount,
                    returnedCount > 0 ? "" : "delegate_empty", "", elapsedMs(started), queryHash12);
        } catch (Exception ex) {
            String failureClass = failureClass(ex);
            recordKgFixedChain("failed", delegate != null, 0, 0, "delegate_exception",
                    failureClass, elapsedMs(started), queryHash12);
            log.warn("[KG][fixed-chain] failed; continuing retrieval chain failureClass={}",
                    failureClass);
        }
        return true;
    }

    private static void recordKgFixedChain(String status,
                                           boolean enabled,
                                           int returnedCount,
                                           int addedCount,
                                           String disabledReason,
                                           String failureClass,
                                           long tookMs,
                                           String queryHash12) {
        String safeStatus = safeToken(status, "unknown");
        String safeDisabledReason = safeToken(disabledReason, "");
        String safeFailureClass = safeToken(failureClass, "");
        try {
            TraceStore.put("retrieval.kg.fixedChain.status", safeStatus);
            TraceStore.put("retrieval.kg.fixedChain.enabled", enabled);
            TraceStore.put("retrieval.kg.fixedChain.returnedCount", Math.max(0, returnedCount));
            TraceStore.put("retrieval.kg.fixedChain.addedCount", Math.max(0, addedCount));
            TraceStore.put("retrieval.kg.fixedChain.disabledReason", safeDisabledReason);
            TraceStore.put("retrieval.kg.fixedChain.failureClass", safeFailureClass);
            TraceStore.put("retrieval.kg.fixedChain.failSoft", "failed".equals(safeStatus));
            TraceStore.put("retrieval.kg.fixedChain.tookMs", Math.max(0L, tookMs));
            if (queryHash12 != null && !queryHash12.isBlank()) {
                TraceStore.put("retrieval.kg.fixedChain.queryHash12", queryHash12);
            }
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("status", safeStatus);
            event.put("enabled", enabled);
            event.put("returnedCount", Math.max(0, returnedCount));
            event.put("addedCount", Math.max(0, addedCount));
            event.put("disabledReason", safeDisabledReason);
            event.put("failureClass", safeFailureClass);
            event.put("failSoft", "failed".equals(safeStatus));
            event.put("tookMs", Math.max(0L, tookMs));
            if (queryHash12 != null && !queryHash12.isBlank()) {
                event.put("queryHash12", queryHash12);
            }
            TraceStore.append("retrieval.kg.fixedChain.events", event);
        } catch (Exception ignore) {
            TraceStore.put("retrieval.handler.suppressed.kg.fixedChain.trace", true); RetrievalHandlerTraceSuppressions.trace("kg.fixedChain.trace", ignore);
        }
    }

    private static long elapsedMs(long started) {
        return Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
    }

    private static String failureClass(Throwable failure) {
        Throwable root = rootCause(failure);
        String className = root == null ? "" : blankToDefault(root.getClass().getSimpleName(), "");
        String lowerClass = className.toLowerCase(Locale.ROOT);
        String message = root == null ? "" : blankToDefault(root.getMessage(), "");
        String lowerMessage = message.toLowerCase(Locale.ROOT);
        if (root instanceof java.util.concurrent.CancellationException
                || root instanceof InterruptedException
                || lowerClass.contains("cancel")
                || lowerClass.contains("interrupt")
                || lowerMessage.contains("cancelled")
                || lowerMessage.contains("canceled")
                || lowerMessage.contains("interrupted")) {
            return "cancelled";
        }
        return blankToDefault(className, "Exception");
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        int depth = 0;
        while (current != null && current.getCause() != null && current.getCause() != current && depth++ < 8) {
            current = current.getCause();
        }
        return current;
    }

    private static String safeToken(String value, String fallback) {
        String v = blankToDefault(value, fallback);
        v = v.replaceAll("[^A-Za-z0-9_.:-]", "_");
        return v.length() > 80 ? v.substring(0, 80) : v;
    }

    private static String blankToDefault(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback == null ? "" : fallback;
        }
        return value.trim();
    }
}
