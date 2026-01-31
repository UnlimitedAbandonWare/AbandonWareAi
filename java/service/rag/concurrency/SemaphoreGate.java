// src/main/java/service/rag/concurrency/SemaphoreGate.java
package service.rag.concurrency;

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
            return fallback.get();
        } finally {
            if (ok) sem.release();
        }
    }
}