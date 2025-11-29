package com.example.lms.service.verification;

import com.example.lms.service.disambiguation.DisambiguationResult;
import org.springframework.stereotype.Component;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;



/**
 * Default implementation of {@link EvidenceGate}.  It permits generation
 * only when the disambiguation result resolved to a non-null entity and
 * the evidence snapshot does not indicate contradictions.  Additional
 * heuristics or thresholds could be added here without changing the
 * interface.
 */
@Component
public class DefaultEvidenceGate implements EvidenceGate {
    private static final Logger log = LoggerFactory.getLogger(DefaultEvidenceGate.class);
    @Override
    public boolean allowGeneration(DisambiguationResult dr, EvidenceSnapshot ev) {
        // Treat any non-null DisambiguationResult as resolved; if a more
        // specific resolved entity property becomes available in future
        // versions, it can be checked here.  The evidence must also not
        // contain contradictions.
        boolean resolved = dr != null;
        boolean ok = resolved && ev != null && !ev.isContradictory();
        if (!ok) {
            log.debug("[EVIDENCE_GATE] block: unresolved or contradictory");
        }
        return ok;
    }
}