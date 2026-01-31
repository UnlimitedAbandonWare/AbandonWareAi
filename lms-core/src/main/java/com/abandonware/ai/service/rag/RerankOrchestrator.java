package com.abandonware.ai.service.rag;

import com.abandonware.ai.service.onnx.OnnxCrossEncoderReranker;
import com.abandonware.ai.service.rag.model.ContextSlice;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.service.rag.RerankOrchestrator
 * Role: service
 * Dependencies: com.abandonware.ai.service.onnx.OnnxCrossEncoderReranker, com.abandonware.ai.service.rag.model.ContextSlice
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.service.rag.RerankOrchestrator
role: service
*/
public class RerankOrchestrator {
    private final BiEncoderReranker bi;
    private final OnnxCrossEncoderReranker onnx; // may be null if disabled

    @Autowired
    public RerankOrchestrator(BiEncoderReranker bi, @Autowired(required=false) OnnxCrossEncoderReranker onnx) {
        this.bi = bi;
        this.onnx = onnx;
    }

    public List<ContextSlice> rerank(List<ContextSlice> in) {
        var fast = bi.rerank(in);
        return (onnx != null) ? onnx.rerankTopK(fast, 20) : fast;
    }
}