package ai.abandonware.nova.boot.exec;

import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.infra.exec.ContextAwareExecutorService;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.env.Environment;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;

/**
 * Wrap ExecutorService/ScheduledExecutorService beans so MDC/GuardContext/TraceStore survive
 * thread-hops even when call-sites forget to use ContextPropagation wrappers.
 *
 * <p>Config:
 * <ul>
 *   <li>{@code nova.orch.debug.executor-context-propagation.ignore-names} (CSV)</li>
 *   <li>{@code nova.orch.debug.executor-context-propagation.wrap-all} (default: true)</li>
 *   <li>{@code nova.orch.debug.executor-context-propagation.allow-names} (CSV; used when wrap-all=false)</li>
 * </ul>
 */
public class ExecutorServiceContextPropagationPostProcessor implements BeanPostProcessor, PriorityOrdered {

    private static final System.Logger LOG =
            System.getLogger(ExecutorServiceContextPropagationPostProcessor.class.getName());

    private final Environment env;
    private final Supplier<DebugEventStore> debugEventStoreSupplier;

    private final Set<String> ignoreNames;
    private final boolean wrapAll;
    private final Set<String> allowNames;

    public static ExecutorServiceContextPropagationPostProcessor withLazyDebugEventStore(
            Environment env,
            Supplier<DebugEventStore> debugEventStoreSupplier) {
        return new ExecutorServiceContextPropagationPostProcessor(env, debugEventStoreSupplier);
    }

    public ExecutorServiceContextPropagationPostProcessor(Environment env, @Nullable DebugEventStore debugEventStore) {
        this(env, () -> debugEventStore);
    }

    private ExecutorServiceContextPropagationPostProcessor(Environment env, Supplier<DebugEventStore> debugEventStoreSupplier) {
        this.env = env;
        this.debugEventStoreSupplier = debugEventStoreSupplier == null ? () -> null : debugEventStoreSupplier;

        this.ignoreNames = parseCsv(env.getProperty("nova.orch.debug.executor-context-propagation.ignore-names", ""));
        this.wrapAll = env.getProperty("nova.orch.debug.executor-context-propagation.wrap-all", Boolean.class, Boolean.TRUE);
        this.allowNames = parseCsv(env.getProperty(
                "nova.orch.debug.executor-context-propagation.allow-names",
                "searchIoExecutor,applicationTaskExecutor,taskExecutor,taskScheduler,uawAutolearnTaskScheduler"));
    }

    @Override
    public int getOrder() {
        // late enough that pools are fully initialised, early enough to wrap before most beans use them
        return Ordered.LOWEST_PRECEDENCE - 100;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean == null) {
            return null;
        }

        if (beanName != null && ignoreNames.contains(beanName)) {
            return bean;
        }

        if (!wrapAll && beanName != null && !allowNames.isEmpty() && !allowNames.contains(beanName)) {
            return bean;
        }

        // Already wrapped or already does propagation.
        if (bean instanceof ContextPropagatingExecutorService
                || bean instanceof ContextPropagatingScheduledExecutorService
                || bean instanceof ContextAwareExecutorService) {
            return bean;
        }

        try {
            if (bean instanceof ScheduledExecutorService ses) {
                recordWrap(beanName, bean.getClass().getName(), "scheduled");
                return new ContextPropagatingScheduledExecutorService(ses);
            }
            if (bean instanceof ExecutorService es) {
                recordWrap(beanName, bean.getClass().getName(), "executor");
                return new ContextPropagatingExecutorService(es);
            }
        } catch (Throwable t) {
            // fail-soft: never block app boot
            recordError(beanName, t);
            return bean;
        }

        return bean;
    }

    private void recordWrap(String beanName, String clazz, String kind) {
        try {
            String beanHash = SafeRedactor.hashValue(beanName);
            TraceStore.put("ctx.exec.propagation.enabled", true);
            TraceStore.inc("ctx.exec.propagation.wrap.count");
            TraceStore.put("ctx.exec.propagation.wrap.beanHash", beanHash);
            TraceStore.put("ctx.exec.propagation.wrap.beanLength", lengthOf(beanName));
            TraceStore.put("ctx.exec.propagation.wrap.kind", kind);
        } catch (Throwable traceError) {
            traceSkipped("wrap_trace", traceError);
        }

        DebugEventStore debugEventStore = resolveDebugEventStore();
        if (debugEventStore != null) {
            try {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("beanHash", SafeRedactor.hashValue(beanName));
                data.put("beanLength", lengthOf(beanName));
                data.put("classHash", SafeRedactor.hashValue(clazz));
                data.put("classLength", lengthOf(clazz));
                data.put("kind", kind);
                data.put("wrapAll", wrapAll);
                data.put("allowNamesCount", allowNames.size());
                data.put("ignoreNamesCount", ignoreNames.size());
                debugEventStore.emit(
                        DebugProbeType.CONTEXT_PROPAGATION,
                        DebugEventLevel.INFO,
                        "executor.context-propagation.wrap",
                        "Wrapped ExecutorService for context propagation",
                        "ExecutorServiceContextPropagationPostProcessor",
                        data,
                        null);
            } catch (Throwable ignore) {
                try {
                    TraceStore.inc("ctx.exec.propagation.debugEvent.emit.failed");
                    TraceStore.put("ctx.exec.propagation.debugEvent.emit.failureClass",
                            "ctx_exec_propagation_debug_event_emit_failed");
                } catch (RuntimeException traceFailure) {
                    traceSkipped("debug_event_failure_trace", traceFailure);
                }
            }
        }
    }

    @Nullable
    private DebugEventStore resolveDebugEventStore() {
        try {
            return debugEventStoreSupplier.get();
        } catch (Throwable lookupError) {
            traceSkipped("debug_event_store_lookup", lookupError);
            return null;
        }
    }

    private void recordError(String beanName, Throwable t) {
        try {
            TraceStore.put("ctx.exec.propagation.error", true);
            TraceStore.put("ctx.exec.propagation.error.beanHash", SafeRedactor.hashValue(beanName));
            TraceStore.put("ctx.exec.propagation.error.beanLength", lengthOf(beanName));
            TraceStore.put("ctx.exec.propagation.error.type", t.getClass().getName());
        } catch (Throwable traceError) {
            traceSkipped("error_trace", traceError);
        }
    }

    private static void traceSkipped(String stage, Throwable error) {
        LOG.log(System.Logger.Level.DEBUG,
                "Executor context propagation telemetry skipped stage=" + stage
                        + " errorType=" + errorType(error));
    }

    private static String errorType(Throwable error) {
        return error == null ? "unknown" : error.getClass().getSimpleName();
    }

    private static int lengthOf(String value) {
        return value == null ? 0 : value.length();
    }

    private static Set<String> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        Set<String> out = new HashSet<>();
        for (String s : csv.split(",")) {
            if (s == null) {
                continue;
            }
            String v = s.trim();
            if (!v.isBlank()) {
                out.add(v);
            }
        }
        return out;
    }
}
