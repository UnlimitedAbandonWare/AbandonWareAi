package com.example.lms.service.onnx;

import java.util.concurrent.TimeUnit;
import java.time.Duration;
import telemetry.LoggingSseEventPublisher;
import java.util.*;
import java.util.concurrent.Semaphore;

/** Minimal stub for Cross-Encoder reranker to satisfy compilation. */
public class OnnxCrossEncoderReranker {
  private final Semaphore gate;
  private final Duration timeout;

    public OnnxCrossEncoderReranker() {
        this.gate = new Semaphore(4);
        this.timeout = Duration.ofMillis(120);
    }
  @org.springframework.beans.factory.annotation.Autowired(required = false) private LoggingSseEventPublisher sse;


    private final Semaphore limiter = new Semaphore(1);

    /** Rerank by descending score; if no model available, return input unchanged. */
    public <T> List<T> rerank(String query, List<T> items, java.util.function.ToDoubleFunction<T> scorer, int topN) {
        if (items == null || items.isEmpty()) return items;
        try {
            limiter.acquire();
            List<T> copy = new ArrayList<>(items);
            copy.sort((a,b) -> Double.compare(scorer.applyAsDouble(b), scorer.applyAsDouble(a)));
            if (topN > 0 && topN < copy.size()) return copy.subList(0, topN);
            return copy;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return items;
        } finally {
            limiter.release();
        }
    }
}