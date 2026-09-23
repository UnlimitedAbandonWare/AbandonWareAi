package com.example.lms.llm;

import java.util.LinkedHashMap;

/** Bounded insertion policy for request timelines; never clears unrelated state. */
final class RequestTimelineRetentionPolicy {

    static final int DEFAULT_CAPACITY = 256;

    private final int capacity;

    RequestTimelineRetentionPolicy() {
        this(DEFAULT_CAPACITY);
    }

    RequestTimelineRetentionPolicy(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity");
        }
        this.capacity = capacity;
    }

    <K, V> void retain(LinkedHashMap<K, V> entries, K key, V value) {
        if (entries.containsKey(key)) {
            entries.put(key, value);
            return;
        }
        while (entries.size() >= capacity) {
            entries.remove(entries.keySet().iterator().next());
        }
        entries.put(key, value);
    }
}
