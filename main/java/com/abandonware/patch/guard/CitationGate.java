package com.abandonware.patch.guard;

import java.util.Collection;
import java.util.Objects;

public class CitationGate {
    private final int minCitations;
    public CitationGate(int minCitations) { this.minCitations = Math.max(0, minCitations); }
    public boolean pass(Collection<String> citedSources) {
        if (citedSources == null) return false;
        long distinct = citedSources.stream().filter(Objects::nonNull).distinct().count();
        return distinct >= minCitations;
    }
}