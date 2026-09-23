package com.example.lms.config;

import com.example.lms.search.TraceStore;

/**
 * Simple feature flag container for BM25 retriever.
 */
public class Bm25Config {
    public boolean enabled = Boolean.parseBoolean(System.getProperty("retrieval.bm25.enabled", "false"));
    public String indexPath = System.getProperty("bm25.index.path", "");
    public int topK = parsePositiveIntProperty("bm25.topK", 50);

    public static int parsePositiveIntProperty(String name, int fallback) {
        String raw = System.getProperty(name);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException ignore) {
            traceSuppressed("bm25Config.topK");
            return fallback;
        }
    }

    private static void traceSuppressed(String stage) {
        TraceStore.put("config.suppressed." + stage, true);
        TraceStore.put("config.suppressed." + stage + ".errorType", "invalid_number");
    }
}
