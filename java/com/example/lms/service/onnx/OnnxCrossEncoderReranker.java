package com.example.lms.service.onnx;

import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.abandonware.ai.addons.onnx.OnnxSemaphoreGate;
import com.example.lms.infra.resilience.FaultMaskingLayerMonitor;
import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.infra.resilience.NightmareBreaker.FailureKind;
import com.example.lms.infra.resilience.NightmareKeys;
import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.rerank.CrossEncoderReranker;
import dev.langchain4j.rag.content.Content;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ONNX Cross-Encoder reranker (fail-soft).
 *
 * <p>
 * - Uses {@link OnnxRuntimeService#scorePair(String, String)} for scoring.
 * - Respects request time budget (if installed via {@link TimeBudgetContext}).
 * - Optional concurrency gate via {@link OnnxSemaphoreGate} (if provided by
 * Addons auto-config).
 * - Records failures to {@link NightmareBreaker} /
 * {@link FaultMaskingLayerMonitor} when available.
 * </p>
 */
@Service("onnxCrossEncoderReranker")
@ConditionalOnBean(OnnxRuntimeService.class)
public class OnnxCrossEncoderReranker implements CrossEncoderReranker {

    private static final Logger log = LoggerFactory.getLogger(OnnxCrossEncoderReranker.class);

    private final OnnxRuntimeService onnx;

    @Value("${onnx.enabled:true}")
    private boolean enabled;

    @Autowired(required = false)
    private OnnxSemaphoreGate onnxGate;

    @Autowired(required = false)
    private NightmareBreaker nightmareBreaker;

    @Autowired(required = false)
    private FaultMaskingLayerMonitor faultMaskingLayerMonitor;

    public OnnxCrossEncoderReranker(OnnxRuntimeService onnx) {
        this.onnx = onnx;
    }

    @Override
    public List<Content> rerank(String query, List<Content> candidates, int topN) {
        if (candidates == null || candidates.isEmpty()) {
            return candidates;
        }

        int k = normalizeTopN(topN, candidates.size());

        // hard disable / not ready => stable trim
        if (!enabled) {
            TraceStore.put("rerank.onnx.enabled", false);
            return limitStable(candidates, k);
        }
        if (onnx == null || !onnx.available()) {
            TraceStore.put("rerank.onnx.ready", false);
            return limitStable(candidates, k);
        }

        TimeBudget tb = TimeBudgetContext.get();
        if (tb != null && tb.expired()) {
            TraceStore.append("rerank.skip", "onnx:budget_expired");
            return limitStable(candidates, k);
        }

        final String q = (query == null) ? "" : query;
        long startedNs = System.nanoTime();
        AtomicInteger scoredPairs = new AtomicInteger(0);

        try {
            if (onnxGate != null) {
                return onnxGate.withPermit(
                        () -> doRerank(q, candidates, k, tb, scoredPairs),
                        () -> {
                            TraceStore.append("rerank.skip", "onnx:gate");
                            return limitStable(candidates, k);
                        });
            }
            return doRerank(q, candidates, k, tb, scoredPairs);
        } catch (Throwable t) {
            recordFailure(t, "rerank");
            TraceStore.append("rerank.fail", summarize(t));
            return limitStable(candidates, k);
        } finally {
            long elapsedMs = (System.nanoTime() - startedNs) / 1_000_000L;
            TraceStore.put("rerank.onnx.ms", elapsedMs);

            // Mark success only when we actually ran scorePair at least once.
            if (scoredPairs.get() > 0 && nightmareBreaker != null) {
                try {
                    nightmareBreaker.recordSuccess(NightmareKeys.RERANK_ONNX, elapsedMs);
                } catch (Throwable ignore) {
                }
            }
        }
    }

    private List<Content> doRerank(
            String query,
            List<Content> candidates,
            int topN,
            TimeBudget tb,
            AtomicInteger scoredPairs) {
        if (tb != null && tb.remainingMillis() < 30) {
            TraceStore.append("rerank.skip", "onnx:budget_low");
            return limitStable(candidates, topN);
        }

        List<Scored> scored = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            if (tb != null && tb.remainingMillis() < 30) {
                TraceStore.append("rerank.skip", "onnx:budget_exhausted");
                return limitStable(candidates, topN);
            }

            Content c = candidates.get(i);
            String doc = safeContentText(c);

            float s = 0f;
            try {
                s = (float) onnx.scorePair(query, doc);
                scoredPairs.incrementAndGet();
            } catch (Throwable t) {
                // scorePair can throw if tokenizer/runtime not ready; degrade gracefully.
                recordFailure(t, "scorePair");
                s = 0f;
            }
            scored.add(new Scored(i, c, s));
        }

        scored.sort(Comparator
                .comparingDouble(Scored::score).reversed()
                .thenComparingInt(Scored::idx));

        int k = Math.min(topN, scored.size());
        List<Content> out = new ArrayList<>(k);
        for (int i = 0; i < k; i++) {
            out.add(scored.get(i).content);
        }
        return out;
    }

    private void recordFailure(Throwable t, String note) {
        try {
            if (faultMaskingLayerMonitor != null) {
                faultMaskingLayerMonitor.record(NightmareKeys.RERANK_ONNX, t, note);
            }
        } catch (Throwable ignore) {
        }

        try {
            if (nightmareBreaker != null) {
                FailureKind kind = NightmareBreaker.classify(t);
                nightmareBreaker.recordFailure(NightmareKeys.RERANK_ONNX, kind, t, note);
            }
        } catch (Throwable ignore) {
        }

        try {
            log.warn("[RERANK_ONNX] fail-soft note={} err={}", note, summarize(t));
        } catch (Throwable ignore) {
        }
    }

    private static String safeContentText(Content c) {
        if (c == null)
            return "";
        try {
            if (c.textSegment() != null && c.textSegment().text() != null) {
                return c.textSegment().text();
            }
        } catch (Throwable ignore) {
        }
        return String.valueOf(c);
    }

    private static int normalizeTopN(int topN, int size) {
        if (size <= 0)
            return 0;
        if (topN <= 0)
            return size;
        return Math.min(topN, size);
    }

    private static List<Content> limitStable(List<Content> in, int topN) {
        if (in == null || in.isEmpty())
            return in;
        int k = normalizeTopN(topN, in.size());
        if (k >= in.size())
            return in;
        return new ArrayList<>(in.subList(0, k));
    }

    private static String summarize(Throwable t) {
        if (t == null)
            return "";
        Throwable root = t;
        int guard = 0;
        while (root.getCause() != null && root.getCause() != root && guard++ < 12) {
            root = root.getCause();
        }
        String msg = root.getMessage();
        if (msg == null)
            msg = "";
        msg = msg.replace('\n', ' ').replace('\r', ' ').trim();
        String name = root.getClass().getSimpleName();
        return msg.isBlank() ? name : (name + ": " + msg);
    }

    private record Scored(int idx, Content content, float score) {
    }
}
