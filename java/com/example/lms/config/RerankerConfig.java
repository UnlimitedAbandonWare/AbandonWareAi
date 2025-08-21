package com.example.lms.config;

import com.example.lms.service.onnx.OnnxCrossEncoderReranker;
import com.example.lms.service.onnx.OnnxRuntimeService;
import com.example.lms.service.rag.EmbeddingModelCrossEncoderReranker;
import com.example.lms.service.rag.auth.AuthorityScorer;
import com.example.lms.service.rag.detector.GameDomainDetector;
import com.example.lms.service.rag.filter.GenericDocClassifier;
import com.example.lms.service.rag.rerank.CrossEncoderReranker;
import com.example.lms.service.rag.rerank.RelationshipRuleScorer;
import com.example.lms.service.scoring.AdaptiveScoringService;
import com.example.lms.service.config.HyperparameterService;
import com.example.lms.service.knowledge.KnowledgeBaseService;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class RerankerConfig {

    /** Embedding 기반 재랭커 (기본값) */
    @Bean(name = "embeddingCrossEncoderReranker")
    @ConditionalOnProperty(
            prefix = "abandonware.reranker",
            name   = "backend",
            havingValue = "embedding-model",
            matchIfMissing = false
    )
    public CrossEncoderReranker embeddingModelCrossEncoderReranker(
            EmbeddingModel embeddingModel,
            KnowledgeBaseService knowledgeBase,
            GameDomainDetector domainDetector,
            AdaptiveScoringService adaptiveScorer,
            RelationshipRuleScorer ruleScorer,
            AuthorityScorer authorityScorer,
            HyperparameterService hyperparameters,
            GenericDocClassifier genericClassifier
    ) {
        return new EmbeddingModelCrossEncoderReranker(
                embeddingModel,
                knowledgeBase,
                domainDetector,
                adaptiveScorer,
                ruleScorer,
                authorityScorer,
                hyperparameters,
                genericClassifier
        );
    }

    @Bean(name = "onnxCrossEncoderReranker")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnExpression(
            "'${abandonware.reranker.backend:}'=='onnx-runtime' and "
                    + "'${abandonware.reranker.onnx.model-path:}'!=''"
    )
    public CrossEncoderReranker onnxCrossEncoderReranker(com.example.lms.service.onnx.OnnxRuntimeService onnxRuntimeService) {
        return new com.example.lms.service.onnx.OnnxCrossEncoderReranker(onnxRuntimeService);
    }
}