package com.example.lms.service.rag.safety;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;

import java.util.*;
import java.util.function.Supplier;

/**
 * Generic safe retrieval decorator that uses a stale supplier on error and otherwise falls back to an empty list.
 * This class is framework-agnostic and can wrap any supplier returning a List<T>.
 */
public class SafeRetrieveDecorator<T> {

    public interface StaleProvider<T> {
        List<T> getStale(String cacheKey, long maxAgeSeconds);
    }

    /**
     * Optional error handler callback to surface failures without coupling to any specific logging framework.
     */
    public interface ErrorHandler {
        void onError(String cacheKey, Exception ex);
    }

    private final StaleProvider<T> stale;
    private final boolean staleOnError;
    private final long maxAgeSeconds;
    private final ErrorHandler errorHandler;

    public SafeRetrieveDecorator(StaleProvider<T> stale,
                                 boolean staleOnError,
                                 long maxAgeSeconds) {
        this(stale, staleOnError, maxAgeSeconds, null);
    }

    public SafeRetrieveDecorator(StaleProvider<T> stale,
                                 boolean staleOnError,
                                 long maxAgeSeconds,
                                 ErrorHandler errorHandler) {
        this.stale = stale;
        this.staleOnError = staleOnError;
        this.maxAgeSeconds = maxAgeSeconds;
        this.errorHandler = errorHandler;
    }

    /**
     * Execute the delegate safely, optionally falling back to stale data when an exception occurs.
     */
    public List<T> retrieve(String cacheKey, Supplier<List<T>> delegate) {
        try {
            return delegate.get();
        } catch (Exception ex) {
            traceDelegateSuppressed(ex);
            if (errorHandler != null) {
                try {
                    errorHandler.onError(cacheKey, ex);
                } catch (Exception handlerEx) {
                    traceErrorHandlerSuppressed(handlerEx);
                }
            }
            if (staleOnError && stale != null) {
                List<T> s = stale.getStale(cacheKey, maxAgeSeconds);
                if (s != null && !s.isEmpty()) {
                    return s;
                }
            }
            return Collections.emptyList();
        }
    }

    private static void traceDelegateSuppressed(Exception ex) {
        String errorType = SafeRedactor.traceLabelOrFallback(ex.getClass().getSimpleName(), "unknown");
        TraceStore.put("safeRetrieve.suppressed.stage", "delegate");
        TraceStore.put("safeRetrieve.suppressed.errorType", errorType);
        TraceStore.put("safeRetrieve.suppressed.delegate", true);
        TraceStore.put("safeRetrieve.suppressed.delegate.errorType", errorType);
    }

    private static void traceErrorHandlerSuppressed(Exception ex) {
        String errorType = SafeRedactor.traceLabelOrFallback(ex.getClass().getSimpleName(), "unknown");
        TraceStore.put("safeRetrieve.suppressed.stage", "errorHandler");
        TraceStore.put("safeRetrieve.suppressed.errorType", errorType);
        TraceStore.put("safeRetrieve.suppressed.errorHandler", true);
        TraceStore.put("safeRetrieve.suppressed.errorHandler.errorType", errorType);
    }
}
