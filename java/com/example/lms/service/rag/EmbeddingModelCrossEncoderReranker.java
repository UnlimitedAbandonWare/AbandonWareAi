// src/main/java/com/example/lms/service/rag/EmbeddingModelCrossEncoderReranker.java
package com.example.lms.service.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.rag.content.Content;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 임베딩 기반 Cross-Encoder 스타일 리랭커(통합판).
 * - 정책(원소 허용/비선호) + 주어 앵커 + 제너릭 문서 감점
 * - 사용자 피드백(AdaptiveScoring) 기반 궁합 보너스
 * - 배치 임베딩 + 시간 로깅
 */
@Slf4j
@Component("embeddingModelCrossEncoderReranker") // ★ 이름 변경(충돌 회피)
@Primary
@RequiredArgsConstructor
public class EmbeddingModelCrossEncoderReranker implements CrossEncoderReranker {

    private final EmbeddingModel embeddingModel;
    private final com.example.lms.genshin.GenshinElementLexicon lexicon;                 // fallback 정책 계산
    private final com.example.lms.service.rag.rerank.ElementConstraintScorer elemScorer; // 정책 가중
    private final com.example.lms.service.knowledge.KnowledgeBaseService knowledgeBase;  // 엔티티/정책/파트너 탐지
    private final com.example.lms.service.rag.detector.GameDomainDetector domainDetector;// 도메인 감지
    private final com.example.lms.service.scoring.AdaptiveScoringService adaptiveScorer; // 피드백 기반 보너스

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

            // 정책/도메인/주어 계산
            var policy = lexicon.policyForQuery(query); // KB 기반 정책은 전처리에서 주입되며, 여기선 보수적 fallback
            String domain = domainDetector.detect(query);
            String subject = com.example.lms.service.subject.SubjectResolver
                    .guessSubjectFromQueryStatic(knowledgeBase, domain, query);

            record ScoredContent(Content content, double score) {}
            final List<ScoredContent> scored = new ArrayList<>(n);

            for (int i = 0; i < n; i++) {
                Content c = snapshot.get(i);
                String text = segments.get(i).text(); // 위에서 만든 segments 기준으로 텍스트 사용
                double sim = cosineSimilarity(qv, docEmbeddings.get(i).vector());

                boolean isGeneric = genericClassifier.isGenericText(text);
                boolean hasSubject = !subject.isBlank() && text.toLowerCase().contains(subject.toLowerCase());

                // 원소 정책 가중
                double deltaElem = elemScorer.deltaForText(text, policy.allowed(), policy.discouraged());

                // 파트너 추출 → 적응형 궁합 보너스
                String partner = knowledgeBase.findFirstMentionedEntityExcluding(domain, text, subject).orElse("");
                double synergyBonus = adaptiveScorer.getSynergyScore(domain, subject, partner); // [-0.05, +0.10]

                double score = sim
                        + (hasSubject ? 0.15 : -0.20)
                        + (isGeneric ? -0.25 : 0.0)
                        + deltaElem
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
            // 임베딩 실패 시 안정성을 위해 원본 순서대로 반환
            log.warn("임베딩 기반 리랭킹 실패. 원본 순서로 대체합니다. Query: '{}', Error: {}", query, e.getMessage());
            int k = Math.max(1, Math.min(topN, candidates.size()));
            return new ArrayList<>(candidates.subList(0, k));
        }
    }

    private static double cosineSimilarity(float[] v1, float[] v2) {
        if (v1 == null || v2 == null || v1.length != v2.length || v1.length == 0) {
            return 0.0;
        }
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
