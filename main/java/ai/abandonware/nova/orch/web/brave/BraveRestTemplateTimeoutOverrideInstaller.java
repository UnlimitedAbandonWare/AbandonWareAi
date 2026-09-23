package ai.abandonware.nova.orch.web.brave;

import com.example.lms.service.web.BraveSearchService;
import com.example.lms.trace.SafeRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

import java.util.Objects;

/**
 * Removes the implicit 4s timeout floor inside the core BraveSearchService by overriding
 * its RestTemplate request-factory timeouts after initialization (overlay-only).
 */
public class BraveRestTemplateTimeoutOverrideInstaller implements BeanPostProcessor, Ordered {

    private static final Logger log = LoggerFactory.getLogger(BraveRestTemplateTimeoutOverrideInstaller.class);

    private final Environment env;

    public BraveRestTemplateTimeoutOverrideInstaller(Environment env) {
        this.env = Objects.requireNonNull(env);
    }

    @Override
    public int getOrder() {
        // Run late so it applies after BraveSearchService @PostConstruct.
        return Ordered.LOWEST_PRECEDENCE - 10;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!(bean instanceof BraveSearchService brave)) {
            return bean;
        }

        try {
            long timeoutMs = resolveTimeoutMs();
            brave.applyRestTemplateTimeout(timeoutMs);

            log.info("[nova] Brave RestTemplate timeout override applied: {} ms (beanHash={} beanLength={})",
                    timeoutMs, SafeRedactor.hashValue(beanName), lengthOf(beanName));
        } catch (Throwable t) {
            // Fail-soft; never block app boot.
            log.warn("[nova] Brave RestTemplate timeout override failed (fail-soft): errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(t)), messageLength(t));
        }

        return bean;
    }

    private long resolveTimeoutMs() {
        // Prefer an overlay-specific key, else reuse core property, else default to 1800ms.
        Long v = env.getProperty("nova.orch.brave.rest-template.timeout-ms", Long.class);
        if (v == null) {
            v = env.getProperty("gpt-search.brave.timeout-ms", Long.class);
        }
        if (v == null) {
            v = 1800L;
        }
        // Clamp to sane bounds.
        v = Math.max(200L, Math.min(60_000L, v));
        return v;
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
