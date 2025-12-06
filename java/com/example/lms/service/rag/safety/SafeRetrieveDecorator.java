package com.example.lms.service.rag.safety;

import java.util.*;
import java.util.function.Supplier;

/**
 * Generic safe retrieval decorator that uses a stale supplier on error and otherwise falls back to empty list.
 * This class is framework-agnostic and can wrap any supplier returning a List<T>.
 */
public class SafeRetrieveDecorator<T> {
    public interface StaleProvider<T> { List<T> getStale(String key, long maxAgeSeconds); }

    private final StaleProvider<T> stale;
    private final boolean staleOnError;
    private final long maxAgeSeconds;

    public SafeRetrieveDecorator(StaleProvider<T> stale, boolean staleOnError, long maxAgeSeconds) {
        this.stale = stale;
        this.staleOnError = staleOnError;
        this.maxAgeSeconds = maxAgeSeconds;
    }

    public List<T> retrieve(String cacheKey, Supplier<List<T>> delegate) {
        try {
            return delegate.get();
        } catch (Exception ex) {
            if (staleOnError && stale != null) {
                List<T> s = stale.getStale(cacheKey, maxAgeSeconds);
                if (s != null && !s.isEmpty()) return s;
            }
            return Collections.emptyList();
        }
    }
}