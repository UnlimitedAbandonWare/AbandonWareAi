// src/main/java/com/example/lms/search/terms/SelectedTermsDebug.java
package com.example.lms.search.terms;

import com.example.lms.trace.SafeRedactor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Debug-friendly (and prompt/PII-safe) representation for SelectedTerms.
 *
 * NOTE: This is meant for TraceStore/console/HTML rendering. It intentionally:
 * - stores only counts + small samples
 * - redacts tokens aggressively
 */
public final class SelectedTermsDebug {

    private SelectedTermsDebug() {
    }

    public static String toSummaryString(SelectedTerms st) {
        if (st == null) return "<null>";
        return "domainProfile=" + nullToDash(st.getDomainProfile())
                + " exact=" + sizeOf(st.getExact())
                + " must=" + sizeOf(st.getMust())
                + " should=" + sizeOf(st.getShould())
                + " negative=" + sizeOf(st.getNegative())
                + " aliases=" + sizeOf(st.getAliases())
                + " domains=" + sizeOf(st.getDomains());
    }

    public static Map<String, Object> toDebugMap(SelectedTerms st, int sampleLimit) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (st == null) {
            m.put("summary", "<null>");
            return m;
        }
        m.put("domainProfile", nullToDash(st.getDomainProfile()));

        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("exact", sizeOf(st.getExact()));
        counts.put("must", sizeOf(st.getMust()));
        counts.put("should", sizeOf(st.getShould()));
        counts.put("negative", sizeOf(st.getNegative()));
        counts.put("aliases", sizeOf(st.getAliases()));
        counts.put("domains", sizeOf(st.getDomains()));
        m.put("counts", counts);

        // Samples (redacted)
        Map<String, Object> samples = new LinkedHashMap<>();
        samples.put("exact", sampleTokens(st.getExact(), Math.min(sampleLimit, 3)));
        samples.put("must", sampleTokens(st.getMust(), Math.min(sampleLimit, 4)));
        samples.put("should", sampleTokens(st.getShould(), Math.min(sampleLimit, 4)));
        samples.put("negative", sampleTokens(st.getNegative(), sampleLimit));
        samples.put("aliases", sampleTokens(st.getAliases(), sampleLimit));
        samples.put("domains", sampleTokens(st.getDomains(), sampleLimit));
        m.put("samples", samples);

        m.put("summary", toSummaryString(st));
        return m;
    }

    public static List<String> sampleTokens(List<String> tokens, int limit) {
        List<String> out = new ArrayList<>();
        if (tokens == null || tokens.isEmpty() || limit <= 0) return out;
        int n = Math.min(limit, tokens.size());
        for (int i = 0; i < n; i++) {
            String t = tokens.get(i);
            String s = SafeRedactor.redact(t == null ? "" : t);
            s = s.replace("\n", " ").replace("\r", " ").trim();
            if (s.length() > 64) s = s.substring(0, 61) + "...";
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    private static int sizeOf(List<?> list) {
        return list == null ? 0 : list.size();
    }

    private static String nullToDash(String s) {
        return (s == null || s.isBlank()) ? "-" : s;
    }
}
