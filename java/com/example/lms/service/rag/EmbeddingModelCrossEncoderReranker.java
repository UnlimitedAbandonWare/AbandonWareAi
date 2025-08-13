
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
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 임베딩 모델과 코사인 유사도를 사용하여 후보군을 재정렬하는 Reranker 구현체.
 * CrossEncoderReranker 인터페이스의 주요 구현이며, {@code @Primary}를 통해 기본으로 활성화됩니다.
 */
@Slf4j
@Component
@Primary
@RequiredArgsConstructor
public class EmbeddingModelCrossEncoderReranker implements CrossEncoderReranker {

    private final EmbeddingModel embeddingModel;
    private final com.example.lms.service.rag.filter.GenericDocClassifier genericClassifier; //  inject
    private final com.example.lms.genshin.GenshinElementLexicon lexicon;                    //  NEW
    private final com.example.lms.service.rag.rerank.ElementConstraintScorer elemScorer;    //  NEW
    private final com.example.lms.service.rag.filter.GenericDocClassifier genericClassifier =
            new com.example.lms.service.rag.filter.GenericDocClassifier(); // 가벼운 유틸

    /**
     * 후보 목록(candidates)을 쿼리(query)와의 관련도에 따라 재정렬하고 상위 N개를 반환합니다.
     *
     * @param query      사용자 쿼리
     * @param candidates 재정렬할 후보 Content 목록
     * @param topN       반환할 결과의 수
     * @return 재정렬된 상위 N개의 Content 목록
     */
    @Override
    public List<Content> rerank(String query, List<Content> candidates, int topN) {
        if (query == null || query.isBlank() || candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }

        try {
            // 쿼리 벡터는 한 번만 계산하여 효율성 확보
            final float[] queryVector = embeddingModel.embed(query).content().vector();
            final int n = candidates.size();
            final int k = Math.max(1, Math.min(topN, n));

            // 정렬 과정에서 반복 호출을 피하기 위해 선계산
            final List<Content> snapshot = new ArrayList<>(candidates);
            final List<TextSegment> segments = snapshot.stream()
                    .map(c -> Optional.ofNullable(c.textSegment())
                            .orElseGet(() -> TextSegment.from(Optional.ofNullable(c.toString()).orElse(""))))
                    .collect(Collectors.toList());

            // 🔹 배치 임베딩: 네트워크 호출 1회
            long t0 = System.nanoTime();
            Response<List<Embedding>> batch = embeddingModel.embedAll(segments);
            long tEmbedMs = (System.nanoTime() - t0) / 1_000_000L;
            List<Embedding> docEmbeddings = (batch != null) ? batch.content() : null;

            if (docEmbeddings == null || docEmbeddings.size() != n) {
                log.warn("embedAll() returned {} embeddings for {} candidates; fallback to original order.",
                        (docEmbeddings == null ? 0 : docEmbeddings.size()), n);
                return new ArrayList<>(snapshot.subList(0, k));
            }

            // 정책(원소 허용/비선호) 계산
            var policy = lexicon.policyForQuery(query);  //  NEW

            record ScoredContent(Content content, double score) {}
            final List<ScoredContent> scored = new ArrayList<>(n);
            String subjectGuess = guessSubjectFromQuery(query);
            for (int i = 0; i < n; i++) {
                Content c = snapshot.get(i);
                Content c = snapshot.get(i);
                String text = String.valueOf(c);
                double score = cosineSimilarity(queryVector, docEmbeddings.get(i).vector());
// 주어/범용 휴리스틱
                String subjectGuess = guessSubjectFromQuery(query);
                boolean hasSubject = !subjectGuess.isBlank()
                        && text.toLowerCase().contains(subjectGuess.toLowerCase());
                boolean isGeneric = genericClassifier.isGenericText(text);
//  원소 정책 가중
                double deltaElem = elemScorer.deltaForText(text, policy.allowed(), policy.discouraged());
                double adj = score + (hasSubject ? 0.15 : -0.20) + (isGeneric ? -0.25 : 0.0) + deltaElem;
                scored.add(new ScoredContent(c, adj));
                boolean hasSubject = !subjectGuess.isBlank()
                        && text.toLowerCase().contains(subjectGuess.toLowerCase());
                boolean isGeneric  = genericClassifier.isGenericText(text);
                // 주어 가산(+), 범용 감점(−)
                double adj = score +  (hasSubject ? 0.15 : -0.20)+ (isGeneric ? -0.25 : 0.0);
                scored.add(new ScoredContent(c, adj));
            }



            long tSort0 = System.nanoTime();
            scored.sort(Comparator.comparingDouble(ScoredContent::score).reversed());
            long tSortMs = (System.nanoTime() - tSort0) / 1_000_000L;

            if (log.isDebugEnabled()) {
                log.debug("[Rerank] embedAll.count={} embed.ms={} sort.ms={} topN/total={}/{}",
                        n, tEmbedMs, tSortMs, k, n);
            }

            // 상위 k개만 반환
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
    // 매우 단순한 주어 추정(따옴표/한글 고유명-like 토큰 우선)
    private static String guessSubjectFromQuery(String q) {
        if (q == null) return "";
        var m = java.util.regex.Pattern.compile("\"([^\"]{2,})\"").matcher(q);
        if (m.find()) return m.group(1);
        String[] toks = q.split("\\s+");
        for (String t : toks) {
            if (t.length() >= 2) return t;
        }
        return "";
    }
    private double cosineSimilarity(float[] v1, float[] v2) {
        if (v1 == null || v2 == null || v1.length != v2.length || v1.length == 0) {
            return 0.0;
        }

        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < v1.length; i++) {
            dotProduct += v1[i] * v2[i];
            normA += v1[i] * v1[i];
            normB += v2[i] * v2[i];
        }

        double denominator = Math.sqrt(normA) * Math.sqrt(normB);
        return (denominator == 0) ? 0.0 : dotProduct / denominator;
    }
}