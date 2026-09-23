package com.example.lms.agent;

import com.example.lms.infra.resilience.NightmareKeys;
import com.example.lms.orchestration.OrchStageKeys;
import com.example.lms.service.knowledge.KnowledgeBaseService;
import com.example.lms.service.vector.VectorBackendHealthService;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.trace.TraceContext;
import com.example.lms.uaw.orchestration.UawOrchestrationGate;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "agent.knowledge-curation", name = "enabled", havingValue = "true")
public class KnowledgeCurationScheduler {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeCurationScheduler.class);

    private final CuriosityTriggerService curiosity;
    private final SynthesisService synthesis;
    private final KnowledgeBaseService knowledgeBase;

    @Autowired(required = false)
    private UawOrchestrationGate uawGate;

    @Autowired(required = false)
    private VectorBackendHealthService vectorBackendHealth;

    @Value("${uaw.curation.idle.cpu-threshold:0.75}")
    private double idleCpuThreshold;

    @Value("${agent.knowledge-curation.min-entity-codepoints:2}")
    private int minEntityCodepoints;

    @Value("${agent.knowledge-curation.blocked-entities:e,unknown,n/a,na,none,null}")
    private String blockedEntitiesCsv;

    @Scheduled(initialDelayString = "${agent.knowledge-curation.initial-delay-ms:30000}",
            fixedDelayString = "${agent.knowledge-curation.period-ms:600000}")
    public void runCycle() {
        long startMs = System.currentTimeMillis();
        try (TraceContext ignored = TraceContext.attach("uaw-curation-" + startMs, "curation-" + startMs)) {

            // Guard: only curate knowledge when UAW gate allows (user absent + cpu idle + breakers OK).
            if (uawGate != null) {
                UawOrchestrationGate.Decision d = uawGate.decide(
                        OrchStageKeys.UAW_CURATION,
                        idleCpuThreshold,
                        NightmareKeys.CHAT_DRAFT,
                        NightmareKeys.FAST_LLM_COMPLETE
                );
                if (!d.allowed()) {
                    log.debug("[AGENT] Skip knowledge curation: reason={} cpu={}", d.reason(), d.cpuLoad());
                    return;
                }
            }

            log.info("[AGENT] Starting new knowledge curation cycle.");
            curiosity.findKnowledgeGap().ifPresent(gap -> {
                String entity = normalizeEntity(gap.entityName());
                int entityLength = entityCodepointLength(entity);
                if (!isAllowedEntity(entity, entityLength)) {
                    log.info("[AWX2AF2][curation] skip reason=low_quality_entity entityLength={} domainHash={} domainLength={}",
                            entityLength, SafeRedactor.hashValue(gap.domain()), lengthOf(gap.domain()));
                    return;
                }

                if (!embeddingReady()) {
                    log.warn("[AWX2AF2][rag][starvation] skip curation reason=embedding_not_ready entityLength={} domainHash={} domainLength={} embeddingReady=false",
                            entityLength, SafeRedactor.hashValue(gap.domain()), lengthOf(gap.domain()));
                    return;
                }

                CuriosityTriggerService.KnowledgeGap normalizedGap = new CuriosityTriggerService.KnowledgeGap(
                        gap.description(), gap.initialQuery(), gap.domain(), entity);

                log.info("[AGENT] Knowledge gap: entityHash={} entityLength={} domainHash={} domainLength={}",
                        SafeRedactor.hashValue(normalizedGap.entityName()), lengthOf(normalizedGap.entityName()),
                        SafeRedactor.hashValue(normalizedGap.domain()), lengthOf(normalizedGap.domain()));

                // shim: replace with an actual collector (web/RAG etc.); currently constructs bootstrap data.
                List<String> raw = List.of(
                        "QUERY: " + normalizedGap.initialQuery(),
                        "DESC: " + normalizedGap.description()
                );

                synthesis.synthesizeAndVerify(raw, normalizedGap).ifPresent(vk -> {
                    var status = knowledgeBase.integrateVerifiedKnowledge(
                            vk.domain(), vk.entityName(), vk.structuredDataJson(), vk.sources(), vk.confidenceScore()
                    );
                    log.info("[AGENT] integration status={} entityHash={} entityLength={} conf={}",
                            status, SafeRedactor.hashValue(vk.entityName()), lengthOf(vk.entityName()), String.format("%.2f", vk.confidenceScore()));
                });
            });
        }
    }

    private boolean embeddingReady() {
        if (vectorBackendHealth == null) {
            return true;
        }
        return vectorBackendHealth.probeEmbeddingOnlyNow();
    }

    private boolean isAllowedEntity(String entity, int entityLength) {
        if (entityLength < Math.max(1, minEntityCodepoints)) {
            return false;
        }
        String lower = entity.toLowerCase(Locale.ROOT);
        return !blockedEntities().contains(lower);
    }

    private Set<String> blockedEntities() {
        return Arrays.stream((blockedEntitiesCsv == null ? "" : blockedEntitiesCsv).split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }

    private static String normalizeEntity(String entity) {
        if (entity == null) {
            return "";
        }
        return entity.replaceAll("[\\p{Cntrl}\\p{Z}]+", " ").trim();
    }

    private static int entityCodepointLength(String entity) {
        if (entity == null || entity.isBlank()) {
            return 0;
        }
        return (int) entity.codePoints()
                .filter(cp -> !Character.isWhitespace(cp))
                .count();
    }

    private static int lengthOf(String value) {
        return value == null ? 0 : value.length();
    }
}
