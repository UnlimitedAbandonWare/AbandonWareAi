package com.abandonware.ai.agent.rerank;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtSession;
import com.abandonware.ai.agent.onnx.OnnxRuntimeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.LongBuffer;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

/**
 * Minimal ONNX Cross-Encoder reranker.
 * If ONNX session absent, falls back to lexical score and returns input order.
 */
@Service
public class OnnxCrossEncoderReranker {

    private final OnnxRuntimeService runtime;
    private final Semaphore limiter = new Semaphore(Integer.getInteger("onnx.maxConcurrency", 1));

    @Autowired
    public OnnxCrossEncoderReranker(OnnxRuntimeService runtime) {
        this.runtime = runtime;
        try { this.runtime.initialize(); } catch (Exception ignore) {}
    }

    public List<Candidate> apply(String query, List<Candidate> candidates, int limit, double scoreThreshold) {
        if (candidates == null || candidates.isEmpty()) return Collections.emptyList();
        List<Candidate> pool = new ArrayList<>(candidates);
        if (pool.size() > limit) pool = pool.subList(0, limit);

        Optional<OrtSession> maybe = runtime.getSession();
        if (maybe.isEmpty()) {
            // fallback lexical score
            final Set<String> q = tokenize(query);
            for (Candidate c : pool) {
                Set<String> t = tokenize(c.text);
                int inter = 0;
                for (String tok : q) if (t.contains(tok)) inter++;
                c.score = q.isEmpty() ? 0.0 : (inter * 1.0 / q.size());
            }
            return pool.stream()
                    .filter(c -> c.score >= Math.max(0.0, scoreThreshold))
                    .sorted(Comparator.comparingDouble((Candidate c) -> c.score).reversed())
                    .collect(Collectors.toList());
        }

        OrtSession session = maybe.get();
        List<Candidate> scored = new ArrayList<>();
        try {
            limiter.acquire();
            for (Candidate c : pool) {
                // NOTE: A real tokenizer is required; here we feed a trivial 2-token tensor as a placeholder
                long[] ids = new long[]{101L, 102L}; // [CLS], [SEP] style tokens
                try (OnnxTensor inputIds = OnnxTensor.createTensor(session.getEnvironment(), LongBuffer.wrap(ids), new long[]{1, ids.length})) {
                    Map<String, OnnxTensor> inputs = new HashMap<>();
                    inputs.put("input_ids", inputIds);
                    OrtSession.Result res = session.run(inputs);
                    // naive single-logit extraction or fallback
                    double logit = 0.5;
                    try {
                        Object first = res.get(0).getValue();
                        if (first instanceof float[]) {
                            float[] arr = (float[]) first;
                            if (arr.length > 0) logit = arr[0];
                        }
                    } catch (Throwable ignore) {}
                    c.score = logit;
                    scored.add(c);
                } catch (Throwable t) {
                    // on any inference error, keep original candidate with default score
                    c.score = 0.0;
                    scored.add(c);
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return pool;
        } finally {
            limiter.release();
        }

        return scored.stream()
                .filter(c -> c.score >= Math.max(0.0, scoreThreshold))
                .sorted(Comparator.comparingDouble((Candidate c) -> c.score).reversed())
                .collect(Collectors.toList());
    }

    // Minimal candidate holder for reranker demo
    public static class Candidate {
        public String id;
        public String text;
        public double score;
        public Candidate() {}
        public Candidate(String id, String text, double score) {
            this.id = id; this.text = text; this.score = score;
        }
    }

    static Set<String> tokenize(String s) {
        Set<String> out = new LinkedHashSet<>();
        if (s == null) return out;
        for (String tok : s.toLowerCase(Locale.ROOT).split("[^a-z0-9가-힣]+")) {
            if (!tok.isBlank()) out.add(tok);
        }
        return out;
    }
}
