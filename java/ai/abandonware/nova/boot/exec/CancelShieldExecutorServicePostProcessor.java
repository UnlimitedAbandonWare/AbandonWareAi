package ai.abandonware.nova.boot.exec;

import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.env.Environment;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * Post-processor that wraps selected {@link ExecutorService} beans with
 * {@link CancelShieldExecutorService}.
 *
 * <p>
 * Motivation: 일부 레거시 코드가 timeout/budget 상황에서 {@code Future.cancel(true)} 를 호출하며,
 * 이는 pooled worker thread 를 interrupt "poison" 시켜 WebClient/Reactive 체인까지 연쇄 취소되는
 * 문제가 발생할 수 있다.
 *
 * <p>
 * 이 PostProcessor 는 특정 beanName(기본: searchIoExecutor)에만 적용하여
 * cancel(true)를 cancel(false) 로 downgrade 하고, 운영 안정화(bootRun/soak)에 필요한
 * fail-soft 동작을 제공한다.
 */
public class CancelShieldExecutorServicePostProcessor implements BeanPostProcessor, PriorityOrdered {

    private static final Logger log = LoggerFactory.getLogger(CancelShieldExecutorServicePostProcessor.class);

    private final Environment env;
    private final ObjectProvider<DebugEventStore> debugEventStoreProvider;

    private volatile Set<String> allowNames;
    private volatile Integer bulkMaxInflight;

    public CancelShieldExecutorServicePostProcessor(Environment env,
                                                    ObjectProvider<DebugEventStore> debugEventStoreProvider) {
        this.env = env;
        this.debugEventStoreProvider = debugEventStoreProvider;
    }

    @Override
    public int getOrder() {
        // Run after ContextPropagation wrapper so we shield the final returned Future.
        // (ExecutorServiceContextPropagationPostProcessor uses LOWEST_PRECEDENCE - 100)
        return Ordered.LOWEST_PRECEDENCE - 50;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!(bean instanceof ExecutorService exec)) {
            return bean;
        }

        boolean enabled = Boolean.parseBoolean(env.getProperty(
                "nova.orch.interrupt-hygiene.cancel-shield.enabled", "true"));
        if (!enabled) {
            return bean;
        }

        Set<String> allow = allowNames;
        if (allow == null) {
            allow = parseAllowNames(env.getProperty(
                    "nova.orch.interrupt-hygiene.cancel-shield.allow-names",
                    "searchIoExecutor,llmFastExecutor"));
            allowNames = allow;
        }

        if (!allow.contains(beanName)) {
            return bean;
        }

        if (bean instanceof CancelShieldExecutorService) {
            return bean;
        }

        Integer maxInflight = bulkMaxInflight;
        if (maxInflight == null) {
            maxInflight = parseInt(env.getProperty(
                    "nova.orch.interrupt-hygiene.cancel-shield.bulk.max-inflight", "8"), 8);
            // Keep within the guardrails of CancelShieldExecutorService.
            if (maxInflight < 1) {
                maxInflight = 1;
            }
            if (maxInflight > 1024) {
                maxInflight = 1024;
            }
            bulkMaxInflight = maxInflight;
        }

        CancelShieldExecutorService wrapped = new CancelShieldExecutorService(exec, beanName, maxInflight);
        log.info("[Nova] CancelShield enabled for ExecutorService bean '{}'", beanName);

        try {
            DebugEventStore store = debugEventStoreProvider.getIfAvailable();
            if (store != null) {
                store.emit(DebugProbeType.EXECUTOR, DebugEventLevel.INFO,
                        "cancelShield.enabled",
                        "[Nova] CancelShield enabled for executor",
                        "CancelShieldExecutorServicePostProcessor",
                        Map.of("beanName", beanName, "bulk.maxInflight", maxInflight),
                        null);
            }
        } catch (Throwable ignore) {
            // fail-soft
        }

        return wrapped;
    }

    private static int parseInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException nfe) {
            return fallback;
        }
    }

    private static Set<String> parseAllowNames(String csv) {
        Set<String> out = new HashSet<>();
        if (csv == null || csv.isBlank()) {
            return out;
        }
        Arrays.stream(csv.split(","))
                .map(s -> s == null ? "" : s.trim())
                .filter(s -> !s.isEmpty())
                .forEach(out::add);
        return out;
    }

}
