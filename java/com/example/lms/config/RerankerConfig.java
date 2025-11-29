package com.example.lms.config;

import com.example.lms.service.rag.BiEncoderReranker;
import com.example.lms.service.rag.rerank.CrossEncoderReranker;
import com.example.lms.service.knowledge.KnowledgeBaseService;
import com.example.lms.service.rag.detector.GameDomainDetector;
import com.example.lms.service.rag.filter.GenericDocClassifier;
import com.example.lms.service.rag.rerank.RelationshipRuleScorer;
import com.example.lms.service.rag.auth.AuthorityScorer;
import com.example.lms.service.scoring.AdaptiveScoringService;
import com.example.lms.service.config.HyperparameterService;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;



/**
 * Configuration class that wires the BiEncoderReranker in place of any
 * cross-encoder reranker.  This ensures a single reranker implementation
 * is active across the application and that Spring can inject all
 * required collaborators.
 */
@Configuration
public class RerankerConfig {
  /**
   * Provide a crossEncoderReranker bean backed by the BiEncoderReranker.
   * The bean name matches the legacy cross-encoder name so that it is
   * selected by the RerankerSelector when the backend is set to
   * "onnx-runtime" or "embedding-model".  All collaborators are
   * injected from the Spring context.
   */
  @Bean(name = "crossEncoderReranker")
  CrossEncoderReranker crossEncoderReranker(
      EmbeddingModel embeddingModel,
      KnowledgeBaseService knowledgeBase,
      GameDomainDetector domainDetector,
      AdaptiveScoringService adaptiveScorer,
      RelationshipRuleScorer ruleScorer,
      AuthorityScorer authorityScorer,
      HyperparameterService hyperparameters,
      GenericDocClassifier genericClassifier
  ) {
    return new BiEncoderReranker(embeddingModel, knowledgeBase, domainDetector,
        adaptiveScorer, ruleScorer, authorityScorer, hyperparameters, genericClassifier);
  }
}