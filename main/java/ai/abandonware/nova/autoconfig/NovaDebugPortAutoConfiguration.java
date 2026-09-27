package ai.abandonware.nova.autoconfig;

import ai.abandonware.nova.boot.NovaReactorContextPropagationHook;
import ai.abandonware.nova.boot.exec.ExecutorServiceContextPropagationPostProcessor;
import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.AblationContributionTracker;
import com.example.lms.trace.SafeRedactor;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Nova debug/observability port.
 *
 * <p>
 * Focus:
 * <ul>
 * <li>Reactor thread-hop context propagation (MDC/GuardContext/TraceStore)</li>
 * <li>Outbound WebClient correlation injection + MDC bridging</li>
 * </ul>
 * </p>
 */
@AutoConfiguration(afterName = {
        "org.springframework.boot.autoconfigure.web.reactive.function.client.WebClientAutoConfiguration",
        "org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration"
})
@ConditionalOnProperty(name = "nova.orch.debug.port.enabled", havingValue = "true", matchIfMissing = true)
public class NovaDebugPortAutoConfiguration {

    private static final System.Logger LOG = System.getLogger(NovaDebugPortAutoConfiguration.class.getName());

    @Bean
    @ConditionalOnClass(name = "reactor.core.scheduler.Schedulers")
    @ConditionalOnProperty(name = "nova.orch.debug.reactor-context-propagation.enabled", havingValue = "true", matchIfMissing = true)
    public NovaReactorContextPropagationHook novaReactorContextPropagationHook(Environment env,
            DebugEventStore debugEventStore) {
        return new NovaReactorContextPropagationHook(env, debugEventStore);
    }

    @Bean
    @ConditionalOnProperty(name = "nova.orch.debug.executor-context-propagation.enabled", havingValue = "true", matchIfMissing = true)
    public static ExecutorServiceContextPropagationPostProcessor executorServiceContextPropagationPostProcessor(
            Environment env,
            ObjectProvider<DebugEventStore> debugEventStoreProvider) {
        return ExecutorServiceContextPropagationPostProcessor.withLazyDebugEventStore(
                env,
                debugEventStoreProvider::getIfAvailable);
    }




    @Bean
    @ConditionalOnClass(WebClientCustomizer.class)
    @ConditionalOnProperty(name = "nova.orch.debug.webclient-correlation.enabled", havingValue = "true", matchIfMissing = true)
    public WebClientCustomizer novaWebClientCorrelationCustomizer(ObjectProvider<DebugEventStore> debugStoreProvider,
            Environment env) {
        boolean generateMissingIds = Boolean.parseBoolean(env.getProperty(
                "nova.orch.debug.webclient-correlation.generate-missing-ids", "true"));
        double penaltyDelta = parseDouble(env.getProperty(
                "nova.orch.debug.webclient-correlation.penalty.delta", "0.03"), 0.03);

        return builder -> builder
                .filter(correlationInjectionFilter(debugStoreProvider, generateMissingIds, penaltyDelta))
                .filter(mdcBridgeFilter(debugStoreProvider, penaltyDelta));
    }

    private static ExchangeFilterFunction correlationInjectionFilter(
            ObjectProvider<DebugEventStore> debugStoreProvider,
            boolean generateMissingIds,
            double penaltyDelta) {
        final String HDR_REQUEST_ID = "X-Request-Id";
        final String HDR_SESSION_ID = "X-Session-Id";
        final String onceKey = "ablation.ctx.correlation.missing";

        return new ExchangeFilterFunction() {
            @Override
            public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
                String existingRid = request.headers().getFirst(HDR_REQUEST_ID);
                String existingSid = request.headers().getFirst(HDR_SESSION_ID);
                boolean hasRid = hasText(existingRid);
                boolean hasSid = hasText(existingSid);

                // Resolve from context only if missing.
                String rid = hasRid ? existingRid : resolveRequestIdFromContext();
                String sid = hasSid ? existingSid : resolveSessionIdFromContext();

                boolean missing = !hasText(rid) || !hasText(sid);
                boolean generated = false;

                if (missing && generateMissingIds) {
                    if (!hasText(rid)) {
                        rid = "rid-missing-" + UUID.randomUUID();
                        generated = true;
                    }
                    if (!hasText(sid)) {
                        sid = "sid-missing-" + UUID.randomUUID();
                        generated = true;
                    }
                }

                // If still nothing (and not generating), just proceed.
                if (!hasText(rid) && !hasText(sid)) {
                    return next.exchange(request);
                }

                if (!hasRid || !hasSid) {
                    ClientRequest.Builder b = ClientRequest.from(request);
                    String _rid = rid;
                    String _sid = sid;
                    b.headers(h -> {
                        if (!hasRid && hasText(_rid)) {
                            h.remove(HDR_REQUEST_ID);
                            h.add(HDR_REQUEST_ID, _rid);
                        }
                        if (!hasSid && hasText(_sid)) {
                            h.remove(HDR_SESSION_ID);
                            h.add(HDR_SESSION_ID, _sid);
                        }
                    });

                    if (missing || generated) {
                        // Mark in TraceStore for later debugging.
                        try {
                            TraceStore.put("ctx.correlation.missing", true);
                            TraceStore.put("ctx.correlation.missing.reason", "webclient.headers.missing");
                            if (generated) {
                                TraceStore.put("ctx.correlation.generated", true);
                            }

                            // Backward/forward compatible alias used by TraceHtmlBuilder risk bump.
                            TraceStore.put("ctx.propagation.missing", true);
                            TraceStore.put("ctx.propagation.missing.webclient", true);
                            TraceStore.put("ctx.propagation.missing.reason", "webclient.headers.missing");
                            TraceStore.inc("ctx.propagation.missing.count");
                            if (generated) {
                                TraceStore.put("ctx.propagation.generated", true);
                            }
                        } catch (Throwable traceError) {
                            logSuppressed("correlation.trace.flags", traceError);
                        }

                        // Append compact per-request event (for Trace UI timeline/table).
                        try {
                            Map<String, Object> ev = new LinkedHashMap<>();
                            ev.put("seq", TraceStore.nextSequence("ctx.propagation.missing.events"));
                            ev.put("ts", java.time.Instant.now().toString());
                            ev.put("kind", generated ? "generated" : "missing");
                            ev.put("where", "webclient.correlationInjection");
                            ev.put("source", "NovaDebugPortAutoConfiguration.correlationInjectionFilter");
                            ev.put("generated", generated);
                            ev.put("missing", missing);
                            ev.put("hasExistingRid", hasRid);
                            ev.put("hasExistingSid", hasSid);
                            ev.put("rid", SafeRedactor.hashValue(_rid));
                            ev.put("sid", SafeRedactor.hashValue(_sid));
                            ev.put("method", String.valueOf(request.method()));
                            putUrlDiagnostics(ev, request);
                            TraceStore.append("ctx.propagation.missing.events", ev);
                        } catch (Throwable traceError) {
                            logSuppressed("correlation.trace.event", traceError);
                        }

                        // UAW/ablation penalty (once).
                        try {
                            AblationContributionTracker.recordPenaltyOnce(
                                    onceKey,
                                    "context",
                                    generated ? "generated-correlation" : "missing-correlation",
                                    penaltyDelta,
                                    generated ? "webclient.generatedIds" : "webclient.missingIds");
                        } catch (Throwable traceError) {
                            logSuppressed("correlation.ablation.penalty", traceError);
                        }

                        // Structured DebugEvent (JSON)
                        DebugEventStore debug = resolveDebugEventStore(debugStoreProvider);
                        if (debug != null) {
                            try {
                                Map<String, Object> extra = new LinkedHashMap<>();
                                extra.put("generated", generated);
                                extra.put("missing", missing);
                                extra.put("rid", SafeRedactor.hashValue(_rid));
                                extra.put("sid", SafeRedactor.hashValue(_sid));
                                putUrlDiagnostics(extra, request);
                                extra.put("method", String.valueOf(request.method()));
                                extra.put("hasExistingRid", hasRid);
                                extra.put("hasExistingSid", hasSid);
                                debug.emit(
                                        DebugProbeType.CONTEXT_PROPAGATION,
                                        DebugEventLevel.WARN,
                                        "webclient.correlation.missing",
                                        "WebClient outbound correlation headers were missing; injected (or generated) ids.",
                                        "NovaDebugPortAutoConfiguration.correlationInjectionFilter",
                                        extra,
                                        null);
                            } catch (Throwable failure) {
                                recordDebugEventEmitFailure(failure);
                            }
                        }
                    }

                    return next.exchange(b.build());
                }

                return next.exchange(request);
            }
        };
    }

    private static ExchangeFilterFunction mdcBridgeFilter(ObjectProvider<DebugEventStore> debugStoreProvider,
            double penaltyDelta) {
        final String HDR_REQUEST_ID = "X-Request-Id";
        final String HDR_SESSION_ID = "X-Session-Id";
        final String onceKey = "ablation.ctx.correlation.missing";

        return (request, next) -> Mono.fromDirect(subscriber -> {
            String rid = request.headers().getFirst(HDR_REQUEST_ID);
            String sid = request.headers().getFirst(HDR_SESSION_ID);
            if (!hasText(rid) && !hasText(sid)) {
                Mono.defer(() -> next.exchange(request)).subscribe(subscriber);
                return;
            }

            // Detect "MDC missing but headers exist" -> context propagation leak signal.
            boolean mdcMissing = false;
            String mdcRid = firstNonBlank(MDC.get("x-request-id"), MDC.get("trace"));
            String mdcSid = firstNonBlank(MDC.get("sid"), MDC.get("sessionId"));
            if (hasText(rid) && !hasText(mdcRid))
                mdcMissing = true;
            if (hasText(sid) && !hasText(mdcSid))
                mdcMissing = true;

            Map<String, String> prev = MDC.getCopyOfContextMap();
            boolean changed = false;
            try {
                if (hasText(rid)) {
                    changed |= putIfBlank("x-request-id", rid);
                    changed |= putIfBlank("trace", rid);
                    changed |= putIfBlank("traceId", rid);
                    try {
                        TraceStore.putIfAbsent("trace.id", SafeRedactor.hashValue(rid));
                    } catch (Throwable traceError) {
                        logSuppressed("mdc.traceId", traceError);
                    }
                }
                if (hasText(sid)) {
                    changed |= putIfBlank("sid", sid);
                    changed |= putIfBlank("sessionId", sid);
                    try {
                        TraceStore.putIfAbsent("sid", SafeRedactor.hashValue(sid));
                    } catch (Throwable traceError) {
                        logSuppressed("mdc.sid", traceError);
                    }
                }
            } catch (Throwable traceError) {
                logSuppressed("mdc.putIfBlank", traceError);
            }

            if (mdcMissing) {
                try {
                    TraceStore.put("ctx.mdc.bridge", true);
                    TraceStore.put("ctx.mdc.bridge.rid", SafeRedactor.hashValue(rid));
                    TraceStore.put("ctx.mdc.bridge.sid", SafeRedactor.hashValue(sid));

                    // Also mark as a propagation leak anchor so the Trace UI can bump risk.
                    TraceStore.put("ctx.propagation.missing", true);
                    TraceStore.put("ctx.propagation.missing.mdcBridge", true);
                    TraceStore.inc("ctx.propagation.missing.count");
                } catch (Throwable traceError) {
                    logSuppressed("mdc.bridge.trace.flags", traceError);
                }

                // Append compact per-request event (for Trace UI timeline/table).
                try {
                    Map<String, Object> ev = new LinkedHashMap<>();
                    ev.put("seq", TraceStore.nextSequence("ctx.propagation.missing.events"));
                    ev.put("ts", java.time.Instant.now().toString());
                    ev.put("kind", "mdcBridge");
                    ev.put("where", "webclient.mdcBridge");
                    ev.put("source", "NovaDebugPortAutoConfiguration.mdcBridgeFilter");
                    ev.put("rid", SafeRedactor.hashValue(rid));
                    ev.put("sid", SafeRedactor.hashValue(sid));
                    ev.put("method", String.valueOf(request.method()));
                    putUrlDiagnostics(ev, request);
                    TraceStore.append("ctx.propagation.missing.events", ev);
                } catch (Throwable traceError) {
                    logSuppressed("mdc.bridge.trace.event", traceError);
                }

                try {
                    AblationContributionTracker.recordPenaltyOnce(
                            onceKey,
                            "context",
                            "mdc-bridge",
                            penaltyDelta,
                            "webclient.mdcBridge");
                } catch (Throwable traceError) {
                    logSuppressed("mdc.bridge.ablation.penalty", traceError);
                }

                DebugEventStore debug = resolveDebugEventStore(debugStoreProvider);
                if (debug != null) {
                    try {
                        Map<String, Object> extra = new LinkedHashMap<>();
                        extra.put("rid", SafeRedactor.hashValue(rid));
                        extra.put("sid", SafeRedactor.hashValue(sid));
                        putUrlDiagnostics(extra, request);
                        extra.put("method", String.valueOf(request.method()));
                        extra.put("phase", "mdcBridge");
                        debug.emit(
                                DebugProbeType.CONTEXT_PROPAGATION,
                                DebugEventLevel.WARN,
                                "webclient.mdc.bridge",
                                "MDC was missing correlation ids while outbound request already had them; bridged MDC from headers.",
                                "NovaDebugPortAutoConfiguration.mdcBridgeFilter",
                                extra,
                                null);
                    } catch (Throwable failure) {
                        recordDebugEventEmitFailure(failure);
                    }
                }
            }

            try {
                Mono.defer(() -> next.exchange(request)).subscribe(subscriber);
            } finally {
                if (changed) {
                    restoreMdc(prev);
                }
            }
        });
    }

    private static String resolveRequestIdFromContext() {
        String rid = firstNonBlank(MDC.get("x-request-id"), MDC.get("trace"));
        if (hasText(rid))
            return rid;
        try {
            Object v = TraceStore.get("x-request-id");
            if (v != null && hasText(String.valueOf(v)))
                return String.valueOf(v);
        } catch (Throwable traceError) {
            logSuppressed("resolveRequestId.xRequestId", traceError);
        }
        try {
            Object v = TraceStore.get("trace.id");
            if (v != null && hasText(String.valueOf(v)))
                return String.valueOf(v);
        } catch (Throwable traceError) {
            logSuppressed("resolveRequestId.traceId", traceError);
        }
        try {
            Object v = TraceStore.get("trace");
            if (v != null && hasText(String.valueOf(v)))
                return String.valueOf(v);
        } catch (Throwable traceError) {
            logSuppressed("resolveRequestId.trace", traceError);
        }
        return null;
    }

    private static String resolveSessionIdFromContext() {
        String sid = firstNonBlank(MDC.get("sid"), MDC.get("sessionId"));
        if (hasText(sid))
            return sid;
        try {
            Object v = TraceStore.get("sid");
            if (v != null && hasText(String.valueOf(v)))
                return String.valueOf(v);
        } catch (Throwable traceError) {
            logSuppressed("resolveSessionId.sid", traceError);
        }
        try {
            Object v = TraceStore.get("sessionId");
            if (v != null && hasText(String.valueOf(v)))
                return String.valueOf(v);
        } catch (Throwable traceError) {
            logSuppressed("resolveSessionId.sessionId", traceError);
        }
        return null;
    }

    private static boolean putIfBlank(String k, String v) {
        if (!hasText(v))
            return false;
        String cur = MDC.get(k);
        if (!hasText(cur)) {
            MDC.put(k, v);
            return true;
        }
        return false;
    }

    private static void putUrlDiagnostics(Map<String, Object> target, ClientRequest request) {
        if (target == null) {
            return;
        }
        java.net.URI uri = request == null ? null : request.url();
        String raw = uri == null ? null : String.valueOf(uri);
        String host = uri == null ? null : uri.getHost();
        target.put("urlHost", host == null ? "" : host);
        target.put("urlHash", SafeRedactor.hashValue(raw));
        target.put("urlLength", raw == null ? 0 : raw.length());
    }

    private static void recordDebugEventEmitFailure(Throwable failure) {
        try {
            TraceStore.inc("ctx.debugPort.debugEvent.emit.failed");
            if (failure != null) {
                TraceStore.put("ctx.debugPort.debugEvent.emit.failureClass",
                        "debug_event_emit_failed");
            }
        } catch (Throwable traceError) {
            logSuppressed("debugEvent.emit.failureTrace", traceError);
        }
    }

    private static DebugEventStore resolveDebugEventStore(ObjectProvider<DebugEventStore> debugStoreProvider) {
        if (debugStoreProvider == null) {
            return null;
        }
        try {
            return debugStoreProvider.getIfAvailable();
        } catch (Throwable failure) {
            recordDebugEventResolveFailure(failure);
            return null;
        }
    }

    private static void recordDebugEventResolveFailure(Throwable failure) {
        try {
            TraceStore.inc("ctx.debugPort.debugEvent.resolve.failed");
            if (failure != null) {
                TraceStore.put("ctx.debugPort.debugEvent.resolve.failureClass",
                        "debug_event_resolve_failed");
            }
        } catch (Throwable traceError) {
            logSuppressed("debugEvent.resolve.failureTrace", traceError);
        }
    }

    private static void restoreMdc(Map<String, String> prev) {
        try {
            MDC.clear();
            if (prev != null && !prev.isEmpty()) {
                MDC.setContextMap(prev);
            }
        } catch (Throwable traceError) {
            logSuppressed("mdc.restore", traceError);
        }
    }

    private static void logSuppressed(String stage) {
        logSuppressed(stage, null);
    }

    private static void logSuppressed(String stage, Throwable error) {
        String safeStage = safeStage(stage);
        String errorType = errorType(error);
        try {
            TraceStore.inc("ctx.debugPort.suppressed.count");
            TraceStore.put("ctx.debugPort.suppressed.stage", safeStage);
            TraceStore.put("ctx.debugPort.suppressed.errorType", errorType);
        } catch (Throwable traceError) {
            LOG.log(System.Logger.Level.DEBUG,
                    "[nova-debug-port] suppressed-trace stage={0} errorType={1}",
                    safeStage,
                    errorType(traceError));
        }
        LOG.log(System.Logger.Level.DEBUG,
                "[nova-debug-port] suppressed stage={0} errorType={1}",
                safeStage,
                errorType);
    }

    private static String errorType(Throwable error) {
        if (error == null) {
            return "none";
        }
        if (error instanceof NumberFormatException) {
            return "invalid_number";
        }
        String label = SafeRedactor.traceLabel(error.getClass().getSimpleName());
        return (label == null || label.isBlank()) ? "unknown" : label;
    }

    private static String safeStage(String stage) {
        String label = SafeRedactor.traceLabel(stage);
        return (label == null || label.isBlank()) ? "unknown" : label;
    }

    private static String firstNonBlank(String... values) {
        if (values == null)
            return null;
        for (String v : values) {
            if (hasText(v))
                return v;
        }
        return null;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static double parseDouble(String raw, double def) {
        try {
            if (raw == null || raw.isBlank())
                return def;
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException parseError) {
            logSuppressed("parseDouble", parseError);
            return def;
        }
    }
}
