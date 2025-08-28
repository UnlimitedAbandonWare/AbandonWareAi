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
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
@Configuration
public class RerankerConfig {

    /**
     * Embedding 기반 재랭커 (기본값).  When no other bean of the same name is
     * defined this bean will be registered.  It does not depend on the
     * abandonware.reranker.backend property; the backend selection is handled
     * at runtime by ChatService.
     */
    @Bean(name = "embeddingCrossEncoderReranker")
    @ConditionalOnMissingBean(name = "embeddingCrossEncoderReranker")
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

    /**
     * ONNX 기반 재랭커 빈 정의.  abandonware.reranker.backend 값이
     * 'onnx-runtime'으로 설정된 경우에만 빈이 등록된다.  실제 사용 여부는
     * {@link com.example.lms.service.onnx.OnnxRuntimeService#available()} 플래그로 판정하며,
     * 모델이 준비되지 않은 경우 No‑op 재랭커로 대체된다.
     */
    @Bean(name = "onnxCrossEncoderReranker")
    @ConditionalOnProperty(name = "abandonware.reranker.backend", havingValue = "onnx-runtime")
    public CrossEncoderReranker onnxCrossEncoderReranker(OnnxRuntimeService onnx) {
        // When the ONNX runtime reports that no model is available, fall back
        // to a no‑op implementation.  Otherwise instantiate the ONNX-backed reranker.
        if (onnx.available()) {
            return new com.example.lms.service.onnx.OnnxCrossEncoderReranker(onnx);
        }
        return new com.example.lms.service.rag.rerank.NoopCrossEncoderReranker();
    }
}