package com.abandonware.ai.observability;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.observability.TracingAspect
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.observability.TracingAspect
role: config
*/
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