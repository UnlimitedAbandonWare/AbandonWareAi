package com.example.lms.guard;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@ConditionalOnProperty(name = "gate.preflight.enabled", havingValue = "true", matchIfMissing = false)
public class AutorunPreflightGate {

    public PreflightReport check(Collection<String> plannedDomains, Set<String> whitelist, int minCitations) {
        List<String> rejected = new ArrayList<>();
        List<String> accepted = new ArrayList<>();
        for (String d : plannedDomains) {
            boolean ok = whitelist == null || whitelist.isEmpty() || whitelist.contains(d);
            (ok ? accepted : rejected).add(d);
        }
        boolean passesWhitelist = rejected.isEmpty();
        boolean passesCitations = (accepted.size() >= Math.max(0, minCitations));
        boolean ok = passesWhitelist && passesCitations;
        return new PreflightReport(ok, accepted, rejected, passesCitations, minCitations);
    }
}