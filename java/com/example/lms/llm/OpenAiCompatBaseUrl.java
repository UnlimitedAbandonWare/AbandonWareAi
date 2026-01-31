package com.example.lms.llm;

import java.util.Locale;

/**
 * Normalizes OpenAI-compatible base URLs.
 *
 * <p>LangChain4j's OpenAiChatModel expects a base URL that ends with "/v1".
 * In the wild, configs often omit it ("http://localhost:11434"), or point to
 * a leaf endpoint (".../v1/chat/completions"). This helper tries to sanitize
 * those cases without being overly clever.</p>
 */
public final class OpenAiCompatBaseUrl {

    private OpenAiCompatBaseUrl() {
    }

    /**
     * @return sanitized baseUrl or empty string if input is null/blank
     */
    public static String sanitize(String baseUrl) {
        if (baseUrl == null) {
            return "";
        }
        String s = baseUrl.trim();
        if (s.isEmpty()) {
            return "";
        }

        // Remove trailing slashes.
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }

        // If URL already contains "/v1" segment, trim anything after it.
        String lower = s.toLowerCase(Locale.ROOT);
        int idx = lower.indexOf("/v1");
        if (idx >= 0) {
            int end = idx + 3; // length of "/v1"
            // Only treat as segment if boundary matches
            boolean boundaryOk = (lower.length() == end)
                    || (lower.charAt(end) == '/')
                    || (lower.charAt(end) == '?')
                    || (lower.charAt(end) == '#');
            if (boundaryOk) {
                return s.substring(0, end);
            }
        }

        // Otherwise append /v1
        return s + "/v1";
    }
}
