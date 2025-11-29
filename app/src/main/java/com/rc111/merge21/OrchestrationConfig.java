
package com.rc111.merge21;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Semaphore;

@Configuration
public class OrchestrationConfig {
    @Bean
    public rag.DynamicRetrievalHandlerChain retrievalChain(
            rag.WebSearchHandler webHandler,
            rag.VectorSearchHandler vectorHandler,
            rag.KnowledgeGraphHandler kgHandler,
            rag.RetrievalOrderService orderService,
            rag.fusion.RrfFusion fusion) {
        rag.DynamicRetrievalHandlerChain chain = new rag.DynamicRetrievalHandlerChain(orderService);
        chain.addHandler(webHandler);
        chain.addHandler(vectorHandler);
        chain.addHandler(kgHandler);
        chain.setFusion(fusion);
        return chain;
    }

    @Bean
    public rag.fusion.RrfFusion weightedRrfFusion() {
        return new rag.fusion.WeightedRRF();
    }

    @Bean
    @ConditionalOnProperty(name="onnx.enabled", havingValue="true", matchIfMissing = true)
    public onnx.OnnxCrossEncoderReranker crossEncoderReranker(onnx.OnnxRuntimeService ort,
                                                              @Value("{onnx.model.cross-encoder.path:/models/cross-encoder.onnx}") String modelPath,
                                                              Semaphore onnxLimiter) {
        return new onnx.OnnxCrossEncoderReranker(ort, modelPath, onnxLimiter);
    }

    @Bean
    public java.util.concurrent.Semaphore onnxLimiter(@Value("{zsys.onnx.max-concurrency:2}") int max) {
        return new Semaphore(Math.max(1, max));
    }

    @Bean
    public qa.ChatAnswerService chatAnswerService(llm.LlamaCppClient llama,
                                                  rag.DynamicRetrievalHandlerChain chain,
                                                  onnx.OnnxCrossEncoderReranker cross,
                                                  List<guard.AnswerGuard> guards) {
        return new qa.ChatAnswerService(llama, chain, cross, guards);
    }

    @Bean
    public List<guard.AnswerGuard> guardRailChain(guard.CitationGate citationGate,
                                                  guard.FinalSigmoidGate finalSigmoidGate,
                                                  guard.OverdriveGuard overdriveGuard,
                                                  guard.AnswerDriftGuard answerDriftGuard) {
        return Arrays.asList(overdriveGuard, answerDriftGuard, citationGate, finalSigmoidGate);
    }
}
