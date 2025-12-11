package com.abandonware.ai.service.rag.handler;

import com.abandonware.ai.service.rag.model.ContextSlice;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import java.util.*;

/**
 * Hardened KG handler:
 * - Catch InterruptedException from tryAcquire(timeout)
 * - Ensure no unreachable statements
 * - Release semaphore only when acquired
 */
@Component
public class KnowledgeGraphHandler {
    private final Semaphore limiter;
    private final long timeoutMs;
    private final int topKDefault;

    public KnowledgeGraphHandler(
        @Value("${kg.max-concurrency:2}") int maxConcurrency,
        @Value("${kg.timeout-ms:1200}") long timeoutMs,
        @Value("${kg.top-k:5}") int topKDefault
    ) {
        this.limiter = new Semaphore(Math.max(1, maxConcurrency));
        this.timeoutMs = timeoutMs;
        this.topKDefault = topKDefault;
    }

    public List<ContextSlice> lookup(String query, int topK) {
        boolean acquired = false;
        try {
            try {
                acquired = limiter.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                // preserve interrupt status and fail soft
                Thread.currentThread().interrupt();
                return java.util.Collections.emptyList();
            }
            if (!acquired) {
                return java.util.Collections.emptyList();
            }
            // TODO: plug real KG backend; for now, safe empty result
            return java.util.Collections.emptyList();
        } finally {
            if (acquired) {
                limiter.release();
            }
        }
    }
}