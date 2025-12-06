package com.abandonware.ai.addons.synthesis;

import java.util.List;

/**
 * Compact context allocator that concatenates items into a prompt-friendly text block.
 */
public class MatrixTransformer {

    public String allocate(List<ContextItem> items) {
        if (items == null || items.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (ContextItem ci : items) {
            sb.append("### ").append(emptySafe(ci.title))
              .append(" (").append(emptySafe(ci.source)).append(")\n");
            sb.append(emptySafe(ci.snippet)).append("\n\n");
        }
        return sb.toString();
    }

    private static String emptySafe(String s){ return s == null ? "" : s; }

    public static final class ContextItem {
        public final String id, title, snippet, source;
        public ContextItem(String id, String title, String snippet, String source) {
            this.id = id; this.title = title; this.snippet = snippet; this.source = source;
        }
    }
}