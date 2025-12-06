// --- New File ---
// src/main/java/com/example/lms/service/discovery/ExplorationService.java

package com.example.lms.service.discovery;

import com.example.lms.repository.SynergyStatRepository;
import com.example.lms.service.knowledge.KnowledgeBaseService;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.*;



@Slf4j
@Service
@RequiredArgsConstructor
public class ExplorationService {

    private final EmbeddingModel embeddingModel;
    private final KnowledgeBaseService kb;
    private final SynergyStatRepository repo;

    @Value("${exploration.max-candidates:200}")
    private int maxCandidates;

    @Value("${exploration.min-untried-interactions:3}")
    private int minUntriedInteractions;

    public record Suggestion(String partner, double similarity) {}

    /** 주체와 의미적으로 가까우나 상호작용 기록이 적은 파트너를 제안한다. */
    public List<Suggestion> suggestUntriedPartners(String domain, String subject, int limit) {
        Set<String> all = kb.listEntityNames(domain, "CHARACTER");
        if (all == null || all.isEmpty()) return List.of();

        float[] vecSubject = embeddingModel.embed(subject).content().vector();
        List<Suggestion> pool = new ArrayList<>();
        int scanned = 0;

        for (String partner : all) {
            if (partner.equalsIgnoreCase(subject)) continue;

            // 이미 많이 시도된 조합 제외
            if (repo.findByDomainAndSubjectIgnoreCaseAndPartnerIgnoreCase(domain, subject, partner)
                    .map(s -> s.getPositive() + s.getNegative() >= minUntriedInteractions)
                    .orElse(false)) continue;

            try {
                float[] v = embeddingModel.embed(partner).content().vector();
                double sim = cosine(vecSubject, v);
                pool.add(new Suggestion(partner, sim));
            } catch (Exception ignore) { }

            if (++scanned >= maxCandidates) break;
        }

        pool.sort((a, b) -> Double.compare(b.similarity, a.similarity));
        return pool.subList(0, Math.min(limit, pool.size()));
    }

    private static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0.0;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) { dot += a[i]*b[i]; na += a[i]*a[i]; nb += b[i]*b[i]; }
        if (na == 0 || nb == 0) return 0.0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb) + 1e-9);
    }
}