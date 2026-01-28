package ai.abandonware.nova.orch.aop;

import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.infra.resilience.NightmareKeys;
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
 *         guard/trace to know a provider is down so extra helpers can fail-soft.</li>
 * </ul>
 * </p>
 *
 * <p>
 * IMPORTANT: {@code GuardContext.webRateLimited} MUST mean <b>effective web-down</b> (hybrid down or both providers down),
 * not "one provider is down". One-provider-down is recorded as {@code orch.webPartialDown} / {@code orch.webRateLimited.anyDown}
 * to avoid deadlock-style mutual blocking (e.g., Brave 429 → Naver also skipped).
 * </p>
 *
 * <p>Fail-soft by design: never break the request path.</p>
 */
@Aspect
public class NightmareBreakerWebRateLimitPropagatorAspect {

    private static final Logger log = LoggerFactory.getLogger(NightmareBreakerWebRateLimitPropagatorAspect.class);

    /**
     * Reentry guard to avoid recursion when we probe other breaker keys from inside this aspect.
     * (NightmareBreaker.isOpenOrHalfOpen is itself woven by this aspect.)
     */
    private static final ThreadLocal<Boolean> TL_REENTRY = ThreadLocal.withInitial(() -> Boolean.FALSE);

    @Around("execution(* com.example.lms.infra.resilience.NightmareBreaker.recordRateLimit(..))")
    public Object aroundRecordRateLimit(ProceedingJoinPoint pjp) throws Throwable {
        Object ret = pjp.proceed();

        // Avoid propagating when we are inside our own breaker-state probe.
        if (Boolean.TRUE.equals(TL_REENTRY.get())) {
            return ret;
        }

        try {
            Object[] args = pjp.getArgs();
            if (args == null || args.length < 1) {
                return ret;
            }

            String key = (args[0] == null) ? null : String.valueOf(args[0]);
            if (!isWebSearchKey(key)) {
                return ret;
            }

            NightmareBreaker nb = (pjp.getTarget() instanceof NightmareBreaker n) ? n : null;

            // If breaker is now open/half-open, set a request-local signal immediately.
            boolean openNow = true;
            try {
                if (nb != null && key != null) {
                    openNow = nb.isOpenOrHalfOpen(key);
                }
            } catch (Exception ignore) {
                openNow = true; // best-effort
            }

            String reason = null;
            if (args.length >= 3 && args[2] != null) {
                reason = safeTrim(String.valueOf(args[2]), 160);
            }

            propagate(nb, key, reason, "nightmareBreaker.recordRateLimit", openNow);
        } catch (Exception e) {
            // Never break request path.
            log.debug("[nova][webRateLimited] propagate failed (ignored): {}", e.toString());
        }

        return ret;
    }

    @Around("execution(boolean com.example.lms.infra.resilience.NightmareBreaker.isOpen(..))")
    public Object aroundIsOpen(ProceedingJoinPoint pjp) throws Throwable {
        Object ret = pjp.proceed();

        if (Boolean.TRUE.equals(TL_REENTRY.get())) {
            return ret;
        }

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

            NightmareBreaker nb = (pjp.getTarget() instanceof NightmareBreaker n) ? n : null;
            propagate(nb, key, "breaker_open", "nightmareBreaker.isOpen", true);
        } catch (Exception e) {
            log.debug("[nova][webRateLimited] propagate(isOpen) failed (ignored): {}", e.toString());
        }

        return ret;
    }

    @Around("execution(boolean com.example.lms.infra.resilience.NightmareBreaker.isOpenOrHalfOpen(..))")
    public Object aroundIsOpenOrHalfOpen(ProceedingJoinPoint pjp) throws Throwable {
        Object ret = pjp.proceed();

        if (Boolean.TRUE.equals(TL_REENTRY.get())) {
            return ret;
        }

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

            NightmareBreaker nb = (pjp.getTarget() instanceof NightmareBreaker n) ? n : null;
            propagate(nb, key, "breaker_open_or_half_open", "nightmareBreaker.isOpenOrHalfOpen", true);
        } catch (Exception e) {
            log.debug("[nova][webRateLimited] propagate(isOpenOrHalfOpen) failed (ignored): {}", e.toString());
        }

        return ret;
    }

    private static void propagate(NightmareBreaker nb, String key, String reason, String setBy, boolean openNow) {
        GuardContext ctx = null;
        try {
            ctx = GuardContextHolder.getOrDefault();
        } catch (Exception ignore) {
            ctx = null;
        }

        boolean braveDown = false;
        boolean naverDown = false;
        boolean hybridDown = false;

        try {
            TL_REENTRY.set(Boolean.TRUE);

            // Best-effort: compute provider down-ness without poisoning the request path.
            if (nb != null) {
                braveDown = isDown(nb, NightmareKeys.WEBSEARCH_BRAVE, key, openNow);
                naverDown = isDown(nb, NightmareKeys.WEBSEARCH_NAVER, key, openNow);
                hybridDown = isDown(nb, NightmareKeys.WEBSEARCH_HYBRID, key, openNow);
            } else {
                // If we cannot probe other keys, infer only from the current key.
                braveDown = keyEquals(key, NightmareKeys.WEBSEARCH_BRAVE) && openNow;
                naverDown = keyEquals(key, NightmareKeys.WEBSEARCH_NAVER) && openNow;
                hybridDown = keyEquals(key, NightmareKeys.WEBSEARCH_HYBRID) && openNow;
            }
        } catch (Exception ignore) {
            // fail-soft
        } finally {
            TL_REENTRY.set(Boolean.FALSE);
        }

        boolean anyDown = braveDown || naverDown || hybridDown;
        boolean effectiveDown = hybridDown || (braveDown && naverDown);

        // ── 1) Always record provider-scoped partial-down flags (never block the whole web on single-provider down)
        try {
            if (anyDown) TraceStore.put("orch.webRateLimited.anyDown", true);
            if (braveDown) TraceStore.put("orch.webRateLimited.braveDown", true);
            if (naverDown) TraceStore.put("orch.webRateLimited.naverDown", true);
            if (hybridDown) TraceStore.put("orch.webRateLimited.hybridDown", true);

            if (anyDown && !effectiveDown) {
                TraceStore.put("orch.webPartialDown", true);
                TraceStore.put("orch.webPartialDown.anyDown", true);
                if (braveDown) TraceStore.put("orch.webPartialDown.braveDown", true);
                if (naverDown) TraceStore.put("orch.webPartialDown.naverDown", true);
                if (hybridDown) TraceStore.put("orch.webPartialDown.hybridDown", true);

                if (key != null) TraceStore.putIfAbsent("orch.webPartialDown.key", key);
                if (reason != null && !reason.isBlank())
                    TraceStore.putIfAbsent("orch.webPartialDown.reason", safeTrim(reason, 160));
                if (setBy != null) TraceStore.put("orch.webPartialDown.setBy", setBy);
                TraceStore.put("orch.webPartialDown.openNow", openNow);
            }
        } catch (Exception ignore) {
        }

        // ── 2) Hint provider preference on partial-down (fail-soft routing)
        try {
            if (ctx != null) {
                if (braveDown && !naverDown) {
                    ctx.putPlanOverride("search.web.preferNaver", true);
                    ctx.putPlanOverride("web.preferNaver", true);
                    String cur = ctx.getWebPrimary();
                    if (cur == null || cur.isBlank() || "BRAVE".equalsIgnoreCase(cur)) {
                        ctx.setWebPrimary("NAVER");
                    }
                } else if (naverDown && !braveDown) {
                    String cur = ctx.getWebPrimary();
                    if (cur == null || cur.isBlank() || "NAVER".equalsIgnoreCase(cur)) {
                        ctx.setWebPrimary("BRAVE");
                    }
                }
            }
        } catch (Exception ignore) {
        }

        // ── 3) Only when effective web-down, set the global webRateLimited flag.
        if (!effectiveDown) {
            return;
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

    private static boolean isDown(NightmareBreaker nb, String checkKey, String currentKey, boolean openNow) {
        if (nb == null || checkKey == null || checkKey.isBlank()) {
            return false;
        }
        if (keyEquals(currentKey, checkKey)) {
            return openNow;
        }
        try {
            return nb.isOpenOrHalfOpen(checkKey);
        } catch (Exception ignore) {
            return false;
        }
    }

    private static boolean keyEquals(String a, String b) {
        if (a == null || b == null) return false;
        return a.trim().equalsIgnoreCase(b.trim());
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
