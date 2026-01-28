package com.example.lms.service.rag.fusion;

import java.net.URI;
import java.util.Locale;

/** Canonical key generator for deduping same documents across sources. */
public final class RerankCanonicalizer {
    private RerankCanonicalizer() {}
    public static String canonicalKey(String urlOrId) {
        if (urlOrId == null) return "";
        String s = urlOrId.trim().toLowerCase(Locale.ROOT);
        // Strip URL fragment (#/* ... */) and common tracking params
        try {
            URI u = URI.create(s);
            String path = (u.getPath() == null) ? "" : u.getPath();
            String host = (u.getHost() == null) ? "" : u.getHost();
            String query = u.getQuery();
            if (query != null) {
                // Drop UTM params (best-effort)
                String[] parts = query.split("&");
                StringBuilder kept = new StringBuilder();
                for (String p : parts) {
                    if (p.startsWith("utm_")) continue;
                    if (!p.isBlank()) {
                        if (kept.length() > 0) kept.append('&');
                        kept.append(p);
                    }
                }
                query = kept.length() == 0 ? null : kept.toString();
            }
            String base = (host + path);
            return (query == null) ? base : (base + "?" + query);
        } catch (IllegalArgumentException e) {
            // Not a URL; use as-is without fragments
            int i = s.indexOf('#');
            return (i >= 0) ? s.substring(0, i) : s;
        }
    }
}