package com.example.lms.guard;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
/** Lightweight concurrency + time-budget guard for ONNX-like stages. */
public final class OnnxBudgetGuard {
    private final Semaphore sem;
    private final long acquireTimeoutMs;
    private final long minRequiredMs;
    public OnnxBudgetGuard(int maxConcurrency, long acquireTimeoutMs, long minRequiredMs) {
        this.sem = new Semaphore(Math.max(1, maxConcurrency), true);
        this.acquireTimeoutMs = Math.max(1L, acquireTimeoutMs);
        this.minRequiredMs = Math.max(0L, minRequiredMs);
    }
    public interface Checked<T> { T run() throws Exception; }
    public <T> T runWithBudget(long remainingMs, Checked<T> block, T fallback) {
        if (remainingMs < minRequiredMs) return fallback;
        boolean ok = false;
        try {
            ok = sem.tryAcquire(acquireTimeoutMs, TimeUnit.MILLISECONDS);
            if (!ok) return fallback;
            return block.run();
        } catch (Exception ex) { return fallback; }
        finally { if (ok) sem.release(); }
    }
}