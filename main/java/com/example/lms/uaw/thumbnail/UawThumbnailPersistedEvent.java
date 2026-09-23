package com.example.lms.uaw.thumbnail;

import com.example.lms.trace.SafeRedactor;

import java.util.List;

public record UawThumbnailPersistedEvent(
        String planId,
        String knowledgeDomain,
        String entityType,
        String caption,
        List<String> anchors,
        double confidenceScore) {

    public UawThumbnailPersistedEvent {
        planId = safe(planId, "UAW_thumbnail.v1", 120);
        knowledgeDomain = safe(knowledgeDomain, "UAW_THUMB", 80);
        entityType = safe(entityType, "THUMBNAIL", 80);
        caption = safe(caption, "", 240);
        anchors = anchors == null
                ? List.of()
                : anchors.stream()
                .filter(v -> v != null && !v.isBlank())
                .map(v -> safe(v, "", 120))
                .distinct()
                .limit(20)
                .toList();
        if (Double.isNaN(confidenceScore) || Double.isInfinite(confidenceScore)) {
            confidenceScore = 0.0d;
        }
        confidenceScore = Math.max(0.0d, Math.min(1.0d, confidenceScore));
    }

    public String graphText() {
        StringBuilder out = new StringBuilder();
        if (!caption.isBlank()) {
            out.append(caption);
        }
        if (!anchors.isEmpty()) {
            if (!out.isEmpty()) {
                out.append('\n');
            }
            out.append("anchors: ");
            for (int i = 0; i < anchors.size(); i++) {
                if (i > 0) {
                    out.append(", ");
                }
                out.append(anchors.get(i));
            }
        }
        String text = out.toString().trim();
        return text.length() <= 1_500 ? text : text.substring(0, 1_500).trim();
    }

    private static String safe(String value, String fallback, int max) {
        String raw = value == null ? fallback : value.trim().replaceAll("\\s+", " ");
        String out = SafeRedactor.safeMessage(raw, max);
        if (out == null) {
            out = "";
        }
        if (out.isBlank()) {
            out = fallback;
        }
        return out.length() <= max ? out : out.substring(0, max).trim();
    }
}
