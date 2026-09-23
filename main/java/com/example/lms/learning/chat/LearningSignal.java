package com.example.lms.learning.chat;

import org.springframework.util.StringUtils;

import java.util.Locale;

public record LearningSignal(String kind, String label, String value, double score) {
    private static final int MAX_FIELD_LEN = 160;

    public LearningSignal(LearningSignalKind kind, String label, String value, double score) {
        this(kind == null ? null : kind.value(), label, value, score);
    }

    public LearningSignal {
        kind = normalizeKind(kind);
        label = sanitize(label, MAX_FIELD_LEN);
        value = sanitize(value, MAX_FIELD_LEN);
        score = clamp(score);
    }

    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append(kind);
        if (StringUtils.hasText(label)) {
            sb.append(" | ").append(label);
        }
        if (StringUtils.hasText(value)) {
            sb.append(": ").append(value);
        }
        sb.append(" | score=").append(String.format(Locale.ROOT, "%.2f", score));
        return sb.toString();
    }

    static String sanitize(String raw, int maxLen) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        String normalized = raw.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (normalized.length() <= maxLen) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLen - 1)) + "...";
    }

    private static String normalizeKind(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "LEARNING_SIGNAL";
        }
        return sanitize(raw, 64)
                .replaceAll("[^A-Za-z0-9_.-]", "_")
                .toUpperCase(Locale.ROOT);
    }

    private static double clamp(double raw) {
        if (!Double.isFinite(raw)) {
            return 0.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, raw));
    }
}
