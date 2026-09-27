package com.example.lms.search;

import com.example.lms.trace.SafeRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Request-scoped trace bag.
 *
 * <p>
 * This store is frequently accessed across async boundaries. The underlying
 * map is a {@link ConcurrentHashMap} to reduce cross-thread corruption when the
 * same context is installed in worker threads.
 *
 * <p>
 * TraceStore is not a sanitizer. Callers must store only redacted, hashed,
 * count-only, or explicitly allowlisted values because snapshots can be
 * serialized into diagnostics and UI surfaces.
 * </p>
 */
public final class TraceStore {
    private static final Logger log = LoggerFactory.getLogger(TraceStore.class);

    private static final ThreadLocal<Map<String, Object>> TRACE = ThreadLocal.withInitial(ConcurrentHashMap::new);

    private static final ThreadLocal<Map<String, AtomicLong>> SEQ = ThreadLocal.withInitial(ConcurrentHashMap::new);

    /**
     * Internal prefix for per-key sequence counters stored inside the trace context
     * map.
     */
    private static final String SEQ_PREFIX = "__seq.";

    private static final Set<String> INTERNAL_ONLY_KEYS = Set.of("selectedTerms");
    private static final List<String> INTERNAL_ONLY_PREFIXES = List.of(
            "web.failsoft.hybridEmptyFallback.result.");
    private static final String INTERNAL_DYNAMIC_KEYS = "__trace.internal.keys";
    private static final String TRACE_POOL_ITEMS_KEY = "tracePool.items";
    private static final String TRACE_POOL_RESCUE_ITEMS_KEY =
            "web.failsoft.hybridEmptyFallback.cacheOnly.rescueMerge.tracePool.items";
    private static final String MLA_BREADCRUMBS_KEY = "ml.breadcrumbs.v1";
    private static final String MLA_BREADCRUMB_COUNT_KEY = "cihRag.mlaBreadcrumbCount";

    private TraceStore() {
    }

    /** Return the live underlying context map for this thread. */
    public static Map<String, Object> context() {
        return TRACE.get();
    }

    /** Install a context map for this thread during async propagation. */
    public static void installContext(Map<String, Object> ctx) {
        if (ctx == null) {
            clear();
            return;
        }
        TRACE.set(ctx);
    }

    private static final Set<String> SEARCH_CORRELATION_KEYS = Set.of(
            "retrievalExecutionId", "searchExecutionId", "providerAttemptId");

    /** Immutable invocation bindings travel with the existing captured context, not a new ThreadLocal.
     * Legacy scalar writes still reach the caller and respect HybridSearchExecution's late-write cutoff.
     */
    public static Map<String, Object> searchContext(Map<String, Object> parent, String key) {
        if (!SEARCH_CORRELATION_KEYS.contains(key)) throw new IllegalArgumentException("unsupported search scope");
        String id = SafeRedactor.hashValue(java.util.UUID.randomUUID().toString());
        return new java.util.AbstractMap<>() {
            @Override public Object get(Object k) { return key.equals(k) ? id : parent.get(k); }
            @Override public Object put(String k, Object v) { return key.equals(k) ? id : parent.put(k, v); }
            @Override public Object remove(Object k) { return key.equals(k) ? id : parent.remove(k); }
            @Override public Object putIfAbsent(String k, Object v) { return key.equals(k) ? id : parent.putIfAbsent(k, v); }
            @Override public Object compute(String k, java.util.function.BiFunction<? super String, ? super Object, ?> f) {
                return key.equals(k) ? id : parent.compute(k, f);
            }
            @Override public Object computeIfAbsent(String k, java.util.function.Function<? super String, ?> f) {
                return key.equals(k) ? id : parent.computeIfAbsent(k, f);
            }
            @Override public Set<Entry<String, Object>> entrySet() {
                Map<String, Object> view = new HashMap<>(parent);
                view.put(key, id);
                return java.util.Collections.unmodifiableMap(view).entrySet();
            }
        };
    }

    public static <T> T withSearchContext(String key, java.util.function.Supplier<T> action) {
        Map<String, Object> previous = context();
        installContext(searchContext(previous, key));
        try { return action.get(); }
        finally { installContext(previous); }
    }

    public static Map<String, Object> searchCorrelation(Map<String, Object> context) {
        Map<String, Object> ids = new HashMap<>();
        for (String key : SEARCH_CORRELATION_KEYS) {
            Object value = context.get(key);
            if (value instanceof String id && id.matches("hash:[a-f0-9]{12}")) ids.put(key, id);
        }
        return Map.copyOf(ids);
    }

    public static void put(String key, Object value) {
        if (key == null || key.isBlank()) {
            return;
        }
        // ConcurrentHashMap forbids null values.
        // Many trace attributes are optional, so treat null as "remove".
        if (value == null) {
            TRACE.get().remove(key);
            internalKeys(TRACE.get()).remove(key);
            return;
        }
        TRACE.get().put(key, value);
        internalKeys(TRACE.get()).remove(key);
    }

    /**
     * Store behavior-only state that must be available to internal pipeline
     * consumers but must not be exposed through public diagnostic snapshots.
     */
    public static void putInternal(String key, Object value) {
        if (key == null || key.isBlank()) {
            return;
        }
        Map<String, Object> trace = TRACE.get();
        if (value == null) {
            trace.remove(key);
            internalKeys(trace).remove(key);
            return;
        }
        trace.put(key, value);
        internalKeys(trace).add(key);
    }

    /** Put only if absent. Returns the existing value (or null if installed). */
    public static Object putIfAbsent(String key, Object value) {
        if (key == null || key.isBlank() || value == null) {
            return null;
        }
        return TRACE.get().putIfAbsent(key, value);
    }

    public static Object get(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        return TRACE.get().get(key);
    }

    /**
     * Return redacted trace-pool rescue candidates without exposing raw trace keys to
     * KPI callers.
     */
    public static List<Object> getPoolItems() {
        List<Object> primary = poolItems(TRACE_POOL_ITEMS_KEY);
        if (!primary.isEmpty()) {
            return primary;
        }
        return poolItems(TRACE_POOL_RESCUE_ITEMS_KEY);
    }

    /** Snapshot all trace attributes for UI/serialization. */
    public static Map<String, Object> getAll() {
        Map<String, Object> snapshot = new HashMap<>(TRACE.get());
        snapshot.remove(INTERNAL_DYNAMIC_KEYS);
        internalKeys(TRACE.get()).forEach(snapshot::remove);
        INTERNAL_ONLY_KEYS.forEach(snapshot::remove);
        snapshot.keySet().removeIf(TraceStore::isInternalOnlyPrefixKey);
        return snapshot;
    }

    /** Read-only filtered snapshot for diagnostics/reporting surfaces. */
    public static Map<String, Object> getByPrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return Map.of();
        }
        Map<String, Object> filtered = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : getAll().entrySet()) {
            if (entry.getKey() != null && entry.getKey().startsWith(prefix)) {
                filtered.put(entry.getKey(), entry.getValue());
            }
        }
        return filtered;
    }

    /** Remove all entries from the current thread's trace map. */
    public static void clear() {
        // Prefer remove() over clear() so the ThreadLocal doesn't retain a large map
        // across reused threads.
        TRACE.remove();
        SEQ.remove();
    }

    /**
     * Returns the next per-thread sequence number for the given name.
     *
     * <p>
     * This is useful for tagging multiple events of the same family with a stable
     * monotonic counter without depending on wall-clock timestamps.
     * </p>
     */
    public static long nextSequence(String name) {
        String suffix = (name == null ? "" : name.trim());
        if (suffix.isEmpty()) {
            suffix = "default";
        }
        String key = SEQ_PREFIX + suffix;
        AtomicLong counter = SEQ.get().computeIfAbsent(key, k -> new AtomicLong(0L));
        return counter.incrementAndGet();
    }

    /** Append a value to a list under key. */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public static void append(String key, Object value) {
        if (key == null || key.isBlank() || value == null) {
            return;
        }
        Map<String, Object> m = TRACE.get();
        if ("web.naver.filter.runs".equals(key) || "web.brave.attempt.runs".equals(key) || "web.tavily.attempt.runs".equals(key) || "web.serpapi.attempt.runs".equals(key)) {
            boolean[] dropped = {false};
            m.compute(key, (k, cur) -> {
                List<Object> rows = new java.util.ArrayList<>();
                if (cur instanceof List<?> prior) rows.addAll(prior);
                if (rows.size() >= 128) {
                    rows.remove(0);
                    dropped[0] = true;
                }
                rows.add(value instanceof Map<?, ?> row ? Map.copyOf(row) : value);
                return List.copyOf(rows);
            });
            if (dropped[0]) m.compute(key + ".dropped", (ignored, count) -> count instanceof Number n ? n.longValue() + 1 : 1L);
            return;
        }
        Object appended = m.compute(key, (k, cur) -> {
            if (cur == null) {
                CopyOnWriteArrayList list = new CopyOnWriteArrayList();
                list.add(value);
                return list;
            }
            if (cur instanceof CopyOnWriteArrayList cow) {
                cow.add(value);
                return cow;
            }
            if (cur instanceof List list) {
                CopyOnWriteArrayList cow = new CopyOnWriteArrayList(list);
                cow.add(value);
                return cow;
            }
            CopyOnWriteArrayList list = new CopyOnWriteArrayList();
            list.add(cur);
            list.add(value);
            return list;
        });
        if (MLA_BREADCRUMBS_KEY.equals(key)) {
            int count = appended instanceof java.util.Collection<?> rows ? rows.size() : 1;
            maxLong(MLA_BREADCRUMB_COUNT_KEY, count);
        }
    }

    private static List<Object> poolItems(String key) {
        Object value = get(key);
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            return List.copyOf(list);
        }
        return List.of(value);
    }

    /**
     * Atomically add a delta to a numeric long counter stored under the given key.
     *
     * <p>
     * If the existing value is missing or non-numeric, it is treated as 0.
     * </p>
     */
    public static long addLong(String key, long delta) {
        if (key == null || key.isBlank()) {
            return 0L;
        }
        Object newVal = TRACE.get().compute(key, (k, cur) -> {
            long curVal = toLong(cur);
            return curVal + delta;
        });
        return toLong(newVal);
    }

    /** Atomically increment a numeric long counter by 1. */
    public static long inc(String key) {
        return addLong(key, 1L);
    }

    /** Atomically increment a numeric long counter by delta. */
    public static long inc(String key, long delta) {
        return addLong(key, delta);
    }

    /** Atomically update the maximum value for a numeric long counter. */
    public static long maxLong(String key, long candidate) {
        if (key == null || key.isBlank()) {
            return 0L;
        }
        Object newVal = TRACE.get().compute(key, (k, cur) -> {
            long curVal = (cur == null) ? Long.MIN_VALUE : toLong(cur);
            return Math.max(curVal, candidate);
        });
        return toLong(newVal);
    }

    /** Read a String value from trace (null if absent or not a String). */
    public static String getString(String key) {
        Object val = get(key);
        return (val == null) ? null : String.valueOf(val);
    }

    /** Read a numeric long counter (0 if absent or non-numeric). */
    public static long getLong(String key) {
        if (key == null || key.isBlank()) {
            return 0L;
        }
        return toLong(TRACE.get().get(key));
    }

    private static long toLong(Object v) {
        if (v == null) {
            return 0L;
        }
        if (v instanceof Number n) {
            double numeric = n.doubleValue();
            if (Double.isFinite(numeric)) {
                return n.longValue();
            }
            String marker = "non_finite_number";
            log.debug("[TraceStore] numeric parse fallback errorHash={} errorLength={}",
                    SafeRedactor.hashValue(marker), marker.length());
            return 0L;
        }
        if (v instanceof String s) {
            String t = s.trim();
            if (t.isEmpty())
                return 0L;
            try {
                return Long.parseLong(t);
            } catch (NumberFormatException ignore) {
                log.debug("[TraceStore] numeric parse fallback errorHash={} errorLength={}",
                        SafeRedactor.hashValue(String.valueOf(ignore)), String.valueOf(ignore).length());
                return 0L;
            }
        }
        return 0L;
    }

    private static boolean isInternalOnlyPrefixKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        for (String prefix : INTERNAL_ONLY_PREFIXES) {
            if (key.startsWith(prefix)) {
                String suffix = key.substring(prefix.length());
                return !suffix.equals("cached") && !suffix.equals("cached.size");
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> internalKeys(Map<String, Object> trace) {
        Object existing = trace.get(INTERNAL_DYNAMIC_KEYS);
        if (existing instanceof Set<?> set) {
            return (Set<String>) set;
        }
        Set<String> created = ConcurrentHashMap.newKeySet();
        Object raced = trace.putIfAbsent(INTERNAL_DYNAMIC_KEYS, created);
        if (raced instanceof Set<?> set) {
            return (Set<String>) set;
        }
        return created;
    }

}
