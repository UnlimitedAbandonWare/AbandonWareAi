// --- New File ---
// src/main/java/com/example/lms/service/bootstrap/SynergyBootstrapperService.java

package com.example.lms.service.bootstrap;

import com.example.lms.service.knowledge.KnowledgeBaseService;
import com.example.lms.service.scoring.AdaptiveScoringService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.stereotype.Component;
import java.util.*;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;


@Component
@RequiredArgsConstructor
public class SynergyBootstrapperService {
    private static final Logger log = LoggerFactory.getLogger(SynergyBootstrapperService.class);

    private final KnowledgeBaseService kb;
    private final AdaptiveScoringService scoring;

    @Value("${bootstrap.synergy.enabled:true}")
    private boolean enabled;

    @Value("${bootstrap.synergy.domains:GENSHIN}")
    private String domainsCsv;

    @Value("${bootstrap.synergy.maxPairs:300}")
    private int maxPairs;

    @EventListener(ApplicationReadyEvent.class)
    public void bootstrap() {
        if (!enabled) return;
        List<String> domains = Arrays.stream(domainsCsv.split(","))
                .map(String::trim).filter(s -> !s.isBlank()).toList();
        int injected = 0;
        for (String domain : domains) {
            Set<String> names = kb.listEntityNames(domain, "CHARACTER");
            List<String> list = new ArrayList<>(names);
            for (int i = 0; i < list.size() && injected < maxPairs; i++) {
                for (int j = i + 1; j < list.size() && injected < maxPairs; j++) {
                    String a = list.get(i), b = list.get(j);
                    if (kb.isHeuristicallySynergetic(domain, a, b)) {
                        // 높은 가중치(0.9)로 초기 데이터 주입
                        scoring.applyImplicitPositive(domain, a, b, 0.9);
                        injected++;
                    }
                }
            }
        }
        log.info("[SynergyBootstrapper] injected {} heuristic pairs", injected);
    }
}