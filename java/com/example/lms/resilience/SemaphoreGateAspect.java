package com.example.lms.resilience;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;

/**
 * Simple concurrency gate for ONNX rerank to prevent request pile-ups when the
 * runtime is slow or under contention.
 *
 * <p>
 * Fail-soft policy: if we cannot acquire a permit immediately, we skip the
 * expensive rerank call and return the pre-filtered list trimmed to topN.
 * </p>
 */
@Aspect
@Component
@ConditionalOnProperty(name = "reranker.ce.maxConcurrency")
public class SemaphoreGateAspect {

    private static final Logger log = LoggerFactory.getLogger(SemaphoreGateAspect.class);

    private final Semaphore semaphore;

    @Value("${reranker.ce.maxConcurrency:1}")
    private int maxConcurrency;

    public SemaphoreGateAspect(@Value("${reranker.ce.maxConcurrency:1}") int maxConcurrency) {
        this.maxConcurrency = maxConcurrency;
        this.semaphore = new Semaphore(Math.max(1, maxConcurrency));
    }

    // Gate only the rerank call; do not wrap all methods on the class.
    @Around("execution(java.util.List *..OnnxCrossEncoderReranker.rerank(..))")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        if (maxConcurrency <= 0) {
            return pjp.proceed();
        }

        // Fast-fail: do not enqueue.
        if (!semaphore.tryAcquire()) {
            log.debug("[SemaphoreGate] ONNX rerank concurrency limit reached; skipping rerank (fail-soft)");

            Object[] args = pjp.getArgs();
            if (args != null && args.length >= 3 && args[1] instanceof List<?> list && args[2] instanceof Integer topN) {
                int k = topN;
                if (k <= 0) k = list.size();
                k = Math.min(k, list.size());
                return new ArrayList<>(list.subList(0, k));
            }
            // Fallback: if signature unexpected, proceed.
            return pjp.proceed();
        }

        try {
            return pjp.proceed();
        } finally {
            semaphore.release();
        }
    }
}
