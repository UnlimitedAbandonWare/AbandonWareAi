package com.example.lms.guard;

import java.util.*;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.example.lms.guard.PreflightReport
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.example.lms.guard.PreflightReport
role: config
*/
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