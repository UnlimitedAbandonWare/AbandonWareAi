package ai.abandonware.nova.boot.reactor;

import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.infra.resilience.FaultMaskingLayerMonitor;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.LogCorrelation;
import com.example.lms.trace.SafeRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Hooks;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CancellationException;

/**
 * Global Reactor hook for {@code onErrorDropped}.
 *
 * <p>
 * When reactive chains are cancelled (e.g., Future.cancel(true) / timeout budget),
 * WebClient/Netty may emit late errors that Reactor would otherwise route to
 * the default {@code onErrorDropped} logger.
 *
 * <p>
 * This hook:
 * <ul>
 *   <li>classifies & suppresses known cancellation-noise ("body released due to cancellation")</li>
 *   <li>records trace counters for ops/CI</li>
 *   <li>best-effort logs error bodies when the dropped error contains them (e.g. WebClientResponseException)</li>
 *   <li>optionally reports into {@link FaultMaskingLayerMonitor} as a masked fault</li>
 * </ul>
 */
public class NovaReactorDroppedErrorHook implements InitializingBean, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(NovaReactorDroppedErrorHook.class);

    private final Environment env;
    private final ObjectProvider<FaultMaskingLayerMonitor> faultMaskMonitorProvider;
    private final ObjectProvider<DebugEventStore> debugEventStoreProvider;

    private volatile boolean installed;

    public NovaReactorDroppedErrorHook(
            Environment env,
            ObjectProvider<FaultMaskingLayerMonitor> faultMaskMonitorProvider,
            ObjectProvider<DebugEventStore> debugEventStoreProvider) {
        this.env = env;
        this.faultMaskMonitorProvider = faultMaskMonitorProvider;
        this.debugEventStoreProvider = debugEventStoreProvider;
    }

    @Override
    public void afterPropertiesSet() {
        boolean enabled = Boolean.parseBoolean(env.getProperty(
                "nova.orch.debug.reactor-onErrorDropped.enabled", "true"));
        if (!enabled) {
            log.info("[Nova] Reactor onErrorDropped hook disabled (nova.orch.debug.reactor-onErrorDropped.enabled=false)");
            return;
        }

        int maxChars = parseInt(env.getProperty(
                "nova.orch.debug.reactor-onErrorDropped.max-body-chars", "768"), 768);
        boolean logBodies = Boolean.parseBoolean(env.getProperty(
                "nova.orch.debug.reactor-onErrorDropped.log-body", "true"));

        Hooks.onErrorDropped(t -> handleDroppedError(t, maxChars, logBodies));
        installed = true;
        log.info("[Nova] Reactor onErrorDropped hook installed (maxBodyChars={}, logBody={})", maxChars, logBodies);
    }

    @Override
    public void destroy() {
        if (!installed) {
            return;
        }
        try {
            Hooks.resetOnErrorDropped();
            log.info("[Nova] Reactor onErrorDropped hook reset");
        } catch (Throwable t) {
            log.debug("[Nova] Reactor onErrorDropped reset failed: {}", t.toString());
        }
    }

    private void handleDroppedError(Throwable t, int maxChars, boolean logBodies) {
        if (t == null) {
            return;
        }

        // -------- classify (cheap string ops first) --------
        String msg = safeMsg(t);
        String cls = t.getClass().getSimpleName();

        boolean isCancel = (t instanceof CancellationException)
                || (msg != null && msg.toLowerCase(java.util.Locale.ROOT).contains("cancel"));

        boolean bodyReleased = isBodyReleasedDueToCancellation(t, msg);

        // -------- trace / metrics (fail-soft) --------
        try {
            TraceStore.inc("reactor.onErrorDropped.count");
            TraceStore.put("reactor.onErrorDropped.last", cls);
            if (isCancel) {
                TraceStore.inc("reactor.onErrorDropped.cancel.count");
            }
            if (bodyReleased) {
                TraceStore.inc("reactor.onErrorDropped.bodyReleased.count");
            }
        } catch (Throwable ignore) {
            // best-effort
        }

        // -------- FaultMask integration (throttled by TraceStore once-per-request) --------
        try {
            Object prev = TraceStore.putIfAbsent("reactor.onErrorDropped.faultMaskOnce", Boolean.TRUE);
            if (prev == null) {
                FaultMaskingLayerMonitor monitor = faultMaskMonitorProvider.getIfAvailable();
                if (monitor != null) {
                    String note = bodyReleased ? "body-released-due-to-cancellation" : (isCancel ? "cancel" : "dropped");
                    monitor.record("reactor.onErrorDropped", t, note);
                }
            }
        } catch (Throwable ignore) {
            // fail-soft
        }

        // -------- best-effort body logging for WebClientResponseException --------
        if (logBodies && t instanceof WebClientResponseException wcre) {
            String body = safeBodyString(wcre, maxChars);
            // Log only once per request to avoid spam.
            boolean first = true;
            try {
                first = TraceStore.putIfAbsent("reactor.onErrorDropped.wcreOnce", Boolean.TRUE) == null;
            } catch (Throwable ignore) {
                // if no TraceStore, still log once-ish
            }

            if (first) {
                log.warn("[Nova] onErrorDropped(WebClientResponseException): status={} uri={} body={}{}",
                        wcre.getRawStatusCode(), safeUri(wcre), body, LogCorrelation.suffix());
                emitDebugEvent("onErrorDropped.wcre", Map.of(
                        "status", wcre.getRawStatusCode(),
                        "uri", safeUri(wcre),
                        "bodyPreview", body,
                        "exception", cls));
            }
            return;
        }

        // -------- suppress known noise, keep minimal signal for unknown --------
        if (bodyReleased) {
            // cancellation-noise: keep at debug only
            log.debug("[Nova] onErrorDropped suppressed ({}): {}{}", cls, SafeRedactor.redact(msg), LogCorrelation.suffix());
            return;
        }

        // Unknown dropped errors: warn once per request, debug thereafter.
        boolean first = true;
        try {
            first = TraceStore.putIfAbsent("reactor.onErrorDropped.warnOnce", Boolean.TRUE) == null;
        } catch (Throwable ignore) {
            // best-effort
        }
        if (first) {
            log.warn("[Nova] onErrorDropped: {}: {}{}", cls, SafeRedactor.redact(msg), LogCorrelation.suffix());
        } else {
            log.debug("[Nova] onErrorDropped: {}: {}{}", cls, SafeRedactor.redact(msg), LogCorrelation.suffix());
        }
    }

    private void emitDebugEvent(String key, Map<String, Object> attrs) {
        try {
            DebugEventStore store = debugEventStoreProvider.getIfAvailable();
            if (store == null) {
                return;
            }
            store.emit(DebugProbeType.REACTOR, DebugEventLevel.WARN,
                    key,
                    "[Nova] Reactor dropped error observed",
                    "NovaReactorDroppedErrorHook",
                    attrs,
                    null);
        } catch (Throwable ignore) {
            // fail-soft
        }
    }

    private static boolean isBodyReleasedDueToCancellation(Throwable t, String msg) {
        if (t == null) {
            return false;
        }
        String m = (msg == null) ? "" : msg.toLowerCase(java.util.Locale.ROOT);
        if (m.contains("body") && m.contains("released") && m.contains("cancell")) {
            return true;
        }

        // Sometimes the root cause is wrapped.
        Throwable c = t.getCause();
        if (c != null && c != t) {
            String cm = safeMsg(c);
            return isBodyReleasedDueToCancellation(c, cm);
        }
        return false;
    }

    private static String safeMsg(Throwable t) {
        try {
            return (t == null) ? "" : String.valueOf(t.getMessage());
        } catch (Throwable ignore) {
            return "";
        }
    }

    private static String safeUri(WebClientResponseException wcre) {
        try {
            return (wcre == null || wcre.getRequest() == null || wcre.getRequest().getURI() == null)
                    ? "" : String.valueOf(wcre.getRequest().getURI());
        } catch (Throwable ignore) {
            return "";
        }
    }

    private static String safeBodyString(WebClientResponseException wcre, int maxChars) {
        if (wcre == null) {
            return "";
        }
        try {
            byte[] bytes = wcre.getResponseBodyAsByteArray();
            if (bytes == null || bytes.length == 0) {
                return "";
            }
            String body = new String(bytes, StandardCharsets.UTF_8);
            if (body.length() > maxChars) {
                return body.substring(0, maxChars) + "...(truncated)";
            }
            return body;
        } catch (Throwable ignore) {
            try {
                String body = wcre.getResponseBodyAsString();
                if (body == null) {
                    return "";
                }
                if (body.length() > maxChars) {
                    return body.substring(0, maxChars) + "...(truncated)";
                }
                return body;
            } catch (Throwable ignore2) {
                return "";
            }
        }
    }

    private static int parseInt(String v, int def) {
        if (v == null) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (Throwable ignore) {
            return def;
        }
    }
}
