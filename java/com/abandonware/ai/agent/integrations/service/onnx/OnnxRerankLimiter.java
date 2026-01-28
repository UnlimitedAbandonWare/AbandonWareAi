package com.abandonware.ai.agent.integrations.service.onnx;


import java.util.concurrent.*;
/**
 * Two-pass guard + CE semaphore.
 */
public class OnnxRerankLimiter {
    private final Semaphore sem;
    private final long timeoutMs;
    public OnnxRerankLimiter(int maxPermits, long timeoutMs){
        this.sem = new Semaphore(Math.max(1, maxPermits));
        this.timeoutMs = timeoutMs;
    }
    public <T> T withPermit(Callable<T> call, T fallback){
        boolean acquired = false;
        try {
            acquired = sem.tryAcquire(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!acquired) return fallback;
            return call.call();
        } catch (Exception e){
            return fallback;
        } finally {
            if (acquired) sem.release();
        }
    }
}