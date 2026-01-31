package com.example.lms.guard;

import java.util.List;

/**
 * Citation gate that checks minimum count and (optionally) requires official sources tier.
 * This is a passive utility not tied to any framework.
 */
public class CitationGate {

    private final int minCount;
    private final boolean requireOfficial;

    public CitationGate() {
        this.minCount = Integer.parseInt(System.getProperty("guard.citation.min_count", "2"));
        this.requireOfficial = Boolean.parseBoolean(System.getProperty("guard.citation.require_official", "true"));
    }

    public boolean check(List<String> sources, List<String> official) {
        if (sources == null) return false;
        if (sources.size() < minCount) return false;
        if (requireOfficial && (official == null || official.isEmpty())) return false;
        return true;
    }
}