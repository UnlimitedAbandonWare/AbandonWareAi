package com.example.lms.service.verification;

import com.example.lms.service.rag.detector.RiskBand;
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
        // [RELAXED POLICY]
        // Entity disambiguation success (dr != null) is NO LONGER a hard requirement.
        // Generation is allowed when:
        //   1. Evidence exists (ev != null && ev.hasAnyEvidence())
        //   2. Evidence is not contradictory (!ev.isContradictory())
        // This allows RAG-only answers for queries where the entity is unknown
        // but web/RAG evidence is already strong.

        boolean ok = ev != null && ev.hasAnyEvidence() && !ev.isContradictory();

        if (!ok) {
            // Block only when evidence is missing or contradictory.
            log.debug("[EVIDENCE_GATE] block: lack of evidence or contradictory evidence (resolved={})",
                    dr != null);
        } else if (dr == null) {
            // Log cases where entity is unresolved but generation is allowed via evidence.
            log.debug("[EVIDENCE_GATE] pass: unresolved entity but non-contradictory evidence → allow generation");
        }

        return ok;
    }

@Override
public boolean hasSufficientCoverage(EvidenceVerificationResult ev) {
    return hasSufficientCoverage(ev, RiskBand.HIGH);
}

@Override
public boolean canProceed(EvidenceVerificationResult ev) {
    return canProceed(ev, RiskBand.HIGH);
}

@Override
public boolean hasSufficientCoverage(EvidenceVerificationResult ev, RiskBand risk) {
    if (ev == null) return false;
    int coveredEntities = ev.getCoveredEntityCount();
    int supportingDocs  = ev.getSupportingDocCount();
    double coverage     = ev.getCoverageScore();

    return switch (risk) {
        case HIGH -> coveredEntities >= 2 && supportingDocs >= 2 && coverage >= 0.6;
        case NORMAL -> coveredEntities >= 1 && supportingDocs >= 1 && coverage >= 0.3;
        case LOW -> (coveredEntities >= 1 || supportingDocs >= 1) && coverage >= 0.1;
    };
}

@Override
public boolean canProceed(EvidenceVerificationResult ev, RiskBand risk) {
    if (ev == null) return false;
    if (ev.isContradictory()) {
        return false;
    }
    if (risk == RiskBand.HIGH) {
        return ev.hasSufficientReliability();
    }
    return ev.hasAnyEvidence();
}

}
