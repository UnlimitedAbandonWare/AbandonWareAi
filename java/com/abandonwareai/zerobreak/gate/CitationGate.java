package com.abandonwareai.zerobreak.gate;

import java.util.Collection;

/** Ensures at least N credible citations exist before answer release. */
public class CitationGate {
    public boolean validate(Collection<String> citations, int minSources) {
        if (citations == null) return false;
        long distinct = citations.stream().map(CitationGate::canonical).distinct().count();
        return distinct >= minSources;
    }
    private static String canonical(String s) {
        if (s == null) return "";
        String t = s.trim().toLowerCase();
        // strip protocol / www
        t = t.replace("https://", "").replace("http://", "");
        if (t.startsWith("www.")) t = t.substring(4);
        // domain only
        int slash = t.indexOf('/');
        return slash >= 0 ? t.substring(0, slash) : t;
    }
}