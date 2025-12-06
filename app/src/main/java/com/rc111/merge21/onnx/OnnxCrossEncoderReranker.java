
package com.rc111.merge21.onnx;

import ai.onnxruntime.*;
import com.rc111.merge21.rag.SearchDoc;
import org.springframework.stereotype.Component;

import java.nio.FloatBuffer;
import java.util.*;
import java.util.concurrent.Semaphore;

@Component
public class OnnxCrossEncoderReranker {
    @org.springframework.beans.factory.annotation.Autowired
    private java.util.concurrent.Semaphore onnxLimiter;

    private final OnnxRuntimeService ort;
    private final String modelPath;
    private final Semaphore limiter;

    public OnnxCrossEncoderReranker(OnnxRuntimeService ort, String modelPath, Semaphore limiter) {
        this.ort = ort;
        this.modelPath = modelPath;
        this.limiter = limiter;
    }

    // Dummy scoring for scaffold; integrate tokenizer + inputs in real impl
    public List<SearchDoc> rerank(String query, List<SearchDoc> docs) {
        if (onnxLimiter!=null) try { onnxLimiter.acquire(); } catch(InterruptedException ie){ Thread.currentThread().interrupt(); }
        List<SearchDoc> sorted = new ArrayList<>(docs);
        // Stable sort by existing rank (placeholder)
        sorted.sort(Comparator.comparingInt(d -> d.rank));
        return sorted;
    }
}
