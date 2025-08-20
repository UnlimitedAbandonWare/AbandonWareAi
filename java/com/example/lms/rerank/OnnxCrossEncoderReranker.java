package com.example.lms.rerank;

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
@Component
public class OnnxCrossEncoderReranker implements CrossEncoderReranker {
    @Override
    public List<Content> rerank(String query, List<Content> candidates, int topN) {
        // TODO: implement scoring via ONNX runtime.  For now, return original order.
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        int limit = Math.min(topN, candidates.size());
        return candidates.subList(0, limit);
    }
}