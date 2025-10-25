package com.example.lms.service.rag;

import com.example.lms.util.RelevanceScorer;
import dev.langchain4j.model.embedding.EmbeddingModel;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;



/**
 * 임베딩 기반 간단 관련도 점수 서비스.
 * 내부적으로 {@link RelevanceScorer} 를 사용합니다.
 */
@Service
@RequiredArgsConstructor
public class RelevanceScoringService {
    private final EmbeddingModel embeddingModel;
    private volatile RelevanceScorer scorer;

    @PostConstruct
    void init() {
        this.scorer = new RelevanceScorer(embeddingModel);
    }

    /** 0.0 ≤ score ≤ 1.0 */
    public double relatedness(String query, String text) {
        if (scorer == null) {
            scorer = new RelevanceScorer(embeddingModel);
        }
        return scorer.score(query, text);
    }
}