package com.example.lms.search;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Request-scoped trace bag.
 *
 * <p>This store is frequently accessed across async boundaries. The underlying
 * map is a {@link ConcurrentHashMap} to reduce cross-thread corruption when the
 * same context is installed in worker threads.</p>
 */
public final class TraceStore {
    private static final ThreadLocal<Map<String, Object>> TRACE =
            ThreadLocal.withInitial(ConcurrentHashMap::new);

    private static final ThreadLocal<Map<String, AtomicLong>> SEQ =
            ThreadLocal.withInitial(ConcurrentHashMap::new);

    /** Internal prefix for per-key sequence counters stored inside the trace context map. */
    private static final String SEQ_PREFIX = "__seq.";

    private TraceStore() {
    }

    /** Return the live underlying context map for this thread. */
    public static Map<String, Object> context() {
        return TRACE.get();
    }

    /** Install a context map for this thread (async propagation용). */
    public static void installContext(Map<String, Object> ctx) {
        if (ctx == null) {
            clear();
            return;
        }
        TRACE.set(ctx);
    }

    public static void put(String key, Object value) {
        if (key == null || key.isBlank()) {
            return;
        }
        TRACE.get().put(key, value);
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

    /** Snapshot all trace attributes for UI/serialization. */
    public static Map<String, Object> getAll() {
        return new HashMap<>(TRACE.get());
    }

    /** Remove all entries from the current thread's trace map. */
    public static void clear() {
        // Prefer remove() over clear() so the ThreadLocal doesn't retain a large map across reused threads.
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
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void append(String key, Object value) {
        if (key == null || key.isBlank() || value == null) {
            return;
        }
        Map<String, Object> m = TRACE.get();
        m.compute(key, (k, cur) -> {
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
    }
}
