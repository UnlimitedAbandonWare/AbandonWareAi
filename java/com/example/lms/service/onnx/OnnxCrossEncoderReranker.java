package com.example.lms.service.onnx;

import com.example.rerank.cross.SemaphoreGate;
import com.example.lms.service.rag.rerank.CrossEncoderReranker;
import dev.langchain4j.rag.content.Content;

import java.util.ArrayList;
import java.util.List;

/**
 * Safe minimal ONNX cross‑encoder reranker implementation.
 * <p>
 * - Uses a semaphore gate to limit concurrency.
 * - If the gate cannot be acquired within a short timeout, returns the first topN candidates unchanged.
 * - The actual ONNX scoring is intentionally omitted here to keep the class compilable in environments
 *   where the ONNX runtime/model may be absent.
 */
public class OnnxCrossEncoderReranker implements CrossEncoderReranker {

    private final SemaphoreGate gate;

    public OnnxCrossEncoderReranker(SemaphoreGate gate) {
        this.gate = gate;
    }

    @Override
    public List<Content> rerank(String query, List<Content> candidates, int topN) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        int k = Math.max(1, Math.min(topN, candidates.size()));
        boolean ok = false;
        try {
            if (gate != null) {
                ok = gate.tryAcquire(150L);
            }
            // TODO: integrate actual ONNX scoring here
            return new ArrayList<>(candidates.subList(0, k));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return new ArrayList<>(candidates.subList(0, k));
        } finally {
            if (ok && gate != null) {
                gate.release();
            }
        }
    }

    /**
     * Convenience overload: return the list unchanged (identity ranking).
     */
    public List<Content> rerank(String query, List<Content> candidates) {
        return (candidates == null) ? List.of() : new ArrayList<>(candidates);
    }
}