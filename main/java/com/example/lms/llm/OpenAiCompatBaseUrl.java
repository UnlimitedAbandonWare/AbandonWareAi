package com.example.lms.llm;

import java.net.URI;
import java.util.List;

/**
 * Normalizes OpenAI-compatible base URLs.
 *
 * <p>Preserves provider and reverse-proxy prefixes. Host-only compatibility
 * endpoints retain the established /v1 default; known endpoint suffixes are removed.</p>
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

        try {
            URI uri = URI.create(s);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException();
            }
            String path = uri.getRawPath();
            while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
            for (String suffix : List.of("/chat/completions", "/responses", "/embeddings", "/completions")) {
                if (path.endsWith(suffix)) {
                    path = path.substring(0, path.length() - suffix.length());
                    break;
                }
            }
            if (path.isEmpty()) path = "/v1";
            return uri.getScheme() + "://" + uri.getRawAuthority() + path;
        } catch (IllegalArgumentException invalid) {
            // Do not echo a URL that may contain credentials or private query values.
            throw new IllegalArgumentException("invalid_openai_compatible_base_url");
        }
    }
}
