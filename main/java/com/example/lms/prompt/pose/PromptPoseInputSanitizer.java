package com.example.lms.prompt.pose;

import com.example.lms.config.PromptPoseProperties;
import com.example.lms.trace.SafeRedactor;

import java.util.Locale;
import java.util.regex.Pattern;

public final class PromptPoseInputSanitizer {

    private static final Pattern PRIVATE_PAYLOAD = Pattern.compile(
            "(?is)(authorization\\s*:|bearer\\s+|api[_-]?key|client[_-]?secret|owner\\s*token|ownertoken|"
                    + "-----BEGIN|sk-[A-Za-z0-9_-]{12,}|gsk_[A-Za-z0-9_-]{12,}|"
                    + "sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|"
                    + "[A-Za-z]:\\\\|/Users/|/home/|\\\\Downloads\\\\|\\\\OneDrive\\\\)");
    private static final Pattern HANGUL = Pattern.compile("[\\uAC00-\\uD7AF]");

    private PromptPoseInputSanitizer() {
    }

    public static SanitizedInput sanitize(String query, PromptPoseProperties props) {
        String raw = query == null ? "" : query.trim();
        int maxChars = props == null || props.getDraft() == null ? 360 : props.getDraft().getMaxInputChars();
        maxChars = clamp(maxChars, 80, 720);
        String queryHash12 = SafeRedactor.hash12(raw);
        if (raw.isBlank()) {
            return SanitizedInput.blocked("blank_query", queryHash12, raw.length());
        }
        if (PRIVATE_PAYLOAD.matcher(raw).find()) {
            return SanitizedInput.blocked("private_payload", queryHash12, raw.length());
        }
        String preview = SafeRedactor.safeMessage(raw, maxChars);
        if (preview == null || preview.isBlank()) {
            return SanitizedInput.blocked("empty_after_redaction", queryHash12, raw.length());
        }
        preview = preview.replaceAll("\\s+", " ").trim();
        if (PRIVATE_PAYLOAD.matcher(preview).find()) {
            return SanitizedInput.blocked("private_payload", queryHash12, raw.length());
        }
        return new SanitizedInput(false, "", preview, queryHash12, language(raw), coarseIntent(raw), raw.length());
    }

    private static String language(String raw) {
        if (raw != null && HANGUL.matcher(raw).find()) {
            return "ko";
        }
        return "unknown";
    }

    private static String coarseIntent(String raw) {
        String s = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
        if (s.contains("error") || s.contains("exception") || s.contains("fail")
                || s.contains("오류") || s.contains("실패")) {
            return "debug";
        }
        if (s.contains("compare") || s.contains("vs") || s.contains("비교")) {
            return "compare";
        }
        if (s.contains("patch") || s.contains("fix") || s.contains("수정")) {
            return "patch";
        }
        return "general";
    }

    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }

    public record SanitizedInput(
            boolean blocked,
            String skipReason,
            String preview,
            String queryHash12,
            String language,
            String coarseIntent,
            int originalLength) {

        static SanitizedInput blocked(String reason, String queryHash12, int originalLength) {
            return new SanitizedInput(true, reason == null ? "blocked" : reason, "",
                    queryHash12, "unknown", "blocked", originalLength);
        }
    }
}
