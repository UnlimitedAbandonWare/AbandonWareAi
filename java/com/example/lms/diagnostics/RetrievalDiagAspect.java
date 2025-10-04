package com.example.lms.diagnostics;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
// ⬇ 추가
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Simple aspect that measures execution time of retrieval handlers.
 */
@Slf4j
@Aspect
@Profile("diag-simple")                      // ⬅ 기본 비활성 (원할 때만 프로필 켜서 사용)
@Component("retrievalDiagAspectSimple")      // ⬅ 빈 이름 충돌 방지
@Order(Ordered.LOWEST_PRECEDENCE)            // ⬅ 둘 다 켜질 때 중복 영향 최소화
public class RetrievalDiagAspect {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RetrievalDiagAspect.class);


    @Around("execution(* com.example.lms.service.rag.handler..*.handle(..))")
    public Object span(ProceedingJoinPoint pjp) throws Throwable {
        long t0 = System.currentTimeMillis();
        try {
            return pjp.proceed();
        } finally {
            long elapsed = System.currentTimeMillis() - t0;
            log.debug("[SPAN] {} took {}ms", pjp.getSignature().toShortString(), elapsed);
        }
    }
}
