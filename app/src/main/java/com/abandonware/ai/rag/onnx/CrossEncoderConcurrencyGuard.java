package com.abandonware.ai.rag.onnx;

import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.function.Function;

/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.rag.onnx.CrossEncoderConcurrencyGuard
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.rag.onnx.CrossEncoderConcurrencyGuard
role: config
*/
public class CrossEncoderConcurrencyGuard<T> {

    public interface RerankFn<T> extends Function<List<T>, List<T>> {}

    private final RerankFn<T> delegate;
    private final Semaphore gate;

    public CrossEncoderConcurrencyGuard(RerankFn<T> delegate, int maxConcurrency) {
        this.delegate = delegate;
        this.gate = new Semaphore(Math.max(1, maxConcurrency));
    }

    public List<T> guardedRerank(List<T> candidates, long remainingBudgetMs){
        if (candidates == null || candidates.isEmpty()) return candidates;
        if (remainingBudgetMs < 300) return candidates; // budget almost gone: graceful bypass
        if (!gate.tryAcquire()) return candidates;      // congestion: keep original ordering
        try {
            return delegate.apply(candidates);
        } finally {
            gate.release();
        }
    }
}