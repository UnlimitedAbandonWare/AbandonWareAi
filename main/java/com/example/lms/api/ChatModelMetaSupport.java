package com.example.lms.api;

import java.util.Locale;

final class ChatModelMetaSupport {

    private static final String MODEL_META_PREFIX = "?MODEL?";
    private static final String LEGACY_MODEL_META_PREFIX = "[MODEL] ";
    private static final String LEGACY_MODEL_META_PREFIX_Q = "?MODEL?";
    private static final String TRACE_META_PREFIX = "?TRACE?";
    private static final String LEGACY_TRACE_META_PREFIX_Q = "?TRACE?";

    private ChatModelMetaSupport() {
    }

    /** Prefer a concrete model id over LangChain wrapper labels. */
    static String resolveModelUsed(String fromLlm, String requested, String fallbackModel) {
        String cand = safeTrim(fromLlm);
        if (cand == null) {
            String req = safeTrim(requested);
            return (req != null && !req.isBlank()) ? req : fallbackModel;
        }

        String suffix = "";
        int fbPos = cand.indexOf(":fallback:");
        if (fbPos >= 0) {
            suffix = cand.substring(fbPos);
            cand = cand.substring(0, fbPos);
        }

        if (!cand.isBlank() && !isWrapperLabel(cand)) {
            return cand + suffix;
        }

        String req = safeTrim(requested);
        String base = (req != null && !req.isBlank()) ? req : fallbackModel;
        return base + suffix;
    }

    static String safeTrim(String s) {
        return (s == null) ? null : s.trim();
    }

    static boolean isWrapperLabel(String v) {
        if (v == null) {
            return true;
        }
        String s = v.trim().toLowerCase(Locale.ROOT);
        if (s.isBlank()) {
            return true;
        }

        String base = s;
        int colon = base.indexOf(':');
        if (colon > 0) {
            base = base.substring(0, colon);
        }

        if ("lc".equals(base)) {
            String rest = s.substring(s.indexOf(':') + 1);
            int colon2 = rest.indexOf(':');
            base = (colon2 > 0) ? rest.substring(0, colon2) : rest;
        }

        return base.endsWith("chatmodel");
    }

    static String extractModelUsed(String c) {
        if (c == null) {
            return null;
        }
        if (c.startsWith(MODEL_META_PREFIX)) {
            return c.substring(MODEL_META_PREFIX.length());
        }
        if (c.startsWith(LEGACY_MODEL_META_PREFIX)) {
            return c.substring(LEGACY_MODEL_META_PREFIX.length());
        }
        if (c.startsWith(LEGACY_MODEL_META_PREFIX_Q)) {
            return c.substring(LEGACY_MODEL_META_PREFIX_Q.length());
        }
        return null;
    }

    static String extractTraceHtml(String c) {
        if (c == null) {
            return null;
        }
        if (c.startsWith(TRACE_META_PREFIX)) {
            return c.substring(TRACE_META_PREFIX.length());
        }
        if (c.startsWith(LEGACY_TRACE_META_PREFIX_Q)) {
            return c.substring(LEGACY_TRACE_META_PREFIX_Q.length());
        }
        return null;
    }
}
