package com.example.lms.agent;

import com.example.lms.domain.knowledge.DomainKnowledge;
import com.example.lms.domain.scoring.SynergyStat;
import com.example.lms.repository.DomainKnowledgeRepository;
import com.example.lms.repository.SynergyStatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;




/**
 * Service that gradually decays the confidence scores of knowledge entries based on recency of
 * access and negative user feedback.  Entries that have not been accessed for a long time or
 * associated with poor synergy statistics will see their confidence reduced.  This allows the
 * retrieval layer to prioritise fresher and more reliable knowledge.
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "agent.knowledge-decay", name = "enabled", havingValue = "true", matchIfMissing = false)
public class KnowledgeDecayService {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeDecayService.class);

    private final DomainKnowledgeRepository knowledgeRepo;
    private final SynergyStatRepository synergyRepo;

    @Scheduled(initialDelayString = "${agent.knowledge-decay.initial-delay-ms:300000}",
            fixedDelayString = "${agent.knowledge-decay.period-ms:86400000}")
    public void decay() {
        try {
            List<DomainKnowledge> list = knowledgeRepo.findAll();
            Instant now = Instant.now();
            for (DomainKnowledge dk : list) {
                double oldConf = dk.getConfidenceScore();
                // compute time since last access
                Duration sinceLast = dk.getLastAccessedAt() == null
                        ? Duration.ofDays(365)
                        : Duration.between(dk.getLastAccessedAt(), now);
                double factor = 1.0;
                // older than a week decays more strongly
                if (sinceLast.toDays() >= 14) {
                    factor *= 0.80;
                } else if (sinceLast.toDays() >= 7) {
                    factor *= 0.90;
                } else if (sinceLast.toDays() >= 3) {
                    factor *= 0.95;
                }
                // incorporate synergy feedback: if negative feedback outweighs positive for this entity
                if (dk.getEntityName() != null && !dk.getEntityName().isBlank()) {
                    try {
                        List<SynergyStat> stats = synergyRepo.findAll();
                        long pos = 0;
                        long neg = 0;
                        for (SynergyStat s : stats) {
                            if (dk.getDomain().equalsIgnoreCase(s.getDomain())
                                    && dk.getEntityName().equalsIgnoreCase(s.getSubject())) {
                                pos += s.getPositive();
                                neg += s.getNegative();
                            }
                        }
                        if (neg > pos) {
                            // stronger decay when negative feedback dominates
                            factor *= 0.80;
                        }
                    } catch (Exception ignore) {
                        // ignore synergy lookup errors
                    }
                }
                // update confidence if factor < 1
                double newConf = oldConf * factor;
                // clamp to a minimum of 0.05 to avoid removing knowledge entirely
                if (newConf < 0.05) newConf = 0.05;
                if (Math.abs(newConf - oldConf) > 1e-6) {
                    dk.setConfidenceScore(newConf);
                    // persist the updated entity
                    try {
                        knowledgeRepo.save(dk);
                    } catch (Exception e) {
                        log.warn("[KnowledgeDecay] Failed to save updated confidence for {}:{}", dk.getDomain(), dk.getEntityName());
                    }
                    log.debug("[KnowledgeDecay] Updated confidence for {}:{} from {} to {}", dk.getDomain(), dk.getEntityName(), String.format("%.3f", oldConf), String.format("%.3f", newConf));
                }
            }
        } catch (Exception e) {
            log.error("[KnowledgeDecay] Error while decaying knowledge", e);
        }
    }
}