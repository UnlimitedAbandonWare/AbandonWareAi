package com.abandonware.ai.normalization.service.onnx;

import java.util.*;
import java.util.concurrent.*;

/** Minimal batching+semaphore guard for ONNX Cross-Encoder calls (stub). */
public class OnnxBatchGuard {
    private final Semaphore sem;
    private final int maxBatch;
    private final int maxWaitMs;
    public OnnxBatchGuard(int permits, int maxBatch, int maxWaitMs) {
        this.sem = new Semaphore(permits);
        this.maxBatch = maxBatch;
        this.maxWaitMs = maxWaitMs;
    }
    public <T> List<T> process(List<Callable<T>> calls) throws Exception {
        if (!sem.tryAcquire(1, maxWaitMs, TimeUnit.MILLISECONDS)) {
            // caller should fallback to Bi-Encoder
            throw new TimeoutException("OnnxBatchGuard timeout");
        }
        try {
            int n = Math.min(maxBatch, calls.size());
            ExecutorService ex = Executors.newFixedThreadPool(n);
            try {
                List<Future<T>> futs = ex.invokeAll(calls.subList(0,n), maxWaitMs, TimeUnit.MILLISECONDS);
                List<T> out = new ArrayList<>();
                for (Future<T> f: futs) if (f.isDone()) out.add(f.get());
                return out;
            } finally {
                ex.shutdownNow();
            }
        } finally {
            sem.release();
        }
    }
}