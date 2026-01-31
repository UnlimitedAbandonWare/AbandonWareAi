package com.example.lms.resilience;

import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import com.example.lms.cfvm.storage.CfvmRawStore;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Aspect
@Order(Ordered.LOWEST_PRECEDENCE)
@Component
@RequiredArgsConstructor
public class ErrorBreakTelemetryAspect {
  private final CfvmRawStore store;
  private static final long P95_MS = 1200L;

  @Around("execution(* com.example.lms.service.rag..*(..)) || " +
          "execution(* com.example.lms.service.NaverSearchService..*(..)) || " +
          "execution(* com.example.lms.service.onnx..*(..))")
  public Object around(ProceedingJoinPoint pjp) throws Throwable {
    long t0 = System.nanoTime();
    try {
      return pjp.proceed();
    } catch (Throwable ex) {
      store.append(Map.of(
          "stage", "rag",
          "component", pjp.getSignature().toShortString(),
          "exception", ex.getClass().getSimpleName(),
          "signal", "EXC"));
      throw ex;
    } finally {
      long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
      if (ms > P95_MS) {
        store.append(Map.of(
            "stage", "rag",
            "component", pjp.getSignature().toShortString(),
            "latency_ms", ms,
            "signal", "SLOW"));
      }
    }
  }
}