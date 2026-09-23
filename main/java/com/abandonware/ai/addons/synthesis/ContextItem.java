package com.abandonware.ai.addons.synthesis;

import java.util.Map;



public record ContextItem(
        String id, String title, String snippet, String source, double score, int rank,
        Map<String, Object> meta
) {
    public ContextItem {
        id = safeText(id);
        title = safeText(title);
        snippet = safeText(snippet);
        source = safeText(source);
        score = Double.isFinite(score) ? Math.max(0.0d, score) : 0.0d;
        rank = Math.max(0, rank);
        meta = meta == null ? Map.of() : Map.copyOf(meta);
    }

    private static String safeText(String value) {
        return value == null ? "" : value;
    }
}
