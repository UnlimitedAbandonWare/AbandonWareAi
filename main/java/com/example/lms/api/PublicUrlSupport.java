package com.example.lms.api;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;

import java.net.URI;
import java.util.Locale;

final class PublicUrlSupport {
    private static final String DEFAULT_PUBLIC_ORIGIN = "https://abandonwareai.kro.kr";

    private PublicUrlSupport() {
    }

    static String publicOriginUrl(String configured) {
        String raw = configured == null ? "" : configured.trim();
        if (raw.isBlank()) {
            return DEFAULT_PUBLIC_ORIGIN;
        }
        if (!raw.startsWith("https://") && !raw.startsWith("http://")) {
            raw = "https://" + raw;
        }
        try {
            URI uri = URI.create(raw);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme != null && host != null
                    && ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) {
                scheme = scheme.toLowerCase(Locale.ROOT);
                host = host.toLowerCase(Locale.ROOT);
                if (host.contains(":") && !host.startsWith("[")) {
                    host = "[" + host + "]";
                }
                String port = uri.getPort() > 0 ? ":" + uri.getPort() : "";
                return scheme + "://" + host + port;
            }
        } catch (IllegalArgumentException ex) {
            tracePublicUrlSuppressed("public.origin", raw, ex);
            // Fall through to a conservative slash-only cleanup for invalid operator input.
        }
        return raw.replaceAll("/+$", "");
    }

    private static void tracePublicUrlSuppressed(String stage, String input, RuntimeException failure) {
        String safeStage = stage == null || stage.isBlank() ? "unknown" : stage;
        TraceStore.put("api.publicUrl.suppressed.stage", safeStage);
        TraceStore.put("api.publicUrl.suppressed.errorType",
                failure == null ? "unknown" : failure.getClass().getSimpleName());
        TraceStore.put("api.publicUrl.suppressed." + safeStage, true);
        TraceStore.put("api.publicUrl.suppressed.inputLength", input == null ? 0 : input.length());
        TraceStore.put("api.publicUrl.suppressed.inputHash", SafeRedactor.hashValue(input));
    }
}
