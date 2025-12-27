package com.nova.protocol.guard;


public class CitationGate {
    private final int minCitations;

    public CitationGate(int minCitations) {
        this.minCitations = minCitations;
    }

    public void enforce(int trustedCitations) {
        if (trustedCitations < minCitations) {
            throw new AutorunPreflightGate.GateRejected("insufficient citations: " + trustedCitations + " < " + minCitations);
        }
    }
}