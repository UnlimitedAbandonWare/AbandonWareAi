package com.example.lms.service.rag.safety;

import java.util.*;
import java.util.function.Supplier;

/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.example.lms.service.rag.safety.SafeRetrieveDecorator
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.example.lms.service.rag.safety.SafeRetrieveDecorator
role: config
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