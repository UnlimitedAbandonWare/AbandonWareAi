package com.example.lms.agent;

import com.example.lms.service.knowledge.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "agent.knowledge-curation", name = "enabled", havingValue = "true")
public class KnowledgeCurationScheduler {

    private final CuriosityTriggerService curiosity;
    private final SynthesisService synthesis;
    private final KnowledgeBaseService knowledgeBase;

    @Scheduled(initialDelayString = "${agent.knowledge-curation.initial-delay-ms:30000}",
            fixedDelayString   = "${agent.knowledge-curation.period-ms:600000}")
    public void runCycle() {
        log.info("[AGENT] Starting new knowledge curation cycle.");
        curiosity.findKnowledgeGap().ifPresent(gap -> {
            log.info("[AGENT] Knowledge gap: entity='{}', domain='{}'", gap.entityName(), gap.domain());

            // TODO: 실제 수집기로 대체 (웹/RAG 등). 일단 bootstrap 데이터 구성.
            List<String> raw = List.of(
                    "QUERY: " + gap.initialQuery(),
                    "DESC: "  + gap.description()
            );

            synthesis.synthesizeAndVerify(raw, gap).ifPresent(vk -> {
                var status = knowledgeBase.integrateVerifiedKnowledge(
                        vk.domain(), vk.entityName(), vk.structuredDataJson(), vk.sources(), vk.confidenceScore()
                );
                log.info("[AGENT] integration status={}, entity={}, conf={}",
                        status, vk.entityName(), String.format("%.2f", vk.confidenceScore()));
            });
        });
    }
}