package ai.abandonware.nova.orch.aop;

import ai.abandonware.nova.orch.trace.OrchEventEmitter;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.trace.TraceContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Seeds MDC + TraceStore envelopes at scheduler boundaries.
 *
 * <p>Why: MDC/TraceStore are ThreadLocal-based; on scheduler threads there may be no incoming
 * {@link com.example.lms.web.TraceFilter} to seed them. ERROWRLW showed blank sid/trace ([ ])
 * logs originating from UAW scheduler runs.
 */
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class UawTickTraceSeedAspect {
    private static final Logger log = LoggerFactory.getLogger(UawTickTraceSeedAspect.class);

    @Around("execution(* com.example.lms.uaw.autolearn.UawAutolearnOrchestrator.tick(..))")
    public Object aroundTick(ProceedingJoinPoint pjp) throws Throwable {
        long seedMs = System.currentTimeMillis();
        String sid = "uaw-idle-" + seedMs;
        String trace = "uaw-" + seedMs;

        try (TraceContext ignored = TraceContext.attach(sid, trace)) {
            // Start a fresh envelope on the scheduler thread to avoid thread-reuse leakage.
            try {
                TraceStore.clear();
            } catch (Throwable ignore) {
                logSuppressed("trace.clear.before", ignore);
            }

            try {
                TraceStore.put("sid", SafeRedactor.hashValue(sid));
                TraceStore.put("trace.id", SafeRedactor.hashValue(trace));
                TraceStore.put("trace.runId", SafeRedactor.hashValue(trace));
                TraceStore.put("uaw.tick.seeded", true);
                TraceStore.put("uaw.tick.seedTs", Instant.now().toString());
            } catch (Throwable ignore) {
                logSuppressed("trace.seed", ignore);
            }

            try {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("sid", SafeRedactor.hashValue(sid));
                data.put("trace", SafeRedactor.hashValue(trace));
                data.put("seedMs", seedMs);
                OrchEventEmitter.breadcrumb(
                        "uaw.tick.seed",
                        "Seeded MDC/TraceStore envelope for scheduled tick",
                        "UawTickTraceSeedAspect",
                        data);
            } catch (Throwable ignore) {
                logSuppressed("breadcrumb.seed", ignore);
            }

            return pjp.proceed();
        } finally {
            // Make sure we don't leak trace state on a long-lived scheduler thread.
            try {
                TraceStore.clear();
            } catch (Throwable ignore) {
                logSuppressed("trace.clear.after", ignore);
            }
        }
    }

    private static void logSuppressed(String stage, Throwable ignored) {
        if (log.isDebugEnabled()) {
            log.debug("[uaw.tick] suppressed stage={}",
                    SafeRedactor.traceLabelOrFallback(stage, "unknown"));
        }
    }
}
