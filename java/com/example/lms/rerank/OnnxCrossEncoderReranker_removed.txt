package com.example.lms.rerank;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.example.lms.service.rag.rerank.CrossEncoderReranker;
import dev.langchain4j.rag.content.Content;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Placeholder ONNX‑based Cross‑Encoder reranker.  A full implementation
 * would load an ONNX model via onnxruntime and compute relevance scores
 * for each candidate.  For now this class simply returns the input list
 * unchanged.  To enable it, set {@code abandonware.reranker.backend=onnx-runtime}
 * in your configuration.  When disabled, Spring will supply another
 * {@link CrossEncoderReranker} bean or none at all.
 */
@Component("onnxCrossEncoderRerankerPlaceholder") // 개선: 실제 빈 이름과 충돌 방지
@ConditionalOnProperty(name = "abandonware.reranker.backend", havingValue = "__never__") // 개선: 항상 비활성화
public class OnnxCrossEncoderReranker implements CrossEncoderReranker {
    private final com.example.lms.service.onnx.OnnxRuntimeService onnx; // 수정: 누락된 필드 주입
    public OnnxCrossEncoderReranker(com.example.lms.service.onnx.OnnxRuntimeService onnx) {
        this.onnx = onnx;
    }
    @Override
    public List<Content> rerank(String query, List<Content> candidates, int topN) {
        if (candidates == null || candidates.isEmpty()) return List.of();
        int limit = Math.min(topN, candidates.size());
        return candidates.subList(0, limit); // 개선: 플레이스홀더이므로 no-op
    }
}