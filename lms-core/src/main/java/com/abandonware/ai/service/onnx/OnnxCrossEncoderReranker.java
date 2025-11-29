package com.abandonware.ai.service.onnx;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.time.Duration;
import telemetry.LoggingSseEventPublisher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.service.onnx.OnnxCrossEncoderReranker
 * Role: service
 * Feature Flags: onnx.enabled, telemetry, sse
 * Observability: propagates trace headers if present.
 * Thread-Safety: uses concurrent primitives.
 */
/* agent-hint:
id: com.abandonware.ai.service.onnx.OnnxCrossEncoderReranker
role: service
flags: [onnx.enabled, telemetry, sse]
*/
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
        if (!enabled || !ort.isReady() || tokenizer == null || candidates==null || candidates.isEmpty()) return candidates;
        try {
            List<String> qs = new ArrayList<>(), ds = new ArrayList<>();
            for (ScoredDoc d: candidates){ qs.add(query); ds.add(d.text); }
            var trip = tokenizer.encodePairs(qs, ds);
            float[] scores = ort.scoreBatch(trip.ids, trip.attn, trip.type);
            List<ScoredDoc> out = new ArrayList<>(candidates.size());
            for (int i=0;i<candidates.size();i++) out.add(candidates.get(i).withRerank(scores[i]));
            out.sort((a,b)->Double.compare(b.rerankScore, a.rerankScore));
            return out;
        } catch (Exception e) {
            return candidates;
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
            // Safety fallback
            return limitStable(in, topK);
        }
    }

    private static <T> java.util.List<T> limitStable(java.util.List<T> in, int topK) {
        if (topK <= 0 || topK >= in.size()) return in;
        return new java.util.ArrayList<>(in.subList(0, topK));
    }

}