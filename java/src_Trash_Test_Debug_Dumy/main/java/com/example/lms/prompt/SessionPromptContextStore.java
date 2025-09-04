package com.example.lms.prompt;

/**
 * Simple in‑memory cache for associating a PromptContext with a session ID.
 * This store is intentionally non‑expiring to minimise coupling between
 * components.  The supervising components are responsible for clearing
 * entries when sessions end.  See {@link PromptContext} for the data
 * structure carried.
 */
public final class SessionPromptContextStore {
    private static final java.util.concurrent.ConcurrentHashMap<String, PromptContext> MAP =
            new java.util.concurrent.ConcurrentHashMap<>();

    private SessionPromptContextStore() {
        // static utility class
    }

    /**
     * Associate the given session ID with a PromptContext.  When either
     * argument is null no action is taken.
     *
     * @param sid session identifier
     * @param ctx prompt context to store
     */
    public static void put(String sid, PromptContext ctx) {
        if (sid != null && ctx != null) {
            MAP.put(sid, ctx);
        }
    }

    /**
     * Retrieve the PromptContext associated with the given session ID.
     * Returns null when no mapping exists.
     *
     * @param sid session identifier
     * @return the stored PromptContext or null
     */
    public static PromptContext get(String sid) {
        return (sid == null) ? null : MAP.get(sid);
    }

    /**
     * Remove any association for the given session ID.
     *
     * @param sid session identifier
     */
    public static void remove(String sid) {
        if (sid != null) {
            MAP.remove(sid);
        }
    }
}