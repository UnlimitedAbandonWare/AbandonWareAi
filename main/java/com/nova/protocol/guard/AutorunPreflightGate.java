package com.nova.protocol.guard;

import java.util.Collection;
import java.util.Set;



public class AutorunPreflightGate {

    public static final class PreflightInput {
        public final Collection<String> domains;
        public final int evidenceCount;
        public final boolean isAuthorized;

        public PreflightInput(Collection<String> domains, int evidenceCount, boolean isAuthorized) {
            this.domains = domains; this.evidenceCount = evidenceCount; this.isAuthorized = isAuthorized;
        }
    }

    public void check(PreflightInput in) {
        requireTrustedDomains(in.domains);
        requireMinEvidence(in.evidenceCount, 2);
        if (!in.isAuthorized) throw new GateRejected("unauthorized");
    }

    private void requireTrustedDomains(Collection<String> domains) {
        if (domains == null || domains.isEmpty()) throw new GateRejected("no domains");
        // Optionally: check against a whitelist here (pluggable)
    }

    private void requireMinEvidence(int count, int min) {
        if (count < min) throw new GateRejected("insufficient evidence: " + count + " < " + min);
    }

    public static class GateRejected extends RuntimeException {
        public GateRejected(String msg) { super(msg); }
    }
}