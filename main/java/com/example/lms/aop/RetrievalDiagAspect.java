// src/main/java/com/example/lms/aop/RetrievalDiagAspect.java
package com.example.lms.aop;

import com.example.lms.diag.RetrievalDiagnosticsCollector;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;



/**
 * Aspect that wraps retrieval handler invocations so that timing and hit
 * diagnostics can be collected.  Each method execution in the
 * {@code com.example.lms.service.rag.handler} package is executed under a
 * named span corresponding to the simple class name of the handler.  When
 * all handlers have completed, a one-line summary is logged at INFO level.
 */
@Aspect
@Component
@RequiredArgsConstructor
@Order(Ordered.LOWEST_PRECEDENCE - 120)
public class RetrievalDiagAspect {
    private static final Logger log = LoggerFactory.getLogger(RetrievalDiagAspect.class);
    private final RetrievalDiagnosticsCollector collector;

    @Around("execution(* com.example.lms.service.rag.handler..*(..))")
    public Object aroundHandler(ProceedingJoinPoint pjp) throws Throwable {
        // Derive a stage name from the class without the package
        String name = pjp.getSignature().getDeclaringType().getSimpleName();
        return collector.withSpan(name, () -> {
            try {
                return pjp.proceed();
            } catch (RuntimeException e) {
                traceSuppressed(name, e);
                // propagate runtime exceptions so existing error handling applies
                throw e;
            } catch (Throwable t) {
                traceSuppressed(name, t);
                // wrap checked exceptions in unchecked for supplier
                throw new RuntimeException(t);
            }
        });
    }

    private static void traceSuppressed(String stage, Throwable failure) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String errorType = failure == null
                ? "unknown"
                : SafeRedactor.traceLabelOrFallback(failure.getClass().getSimpleName(), "unknown");
        TraceStore.put("retrieval.diag.suppressed", true);
        TraceStore.put("retrieval.diag.suppressed.stage", safeStage);
        TraceStore.put("retrieval.diag.suppressed.errorType", errorType);
    }
}
