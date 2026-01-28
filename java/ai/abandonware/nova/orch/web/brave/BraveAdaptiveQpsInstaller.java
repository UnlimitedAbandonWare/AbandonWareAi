package ai.abandonware.nova.orch.web.brave;

import ai.abandonware.nova.config.NovaBraveAdaptiveQpsProperties;
import com.example.lms.service.web.BraveSearchService;
import com.google.common.util.concurrent.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Installs a RestTemplate interceptor on the core {@link BraveSearchService} bean
 * without modifying core sources.
 */
public class BraveAdaptiveQpsInstaller implements BeanPostProcessor, Ordered {

    private static final Logger log = LoggerFactory.getLogger(BraveAdaptiveQpsInstaller.class);

    private final NovaBraveAdaptiveQpsProperties props;
    private final BraveRateLimitState state;


    public BraveAdaptiveQpsInstaller(NovaBraveAdaptiveQpsProperties props, BraveRateLimitState state) {
        this.props = Objects.requireNonNull(props);
        this.state = Objects.requireNonNull(state);
    }

    @Override
    public int getOrder() {
        // Install fairly late; we only need the bean to exist.
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!props.isEnabled()) {
            return bean;
        }
        if (!(bean instanceof BraveSearchService brave)) {
            return bean;
        }

        try {
            RestTemplate rt = (RestTemplate) readField(brave, BraveSearchService.class, "restTemplate");
            if (rt == null) {
                return bean;
            }

            // Avoid double installation.
            List<ClientHttpRequestInterceptor> interceptors = new ArrayList<>(rt.getInterceptors());
            for (ClientHttpRequestInterceptor it : interceptors) {
                if (it instanceof BraveAdaptiveQpsRestTemplateInterceptor) {
                    return bean;
                }
            }

            RateLimiter rateLimiter = (RateLimiter) readField(brave, BraveSearchService.class, "rateLimiter");
            AtomicLong cooldownUntilEpochMs = (AtomicLong) readField(brave, BraveSearchService.class,
                    "cooldownUntilEpochMs");
            AtomicInteger monthlyRemaining = (AtomicInteger) readField(brave, BraveSearchService.class, "monthlyRemaining");
            Field quotaExhaustedField = findField(BraveSearchService.class, "quotaExhausted");

            Field enabledField = findField(BraveSearchService.class, "enabled");
            Field disabledReasonField = findField(BraveSearchService.class, "disabledReason");

            BraveAdaptiveQpsRestTemplateInterceptor it = new BraveAdaptiveQpsRestTemplateInterceptor(
                    props,
                    rateLimiter,
                    cooldownUntilEpochMs,
                    monthlyRemaining,
                    brave,
                    quotaExhaustedField,
                    enabledField,
                    disabledReasonField,
                    state);

            interceptors.add(0, it);
            rt.setInterceptors(interceptors);

            log.info("[nova] Installed Brave adaptive-QPS interceptor on bean='{}'", beanName);
        } catch (Throwable t) {
            // Fail-soft; never block app boot.
            log.warn("[nova] Brave adaptive-QPS interceptor install failed (fail-soft): {}", t.toString());
        }

        return bean;
    }

    private static Object readField(Object target, Class<?> declaringType, String fieldName) throws Exception {
        Field f = findField(declaringType, fieldName);
        if (f == null) {
            return null;
        }
        f.setAccessible(true);
        return f.get(target);
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
