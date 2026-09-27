package com.abandonware.ai.agent.integrations.service.onnx;


import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;
/**
 * Two-pass guard + CE semaphore.
 */
public class OnnxRerankLimiter {
    private static final Logger log = Logger.getLogger(OnnxRerankLimiter.class.getName());

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
            logFailSoft("withPermit", e);
            return fallback;
        } finally {
            if (acquired) sem.release();
        }
    }

    private static void logFailSoft(String stage, Exception e) {
        if (log.isLoggable(Level.FINE)) {
            String errorType = e == null ? "unknown" : e.getClass().getSimpleName();
            log.fine("[AWX][onnx][rerank-limiter] failSoft stage=" + stage + " errorType=" + errorType);
        }
    }
}
