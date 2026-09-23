package com.example.lms.search;

import com.example.lms.trace.SafeRedactor;

import java.util.Locale;

/**
 * Canonical, redacted buckets for cross-provider web-search trace aliases.
 *
 * <p>Provider-specific traces may keep detailed internal reason codes, but
 * shared keys such as {@code web.provider.disabledReason} should stay stable
 * across Brave, Naver, SerpApi, and Tavily.
 */
public final class WebProviderTraceReasons {

    private WebProviderTraceReasons() {
    }

    public static String disabledReason(String reason) {
        String raw = reason == null ? "" : reason.trim();
        if (raw.isBlank()) {
            return "provider-disabled";
        }
        String normalized = raw.toLowerCase(Locale.ROOT)
                .replace('_', '-')
                .replace('.', '-')
                .replace(' ', '-');
        if (isBlankOrDummyKey(normalized)) {
            return "blank-or-dummy-key";
        }
        if (normalized.contains("missing")
                && (normalized.contains("key")
                || normalized.contains("credential")
                || normalized.contains("client-id")
                || normalized.contains("client-secret"))) {
            return "missing-key";
        }
        if (normalized.contains("disabled-by-config")
                || normalized.equals("disabled")
                || normalized.equals("provider-disabled")) {
            return "provider-disabled";
        }
        if (normalized.contains("quota")
                || normalized.contains("rate-limit")
                || normalized.contains("ratelimit")) {
            return "rate-limit";
        }

        return SafeRedactor.traceLabelOrFallback(raw, "unknown");
    }

    private static boolean isBlankOrDummyKey(String normalized) {
        if (normalized.isBlank()) {
            return true;
        }
        return normalized.contains("invalid")
                || normalized.contains("dummy")
                || normalized.contains("changeme")
                || normalized.contains("sk-local")
                || normalized.contains("placeholder")
                || normalized.contains("${")
                || normalized.contains("unresolved");
    }
}
