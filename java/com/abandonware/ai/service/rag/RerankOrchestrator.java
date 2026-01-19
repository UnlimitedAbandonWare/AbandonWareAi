package com.abandonware.ai.service.rag;

import com.abandonware.ai.service.onnx.OnnxCrossEncoderReranker;
import com.abandonware.ai.service.rag.model.ContextSlice;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
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