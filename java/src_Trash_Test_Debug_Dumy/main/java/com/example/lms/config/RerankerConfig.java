
package com.example.lms.config;

import com.example.lms.service.config.HyperparameterService;
import com.example.lms.service.knowledge.KnowledgeBaseService;
import com.example.lms.service.onnx.OnnxRuntimeService;
import com.example.lms.service.rag.BiEncoderReranker;
import com.example.lms.service.rag.auth.AuthorityScorer;
import com.example.lms.service.rag.detector.GameDomainDetector;
import com.example.lms.service.rag.filter.GenericDocClassifier;
import com.example.lms.service.rag.rerank.CrossEncoderReranker;
import com.example.lms.service.rag.rerank.DppDiversityReranker;
import com.example.lms.service.rag.rerank.NoopCrossEncoderReranker;
import com.example.lms.service.rag.rerank.RelationshipRuleScorer;
import com.example.lms.service.scoring.AdaptiveScoringService;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration class that wires the BiEncoderReranker in place of any
 * cross‑encoder reranker.  This ensures a single reranker implementation
 * is active across the application and that Spring can inject all
 * required collaborators.
 */
@Configuration
public class RerankerConfig {
    /**
     * Provide the embedding based CrossEncoderReranker.  This bean is named
     * {@code embeddingCrossEncoderReranker} to align with the backend
     * identifiers used by {@link com.example.lms.service.llm.RerankerSelector}.
     * It reuses the same collaborators as the former BiEncoderReranker.
     */
    @Bean(name = "embeddingCrossEncoderReranker")
    @ConditionalOnProperty(
            prefix = "abandonware.reranker",
            name = "backend",
            havingValue = "embedding",
            matchIfMissing = true // 프로퍼티가 없거나 값이 embedding일 때 이 Bean을 사용 (기본값)
    )
    CrossEncoderReranker embeddingCrossEncoderReranker(
            @Qualifier("embeddingModel") EmbeddingModel embeddingModel,
            KnowledgeBaseService knowledgeBase,
            GameDomainDetector domainDetector,
            AdaptiveScoringService adaptiveScorer,
            RelationshipRuleScorer ruleScorer,
            AuthorityScorer authorityScorer,
            HyperparameterService hyperparameters,
            GenericDocClassifier genericClassifier
    ) {
        var base = new BiEncoderReranker(
                embeddingModel,
                knowledgeBase,
                domainDetector,
                adaptiveScorer,
                ruleScorer,
                authorityScorer,
                hyperparameters,
                genericClassifier
        );
        return new DppDiversityReranker(base, embeddingModel);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(CrossEncoderReranker.class)
    public CrossEncoderReranker fallbackCrossEncoderReranker() {
        return new NoopCrossEncoderReranker();
    }
    /**
     * Provide a no‑operation reranker for the ONNX backend.  When the
     * application is configured to use the onnx-runtime backend but no ONNX
     * model is available a noop implementation is returned, effectively
     * disabling reranking.
     */
    @Bean(name = "onnxCrossEncoderReranker")
    @ConditionalOnProperty(prefix = "abandonware.reranker", name = "backend", havingValue = "onnx-runtime")
    @ConditionalOnClass(name = "ai.onnxruntime.OrtEnvironment")
    @ConditionalOnBean(OnnxRuntimeService.class)
    CrossEncoderReranker onnxCrossEncoderReranker(OnnxRuntimeService onnx) {
        return onnx.available()
                ? new com.example.lms.service.onnx.OnnxCrossEncoderReranker(onnx)
                : new NoopCrossEncoderReranker();
    }

    /**
     * Provide an explicit noop reranker.  This bean is selected when the
     * reranking backend is set to "noop".
     */
    @Bean(name = "noopCrossEncoderReranker")
    @ConditionalOnProperty(
            prefix = "abandonware.reranker",
            name = "backend",
            havingValue = "noop" // 프로퍼티 값이 noop일 때만 이 Bean을 사용
    )
    CrossEncoderReranker noopCrossEncoderReranker() {
        return new NoopCrossEncoderReranker();
    }
}