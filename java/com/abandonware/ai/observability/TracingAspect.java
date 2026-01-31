package com.abandonware.ai.observability;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Minimal tracing aspect (compile-safe). Logs method latency in debug level.
 * Does not require Micrometer; safe in environments without aop starter it simply won't be activated.
 */
@Aspect
@Component
public class TracingAspect {

    private static final Logger log = LoggerFactory.getLogger(TracingAspect.class);

    @Around("execution(* com.abandonware.ai.service.rag..*(..))")
    public Object traceRag(ProceedingJoinPoint pjp) throws Throwable {
        long t0 = System.nanoTime();
        try {
            return pjp.proceed();
        } finally {
            long durMs = (System.nanoTime() - t0) / 1_000_000;
            if (log.isDebugEnabled()) {
                log.debug("[trace] {} took {} ms", pjp.getSignature().toShortString(), durMs);
            }
        }
    }
}