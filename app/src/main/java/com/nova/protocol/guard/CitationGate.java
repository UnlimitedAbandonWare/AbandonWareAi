package com.nova.protocol.guard;

import java.net.URI;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.nova.protocol.guard.CitationGate
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.nova.protocol.guard.CitationGate
role: config
*/
public class CitationGate {
    public static final class Policy {
        public final int min;
        public final int minForRuleBreak;
        public final int hostDiversityMin;
        public final boolean noveltyBoost;

        public Policy(int min, int minForRuleBreak, int hostDiversityMin, boolean noveltyBoost) {
            this.min = min;
            this.minForRuleBreak = minForRuleBreak;
            this.hostDiversityMin = hostDiversityMin;
            this.noveltyBoost = noveltyBoost;
        }
    }

    private final Policy policy;

    public CitationGate(int minCitations) {
        this.policy = new Policy(minCitations, Math.max(minCitations, 4), 1, false);
    }

    public CitationGate(Policy policy) {
        this.policy = policy;
    }

    /** Enforce citation constraints.
     * @param evidenceUrls URLs of evidence documents actually used.
     * @param noveltyScore [0,1] novelty/Δ-context score (optional; pass 0 if unknown).
     * @param ruleBreakActive whether RuleBreak/Zero-Break like modes are active.
     */
    public void enforce(Collection<String> evidenceUrls, double noveltyScore, boolean ruleBreakActive) {
        if (evidenceUrls == null) throw new AutorunPreflightGate.GateRejected("no evidence");
        int min = policy.min;
        if (ruleBreakActive) min = Math.max(min, policy.minForRuleBreak);
        if (policy.noveltyBoost && noveltyScore > 0.65) min += 1;

        if (evidenceUrls.size() < min) {
            throw new AutorunPreflightGate.GateRejected("insufficient citations: " + evidenceUrls.size() + " < " + min);
        }
        int hostDistinct = distinctHosts(evidenceUrls);
        if (hostDistinct < policy.hostDiversityMin) {
            throw new AutorunPreflightGate.GateRejected("need host diversity ≥ " + policy.hostDiversityMin + " (actual " + hostDistinct + ")");
        }
    }

    private int distinctHosts(Collection<String> urls) {
        Set<String> hosts = new HashSet<>();
        for (String u : urls) {
            if (u == null) continue;
            try {
                String h = URI.create(u).getHost();
                if (h != null) hosts.add(h.toLowerCase());
            } catch (Exception ignore) {}
        }
        return hosts.size();
    }
}