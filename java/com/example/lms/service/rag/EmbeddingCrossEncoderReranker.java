package com.example.lms.service.rag.rerank;

import com.example.lms.service.rag.CrossEncoderReranker;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 간단한 임베딩 기반 Cross-Encoder 유사도 재정렬.
 * - query, 문서 텍스트를 임베딩하고 코사인 유사도로 상위 N 재정렬
 * - LangChain4j EmbeddingModel 사용
 */
@Component
@RequiredArgsConstructor
public class EmbeddingCrossEncoderReranker implements CrossEncoderReranker {

    private final EmbeddingModel embeddingModel;

    @Override
    public List<Content> rerank(String query, List<Content> candidates, int topN) {
        if (query == null || query.isBlank() || candidates == null || candidates.isEmpty()) {
            return candidates == null ? List.of() : candidates;
        }
        float[] qv = embeddingModel.embed(query).content().vector();

        record Scored(Content c, double s) {}
        List<Scored> scored = new ArrayList<>(candidates.size());
        for (Content c : candidates) {
            if (c == null) continue;
            String text = Optional.ofNullable(c.textSegment())
                    .map(TextSegment::text)
                    .orElse(c.toString());
            if (text == null || text.isBlank()) continue;
            float[] dv = embeddingModel.embed(text).content().vector();
            double sim = cosine(qv, dv);
            scored.add(new Scored(c, sim));
        }
        return scored.stream()
                .sorted((a, b) -> Double.compare(b.s, a.s))
                .limit(Math.max(1, topN))
                .map(Scored::c)
                .collect(Collectors.toList());
    }

    private static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0.0;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        double denom = Math.sqrt(na) * Math.sqrt(nb);
        return denom == 0 ? 0.0 : dot / denom;
    }
}
