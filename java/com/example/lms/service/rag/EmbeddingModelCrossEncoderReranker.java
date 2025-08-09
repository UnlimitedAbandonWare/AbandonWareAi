package com.example.lms.service.rag;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 임베딩 모델과 코사인 유사도를 사용하여 후보군을 재정렬하는 Reranker 구현체.
 * CrossEncoderReranker 인터페이스의 주요 구현이며, `@Primary`를 통해 기본으로 활성화됩니다.
 */
@Slf4j
@Component
@Primary // 여러 Reranker 구현체 중 이 클래스를 기본으로 사용하도록 지정
@RequiredArgsConstructor
public class EmbeddingModelCrossEncoderReranker implements CrossEncoderReranker {

    private final EmbeddingModel embeddingModel;

    @Override
    public List<Content> rerank(String query, List<Content> candidates, int topN) {
        if (query == null || query.isBlank() || candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }

        try {
            // 쿼리 벡터는 한 번만 계산하여 효율성 확보
            final float[] queryVector = embeddingModel.embed(query).content().vector();

            return candidates.stream()
                    .sorted(Comparator.comparingDouble(candidate -> -score(queryVector, candidate))) // 내림차순 정렬
                    .limit(Math.max(1, topN))
                    .collect(Collectors.toList());

        } catch (Exception e) {
            // 임베딩 실패 시 안정성을 위해 원본 순서대로 반환
            log.warn("임베딩 기반 리랭킹 실패. 원본 순서로 대체합니다. Query: '{}', Error: {}", query, e.getMessage());
            return candidates.stream().limit(Math.max(1, topN)).collect(Collectors.toList());
        }
    }


    private double score(float[] queryVector, Content candidate) {
        String text = Optional.ofNullable(candidate.textSegment())
                .map(TextSegment::text)
                .orElse(candidate.toString());

        if (text == null || text.isBlank()) return 0.0;

        float[] v = embeddingModel.embed(text).content().vector();
        return cosineSimilarity(queryVector, v);
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