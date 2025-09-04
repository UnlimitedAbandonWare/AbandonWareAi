package com.example.lms.service.trace;

import com.example.lms.service.NaverSearchService.SearchTrace;

/**
 * Compatibility layer for {@link SearchTrace}.  Over time the {@code SearchTrace}
 * interface has changed the names of various accessor methods (for example
 * {@code totalMs()}, {@code tookMs()}, {@code elapsedMs()}, {@code durationMs()}).
 * This helper provides a uniform way to extract the desired fields regardless
 * of the underlying implementation by trying multiple method names via reflection.
 */
public final class TraceCompat {
    private TraceCompat() {}

    /**
     * Return the total duration of the search in milliseconds.  Attempts to call
     * a series of candidate methods and returns the first numeric value found.
     *
     * @param t the trace object
     * @return the duration in milliseconds, or {@code -1} when unavailable
     */
    public static long totalMs(SearchTrace t) {
        Long v = longOf(t, "totalMs", "tookMs", "elapsedMs", "durationMs");
        return v == null ? -1L : v;
    }

    /**
     * Determine whether the domain filter was enabled for this search.
     * Attempts to call multiple boolean accessors and returns {@code true}
     * only when an enabled flag is present.
     *
     * @param t the trace object
     * @return {@code true} if the domain filter was enabled
     */
    public static boolean domainFilterEnabled(SearchTrace t) {
        Boolean v = boolOf(t, "domainFilterEnabled", "isDomainFilterEnabled");
        return v != null && v;
    }

    /**
     * Determine whether the keyword filter was enabled.
     *
     * @param t the trace object
     * @return {@code true} if the keyword filter was enabled
     */
    public static boolean keywordFilterEnabled(SearchTrace t) {
        Boolean v = boolOf(t, "keywordFilterEnabled", "isKeywordFilterEnabled");
        return v != null && v;
    }

    /**
     * Return the suffix applied to the search query if any.  Looks for both
     * accessor method names {@code suffixApplied} and {@code getSuffixApplied}.
     *
     * @param t the trace object
     * @return the suffix string or {@code null} when absent
     */
    public static String suffixApplied(SearchTrace t) {
        return strOf(t, "suffixApplied", "getSuffixApplied");
    }

    /**
     * Generic totalMs extractor for any trace type.  Uses reflection to probe common
     * accessor names (tookMs, getTookMs, elapsedMs, durationMs) on the supplied object.
     * Returns null when no suitable method is found or invocation fails.
     *
     * @param trace the trace object (may be any type)
     * @return a Long value representing the total duration in milliseconds, or null
     */
    public static Long totalMs(Object trace) {
        if (trace == null) return null;
        try {
            // Try common names for elapsed time; do not include "totalMs" here to avoid
            // shadowing the typed variant above.
            Long v = longOf(trace, "tookMs", "getTookMs", "elapsedMs", "durationMs");
            if (v != null) return v;
        } catch (Exception ignore) {
        }
        return null;
    }

    /**
     * Determine if a trace should be considered offline or invalid.  A trace is
     * offline when there are no snippets and either no providers are present or
     * the elapsed time is below 50ms.  Utilises {@link #totalMs(Object)} and
     * common boolean accessors (noProviders, isNoProviders, providersEmpty).
     *
     * @param trace    the trace object to inspect
     * @param snippets the collected snippet list (may be null)
     * @return true when the trace appears offline or empty, false otherwise
     */
    public static boolean offline(Object trace, java.util.List<String> snippets) {
        Long took = totalMs(trace);
        boolean noSnippets = (snippets == null || snippets.isEmpty());
        boolean noProviders = boolOf(trace, "noProviders", "isNoProviders", "providersEmpty") == Boolean.TRUE;
        long t = (took == null ? 0L : took.longValue());
        return noSnippets && (noProviders || t < 50L);
    }
private static Long longOf(Object o, String name) {
    try {
        var m = o.getClass().getMethod(name);
        Object r = m.invoke(o);
        if (r == null) return null;
        if (r instanceof Number num) return num.longValue();
        return Long.valueOf(String.valueOf(r));
    } catch (Exception ignore) {}
    return null;
}
private static Long longOf(Object o, String... names) {
    for (String n : names) {
        Long v = longOf(o, n);
        if (v != null) return v;
    }
    return null;
}
private static Boolean boolOf(Object o, String name) {
    try {
        var m = o.getClass().getMethod(name);
        Object r = m.invoke(o);
        if (r == null) return null;
        if (r instanceof Boolean b) return b;
        String s = String.valueOf(r).toLowerCase();
        if ("true".equals(s) || "1".equals(s) || "yes".equals(s)) return Boolean.TRUE;
        if ("false".equals(s) || "0".equals(s) || "no".equals(s)) return Boolean.FALSE;
    } catch (Exception ignore) {}
    return null;
}
private static Boolean boolOf(Object o, String... names) {
    for (String n : names) {
        Boolean v = boolOf(o, n);
        if (v != null) return v;
    }
    return null;
}
private static String strOf(Object o, String name) {
    try {
        var m = o.getClass().getMethod(name);
        Object r = m.invoke(o);
        if (r == null) return null;
        return String.valueOf(r);
    } catch (Exception ignore) {}
    return null;
}
private static String strOf(Object o, String... names) {
    for (String n : names) {
        String v = strOf(o, n);
        if (v != null && !v.isBlank()) return v;
    }
    return null;
}
}