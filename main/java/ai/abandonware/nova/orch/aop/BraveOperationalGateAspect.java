package ai.abandonware.nova.orch.aop;

import ai.abandonware.nova.orch.web.brave.BraveRateLimitState;
import com.example.lms.search.TraceStore;
import com.example.lms.service.web.BraveSearchService;
import com.example.lms.trace.SafeRedactor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;

import java.time.LocalDate;

@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 15)
public class BraveOperationalGateAspect {

    private static final Logger log = LoggerFactory.getLogger(BraveOperationalGateAspect.class);

    private final ObjectProvider<BraveSearchService> braveProvider;
    private final BraveRateLimitState state;
    private final Environment env;

    public BraveOperationalGateAspect(
            ObjectProvider<BraveSearchService> braveProvider,
            BraveRateLimitState state,
            Environment env) {
        this.braveProvider = braveProvider;
        this.state = state;
        this.env = env;
    }

    @Around("execution(* com.example.lms.search.provider.HybridWebSearchProvider.search(..)) || execution(* com.example.lms.search.provider.HybridWebSearchProvider.searchWithTrace(..))")
    public Object aroundHybridSearch(ProceedingJoinPoint pjp) throws Throwable {
        BraveSearchService brave = braveProvider.getIfAvailable();
        if (brave != null) {
            try {
                apply(brave);
            } catch (Throwable t) {
                log.debug("[nova][brave-gate] apply failed (ignored): errorHash={} errorLength={}",
                        SafeRedactor.hashValue(messageOf(t)), messageLength(t));
            }
        }
        return pjp.proceed();
    }

    private static String messageOf(Throwable t) {
        return t == null ? null : t.getMessage();
    }

    private static int messageLength(Throwable t) {
        String msg = messageOf(t);
        return msg == null ? 0 : msg.length();
    }

    private void apply(BraveSearchService brave) {
        boolean keyConflict = env.getProperty("nova.provider.brave.key.conflict", Boolean.class, false);
        if (keyConflict) {
            TraceStore.putIfAbsent("web.await.brave.disabledReason", "key_source_conflict");
        }

        if (brave.isCoolingDown()) {
            long rem = brave.cooldownRemainingMs();
            TraceStore.putIfAbsent("web.await.brave.disabledReason", "cooldown");
            TraceStore.put("web.brave.cooldown.remainingMs", rem);
        }

        brave.resetQuotaForMonthRollover(LocalDate.now());

        long now = System.currentTimeMillis();
        long until = state.quotaExhaustedUntilEpochMs();
        if (until > 0L && now < until) {
            long remainingMs = Math.max(0L, until - now);
            TraceStore.putIfAbsent("web.await.brave.disabledReason", "quota_exhausted");
            setOperationalDisabled(brave, until, remainingMs);
            return;
        }

        if (until > 0L && now >= until) {
            state.clearQuotaLatch();
            brave.clearQuotaExhausted();
            brave.clearOperationalDisableIfQuota();
            TraceStore.put("brave.quota.unlatched", true);
        }

        if (brave.isQuotaExhausted() && state.quotaExhaustedUntilEpochMs() <= 0L) {
            long fallbackUntil = BraveRateLimitState.computeNextMonthStartUtcEpochMs(now);
            state.latchQuotaUntil(fallbackUntil);
            long remainingMs = Math.max(0L, fallbackUntil - now);
            TraceStore.putIfAbsent("web.await.brave.disabledReason", "quota_exhausted");
            setOperationalDisabled(brave, fallbackUntil, remainingMs);
        }
    }

    private void setOperationalDisabled(BraveSearchService brave, long untilEpochMs, long remainingMs) {
        String curReason = brave.disabledReason();
        boolean hardDisabled = curReason != null
                && (curReason.startsWith("missing_api_key") || curReason.startsWith("disabled_by_config"));
        if (hardDisabled) {
            return;
        }

        brave.setOperationallyDisabled("quota_exhausted");
        TraceStore.put("web.brave.skipped", true);
        TraceStore.putIfAbsent("web.brave.skipped.reason", "quota_exhausted");
        TraceStore.putIfAbsent("web.brave.skipped.stage", "quota_exhausted");
        TraceStore.put("web.brave.disabled.untilEpochMs", untilEpochMs);
        TraceStore.put("web.brave.disabled.remainingMs", remainingMs);
    }
}
