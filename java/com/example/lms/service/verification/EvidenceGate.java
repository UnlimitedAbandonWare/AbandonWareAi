package com.example.lms.service.verification;

import com.example.lms.service.disambiguation.DisambiguationResult;



/**
 * Gate for deciding whether content generation should proceed based on the
 * disambiguation result and evidence snapshot.  Applications can supply
 * custom implementations to enforce stricter or more permissive policies.
 */
public interface EvidenceGate {
    /**
     * Decide whether generation is allowed.  Implementations should inspect
     * both the resolved entity (if any) and the evidence to determine if
     * hallucination or misinformation risk is acceptable.
     *
     * @param dr the result of any entity disambiguation step; may be null
     * @param ev snapshot of evidence collected during retrieval
     * @return true if generation is allowed, false if a fallback or
     *         clarification should be returned instead
     */
    boolean allowGeneration(DisambiguationResult dr, EvidenceSnapshot ev);
}