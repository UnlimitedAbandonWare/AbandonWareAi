package com.example.lms.scheduler;

import org.springframework.http.HttpHeaders;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Whitelisted response headers to capture for BLUE(Gemini) calls.
 *
 * <p>We only store a small, safe subset (request IDs, quota/rate-limit hints)
 * to help ops/debugging. All header names are matched case-insensitively.</p>
 */
public final class BlueHeaderWhitelist {

    private static final List<String> EXACT = List.of(
            "retry-after",
            "x-request-id",
            "request-id",
            "x-correlation-id",
            "x-trace-id",
            "trace-id"
    );

    private static final List<String> PREFIX = List.of(
            "x-goog-",
            "x-ratelimit-",
            "ratelimit-"
    );

    private BlueHeaderWhitelist() {}

    /**
     * Extract whitelisted headers as a compact map.
     *
     * @return lower-cased headerName -> first header value
     */
    public static Map<String, String> extract(HttpHeaders headers) {
        if (headers == null || headers.isEmpty()) return Map.of();

        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : headers.entrySet()) {
            String name = e.getKey();
            if (name == null) continue;
            String key = name.toLowerCase();
            if (!isWhitelisted(key)) continue;

            List<String> vals = e.getValue();
            if (vals == null || vals.isEmpty()) continue;
            String v = vals.get(0);
            if (v == null) continue;
            v = v.trim();
            if (v.isEmpty()) continue;
            if (v.length() > 240) v = v.substring(0, 240) + "…";
            out.put(key, v);
        }
        return out.isEmpty() ? Map.of() : Map.copyOf(out);
    }

    private static boolean isWhitelisted(String keyLower) {
        if (keyLower == null) return false;
        for (String s : EXACT) {
            if (keyLower.equals(s)) return true;
        }
        for (String p : PREFIX) {
            if (keyLower.startsWith(p)) return true;
        }
        // quota/rate hints sometimes appear with varied naming
        return keyLower.contains("quota") || keyLower.contains("rate") || keyLower.contains("limit");
    }
}
