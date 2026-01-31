package com.example.lms.agent;

import com.example.lms.infra.resilience.NightmareKeys;
import com.example.lms.orchestration.OrchStageKeys;
import com.example.lms.service.knowledge.KnowledgeBaseService;
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

import java.util.List;

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

    @Value("${uaw.curation.idle.cpu-threshold:0.75}")
    private double idleCpuThreshold;

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
                log.info("[AGENT] Knowledge gap: entity='{}', domain='{}'", gap.entityName(), gap.domain());

                // shim: replace with an actual collector (web/RAG etc.); currently constructs bootstrap data.
                List<String> raw = List.of(
                        "QUERY: " + gap.initialQuery(),
                        "DESC: " + gap.description()
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
}
