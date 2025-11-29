// src/main/java/com/example/lms/aop/RetrievalDiagAspect.java
package com.example.lms.aop;

import com.example.lms.diag.RetrievalDiagnosticsCollector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;



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
@Slf4j
public class RetrievalDiagAspect {
    private final RetrievalDiagnosticsCollector collector;

    @Around("execution(* com.example.lms.service.rag.handler..*(..))")
    public Object aroundHandler(ProceedingJoinPoint pjp) throws Throwable {
        // Derive a stage name from the class without the package
        String name = pjp.getSignature().getDeclaringType().getSimpleName();
        return collector.withSpan(name, () -> {
            try {
                return pjp.proceed();
            } catch (RuntimeException e) {
                // propagate runtime exceptions so existing error handling applies
                throw e;
            } catch (Throwable t) {
                // wrap checked exceptions in unchecked for supplier
                throw new RuntimeException(t);
            }
        });
    }
}