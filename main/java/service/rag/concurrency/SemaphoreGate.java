// src/main/java/service/rag/concurrency/SemaphoreGate.java
package service.rag.concurrency;

import com.example.lms.search.TraceStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Component
public class SemaphoreGate {
    private final Semaphore sem;

    @Value("${features.reranker.semaphore.try-acquire-ms:300}")
    private int tryAcquireMs;

    public SemaphoreGate(@Value("${features.reranker.semaphore.max-concurrent:3}") int maxConc) {
        this.sem = new Semaphore(Math.max(1, maxConc), true);
    }

    public <T> T tryWithPermit(Supplier<T> critical, Supplier<T> fallback, int timeoutMs) {
        boolean ok = false;
        try {
            ok = sem.tryAcquire(Math.max(0, timeoutMs), TimeUnit.MILLISECONDS);
            if (ok) return critical.get();
            return fallback.get();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            traceSuppressed("tryAcquire", timeoutMs, ie);
            return fallback.get();
        } finally {
            if (ok) sem.release();
        }
    }

    private static void traceSuppressed(String stage, int timeoutMs, Exception failure) {
        String safeStage = stage == null || stage.isBlank() ? "unknown" : stage;
        TraceStore.put("reranker.semaphore.suppressed.stage", safeStage);
        TraceStore.put("reranker.semaphore.suppressed.errorType",
                failure == null ? "unknown" : failure.getClass().getSimpleName());
        TraceStore.put("reranker.semaphore.suppressed." + safeStage, true);
        TraceStore.put("reranker.semaphore.suppressed." + safeStage + ".errorType",
                failure == null ? "unknown" : failure.getClass().getSimpleName());
        TraceStore.put("reranker.semaphore.timeoutMs", Math.max(0, timeoutMs));
        TraceStore.put("reranker.semaphore.fallbackUsed", true);
    }
}
