package com.example.lms.service.rag.handler;

import com.example.lms.service.knowledge.KnowledgeBaseService;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;




/**
 * Knowledge-Graph backed retriever that converts simple entity relationships
 * into {@link Content} segments.  Originally this handler returned only
 * unscored text which prevented downstream rank-fusion with other sources.
 * This version attaches a lightweight, explainable score to each entity
 * and returns a ranked list to enable RRF-based fusion.
 */
@Component
@RequiredArgsConstructor
public class KnowledgeGraphHandler implements ContentRetriever {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeGraphHandler.class);

    private final KnowledgeBaseService kbService;

    @Value("${retrieval.kg.half-life-days:60}")
    private int kgHalfLifeDays;

    /**
     * Comma separated weights for: (path, confidence, recency, degree)
     * e.g. "0.25,0.45,0.20,0.10"
     */
    @Value("${retrieval.kg.score.weights:0.25,0.45,0.20,0.10}")
    private String kgScoreWeights;

    private double wPath = 0.25;
    private double wConf = 0.45;
    private double wRec  = 0.20;
    private double wDeg  = 0.10;

    @PostConstruct
    void parseWeights() {
        try {
            if (kgScoreWeights != null && !kgScoreWeights.isBlank()) {
                String[] parts = kgScoreWeights.split(",");
                if (parts.length >= 4) {
                    wPath = Double.parseDouble(parts[0].trim());
                    wConf = Double.parseDouble(parts[1].trim());
                    wRec  = Double.parseDouble(parts[2].trim());
                    wDeg  = Double.parseDouble(parts[3].trim());
                }
            }
        } catch (Exception e) {
            log.warn("[KnowledgeGraphHandler] invalid kg.score.weights '{}', using defaults", kgScoreWeights, e);
            wPath = 0.25; wConf = 0.45; wRec = 0.20; wDeg = 0.10;
        }
    }

    private record Scored(Content c, double s) {}

    @Override
    public List<Content> retrieve(Query query) {
        if (query == null || query.text() == null || kbService == null) {
            return List.of();
        }
        try {
            String text = query.text();
            String domain = kbService.inferDomain(text);
            Set<String> entities = kbService.findMentionedEntities(domain, text);
            if (entities == null || entities.isEmpty()) return List.of();

            List<Scored> buf = new ArrayList<>();
            for (String ent : entities) {
                try {
                    // Read meta BEFORE calling getAllRelationships (which updates lastAccessedAt).
                    double conf = kbService.getConfidenceScore(domain, ent).orElse(1.0);
                    Instant last = kbService.getLastAccessedAt(domain, ent).orElse(Instant.now());

                    Map<String, Set<String>> rels = kbService.getAllRelationships(domain, ent);
                    if (rels == null || rels.isEmpty()) continue;

                    int degree = rels.values().stream()
                            .mapToInt(s -> s != null ? s.size() : 0)
                            .sum();

                    long ageDays = Duration.between(last, Instant.now()).toDays();
                    int half = Math.max(1, this.kgHalfLifeDays);
                    double recency = Math.exp(-Math.log(2.0) * (ageDays / Math.max(1.0, (double) half)));

                    double pathTerm = 1.0 / (1.0 + 1.0); // 1-hop assumption
                    double score = wPath * pathTerm
                            + wConf * conf
                            + wRec  * recency
                            + wDeg  * Math.log1p(Math.max(0, degree));

                    String header = String.format("[KG | %s | score=%.3f | deg=%d | last=%s]",
                            ent, score, degree, last);

                    String body = renderRelations(ent, rels);
                    Content c = Content.from(TextSegment.from(header + "\n  " + body));
                    buf.add(new Scored(c, score));
                } catch (Exception ignore) {
                    // continue other entities
                }
            }

            return buf.stream()
                    .sorted(java.util.Comparator.comparingDouble(Scored::s).reversed())
                    .map(Scored::c)
                    .toList();

        } catch (Exception e) {
            log.warn("[KnowledgeGraphHandler] retrieve failed; returning empty list", e);
            return List.of();
        }
    }

    private static String renderRelations(String entity, Map<String, Set<String>> rels) {
        StringBuilder sb = new StringBuilder();
        sb.append(entity).append(" 관계:").append("\n");
        for (Map.Entry<String, Set<String>> e : rels.entrySet()) {
            sb.append("- ").append(e.getKey()).append(": ");
            if (e.getValue() != null && !e.getValue().isEmpty()) {
                sb.append(String.join(", ", e.getValue()));
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}