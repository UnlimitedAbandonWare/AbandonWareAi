package ai.abandonware.nova.boot;

import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.infra.exec.ContextPropagation;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

/**
 * Reactor Scheduler hook to propagate MDC + GuardContext + TraceStore across reactive thread hops.
 *
 * <p>
 * We intentionally keep it lightweight and only wrap scheduled tasks when there is
 * something meaningful to propagate (rid/sid/trace or GuardContext already installed).
 * </p>
 */
public class NovaReactorContextPropagationHook implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(NovaReactorContextPropagationHook.class);

    private static final String HOOK_KEY = "nova-mdc-guard-trace";

    private final Environment env;
    private final DebugEventStore debugEventStore;

    public NovaReactorContextPropagationHook(Environment env, DebugEventStore debugEventStore) {
        this.env = env;
        this.debugEventStore = debugEventStore;
    }

    @Override
    public void afterPropertiesSet() {
        boolean enabled = Boolean.parseBoolean(env.getProperty(
                "nova.orch.debug.reactor-context-propagation.enabled", "true"));
        if (!enabled) {
            log.info("[Nova] Reactor context propagation hook disabled (nova.orch.debug.reactor-context-propagation.enabled=false)");
            return;
        }

        // Register a global hook (keyed) so we can reset on shutdown.
        Schedulers.onScheduleHook(HOOK_KEY, runnable -> {
            try {
                if (!hasAnyContext()) {
                    return runnable;
                }
            } catch (Throwable ignore) {
                // If we cannot evaluate, prefer safety: wrap.
            }
            return ContextPropagation.wrap(runnable);
        });

        log.info("[Nova] Reactor context propagation hook enabled (key={})", HOOK_KEY);
        try {
            debugEventStore.emit(DebugProbeType.CONTEXT_PROPAGATION, DebugEventLevel.INFO,
                    "reactor.scheduleHook.enabled",
                    "[Nova] Reactor schedule hook enabled (MDC/GuardContext/TraceStore propagation)",
                    "NovaReactorContextPropagationHook.afterPropertiesSet",
                    Map.of("hookKey", HOOK_KEY),
                    null);
        } catch (Throwable ignore) {
            // fail-soft
        }
    }

    @Override
    public void destroy() {
        try {
            Schedulers.resetOnScheduleHook(HOOK_KEY);
            log.info("[Nova] Reactor context propagation hook reset (key={})", HOOK_KEY);
        } catch (Throwable t) {
            log.debug("[Nova] Reactor schedule hook reset failed (key={}): {}", HOOK_KEY, t.toString());
        }
    }

    private boolean hasAnyContext() {
        // 1) MDC
        String trace = MDC.get("trace");
        String rid = MDC.get("x-request-id");
        String sid = MDC.get("sid");
        String sessionId = MDC.get("sessionId");
        if (hasText(trace) || hasText(rid) || hasText(sid) || hasText(sessionId)) {
            return true;
        }

        // 2) TraceStore (ThreadLocal)
        Object tsTrace = TraceStore.get("trace.id");
        Object tsSid = TraceStore.get("sid");
        if (tsTrace != null || tsSid != null) {
            return true;
        }

        // 3) GuardContext installed
        return GuardContextHolder.get() != null;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
