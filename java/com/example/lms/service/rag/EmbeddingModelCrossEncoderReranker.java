// src/main/java/com/example/lms/service/rag/EmbeddingModelCrossEncoderReranker.java
package com.example.lms.service.rag;

import com.example.lms.service.rag.rerank.CrossEncoderReranker;
import com.example.lms.service.rag.rerank.RelationshipRuleScorer;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.rag.content.Content;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;       // ✅ 추가
import java.util.Objects;  // ✅ 추가
import java.util.Optional;
import java.util.Set;      // ✅ 추가
import java.util.stream.Collectors;

/**
 * 임베딩 기반 Cross-Encoder 스타일 리랭커(관계 규칙 지원).
 */
@Slf4j
@Component("embeddingCrossEncoderReranker")

@ConditionalOnProperty(prefix = "rerank", name = "legacy-embedding-enabled", havingValue = "true", matchIfMissing = false)
@Primary
@RequiredArgsConstructor
public class EmbeddingModelCrossEncoderReranker implements CrossEncoderReranker {

    private final EmbeddingModel embeddingModel;
    private final com.example.lms.service.knowledge.KnowledgeBaseService knowledgeBase;
    private final com.example.lms.service.rag.detector.GameDomainDetector domainDetector;
    private final com.example.lms.service.scoring.AdaptiveScoringService adaptiveScorer;
    private final RelationshipRuleScorer ruleScorer;

    // @Component 아닐 수 있으므로 내부 인스턴스 보유
    private final com.example.lms.service.rag.filter.GenericDocClassifier genericClassifier =
            new com.example.lms.service.rag.filter.GenericDocClassifier();

    @Override
    public List<Content> rerank(String query, List<Content> candidates, int topN) {
        if (query == null || query.isBlank() || candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        try {
            // 쿼리 임베딩
            final float[] qv = embeddingModel.embed(TextSegment.from(query)).content().vector();
            final int n = candidates.size();
            final int k = Math.max(1, Math.min(topN, n));

            // Content → TextSegment 스냅샷
            final List<Content> snapshot = new ArrayList<>(candidates);
            final List<TextSegment> segments = snapshot.stream()
                    .map(c -> Optional.ofNullable(c.textSegment())
                            .orElseGet(() -> TextSegment.from(String.valueOf(c))))
                    .collect(Collectors.toList());

            // 배치 임베딩 + 시간 로깅
            long t0 = System.nanoTime();
            Response<List<Embedding>> batch = embeddingModel.embedAll(segments);
            long tEmbedMs = (System.nanoTime() - t0) / 1_000_000L;

            List<Embedding> docEmbeddings = (batch != null) ? batch.content() : null;
            if (docEmbeddings == null || docEmbeddings.size() != n) {
                log.warn("embedAll() returned {} embeddings for {} candidates; fallback to original order.",
                        (docEmbeddings == null ? 0 : docEmbeddings.size()), n);
                return new ArrayList<>(snapshot.subList(0, k));
            }

            String domain = domainDetector.detect(query);
            String subject = com.example.lms.service.subject.SubjectResolver
                    .guessSubjectFromQueryStatic(knowledgeBase, domain, query);

            record ScoredContent(Content content, double score) {}
            final List<ScoredContent> scored = new ArrayList<>(n);

            for (int i = 0; i < n; i++) {
                Content c = snapshot.get(i);
                String text = segments.get(i).text();
                double sim = cosine(qv, docEmbeddings.get(i).vector()); // ✅ 메서드명 일치

                boolean isGeneric = genericClassifier.isGenericText(text);
                boolean hasSubject = !subject.isBlank() && text.toLowerCase().contains(subject.toLowerCase());

                // 파트너 추출 → 적응형 궁합 보너스
                String partner = knowledgeBase.findFirstMentionedEntityExcluding(domain, text, subject).orElse("");
                double synergyBonus = adaptiveScorer.getSynergyScore(domain, subject, partner); // [-0.05, +0.10]

                double score = sim
                        + (hasSubject ? 0.15 : -0.20)
                        + (isGeneric ? -0.25 : 0.0)
                        + synergyBonus;

                scored.add(new ScoredContent(c, score));
            }

            long ts = System.nanoTime();
            scored.sort(Comparator.comparingDouble(ScoredContent::score).reversed());
            long tSortMs = (System.nanoTime() - ts) / 1_000_000L;

            if (log.isDebugEnabled()) {
                log.debug("[Rerank] embedAll.count={} embed.ms={} sort.ms={} topN/total={}/{}",
                        n, tEmbedMs, tSortMs, k, n);
            }

            return scored.stream()
                    .limit(k)
                    .map(ScoredContent::content)
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.warn("임베딩 기반 리랭킹 실패. 원본 순서로 대체합니다. Query: '{}', Error: {}", query, e.getMessage());
            int k = Math.max(1, Math.min(topN, candidates.size()));
            return new ArrayList<>(candidates.subList(0, k));
        }
    }

    /** 관계 규칙을 고려한 오버로드(스코어에 ruleDelta 반영) */
    @Override
    public List<Content> rerank(String query,
                                List<Content> candidates,
                                int topN,
                                Map<String, Set<String>> interactionRules) {
        if (interactionRules == null || interactionRules.isEmpty()) {
            return rerank(query, candidates, topN);
        }
        try {
            final float[] qv = embeddingModel.embed(TextSegment.from(query)).content().vector();
            final int n = (candidates == null ? 0 : candidates.size());
            if (n == 0) return List.of();
            final int k = Math.max(1, Math.min(topN, n));

            final List<Content> snapshot = new ArrayList<>(candidates);
            final List<TextSegment> segments = snapshot.stream()
                    .map(c -> Optional.ofNullable(c.textSegment()).orElseGet(() -> TextSegment.from(String.valueOf(c))))
                    .collect(Collectors.toList());
            Response<List<Embedding>> batch = embeddingModel.embedAll(segments);
            List<Embedding> docEmbeddings = (batch == null) ? null : batch.content();
            if (docEmbeddings == null || docEmbeddings.size() != n) {
                int k2 = Math.max(1, Math.min(topN, candidates.size()));
                return new ArrayList<>(candidates.subList(0, k2));
            }

            String domain = domainDetector.detect(query);
            String subject = com.example.lms.service.subject.SubjectResolver
                    .guessSubjectFromQueryStatic(knowledgeBase, domain, query);

            record SC(Content c, double s) {}
            List<SC> scored = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                String text = segments.get(i).text();
                double sim = cosine(Objects.requireNonNull(qv), docEmbeddings.get(i).vector());
                boolean hasSubject = !subject.isBlank() && text.toLowerCase().contains(subject.toLowerCase());
                double ruleDelta = ruleScorer.deltaForText(text, interactionRules); // 관계 규칙 점수
                String partner = knowledgeBase.findFirstMentionedEntityExcluding(domain, text, subject).orElse("");
                double synergyBonus = adaptiveScorer.getSynergyScore(domain, subject, partner);
                double score = sim
                        + (hasSubject ? 0.15 : -0.20)
                        + (genericClassifier.isGenericText(text) ? -0.25 : 0.0)
                        + ruleDelta
                        + synergyBonus;
                scored.add(new SC(snapshot.get(i), score));
            }
            scored.sort(Comparator.comparingDouble(SC::s).reversed());
            return scored.stream().limit(k).map(SC::c).toList();
        } catch (Exception e) {
            int k = Math.max(1, Math.min(topN, candidates.size()));
            return new ArrayList<>(candidates.subList(0, k));
        }
    }

    private static double cosine(float[] v1, float[] v2) {
        if (v1 == null || v2 == null || v1.length != v2.length || v1.length == 0) return 0.0;
        double dot = 0.0, na = 0.0, nb = 0.0;
        for (int i = 0; i < v1.length; i++) {
            dot += v1[i] * v2[i];
            na  += v1[i] * v1[i];
            nb  += v2[i] * v2[i];
        }
        double den = Math.sqrt(na) * Math.sqrt(nb);
        return den == 0 ? 0.0 : dot / den;
    }
}