package com.example.lms.service.rag.fusion;

import java.net.URI;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Canonical key generator for deduping same documents across sources. */
public final class RerankCanonicalizer {
    private static final Logger log = LoggerFactory.getLogger(RerankCanonicalizer.class);

    private RerankCanonicalizer() {}
    public static String canonicalKey(String urlOrId) {
        if (urlOrId == null) return "";
        String s = urlOrId.trim();
        // Strip URL fragment (#/* ... */) and common tracking params
        try {
            URI u = URI.create(s);
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT) + ":";
            if (u.isOpaque()) return scheme + u.getRawSchemeSpecificPart();
            String authority = u.getRawAuthority();
            if (u.getHost() != null) {
                authority = (u.getRawUserInfo() == null ? "" : u.getRawUserInfo() + "@")
                        + u.getHost().toLowerCase(Locale.ROOT)
                        + (u.getPort() < 0 ? "" : ":" + u.getPort());
            }
            String path = u.getRawPath() == null ? "" : u.getRawPath();
            if (authority != null && path.isEmpty() && ("http:".equals(scheme) || "https:".equals(scheme))) {
                path = "/";
            }
            String query = u.getRawQuery();
            if (query != null && !query.isEmpty()) {
                // Drop common tracking params (best-effort)
                String[] parts = query.split("&");
                StringBuilder kept = new StringBuilder();
                for (String p : parts) {
                    if (isTrackingParam(p)) continue;
                    if (!p.isBlank()) {
                        if (kept.length() > 0) kept.append('&');
                        kept.append(p);
                    }
                }
                query = kept.length() == 0 ? null : kept.toString();
            }
            String base = scheme + (authority == null ? "" : "//" + authority) + path;
            return (query == null) ? base : (base + "?" + query);
        } catch (IllegalArgumentException e) {
            log.debug("[RerankCanonicalizer] fail-soft stage={}", "canonicalKey");
            // Not a URL; use as-is without fragments
            int i = s.indexOf('#');
            return (i >= 0) ? s.substring(0, i) : s;
        }
    }

    private static boolean isTrackingParam(String part) {
        if (part == null || part.isBlank()) {
            return true;
        }
        int equals = part.indexOf('=');
        String name = (equals >= 0 ? part.substring(0, equals) : part).trim().toLowerCase(Locale.ROOT);
        return name.startsWith("utm_")
                || "fbclid".equals(name)
                || "gclid".equals(name)
                || "gbraid".equals(name)
                || "wbraid".equals(name)
                || "msclkid".equals(name);
    }
}
