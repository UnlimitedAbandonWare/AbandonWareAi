package com.example.lms.config;

import com.example.lms.search.TraceStore;

/**
 * Legacy system-property container; not a Spring bean or the main {@code /chat} retrieval switch.
 * The actual Lucene BM25 switch is {@code bm25.enabled}, declared by {@code Bm25Props}
 * with a default of {@code true}. Lucene retrieval is called by the separate
 * {@code /api/probe/search} chain, not the main LMS retrieval chain.
 * Enabling {@code retrieval.bm25.enabled} does not change main chat retrieval.
 * See {@code docs/RAG_SPARSE_STATUS.md} for the LATER assessment and evidence.
 *
 * @deprecated No active application consumer; retained for legacy configuration compatibility.
 */
@Deprecated(forRemoval = false)
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
