package ai.abandonware.nova.orch.aop;

import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * Propagates a webRateLimited signal as early as possible for web-search breaker activity.
 *
 * <p>
 * Two main cases in production:
 * <ul>
 *     <li><b>Mid-request 429</b>: we learn about rate limiting only after a provider call returns.</li>
 *     <li><b>Breaker already OPEN</b>: pre-call checks skip web providers; we still want the request-local
 *         guard/trace to know "web is limited" so extra helpers stop early and fail-soft.</li>
 * </ul>
 * </p>
 *
 * <p>Fail-soft by design: never break the request path.</p>
 */
@Aspect
public class NightmareBreakerWebRateLimitPropagatorAspect {

    private static final Logger log = LoggerFactory.getLogger(NightmareBreakerWebRateLimitPropagatorAspect.class);

    @Around("execution(* com.example.lms.infra.resilience.NightmareBreaker.recordRateLimit(..))")
    public Object aroundRecordRateLimit(ProceedingJoinPoint pjp) throws Throwable {
        Object ret = pjp.proceed();

        try {
            Object[] args = pjp.getArgs();
            if (args == null || args.length < 1) {
                return ret;
            }

            String key = (args[0] == null) ? null : String.valueOf(args[0]);
            if (!isWebSearchKey(key)) {
                return ret;
            }

            // If breaker is now open/half-open, set a request-local signal immediately.
            boolean openNow = true;
            try {
                Object target = pjp.getTarget();
                if (target instanceof NightmareBreaker nb && key != null) {
                    openNow = nb.isOpenOrHalfOpen(key);
                }
            } catch (Exception ignore) {
                openNow = true; // best-effort
            }

            String reason = null;
            if (args.length >= 3 && args[2] != null) {
                reason = safeTrim(String.valueOf(args[2]), 160);
            }

            propagate(key, reason, "nightmareBreaker.recordRateLimit", openNow);
        } catch (Exception e) {
            // Never break request path.
            log.debug("[nova][webRateLimited] propagate failed (ignored): {}", e.toString());
        }

        return ret;
    }

    @Around("execution(boolean com.example.lms.infra.resilience.NightmareBreaker.isOpen(..))")
    public Object aroundIsOpen(ProceedingJoinPoint pjp) throws Throwable {
        Object ret = pjp.proceed();

        try {
            if (!(ret instanceof Boolean b) || !b) {
                return ret;
            }

            Object[] args = pjp.getArgs();
            if (args == null || args.length < 1) {
                return ret;
            }

            String key = (args[0] == null) ? null : String.valueOf(args[0]);
            if (!isWebSearchKey(key)) {
                return ret;
            }

            propagate(key, "breaker_open", "nightmareBreaker.isOpen", true);
        } catch (Exception e) {
            log.debug("[nova][webRateLimited] propagate(isOpen) failed (ignored): {}", e.toString());
        }

        return ret;
    }

    @Around("execution(boolean com.example.lms.infra.resilience.NightmareBreaker.isOpenOrHalfOpen(..))")
    public Object aroundIsOpenOrHalfOpen(ProceedingJoinPoint pjp) throws Throwable {
        Object ret = pjp.proceed();

        try {
            if (!(ret instanceof Boolean b) || !b) {
                return ret;
            }

            Object[] args = pjp.getArgs();
            if (args == null || args.length < 1) {
                return ret;
            }

            String key = (args[0] == null) ? null : String.valueOf(args[0]);
            if (!isWebSearchKey(key)) {
                return ret;
            }

            propagate(key, "breaker_open_or_half_open", "nightmareBreaker.isOpenOrHalfOpen", true);
        } catch (Exception e) {
            log.debug("[nova][webRateLimited] propagate(isOpenOrHalfOpen) failed (ignored): {}", e.toString());
        }

        return ret;
    }

    private static void propagate(String key, String reason, String setBy, boolean openNow) {
        GuardContext ctx = null;
        try {
            ctx = GuardContextHolder.getOrDefault();
        } catch (Exception ignore) {
            ctx = null;
        }

        try {
            if (ctx != null && !ctx.isWebRateLimited()) {
                ctx.setWebRateLimited(true);
            }
        } catch (Exception ignore) {
        }

        try {
            TraceStore.put("orch.webRateLimited", true);

            if (key != null) {
                TraceStore.putIfAbsent("orch.webRateLimited.key", key);
            }
            if (reason != null && !reason.isBlank()) {
                TraceStore.putIfAbsent("orch.webRateLimited.reason", safeTrim(reason, 160));
            }
            TraceStore.put("orch.webRateLimited.openNow", openNow);
            if (setBy != null) {
                TraceStore.put("orch.webRateLimited.setBy", setBy);
            }
        } catch (Exception ignore) {
        }
    }

    private static boolean isWebSearchKey(String key) {
        if (key == null) {
            return false;
        }
        String k = key.trim().toLowerCase(Locale.ROOT);
        if (k.isEmpty()) {
            return false;
        }
        // Core convention: "websearch:*" and NightmareKeys.WEBSEARCH_*.
        return k.startsWith("websearch:") || k.contains("websearch");
    }

    private static String safeTrim(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        if (t.length() <= max) {
            return t;
        }
        return t.substring(0, Math.max(0, max - 1)) + "…";
    }
}
