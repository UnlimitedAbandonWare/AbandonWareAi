package com.example.lms.dto;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public record LearningContextMetadata(
        String actorRole,
        int signalCount,
        boolean summaryPresent,
        List<String> sourceTags,
        boolean degraded,
        String degradedReason) {

    private static final Set<String> ALLOWED_ROLES = Set.of("TRAINING_USER", "TRAINING_SUPPORT", "RAG_ADMIN", "ANONYMOUS");
    private static final Set<String> ALLOWED_SOURCE_TAGS = Set.of(
            "TRAINING_ACTIVITY",
            "TRAINING_TASK",
            "TRAINING_SAMPLE",
            "QUALITY_SIGNAL",
            "FEEDBACK",
            "AUTOLEARN",
            "RAG_OPS");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern SECRET_PATTERN = Pattern.compile("(?i)(bearer\\s+[A-Za-z0-9._~+/\\-]+=*|sk-[A-Za-z0-9_-]{8,}|AIza[0-9A-Za-z_-]{8,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|token[:=][^\\s]+|secret[:=][^\\s]+|ownerToken[:=][^\\s]+)");

    public LearningContextMetadata {
        actorRole = normalizeRole(actorRole);
        signalCount = Math.max(0, signalCount);
        sourceTags = sanitizeTags(sourceTags);
        degradedReason = degraded ? sanitizeReason(degradedReason) : "";
    }

    public static LearningContextMetadata empty() {
        return new LearningContextMetadata("ANONYMOUS", 0, false, List.of(), false, "");
    }

    public static LearningContextMetadata fromTrace(Map<String, Object> trace) {
        if (trace == null || trace.isEmpty()) {
            return empty();
        }
        boolean degraded = toBoolean(firstPresent(trace, "prompt.ragSupport.degraded", "prompt.learningDegraded"));
        return new LearningContextMetadata(
                stringValue(firstPresent(trace, "prompt.ragSupport.role", "prompt.learningRole"), "ANONYMOUS"),
                toNonNegativeInt(firstPresent(trace, "prompt.ragSupport.signalCount", "prompt.learningSignalCount")),
                toBoolean(firstPresent(trace, "prompt.ragSupport.summaryPresent", "prompt.learningSummaryPresent")),
                tagsValue(firstPresent(trace, "prompt.ragSupport.sourceTags", "prompt.learningSourceTags")),
                degraded,
                degraded
                        ? stringValue(firstPresent(trace, "prompt.ragSupport.degradedReason", "prompt.learningDegradedReason"), "")
                        : "");
    }

    private static Object firstPresent(Map<String, Object> trace, String primaryKey, String fallbackKey) {
        if (trace == null || trace.isEmpty()) {
            return null;
        }
        Object primary = trace.get(primaryKey);
        if (primary != null) {
            return primary;
        }
        return trace.get(fallbackKey);
    }

    private static String normalizeRole(String raw) {
        String role = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        role = switch (role) {
            case "STUDENT" -> "TRAINING_USER";
            case "TEACHER" -> "TRAINING_SUPPORT";
            case "ADMIN" -> "RAG_ADMIN";
            default -> role;
        };
        return ALLOWED_ROLES.contains(role) ? role : "ANONYMOUS";
    }

    private static List<String> sanitizeTags(List<String> rawTags) {
        if (rawTags == null || rawTags.isEmpty()) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        for (String raw : rawTags) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String tag = normalizeSourceTag(raw);
            if (ALLOWED_SOURCE_TAGS.contains(tag)) {
                tags.add(tag);
            }
        }
        return List.copyOf(tags);
    }

    private static String normalizeSourceTag(String raw) {
        String tag = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        return switch (tag) {
            case "ATTENDANCE" -> "TRAINING_ACTIVITY";
            case "ASSIGNMENT" -> "TRAINING_TASK";
            case "SUBMISSION" -> "TRAINING_SAMPLE";
            case "GRADE" -> "QUALITY_SIGNAL";
            default -> tag;
        };
    }

    private static List<String> tagsValue(Object raw) {
        if (raw == null) {
            return List.of();
        }
        if (raw instanceof Iterable<?> iterable) {
            List<String> out = new ArrayList<>();
            for (Object item : iterable) {
                if (item != null) {
                    out.add(String.valueOf(item));
                }
            }
            return out;
        }
        String text = String.valueOf(raw).trim();
        if (text.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : text.replace("[", "").replace("]", "").split(",")) {
            if (!part.isBlank()) {
                out.add(part.trim());
            }
        }
        return out;
    }

    private static int toNonNegativeInt(Object raw) {
        if (raw instanceof Number n) {
            return Math.max(0, n.intValue());
        }
        try {
            return Math.max(0, Integer.parseInt(String.valueOf(raw).trim()));
        } catch (RuntimeException ex) {
            traceSuppressed("learningContext.signalCount", ex);
            return 0;
        }
    }

    private static boolean toBoolean(Object raw) {
        if (raw instanceof Boolean b) {
            return b;
        }
        return raw != null && Boolean.parseBoolean(String.valueOf(raw).trim());
    }

    private static String stringValue(Object raw, String fallback) {
        if (raw == null) {
            return fallback;
        }
        String text = String.valueOf(raw).trim();
        return text.isEmpty() ? fallback : text;
    }

    private static void traceSuppressed(String stage, Throwable ignored) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String safeErrorType = errorType(ignored);
        TraceStore.put("learning.context.suppressed.stage", safeStage);
        TraceStore.put("learning.context.suppressed.errorType", safeErrorType);
        TraceStore.put("learning.context.suppressed." + safeStage, true);
        TraceStore.put("learning.context.suppressed." + safeStage + ".errorType", safeErrorType);
    }

    private static String errorType(Throwable ignored) {
        if (ignored instanceof NumberFormatException) {
            return "invalid_number";
        }
        return ignored == null ? "unknown" : SafeRedactor.traceLabelOrFallback(ignored.getClass().getSimpleName(), "unknown");
    }

    private static String sanitizeReason(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String normalized = raw.replaceAll("[\\r\\n\\t]+", " ").trim();
        String candidate = firstReasonToken(normalized);
        if (candidate.isBlank()
                || EMAIL_PATTERN.matcher(candidate).find()
                || SECRET_PATTERN.matcher(candidate).find()) {
            return "REDACTED";
        }
        String reasonCode = candidate.replaceAll("[^A-Za-z0-9_.-]", "_").trim();
        if (reasonCode.isBlank()) {
            return "REDACTED";
        }
        return reasonCode.length() <= 80 ? reasonCode : reasonCode.substring(0, 80);
    }

    private static String firstReasonToken(String normalized) {
        if (normalized == null || normalized.isBlank()) {
            return "";
        }
        String candidate = normalized;
        int colon = candidate.indexOf(':');
        if (colon > 0) {
            candidate = candidate.substring(0, colon);
        }
        String[] tokens = candidate.trim().split("\\s+");
        return tokens.length == 0 ? "" : tokens[0].trim();
    }
}
