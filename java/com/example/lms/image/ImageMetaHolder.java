package com.example.lms.image;

import java.util.HashMap;
import java.util.Map;



/**
 * Thread-local store for image generation metadata.  The RAG chain can
 * populate entries such as {@code image.model}, {@code image.prompt}
 * and {@code image.size} via {@code ChainContext.putMeta()}, and the
 * image service can consult this holder to override its defaults.
 * Using a thread-local avoids leaking metadata across concurrent
 * requests.  Callers should clear the holder when metadata is no
 * longer needed.
 */
public final class ImageMetaHolder {

    private ImageMetaHolder() {
        // utility class
    }

    private static final ThreadLocal<Map<String, String>> META = ThreadLocal.withInitial(HashMap::new);

    /**
     * Store a metadata entry in the current thread context.
     *
     * @param key   metadata key (e.g. "image.prompt")
     * @param value metadata value, may be null
     */
    public static void put(String key, String value) {
        if (key == null) return;
        META.get().put(key, value);
    }

    /**
     * Retrieve a metadata entry from the current thread context.
     *
     * @param key metadata key
     * @return the associated value or null if absent
     */
    public static String get(String key) {
        return META.get().get(key);
    }

    /**
     * Retrieve a metadata entry or return a default value when the entry
     * is absent or null.
     *
     * @param key metadata key
     * @param def default value
     * @return stored value or {@code def} when no value exists
     */
    public static String getOrDefault(String key, String def) {
        String v = META.get().get(key);
        return (v != null) ? v : def;
    }

    /**
     * Remove all metadata entries from the current thread context.  Call
     * this method after completing image generation to avoid retaining
     * stale state.
     */
    public static void clear() {
        META.get().clear();
    }
}