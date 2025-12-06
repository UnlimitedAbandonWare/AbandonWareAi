package com.example.lms.service.rag.fusion;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;



/**
 * Utility methods for deduplicating search result documents.  This class
 * consolidates common deduplication logic used by weighted RRF fusion and
 * other retrieval components.  Documents are considered duplicates when
 * their canonicalised URLs match.  The canonical key normalises the
 * scheme, host and path, strips query parameters and fragments and
 * removes common tracking parameters (e.g. UTM tags).  When the URL is
 * absent the original object identity is used to preserve ordering.
 *
 * <p>Examples:</p>
 * <ul>
 *   <li>https://example.com/page?utm_source=foo → https://example.com/page</li>
 *   <li>HTTP://Example.com/News/ → http://example.com/News</li>
 * </ul>
 */
public final class DedupUtil {

    private DedupUtil() {}

    /**
     * Remove duplicate items from the provided list based on their canonical
     * URL.  The first occurrence of each canonical URL is retained and
     * subsequent duplicates are discarded.  Items lacking a getUrl() method
     * are treated as unique and preserved in order.
     *
     * @param in the list of result objects; may be null
     * @param <T> the element type
     * @return a new list containing only the first occurrence of each URL
     */
    public static <T> List<T> dedupByCanonicalUrl(List<T> in) {
        if (in == null || in.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        Map<String, T> m = new LinkedHashMap<>();
        for (T d : in) {
            if (d == null) continue;
            String url = null;
            try {
                // Attempt to invoke getUrl() via reflection.  Many document
                // DTOs expose a getUrl() accessor.  When absent the call
                // will throw and we treat the item as unique.
                var mtd = d.getClass().getMethod("getUrl");
                Object o = mtd.invoke(d);
                if (o instanceof String) {
                    url = (String) o;
                }
            } catch (Throwable ignore) {
                // ignore reflection errors; treat item as unique
            }
            String key = (url == null || url.isBlank()) ? null : canonicalKey(url);
            // Use the canonical key when present; otherwise rely on the object's identity
            if (key == null) {
                // Fallback: preserve insertion order for items without URLs
                m.putIfAbsent("__idx" + m.size(), d);
            } else {
                m.putIfAbsent(key, d);
            }
        }
        return new ArrayList<>(m.values());
    }

    /**
     * Canonicalise a URL by normalising the scheme, host and path and
     * stripping query parameters, fragments and trailing slashes.  When
     * parsing fails the input string is returned as-is.  The normalisation
     * deliberately retains the path case to avoid collapsing distinct
     * resources on case-sensitive servers.
     *
     * @param u the URL string to canonicalise
     * @return a canonical key suitable for deduplication
     */
    static String canonicalKey(String u) {
        if (u == null || u.isBlank()) return "";
        try {
            URI uri = URI.create(u);
            String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase(java.util.Locale.ROOT) : "";
            String host = uri.getHost() != null ? uri.getHost().toLowerCase(java.util.Locale.ROOT) : "";
            String path = uri.getPath() != null ? uri.getPath() : "";
            // Remove common tracking query parameters (utm_*) by ignoring the query entirely
            // and drop any fragments.
            // Remove trailing slash except when the path is only '/'
            if (path.endsWith("/") && path.length() > 1) {
                path = path.substring(0, path.length() - 1);
            }
            return scheme + "://" + host + path;
        } catch (Exception e) {
            // Fall back to the original URL when parsing fails
            return u;
        }
    }
}