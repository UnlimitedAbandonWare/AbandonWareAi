package com.example.lms.search;

import java.util.HashMap;
import java.util.Map;



/**
 * Thread-local storage for query generation trace data.  This utility
 * allows the planner to expose intermediate lists such as LLM proposed
 * queries, hygiene filtered queries and the final queries used for
 * retrieval.  Consumers such as {@link com.example.lms.service.trace.TraceHtmlBuilder}
 * can read these values to render debugging tables.  Values are stored on
 * a per-thread basis to avoid cross-request contamination and should be
 * cleared when no longer needed.
 */
public final class TraceStore {
    private static final ThreadLocal<Map<String, Object>> TRACE =
            ThreadLocal.withInitial(HashMap::new);

    private TraceStore() {}

    /**
     * Store a key/value pair in the current thread's trace map.  When a
     * previous value exists for the key it will be replaced.
     *
     * @param key   the trace attribute name
     * @param value the associated value, may be {@code null}
     */
    public static void put(String key, Object value) {
        TRACE.get().put(key, value);
    }

    /**
     * Retrieve a value from the current thread's trace map.
     *
     * @param key the trace attribute name
     * @return the associated value or {@code null} when absent
     */
    public static Object get(String key) {
        return TRACE.get().get(key);
    }

    /**
     * Return a copy of all trace attributes for the current thread.  Changes
     * to the returned map do not affect the underlying storage.
     *
     * @return a shallow copy of the trace map
     */
    public static Map<String, Object> getAll() {
        return new HashMap<>(TRACE.get());
    }

    /**
     * Remove all entries from the current thread's trace map.
     */
    public static void clear() {
        TRACE.get().clear();
    }
}