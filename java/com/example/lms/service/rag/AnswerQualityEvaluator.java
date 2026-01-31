package com.example.lms.service.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.rag.content.Content;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Optional;




@Component
@RequiredArgsConstructor
public class AnswerQualityEvaluator {
    private final EmbeddingModel embeddingModel;

    /**
     * 로컬 문서 집합이 충분한지 점검합니다.
     * 기준: 최소 문서 수(minDocs) + 평균 코사인 유사도(minAvgScore)
     */
    public boolean isSufficient(String query, List<Content> docs, int minDocs, double minAvgScore) {
        if (docs == null || docs.size() < Math.max(1, minDocs)) return false;

        try {
            float[] queryVector = embeddingModel.embed(query).content().vector();
            List<TextSegment> segments = docs.stream()
                    .map(c -> Optional.ofNullable(c.textSegment()).orElse(TextSegment.from(c.toString())))
                    .toList();

            Response<List<Embedding>> resp = embeddingModel.embedAll(segments);
            List<Embedding> docVectors = resp.content();

            if (docVectors == null || docVectors.size() != segments.size()) return false;

            double sum = 0.0;
            for (Embedding e : docVectors) {
                sum += cosineSimilarity(queryVector, e.vector());
            }
            double avg = sum / docVectors.size();
            return avg >= minAvgScore;

        } catch (Exception ignore) {
            return false;
        }
    }

    private static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) return 0.0;

        double dot = 0.0, na = 0.0, nb = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na  += a[i] * a[i];
            nb  += b[i] * b[i];
        }
        double denom = Math.sqrt(na) * Math.sqrt(nb);
        return denom == 0.0 ? 0.0 : dot / denom;
    }
}