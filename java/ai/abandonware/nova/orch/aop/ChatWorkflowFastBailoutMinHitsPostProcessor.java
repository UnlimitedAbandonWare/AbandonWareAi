package ai.abandonware.nova.orch.aop;

import com.example.lms.service.ChatWorkflow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.env.Environment;

import java.lang.reflect.Field;

/**
 * Suppresses ChatWorkflow's single-timeout "fast bail" when evidence is already present,
 * while keeping overall latency bounded.
 *
 * <p>
 * Motivation:
 * - In some deployments a single TimeoutException (attempt 1/2) immediately triggers
 *   LLM_FAST_BAIL_TIMEOUT → DEGRADE_EVIDENCE_LIST, skipping the remaining retry.
 * - We expose a knob to require more timeout hits before allowing fast-bail.
 *
 * <p>
 * Risk mitigation:
 * - Disabling fast-bail can increase time-to-fallback when the LLM is unstable.
 *   To cap worst-case latency we optionally clamp:
 *   - max-attempts (so retries don't explode)
 *   - timeout-seconds (so each timeout isn't overly long)
 *   - max-total-ms (optional explicit budget)
 *
 * <p>
 * Constraint note:
 * - Core code is treated as read-only. We use Spring's BeanPostProcessor to adjust
 *   private fields at boot.
 */
public class ChatWorkflowFastBailoutMinHitsPostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(ChatWorkflowFastBailoutMinHitsPostProcessor.class);

    private final Environment env;

    public ChatWorkflowFastBailoutMinHitsPostProcessor(Environment env) {
        this.env = env;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!(bean instanceof ChatWorkflow)) {
            return bean;
        }

        int minHits = env.getProperty(
                "openai.retry.fast-bailout-min-timeout-hits-with-evidence",
                Integer.class,
                1);

        // 1 = keep legacy behavior. >=2 = prevent single-timeout cliff by disabling fast-bail.
        if (minHits <= 1) {
            return bean;
        }

        boolean updated = false;

        // Disable single-timeout cliff.
        updated |= setBooleanField(bean, "llmFastBailoutOnTimeoutWithEvidence", false);

        // When fast-bail is suppressed, keep retries bounded.
        int desiredMinAttempts = Math.max(1, minHits - 1);
        int attemptsCap = env.getProperty(
                "openai.retry.fast-bailout-max-attempts-with-evidence",
                Integer.class,
                desiredMinAttempts);
        attemptsCap = Math.max(desiredMinAttempts, attemptsCap);
        updated |= clampIntField(bean, "llmMaxAttempts", desiredMinAttempts, attemptsCap);

        // Cap per-attempt timeout to avoid long waits when LLM is unhealthy.
        int timeoutCapSeconds = env.getProperty(
                "openai.retry.fast-bailout-timeout-seconds-cap",
                Integer.class,
                0);
        if (timeoutCapSeconds > 0) {
            updated |= capIntFieldAtMost(bean, "llmTimeoutSeconds", timeoutCapSeconds);
        }

        // Optional: cap max-total-ms explicitly (0 = keep existing / auto behavior).
        long maxTotalMsCap = env.getProperty(
                "openai.retry.fast-bailout-max-total-ms-with-evidence",
                Long.class,
                0L);
        if (maxTotalMsCap > 0) {
            updated |= capLongField(bean, "llmRetryMaxTotalMs", maxTotalMsCap);
        }

        if (updated) {
            Integer maxAttemptsNow = getIntField(bean, "llmMaxAttempts");
            Integer timeoutNow = getIntField(bean, "llmTimeoutSeconds");
            Long maxTotalNow = getLongField(bean, "llmRetryMaxTotalMs");

            log.info(
                    "[nova][chatWorkflow] fast-bail suppressed: minTimeoutHitsWithEvidence={} maxAttempts={} timeoutSeconds={} maxTotalMs={}",
                    minHits,
                    (maxAttemptsNow != null ? maxAttemptsNow : "?"),
                    (timeoutNow != null ? timeoutNow : "?"),
                    (maxTotalNow != null ? maxTotalNow : "?"));
        }

        return bean;
    }

    private static boolean setBooleanField(Object bean, String fieldName, boolean value) {
        try {
            Field f = findField(bean.getClass(), fieldName);
            if (f == null) {
                return false;
            }
            f.setAccessible(true);
            Object cur = f.get(bean);
            boolean curVal = (cur instanceof Boolean b) ? b : Boolean.parseBoolean(String.valueOf(cur));
            if (curVal == value) {
                return false;
            }
            f.set(bean, value);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean clampIntField(Object bean, String fieldName, int minInclusive, int maxInclusive) {
        try {
            Field f = findField(bean.getClass(), fieldName);
            if (f == null) {
                return false;
            }
            f.setAccessible(true);
            Object cur = f.get(bean);
            int curVal;
            if (cur instanceof Number n) {
                curVal = n.intValue();
            } else {
                curVal = Integer.parseInt(String.valueOf(cur));
            }

            int next = curVal;
            if (next < minInclusive) {
                next = minInclusive;
            }
            if (next > maxInclusive) {
                next = maxInclusive;
            }

            if (next == curVal) {
                return false;
            }
            f.set(bean, next);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean capIntFieldAtMost(Object bean, String fieldName, int maxInclusive) {
        try {
            Field f = findField(bean.getClass(), fieldName);
            if (f == null) {
                return false;
            }
            f.setAccessible(true);
            Object cur = f.get(bean);
            int curVal;
            if (cur instanceof Number n) {
                curVal = n.intValue();
            } else {
                curVal = Integer.parseInt(String.valueOf(cur));
            }
            if (curVal <= maxInclusive) {
                return false;
            }
            f.set(bean, maxInclusive);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean capLongField(Object bean, String fieldName, long maxInclusive) {
        try {
            Field f = findField(bean.getClass(), fieldName);
            if (f == null) {
                return false;
            }
            f.setAccessible(true);
            Object cur = f.get(bean);
            long curVal;
            if (cur instanceof Number n) {
                curVal = n.longValue();
            } else {
                curVal = Long.parseLong(String.valueOf(cur));
            }

            // 0 means "auto" in ChatWorkflow. Treat auto as already bounded and only clamp if explicit value exists.
            if (curVal == 0) {
                // Keep auto unless caller explicitly wants a hard cap.
                f.set(bean, maxInclusive);
                return true;
            }

            if (curVal <= maxInclusive) {
                return false;
            }
            f.set(bean, maxInclusive);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Integer getIntField(Object bean, String fieldName) {
        try {
            Field f = findField(bean.getClass(), fieldName);
            if (f == null) {
                return null;
            }
            f.setAccessible(true);
            Object cur = f.get(bean);
            if (cur instanceof Number n) {
                return n.intValue();
            }
            return Integer.parseInt(String.valueOf(cur));
        } catch (Throwable t) {
            return null;
        }
    }

    private static Long getLongField(Object bean, String fieldName) {
        try {
            Field f = findField(bean.getClass(), fieldName);
            if (f == null) {
                return null;
            }
            f.setAccessible(true);
            Object cur = f.get(bean);
            if (cur instanceof Number n) {
                return n.longValue();
            }
            return Long.parseLong(String.valueOf(cur));
        } catch (Throwable t) {
            return null;
        }
    }

    private static Field findField(Class<?> type, String name) {
        Class<?> c = type;
        while (c != null && c != Object.class) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignore) {
                c = c.getSuperclass();
            }
        }
        return null;
    }
}
