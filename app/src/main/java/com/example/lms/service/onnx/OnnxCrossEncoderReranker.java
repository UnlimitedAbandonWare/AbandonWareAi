
package com.example.lms.service.onnx;

import com.abandonware.ai.addons.model.ContextSlice;
import com.example.lms.trace.TraceContext;
import com.example.lms.service.onnx.OnnxRuntimeService;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.concurrent.Semaphore;
import java.util.*;
/**
 * Lightweight concurrency-gated reranker stub. If the gate is saturated or budget elapsed,
 * we fall back to the input ordering (bi-encoder scores).
 */
@Component
public class OnnxCrossEncoderReranker {

    /**
     * Semaphore used to limit concurrent cross‑encoder inference.  When the
     * semaphore cannot be acquired the reranker will fall back to the
     * input ordering (i.e. the bi‑encoder scores).  The maximum number of
     * permits can be configured via {@code onnx.maxInFlight} in the
     * application configuration.
     */
    private final Semaphore gate;

    /**
     * Optional trace context used to determine the remaining time budget for
     * the current request.  When present, reranking will be skipped if the
     * budget has been exhausted.
     */
    @Autowired(required=false)
    private TraceContext trace;

    /**
     * Optional ONNX runtime service.  When injected and available this
     * service will be used to compute a cross‑encoder score for each
     * candidate.  If the service is absent or not ready the reranker
     * behaves as a stable sort based on the existing scores.
     */
    @Autowired(required=false)
    private OnnxRuntimeService onnx;

    public OnnxCrossEncoderReranker(@Value("${onnx.maxInFlight:4}") int maxInFlight) {
        this.gate = new Semaphore(Math.max(1, maxInFlight));
    }

    /**
     * Rerank the provided context slices using a local ONNX cross‑encoder if
     * available.  When the ONNX service is unavailable or cannot be
     * consulted (due to time budget or semaphore saturation) the input
     * ordering is preserved.  The returned list is limited to the top
     * {@code topK} results and each slice is assigned a rank starting
     * from one.
     *
     * @param in the list of context slices to rerank
     * @param topK the number of results to return
     * @return a new list containing the top‑ranked slices
     */
    public List<ContextSlice> rerankTopK(List<ContextSlice> in, int topK) {
        if (in == null || in.isEmpty()) {
            return List.of();
        }
        // normalise topK
        if (topK <= 0) {
            topK = Math.min(10, in.size());
        }
        // Skip reranking when the remaining time budget has been exhausted
        long remain = (trace == null ? Long.MAX_VALUE : trace.remainingMillis());
        if (remain <= 0) {
            return takeTop(in, topK);
        }
        // Attempt to acquire a permit.  If none is available fall back
        boolean acquired = gate.tryAcquire();
        if (!acquired) {
            return takeTop(in, topK);
        }
        try {
            // When an ONNX service is available and ready compute a new
            // relevance score for each candidate.  Otherwise preserve the
            // existing bi‑encoder score.  If any exception occurs the
            // existing score is retained.
            List<ContextSlice> copy = new ArrayList<>(in.size());
            boolean canUseOnnx = (onnx != null && onnx.isAvailable());
            if (canUseOnnx) {
                for (ContextSlice s : in) {
                    double newScore;
                    try {
                        // Use the snippet as the document text and an empty query.  In a
                        // real implementation the query should be passed from the
                        // upstream pipeline.  When the ONNX call fails fall back to
                        // the existing score.
                        newScore = onnx.scorePair("", s.snippet == null ? "" : s.snippet);
                    } catch (Exception e) {
                        newScore = s.score;
                    }
                    ContextSlice ns = new ContextSlice(s.id, s.title, s.snippet, s.source, newScore, s.rank);
                    copy.add(ns);
                }
            } else {
                copy.addAll(in);
            }
            // Sort by descending score and id as tiebreaker to ensure stable ordering
            copy.sort(Comparator.<ContextSlice>comparingDouble((ContextSlice s) -> -s.score)
                    .thenComparing(s -> s.id == null ? "" : s.id));
            // Limit to topK and assign ranks
            int limit = Math.min(topK, copy.size());
            List<ContextSlice> result = new ArrayList<>(limit);
            for (int i = 0; i < limit; i++) {
                ContextSlice s = copy.get(i);
                // update rank field
                result.add(new ContextSlice(s.id, s.title, s.snippet, s.source, s.score, i + 1));
            }
            return result;
        } finally {
            gate.release();
        }
    }

    private List<ContextSlice> takeTop(List<ContextSlice> in, int k){
        List<ContextSlice> out = new ArrayList<>(Math.min(k, in.size()));
        for (int i=0;i<in.size() && out.size()<k;i++){
            out.add(in.get(i));
        }
        return out;
    }
}