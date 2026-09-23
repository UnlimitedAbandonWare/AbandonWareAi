package com.abandonware.ai.service.onnx;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.time.Duration;
import com.example.lms.search.TraceStore;
import com.example.lms.telemetry.LoggingSseEventPublisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class OnnxCrossEncoderReranker {
  private final Semaphore gate;
  private final Duration timeout;

    @Autowired
    public OnnxCrossEncoderReranker(
            @Value("${rerank.onnx.semaphore.max-concurrent:4}") int maxConcurrent,
            @Value("${rerank.onnx.semaphore.queue-timeout-ms:120}") long queueWaitMs
    ) {
        this.gate = new Semaphore(Math.max(1, maxConcurrent));
        this.timeout = Duration.ofMillis(Math.max(1L, queueWaitMs));
    }
  @org.springframework.beans.factory.annotation.Autowired(required = false) private LoggingSseEventPublisher sse;


    @Value("${onnx.enabled:false}") private boolean enabled;
    @Autowired private OnnxRuntimeService ort;
    @Autowired(required = false) private TokenizerAdapter tokenizer;

    public static class ScoredDoc {
        public final String id, text;
        public final double baseScore;
        public double rerankScore;
        public ScoredDoc(String id, String text, double baseScore){ this.id=id; this.text=text; this.baseScore=baseScore; }
        public ScoredDoc withRerank(double s){ this.rerankScore=s; return this; }
    }

    public List<ScoredDoc> rerank(String query, List<ScoredDoc> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return candidates;
        }
        if (!enabled || ort == null || !ort.isReady() || tokenizer == null) {
            emitSkip("disabled_or_not_ready");
            return candidates;
        }

        boolean acquired = false;
        try {
            acquired = gate.tryAcquire(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!acquired) {
                emitSkip("gate_timeout");
                return candidates;
            }
            List<String> qs = new ArrayList<>(), ds = new ArrayList<>();
            for (ScoredDoc d: candidates){ qs.add(query); ds.add(d.text); }
            var trip = tokenizer.encodePairs(qs, ds);
            float[] scores = ort.scoreBatch(trip.ids, trip.attn, trip.type);
            List<ScoredDoc> out = new ArrayList<>(candidates.size());
            for (int i=0;i<candidates.size();i++) out.add(candidates.get(i).withRerank(scores[i]));
            out.sort((a,b)->Double.compare(b.rerankScore, a.rerankScore));
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            traceSuppressed("rerank.interrupted", e);
            emitSkip("interrupted");
            return candidates;
        } catch (Exception e) {
            traceSuppressed("rerank.exception", e);
            emitFail(e);
            return candidates;
        } finally {
            if (acquired) {
                gate.release();
            }
        }
    
    }

    // --- Added: Overload used by RerankOrchestrator (accepts ContextSlice list) ---
    public java.util.List<com.abandonware.ai.service.rag.model.ContextSlice> rerankTopK(
            java.util.List<com.abandonware.ai.service.rag.model.ContextSlice> in, int topK) {
        if (in == null || in.isEmpty()) return in;
        // If ONNX path not ready, just trim to topK keeping existing order
        if (!enabled || ort == null || !ort.isReady() || tokenizer == null) {
            return limitStable(in, topK);
        }
        try {
            // Heuristic anchor: use best title as query; if null, empty string.
            String anchor = in.get(0).getTitle();
            if (anchor == null) anchor = "";
            java.util.List<String> qs = new java.util.ArrayList<>(in.size());
            java.util.List<String> ds = new java.util.ArrayList<>(in.size());
            for (var c : in) {
                qs.add(anchor);
                String text = ((c.getTitle() == null ? "" : c.getTitle()) + " " + (c.getSnippet() == null ? "" : c.getSnippet())).trim();
                ds.add(text.isEmpty() ? (c.getTitle()==null?"":c.getTitle()) : text);
            }
            var trip = tokenizer.encodePairs(qs, ds);
            float[] scores = ort.scoreBatch(trip.ids, trip.attn, trip.type);

            // Attach scores back and sort desc; maintain stable order on ties
            java.util.List<com.abandonware.ai.service.rag.model.ContextSlice> out = new java.util.ArrayList<>(in);
            java.util.Map<String, Float> scoreById = new java.util.HashMap<>();
            for (int i=0;i<in.size();i++) {
                scoreById.put(in.get(i).getId(), scores[i]);
            }
            out.sort((a,b) -> {
                float sa = scoreById.getOrDefault(a.getId(), 0f);
                float sb = scoreById.getOrDefault(b.getId(), 0f);
                int cmp = java.lang.Float.compare(sb, sa);
                if (cmp != 0) return cmp;
                return java.lang.Integer.compare(a.getRank(), b.getRank()); // tie-breaker: prior rank
            });
            if (topK > 0 && topK < out.size()) {
                return new java.util.ArrayList<>(out.subList(0, topK));
            }
            return out;
        } catch (Throwable t) {
            traceSuppressed("rerankTopK", t);
            return limitStable(in, topK);
        }
    }

    private static <T> java.util.List<T> limitStable(java.util.List<T> in, int topK) {
        if (topK <= 0 || topK >= in.size()) return in;
        return new java.util.ArrayList<>(in.subList(0, topK));
    }

    private void emitSkip(String reason) {
        emit("onnx.rerank.skip", reason);
    }

    private void emitFail(Throwable error) {
        String reason = error == null ? "unknown" : error.getClass().getSimpleName();
        emit("onnx.rerank.fail", reason);
    }

    private void emit(String type, String value) {
        try {
            if (sse != null) {
                sse.emit(type, value);
            }
        } catch (Throwable ignored) {
            traceSuppressed("emit", ignored);
            // Best-effort diagnostic only; rerank must stay fail-soft.
        }
    }

    private static void traceSuppressed(String stage, Throwable error) {
        TraceStore.put("rerank.onnx.abandonware.suppressed", true);
        TraceStore.put("rerank.onnx.abandonware.suppressed.stage", stage);
        TraceStore.put("rerank.onnx.abandonware.suppressed.errorClass", errorClass(error));
        TraceStore.inc("rerank.onnx.abandonware.suppressed.count");
    }

    private static String errorClass(Throwable error) {
        if (error instanceof InterruptedException) {
            return "cancelled";
        }
        return error == null ? "unknown" : error.getClass().getSimpleName();
    }
}
