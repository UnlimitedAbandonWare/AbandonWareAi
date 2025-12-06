package com.example.rerank.cross;

import org.springframework.stereotype.Component;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;



@Component
public class SemaphoreGate {
    private final Semaphore sem;
    private OnnxRerankProps props;

    public SemaphoreGate() {
        this.props = new OnnxRerankProps(4, 120, 350, 10);
        this.sem = new Semaphore(Math.max(1, props.maxConcurrent()));
    }
    public boolean tryAcquire(long millis) throws InterruptedException {
        return sem.tryAcquire(millis, TimeUnit.MILLISECONDS);
    }
    public void release(){ sem.release(); }

    public record OnnxRerankProps(int maxConcurrent, long acquireTimeoutMs,
                                  long minBudgetMs, int minCandidatesForCross) { }
}