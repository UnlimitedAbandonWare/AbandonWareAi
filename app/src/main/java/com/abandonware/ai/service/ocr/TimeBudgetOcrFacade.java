package com.abandonware.ai.service.ocr;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.*;

/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.service.ocr.TimeBudgetOcrFacade
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: unknown.
 */
/* agent-hint:
id: com.abandonware.ai.service.ocr.TimeBudgetOcrFacade
role: config
*/
public class TimeBudgetOcrFacade {

    private final OcrService delegate;
    private final ExecutorService pool = Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors() / 4));

    @Value("${ocr.enabled:true}") private boolean enabled;
    @Value("${ocr.timeBudgetMs:900}") private long timeBudgetMs;

    public TimeBudgetOcrFacade(OcrService delegate) {
        this.delegate = delegate;
    }

    public CompletableFuture<List<OcrChunk>> extractAsync(byte[] bytes){
        if (!enabled) return CompletableFuture.completedFuture(java.util.List.of());
        return CompletableFuture.supplyAsync(() -> delegate.extract(bytes), pool);
    }

    public List<OcrChunk> extractWithBudget(byte[] bytes) {
        try {
            return extractAsync(bytes).get(timeBudgetMs, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            return java.util.List.of();
        }
    }
}