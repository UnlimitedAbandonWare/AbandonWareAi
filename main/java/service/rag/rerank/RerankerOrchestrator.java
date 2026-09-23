// src/main/java/service/rag/rerank/RerankerOrchestrator.java
package service.rag.rerank;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import service.rag.budget.BudgetManager;
import service.rag.budget.Timeouts;
import service.rag.concurrency.SemaphoreGate;

import java.util.*;
import java.util.function.Function;

@Component
public class RerankerOrchestrator {

    @Autowired private SemaphoreGate gate;
    @Autowired private BudgetManager budget;

    @Value("${features.reranker.semaphore.enabled:true}")
    private boolean gateEnabled;

    @Value("${features.reranker.semaphore.try-acquire-ms:300}")
    private int defaultTryMs;

    /**
     * 재랭커 호출을 게이트로 감싸는 유틸.
     * @param onnx   고정밀 재랭커 (cands, topK) -> reranked
     * @param fast   빠른 폴백(바이-인코더 또는 identity)
     */
    public List<Map<String,Object>> rerankWithGates(
            List<Map<String,Object>> cands, int topK,
            Function<Integer, List<Map<String,Object>>> onnx,
            Function<Integer, List<Map<String,Object>>> fast) {

        if (!gateEnabled) return onnx.apply(topK);

        int gateWait = Timeouts.capToBudgetMillis(budget, defaultTryMs, /*safety*/50);
        if (gateWait <= 0) return fast.apply(topK);

        return gate.tryWithPermit(
            () -> onnx.apply(topK),
            () -> fast.apply(topK),
            gateWait
        );
    }
}