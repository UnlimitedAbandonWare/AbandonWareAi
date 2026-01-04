package com.example.lms.guard;

import java.util.*;

public class PreflightReport {
    private final boolean allowed;
    private final List<String> accepted;
    private final List<String> rejected;
    private final boolean citationThresholdMet;
    private final int minCitations;

    public PreflightReport(boolean allowed, List<String> accepted, List<String> rejected,
                           boolean citationThresholdMet, int minCitations) {
        this.allowed = allowed;
        this.accepted = List.copyOf(accepted == null ? List.of() : accepted);
        this.rejected = List.copyOf(rejected == null ? List.of() : rejected);
        this.citationThresholdMet = citationThresholdMet;
        this.minCitations = minCitations;
    }

    public boolean isAllowed() { return allowed; }
    public List<String> getAccepted() { return accepted; }
    public List<String> getRejected() { return rejected; }
    public boolean isCitationThresholdMet() { return citationThresholdMet; }
    public int getMinCitations() { return minCitations; }
}