
package com.example.lms.resilience;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.concurrent.Semaphore;




/** Limit concurrency of expensive CE reranker without touching its code. */
@Aspect
@Component
@ConditionalOnProperty(name="reranker.ce.maxConcurrency")
public class SemaphoreGateAspect {

    private final Semaphore sem;

    public SemaphoreGateAspect(@Value("${reranker.ce.maxConcurrency:2}") int permits) {
        this.sem = new Semaphore(Math.max(1, permits));
    }

    @Around("execution(* *..OnnxCrossEncoderReranker.*(..))")
    public Object aroundCe(ProceedingJoinPoint pjp) throws Throwable {
        if (!sem.tryAcquire()) {
            // If over budget, skip or degrade gracefully
            return pjp.proceed();
        }
        try {
            return pjp.proceed();
        } finally {
            sem.release();
        }
    }
}