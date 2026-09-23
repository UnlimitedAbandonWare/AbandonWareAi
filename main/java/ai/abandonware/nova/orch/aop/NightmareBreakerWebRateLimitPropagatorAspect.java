package ai.abandonware.nova.orch.aop;

import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.infra.resilience.NightmareKeys;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.trace.SafeRedactor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.context.event.EventListener;

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
@Order(Ordered.HIGHEST_PRECEDENCE + 25)
public class NightmareBreakerWebRateLimitPropagatorAspect {

    private static final Logger log = LoggerFactory.getLogger(NightmareBreakerWebRateLimitPropagatorAspect.class);

    /**
     * Reentry guard to avoid recursion when we probe other breaker keys from inside this aspect.
     * (NightmareBreaker.isOpen is itself woven by this aspect.)
     */
    private static final ThreadLocal<Boolean> TL_REENTRY = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private enum ObservationReason {
        BREAKER_OPEN,
        STATE_OPENED,
        STATE_OPEN_EXTENDED,
        ADMISSION_BLOCKED,
        ADMISSION_BYPASSED
    }

    private enum ObservationSource {
        IS_OPEN,
        STATE_SIGNAL
    }

    private enum ProviderKey {
        BRAVE,
        NAVER,
        HYBRID,
        UNKNOWN;

        private static ProviderKey from(String key) {
            if (keyEquals(key, NightmareKeys.WEBSEARCH_BRAVE)) return BRAVE;
            if (keyEquals(key, NightmareKeys.WEBSEARCH_NAVER)) return NAVER;
            if (keyEquals(key, NightmareKeys.WEBSEARCH_HYBRID)) return HYBRID;
            return UNKNOWN;
        }
    }

    private static void traceSuppressed(String stage, Exception ex) {
        try {
            TraceStore.put("orch.webRateLimited." + stage + ".suppressed", true);
            TraceStore.put("orch.webRateLimited." + stage + ".errorType",
                    ex == null ? "unknown" : ex.getClass().getSimpleName());
        } catch (RuntimeException traceEx) {
            log.debug("[nova][webRateLimited] suppressed trace failed stage={} err={}",
                    stage, traceEx.getClass().getSimpleName());
        }
    }

    @EventListener
    public void onStateSignal(NightmareBreaker.StateSignal signal) {
        if (signal == null || !signal.webSearchKey() || Boolean.TRUE.equals(TL_REENTRY.get())) {
            return;
        }
        ObservationReason reason = observationReason(signal.signalType());
        if (reason == null) {
            return;
        }
        try {
            propagate(signal.sourceBreaker(), signal.diagnosticKey(), ProviderKey.UNKNOWN,
                    reason, ObservationSource.STATE_SIGNAL,
                    signal.mode() == NightmareBreaker.BreakerMode.OPEN);
        } catch (Exception e) {
            // Never break request path or the publisher's successful breaker transition.
            log.debug("[nova][webRateLimited] propagate(stateSignal) failed (ignored): errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(e)), messageLength(e));
        }
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
            propagate(nb, SafeRedactor.hashValue(key), ProviderKey.from(key),
                    ObservationReason.BREAKER_OPEN, ObservationSource.IS_OPEN, true);
        } catch (Exception e) {
            log.debug("[nova][webRateLimited] propagate(isOpen) failed (ignored): errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(e)), messageLength(e));
        }

        return ret;
    }

    private static void propagate(NightmareBreaker nb,
            String diagnosticKey,
            ProviderKey currentProvider,
            ObservationReason reason,
            ObservationSource source,
            boolean openNow) {
        GuardContext ctx = null;
        try {
            ctx = GuardContextHolder.getOrDefault();
        } catch (Exception ignore) {
            traceSuppressed("guardContext", ignore);
            ctx = null;
        }

        boolean braveDown = false;
        boolean naverDown = false;
        boolean hybridDown = false;

        try {
            TL_REENTRY.set(Boolean.TRUE);

            // Best-effort: compute provider down-ness without poisoning the request path.
            if (nb != null) {
                braveDown = isDown(nb, NightmareKeys.WEBSEARCH_BRAVE,
                        currentProvider == ProviderKey.BRAVE, openNow);
                naverDown = isDown(nb, NightmareKeys.WEBSEARCH_NAVER,
                        currentProvider == ProviderKey.NAVER, openNow);
                hybridDown = isDown(nb, NightmareKeys.WEBSEARCH_HYBRID,
                        currentProvider == ProviderKey.HYBRID, openNow);
            }
        } catch (Exception ignore) {
            traceSuppressed("providerDownProbe", ignore);
            // fail-soft
        } finally {
            TL_REENTRY.set(Boolean.FALSE);
        }

        boolean anyDown = braveDown || naverDown || hybridDown;
        boolean effectiveDown = hybridDown || (braveDown && naverDown);
        String safeDiagnosticKey = safeDiagnosticKey(diagnosticKey);

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

                if (safeDiagnosticKey != null) TraceStore.putIfAbsent("orch.webPartialDown.key", safeDiagnosticKey);
                if (reason != null) TraceStore.putIfAbsent("orch.webPartialDown.reason", reason.name());
                if (source != null) TraceStore.put("orch.webPartialDown.setBy", source.name());
                TraceStore.put("orch.webPartialDown.openNow", openNow);
            }
        } catch (Exception ex) {
            traceSuppressed("partialDownTrace", ex);
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
        } catch (Exception ex) {
            traceSuppressed("planPreference", ex);
        }

        // ── 3) Only when effective web-down, set the global webRateLimited flag.
        if (!effectiveDown) {
            return;
        }

        try {
            if (ctx != null && !ctx.isWebRateLimited()) {
                ctx.setWebRateLimited(true);
            }
        } catch (Exception ex) {
            traceSuppressed("contextFlag", ex);
        }

        try {
            TraceStore.put("orch.webRateLimited", true);

            if (safeDiagnosticKey != null) {
                TraceStore.putIfAbsent("orch.webRateLimited.key", safeDiagnosticKey);
            }
            if (reason != null) {
                TraceStore.putIfAbsent("orch.webRateLimited.reason", reason.name());
            }
            TraceStore.put("orch.webRateLimited.openNow", openNow);
            if (source != null) {
                TraceStore.put("orch.webRateLimited.setBy", source.name());
            }
        } catch (Exception ex) {
            traceSuppressed("effectiveDownTrace", ex);
        }
    }

    private static boolean isDown(NightmareBreaker nb, String checkKey, boolean currentProvider, boolean openNow) {
        if (nb == null || checkKey == null || checkKey.isBlank()) {
            return false;
        }
        if (currentProvider) {
            return openNow;
        }
        try {
            return nb.isOpen(checkKey);
        } catch (Exception ignore) {
            traceSuppressed("isDownProbe", ignore);
            return false;
        }
    }

    private static ObservationReason observationReason(NightmareBreaker.SignalType type) {
        if (type == null) return null;
        return switch (type) {
            case OPENED -> ObservationReason.STATE_OPENED;
            case OPEN_EXTENDED -> ObservationReason.STATE_OPEN_EXTENDED;
            case ADMISSION_BLOCKED -> ObservationReason.ADMISSION_BLOCKED;
            case ADMISSION_BYPASSED -> ObservationReason.ADMISSION_BYPASSED;
            default -> null;
        };
    }

    private static boolean keyEquals(String a, String b) {
        if (a == null || b == null) return false;
        return a.trim().equalsIgnoreCase(b.trim());
    }

    private static String messageOf(Throwable t) {
        return t == null ? null : t.getMessage();
    }

    private static int messageLength(Throwable t) {
        String msg = messageOf(t);
        return msg == null ? 0 : msg.length();
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

    private static String safeDiagnosticKey(String diagnosticKey) {
        if (diagnosticKey == null) return null;
        String value = diagnosticKey.trim().toLowerCase(Locale.ROOT);
        return value.matches("hash:[0-9a-f]{12}") ? value : null;
    }
}
