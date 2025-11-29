package com.abandonware.ai.agent.integrations.guard;

import java.util.Collection;
public final class CitationGate {
    private final int minCitations;
    public CitationGate() { this(3); }
    public CitationGate(int minCitations) { this.minCitations = Math.max(0, minCitations); }
    public boolean allow(Collection<?> citations) {
        int n = (citations == null) ? 0 : citations.size();
        return n >= minCitations;
    }
}