package com.example.lms.agent;

import com.example.lms.domain.knowledge.DomainKnowledge;
import com.example.lms.repository.DomainKnowledgeRepository;
import com.example.lms.service.knowledge.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.util.*;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;




/**
 * Periodically verifies the internal knowledge base for logical inconsistencies.  It scans
 * relationship attributes on stored entities and flags contradictions such as the same partner being
 * both preferred and discouraged.  If the free-tier API throttle permits, this service could
 * leverage an external model (e.g. Gemini) for deeper semantic checks, but for now it performs a
 * simple heuristic analysis in-process.
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "agent.knowledge-consistency", name = "enabled", havingValue = "true", matchIfMissing = false)
public class KnowledgeConsistencyVerifier {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeConsistencyVerifier.class);

    private final DomainKnowledgeRepository knowledgeRepo;
    private final KnowledgeBaseService knowledgeBase;
    private final FreeTierApiThrottleService throttle;

    /**
     * Run the consistency check on a fixed schedule.  The interval can be tuned via application
     * properties.  The method collects all DomainKnowledge entries and inspects relationship
     * attributes to detect direct conflicts (e.g. a partner is simultaneously preferred and
     * discouraged).
     */
    @Scheduled(initialDelayString = "${agent.knowledge-consistency.initial-delay-ms:120000}",
            fixedDelayString = "${agent.knowledge-consistency.period-ms:3600000}")
    public void verify() {
        try {
            List<DomainKnowledge> all = knowledgeRepo.findAll();
            for (DomainKnowledge dk : all) {
                String domain = dk.getDomain();
                String entity = dk.getEntityName();
                Map<String, Set<String>> rels = knowledgeBase.getAllRelationships(domain, entity);
                if (rels.isEmpty()) continue;
                // find preferred and discouraged partners
                Set<String> preferred = new LinkedHashSet<>();
                Set<String> discouraged = new LinkedHashSet<>();
                for (Map.Entry<String, Set<String>> e : rels.entrySet()) {
                    String key = e.getKey().toUpperCase(Locale.ROOT);
                    if (key.contains("PREFERRED") || key.contains("HAS_SYNERGY_WITH")) {
                        preferred.addAll(e.getValue());
                    } else if (key.contains("DISCOURAGE") || key.contains("AVOID") || key.contains("ANTAGONISTIC")) {
                        discouraged.addAll(e.getValue());
                    }
                }
                if (!preferred.isEmpty() && !discouraged.isEmpty()) {
                    Set<String> conflict = new LinkedHashSet<>(preferred);
                    conflict.retainAll(discouraged);
                    if (!conflict.isEmpty()) {
                        // log the inconsistency
                        log.warn("[KnowledgeConsistency] Conflict detected for {}:{} -> {} appears in both preferred and discouraged lists", domain, entity, conflict);
                        // In a full implementation we would persist this to a review table or notify administrators
                    }
                }
                // shim for future LLM-based checks: if throttle allows, send combined data to Gemini.
                if (throttle != null && throttle.canProceed()) {
                    // shim: integrate with the Gemini client for advanced contradiction detection.
                    // For now we just log that the throttle permits a call; no API call is made.
                    log.debug("[KnowledgeConsistency] Throttle permits an external consistency check for {}:{}", domain, entity);
                }
            }
        } catch (Exception e) {
            log.error("[KnowledgeConsistency] Error while verifying knowledge consistency", e);
        }
    }
}