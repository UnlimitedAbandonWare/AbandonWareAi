package ai.abandonware.nova.orch.web.brave;

import ai.abandonware.nova.config.NovaBraveAdaptiveQpsProperties;
import com.example.lms.service.web.BraveSearchService;
import com.example.lms.trace.SafeRedactor;
import com.google.common.util.concurrent.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Installs a RestTemplate interceptor on the core {@link BraveSearchService} bean
 * without modifying core sources.
 */
public class BraveAdaptiveQpsInstaller implements BeanPostProcessor, Ordered {

    private static final Logger log = LoggerFactory.getLogger(BraveAdaptiveQpsInstaller.class);

    private final Supplier<NovaBraveAdaptiveQpsProperties> propsSupplier;
    private final Supplier<BraveRateLimitState> stateSupplier;


    public BraveAdaptiveQpsInstaller(NovaBraveAdaptiveQpsProperties props, BraveRateLimitState state) {
        this(() -> Objects.requireNonNull(props), () -> Objects.requireNonNull(state));
    }

    public BraveAdaptiveQpsInstaller(Supplier<NovaBraveAdaptiveQpsProperties> propsSupplier,
                                     Supplier<BraveRateLimitState> stateSupplier) {
        this.propsSupplier = Objects.requireNonNull(propsSupplier);
        this.stateSupplier = Objects.requireNonNull(stateSupplier);
    }

    @Override
    public int getOrder() {
        // Install fairly late; we only need the bean to exist.
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!(bean instanceof BraveSearchService brave)) {
            return bean;
        }

        try {
            NovaBraveAdaptiveQpsProperties props = propsSupplier.get();
            if (props == null || !props.isEnabled()) {
                return bean;
            }
            BraveRateLimitState state = stateSupplier.get();
            if (state == null) {
                log.warn("[nova] Brave adaptive-QPS interceptor skipped: stateMissing=true beanHash={} beanLength={}",
                        SafeRedactor.hashValue(beanName), lengthOf(beanName));
                return bean;
            }

            RateLimiter rateLimiter = brave.rateLimiter();
            AtomicLong cooldownUntilEpochMs = brave.cooldownUntilEpochMs();
            AtomicInteger monthlyRemaining = brave.monthlyRemaining();

            BraveAdaptiveQpsRestTemplateInterceptor it = new BraveAdaptiveQpsRestTemplateInterceptor(
                    props,
                    rateLimiter,
                    cooldownUntilEpochMs,
                    monthlyRemaining,
                    brave,
                    state);

            if (brave.addRestTemplateInterceptorIfAbsent(it)) {
                log.info("[nova] Installed Brave adaptive-QPS interceptor on beanHash={} beanLength={}",
                        SafeRedactor.hashValue(beanName), lengthOf(beanName));
            }
        } catch (Throwable t) {
            // Fail-soft; never block app boot.
            log.warn("[nova] Brave adaptive-QPS interceptor install failed (fail-soft): errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(t)), messageLength(t));
        }

        return bean;
    }

    private static int lengthOf(String value) {
        return value == null ? 0 : value.length();
    }

    private static String messageOf(Throwable t) {
        return t == null ? null : t.getMessage();
    }

    private static int messageLength(Throwable t) {
        String msg = messageOf(t);
        return msg == null ? 0 : msg.length();
    }
}
