package ai.abandonware.nova.orch.aop;

import ai.abandonware.nova.config.Zero100EngineProperties;
import ai.abandonware.nova.orch.zero100.Zero100SessionRegistry;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.trace.LogCorrelation;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.service.NaverSearchService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 35)
public class Zero100WebTimeboxAspect {

    private static final Logger log = LoggerFactory.getLogger(Zero100WebTimeboxAspect.class);

    private final Zero100EngineProperties props;
    private final Zero100SessionRegistry registry;
    private final ObjectProvider<ExecutorService> searchIoExecutorProvider;

    public Zero100WebTimeboxAspect(Zero100EngineProperties props,
            Zero100SessionRegistry registry,
            ObjectProvider<ExecutorService> searchIoExecutorProvider) {
        this.props = props;
        this.registry = registry;
        this.searchIoExecutorProvider = searchIoExecutorProvider;
    }

    @Around("execution(* com.example.lms.search.provider.HybridWebSearchProvider.search(..))")
    public Object aroundHybridSearch(ProceedingJoinPoint pjp) throws Throwable {
        if (!isZero100Active()) {
            return pjp.proceed();
        }
        return callWithTimebox(pjp, java.util.Collections.emptyList());
    }

    @Around("execution(* com.example.lms.search.provider.HybridWebSearchProvider.searchWithTrace(..))")
    public Object aroundHybridSearchWithTrace(ProceedingJoinPoint pjp) throws Throwable {
        if (!isZero100Active()) {
            return pjp.proceed();
        }
        // SearchWithTrace returns NaverSearchService.SearchResult.
        return callWithTimebox(pjp, new NaverSearchService.SearchResult(java.util.Collections.emptyList(),
                new NaverSearchService.SearchTrace()));
    }

    private boolean isZero100Active() {
        try {
            if (props == null || !props.isEngineEnabled()) {
                return false;
            }
            Object v = TraceStore.get("zero100.enabled");
            if (v != null && String.valueOf(v).equalsIgnoreCase("true")) {
                return true;
            }
        } catch (Throwable ignore) {
            traceSuppressed("active.trace");
            return false;
        }
        return false;
    }

    private Object callWithTimebox(ProceedingJoinPoint pjp, Object timeoutFallback) throws Throwable {
        ExecutorService ex = (searchIoExecutorProvider == null) ? null : searchIoExecutorProvider.getIfAvailable();
        if (ex == null) {
            return pjp.proceed();
        }

        long timeoutMs = resolveTimeboxMs();
        if (timeoutMs <= 0L) {
            return pjp.proceed();
        }

        // 람다에서 참조하기 위해 effectively final 변수로 복사
        Map<String, Object> traceCtxTemp = null;
        try {
            traceCtxTemp = TraceStore.context();
        } catch (Throwable ignore) {
            traceSuppressed("context.trace");
        }
        final Map<String, Object> traceCtx = traceCtxTemp;

        GuardContext gcTemp = null;
        try {
            gcTemp = GuardContextHolder.get();
        } catch (Throwable ignore) {
            traceSuppressed("context.guard");
        }
        final GuardContext gc = gcTemp;

        Map<String, String> mdcTemp = null;
        try {
            mdcTemp = MDC.getCopyOfContextMap();
        } catch (Throwable ignore) {
            traceSuppressed("context.mdc");
        }
        final Map<String, String> mdc = mdcTemp;

        Callable<Object> task = () -> {
            try {
                if (traceCtx != null) {
                    TraceStore.installContext(traceCtx);
                }
                if (gc != null) {
                    GuardContextHolder.set(gc);
                }
                if (mdc != null) {
                    MDC.setContextMap(mdc);
                }
            } catch (Throwable ignore) {
                traceSuppressed("task.context.install");
            }

            try {
                return pjp.proceed();
            } catch (Throwable t) {
                // Callable<Object>는 Exception만 던질 수 있으므로 RuntimeException으로 래핑
                if (t instanceof RuntimeException) {
                    throw (RuntimeException) t;
                }
                if (t instanceof Error) {
                    throw (Error) t;
                }
                throw new RuntimeException("Zero100 timebox proceed failed", t);
            } finally {
                try {
                    GuardContextHolder.clear();
                } catch (Throwable ignore) {
                    traceSuppressed("task.guard.clear");
                }
                try {
                    TraceStore.clear();
                } catch (Throwable ignore) {
                    traceSuppressed("task.trace.clear");
                }
                try {
                    MDC.clear();
                } catch (Throwable ignore) {
                    traceSuppressed("task.mdc.clear");
                }
            }
        };

        Future<Object> f = ex.submit(task);
        try {
            Object out = f.get(timeoutMs, TimeUnit.MILLISECONDS);
            try {
                TraceStore.put("zero100.webTimebox.applied", true);
                TraceStore.put("zero100.webTimebox.ms", timeoutMs);
                TraceStore.put("zero100.webTimebox.hit", false);
            } catch (Throwable ignore) {
                traceSuppressed("trace.success");
            }
            return out;
        } catch (java.util.concurrent.TimeoutException te) {
            try {
                TraceStore.put("zero100.webTimebox.applied", true);
                TraceStore.put("zero100.webTimebox.ms", timeoutMs);
                TraceStore.put("zero100.webTimebox.hit", true);
                TraceStore.inc("zero100.webTimebox.timeout.count");
                TraceStore.put("zero100.webTimebox.rid", SafeRedactor.hashValue(LogCorrelation.requestId()));
            } catch (Throwable ignore) {
                traceSuppressed("trace.timeout");
            }

            // Do NOT cancel(true). Let it finish and populate caches; CancelShield will
            // also guard interrupts.
            // f.cancel(false);

            if (log.isDebugEnabled()) {
                log.debug("[Zero100] hybrid websearch timeboxed ({}ms){}", timeoutMs, LogCorrelation.suffix());
            }
            return timeoutFallback;
        } catch (Throwable t) {
            // propagate original exceptions
            throw t;
        }
    }

    private long resolveTimeboxMs() {
        GuardContext gc = null;
        try {
            gc = GuardContextHolder.get();
        } catch (Throwable ignore) {
            traceSuppressed("timebox.guard");
            gc = null;
        }

        long base = props.getWebCallTimeboxMs();
        if (gc != null) {
            base = gc.planLong("search.zero100.webTimeboxMs", base);
        }

        boolean alreadyLimited = false;
        try {
            alreadyLimited = alreadyLimitedProvider("web.brave.skipped.reason")
                    || alreadyLimitedProvider("web.naver.skipped.reason")
                    || alreadyLimitedProvider("web.serpapi.skipped.reason")
                    || alreadyLimitedProvider("web.tavily.skipped.reason");
        } catch (Throwable ignore) {
            traceSuppressed("timebox.rateLimitTrace");
            alreadyLimited = false;
        }

        if (alreadyLimited) {
            long tight = props.getWebCallTimeboxMsWhenRateLimited();
            if (gc != null) {
                tight = gc.planLong("search.zero100.webTimeboxMsWhenRateLimited", tight);
            }
            return Math.max(50L, Math.min(base, tight));
        }

        return Math.max(50L, Math.min(20_000L, base));
    }

    private static boolean alreadyLimitedProvider(String key) {
        Object raw = TraceStore.get(key);
        String value = raw == null ? "" : String.valueOf(raw).toLowerCase(java.util.Locale.ROOT);
        return value.contains("cooldown")
                || value.contains("rate_limit")
                || value.contains("rate-limit")
                || value.contains("breaker_open");
    }

    private static void traceSuppressed(String stage) {
        log.debug("[Zero100] suppressed stage={}",
                SafeRedactor.traceLabelOrFallback(stage, "unknown"));
    }
}
