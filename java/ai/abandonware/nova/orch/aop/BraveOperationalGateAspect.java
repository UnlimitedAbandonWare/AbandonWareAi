package ai.abandonware.nova.orch.aop;

import ai.abandonware.nova.orch.web.brave.BraveRateLimitState;
import com.example.lms.search.TraceStore;
import com.example.lms.service.web.BraveSearchProperties;
import com.example.lms.service.web.BraveSearchService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Operational gate for Brave scheduling.
 *
 * <p>Goals:</p>
 * <ul>
 *   <li>Auto-unlatch quotaExhausted when reset time passes (or month rolls over)</li>
 *   <li>Stop scheduling Brave while quota is known exhausted (prevents IO executor backlog / hard timeouts)</li>
 *   <li>Expose concrete disabled reasons into TraceStore for EvidenceList/metrics</li>
 * </ul>
 */
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 15)
public class BraveOperationalGateAspect {

    private static final Logger log = LoggerFactory.getLogger(BraveOperationalGateAspect.class);

    private final ObjectProvider<BraveSearchService> braveProvider;
    private final BraveRateLimitState state;
    private final Environment env;

    private final Field enabledField;
    private final Field disabledReasonField;
    private final Field quotaExhaustedField;
    private final Field lastResetDateField;
    private final Field monthlyRemainingField;
    private final Field propsField;

    public BraveOperationalGateAspect(
            ObjectProvider<BraveSearchService> braveProvider,
            BraveRateLimitState state,
            Environment env) {
        this.braveProvider = braveProvider;
        this.state = state;
        this.env = env;

        this.enabledField = findField(BraveSearchService.class, "enabled");
        this.disabledReasonField = findField(BraveSearchService.class, "disabledReason");
        this.quotaExhaustedField = findField(BraveSearchService.class, "quotaExhausted");
        this.lastResetDateField = findField(BraveSearchService.class, "lastResetDate");
        this.monthlyRemainingField = findField(BraveSearchService.class, "monthlyRemaining");
        this.propsField = findField(BraveSearchService.class, "props");
    }

    @Around("execution(* com.example.lms.search.provider.HybridWebSearchProvider.search(..)) || execution(* com.example.lms.search.provider.HybridWebSearchProvider.searchWithTrace(..))")
    public Object aroundHybridSearch(ProceedingJoinPoint pjp) throws Throwable {
        BraveSearchService brave = braveProvider.getIfAvailable();
        if (brave != null) {
            try {
                apply(brave);
            } catch (Throwable t) {
                // fail-soft
                log.debug("[nova][brave-gate] apply failed (ignored): {}", t.toString());
            }
        }
        return pjp.proceed();
    }

    private void apply(BraveSearchService brave) {
        // 1) Surface key conflicts/missing key into the request trace (even when Brave is skipped).
        boolean keyConflict = env.getProperty("nova.provider.brave.key.conflict", Boolean.class, false);
        if (keyConflict) {
            TraceStore.putIfAbsent("web.await.brave.disabledReason", "key_source_conflict");
        }

        // 2) If Brave is cooling down, publish that too.
        try {
            if (brave.isCoolingDown()) {
                long rem = brave.cooldownRemainingMs();
                TraceStore.putIfAbsent("web.await.brave.disabledReason", "cooldown remainingMs=" + rem);
            }
        } catch (Exception ignore) {
        }

        // 3) Fix core ordering bug: quotaExhausted short-circuits before month rollover reset.
        maybeRunMonthRolloverReset(brave);

        long now = System.currentTimeMillis();
        long until = state.quotaExhaustedUntilEpochMs();

        // If we have a known latch, keep Brave disabled until reset.
        if (until > 0L && now < until) {
            long remainingMs = Math.max(0L, until - now);
            TraceStore.putIfAbsent("web.await.brave.disabledReason", "quota_exhausted remainingMs=" + remainingMs);

            // Ensure Brave is operationally disabled so HybridWebSearchProvider won't schedule it.
            setOperationalDisabled(brave, until, remainingMs);
            return;
        }

        // If latch expired, auto-unlatch.
        if (until > 0L && now >= until) {
            state.clearQuotaLatch();
            clearOperationalDisableIfQuota(brave);
            TraceStore.put("brave.quota.unlatched", true);
        }

        // If core is latched but we have no state, create a conservative latch until next month.
        if (isQuotaExhausted(brave) && state.quotaExhaustedUntilEpochMs() <= 0L) {
            long fallbackUntil = BraveRateLimitState.computeNextMonthStartUtcEpochMs(now);
            state.latchQuotaUntil(fallbackUntil);
            long remainingMs = Math.max(0L, fallbackUntil - now);
            TraceStore.putIfAbsent("web.await.brave.disabledReason", "quota_exhausted remainingMs=" + remainingMs);
            setOperationalDisabled(brave, fallbackUntil, remainingMs);
        }
    }

    private void maybeRunMonthRolloverReset(BraveSearchService brave) {
        if (lastResetDateField == null || monthlyRemainingField == null || quotaExhaustedField == null || propsField == null) {
            return;
        }

        try {
            Object cur = lastResetDateField.get(brave);
            if (!(cur instanceof LocalDate last)) {
                return;
            }

            LocalDate today = LocalDate.now();
            if (today.getYear() != last.getYear() || today.getMonthValue() != last.getMonthValue()) {
                Object propsObj = propsField.get(brave);
                if (propsObj instanceof BraveSearchProperties props) {
                    Object remObj = monthlyRemainingField.get(brave);
                    if (remObj instanceof AtomicInteger rem) {
                        rem.set(Math.max(0, props.monthlyQuota()));
                    }
                }

                quotaExhaustedField.set(brave, Boolean.FALSE);
                lastResetDateField.set(brave, today);

                // If we previously operationally disabled due to quota, re-enable now.
                clearOperationalDisableIfQuota(brave);

                TraceStore.put("brave.quota.monthRolloverReset", true);
            }
        } catch (Exception ignore) {
            // fail-soft
        }
    }

    private boolean isQuotaExhausted(BraveSearchService brave) {
        if (quotaExhaustedField == null) {
            return false;
        }
        try {
            Object v = quotaExhaustedField.get(brave);
            return Boolean.TRUE.equals(v);
        } catch (Exception ignore) {
            return false;
        }
    }

    private void setOperationalDisabled(BraveSearchService brave, long untilEpochMs, long remainingMs) {
        if (enabledField == null || disabledReasonField == null) {
            return;
        }

        try {
            Object curReason = disabledReasonField.get(brave);
            String s = (curReason == null) ? "" : String.valueOf(curReason);

            // Do not override a hard disable due to config/key.
            boolean hardDisabled = s.startsWith("missing_api_key") || s.startsWith("disabled_by_config");
            if (hardDisabled) {
                return;
            }

            enabledField.set(brave, Boolean.FALSE);
            disabledReasonField.set(brave, "quota_exhausted untilEpochMs=" + untilEpochMs);

            TraceStore.put("web.brave.skipped", true);
            TraceStore.putIfAbsent("web.brave.skipped.reason", "disabled");
            TraceStore.putIfAbsent("web.brave.skipped.stage", "quota_exhausted");
            TraceStore.put("web.brave.disabled.untilEpochMs", untilEpochMs);
            TraceStore.put("web.brave.disabled.remainingMs", remainingMs);
        } catch (Exception ignore) {
            // fail-soft
        }
    }

    private void clearOperationalDisableIfQuota(BraveSearchService brave) {
        if (enabledField == null || disabledReasonField == null) {
            return;
        }
        try {
            Object curReason = disabledReasonField.get(brave);
            String s = (curReason == null) ? "" : String.valueOf(curReason);
            if (s.startsWith("quota_exhausted")) {
                enabledField.set(brave, Boolean.TRUE);
                disabledReasonField.set(brave, "");
            }
        } catch (Exception ignore) {
        }
    }

    private static Field findField(Class<?> type, String fieldName) {
        Class<?> cur = type;
        while (cur != null && cur != Object.class) {
            try {
                Field f = cur.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignore) {
                cur = cur.getSuperclass();
            }
        }
        return null;
    }
}
