package com.example.lms.service.rag.safety;

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
            if (errorHandler != null) {
                try {
                    errorHandler.onError(cacheKey, ex);
                } catch (Exception ignored) {
                    // never block the calling flow if handler itself fails
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
}
