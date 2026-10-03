package com.example.lms.llm.gateway;

import ai.abandonware.nova.config.LlmRouterProperties;
import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.routing.ApiRoutingDebug;
import com.example.lms.search.TraceStore;
import com.example.lms.telemetry.SseEventPublisher;
import com.example.lms.trace.SafeRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Component
public class LlmGatewayBreadcrumbPublisher {

    private static final Logger log = LoggerFactory.getLogger(LlmGatewayBreadcrumbPublisher.class);

    private final ObjectProvider<SseEventPublisher> ssePublisherProvider;
    private final ObjectProvider<LlmRouterProperties> routerPropsProvider;
    private final ObjectProvider<DebugEventStore> debugEventStoreProvider;

    public LlmGatewayBreadcrumbPublisher(ObjectProvider<SseEventPublisher> ssePublisherProvider) {
        this(ssePublisherProvider, null, null);
    }

    public LlmGatewayBreadcrumbPublisher(ObjectProvider<SseEventPublisher> ssePublisherProvider,
            ObjectProvider<LlmRouterProperties> routerPropsProvider) {
        this(ssePublisherProvider, routerPropsProvider, null);
    }

    @Autowired
    public LlmGatewayBreadcrumbPublisher(ObjectProvider<SseEventPublisher> ssePublisherProvider,
            ObjectProvider<LlmRouterProperties> routerPropsProvider,
            ObjectProvider<DebugEventStore> debugEventStoreProvider) {
        this.ssePublisherProvider = ssePublisherProvider;
        this.routerPropsProvider = routerPropsProvider;
        this.debugEventStoreProvider = debugEventStoreProvider;
    }

    public void publishEligibility(RoutingEligibility eligibility) {
        if (eligibility == null) {
            return;
        }
        Map<String, Object> payload = safePayload(eligibility.asBreadcrumb());
        trace("llm.gateway.route.", payload);
        emit("llm.gateway.route", payload);
    }

    public void publishFallback(String fromKey, String toKey, LlmFailureClass failureClass, String reason) {
        LlmRouterProperties.ModelConfig from = routeConfig(fromKey);
        LlmRouterProperties.ModelConfig to = routeConfig(toKey);
        String provider = providerOf(from);
        String fallbackTo = providerOf(to);
        String errorClass = errorClass(failureClass);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fromKey", fromKey);
        payload.put("toKey", toKey);
        payload.put("provider", provider);
        payload.put("failureClass", failureClass == null ? LlmFailureClass.UNKNOWN.name() : failureClass.name());
        payload.put("errorClass", errorClass);
        payload.put("fallbackProvider", fallbackTo);
        payload.put("reason", reason);
        payload = safePayload(payload);
        trace("llm.gateway.fallback.", payload);
        emit("llm.gateway.fallback", payload);
        ApiRoutingDebug.failure("llm", provider, modelOf(from), endpointClassOf(from), 1, null,
                errorClass, fallbackTo);
        Map<String, Object> route = routeData("llm", provider, modelOf(from), endpointClassOf(from),
                1, null, errorClass, fallbackTo, null, null);
        emitDebugEvent("api.route.decision", DebugEventLevel.WARN,
                "api.route.decision.fallback." + fpLabel(provider), route);
    }

    public void publishFailure(String routeKey, LlmFailureClass failureClass, Throwable failure) {
        LlmRouterProperties.ModelConfig cfg = routeConfig(routeKey);
        String provider = providerOf(cfg);
        String errorClass = errorClass(failureClass);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("routeKey", routeKey);
        payload.put("provider", provider);
        payload.put("failureClass", failureClass == null ? LlmFailureClass.UNKNOWN.name() : failureClass.name());
        payload.put("errorClass", errorClass);
        payload.put("exceptionClass", failure == null ? null : failure.getClass().getSimpleName());
        payload = safePayload(payload);
        trace("llm.gateway.failure.", payload);
        emit("llm.gateway.failure", payload);
        ApiRoutingDebug.failure("llm", provider, modelOf(cfg), endpointClassOf(cfg), 1, null,
                errorClass, null);
        Map<String, Object> route = routeData("llm", provider, modelOf(cfg), endpointClassOf(cfg),
                1, null, errorClass, null, null, null);
        emitDebugEvent("api.route.attempt", DebugEventLevel.WARN,
                "api.route.attempt.failure." + fpLabel(provider), route);
    }

    /**
     * Record an actual route selection in {@link DebugEventStore}. Payload is
     * restricted to the api-routing.yaml debug.fields allowlist; unknown fields
     * are simply omitted.
     */
    public void publishDecision(String purpose, String provider, String model, String endpointClass,
            int attempt, Integer httpStatus, String errorClass, String fallbackTo,
            Boolean keyPresent, String keySource) {
        emitDebugEvent("api.route.decision", DebugEventLevel.INFO,
                "api.route.decision." + fpLabel(provider),
                routeData(purpose, provider, model, endpointClass, attempt, httpStatus,
                        errorClass, fallbackTo, keyPresent, keySource));
    }

    /**
     * Record a spend-guard decision in {@link DebugEventStore}. whyCode must be
     * a value from agent-api-spend-guard.yaml; it is kept on this separate
     * projection and never added to route payloads.
     */
    public void publishSpendDecision(String purpose, String provider, String model,
            String whyCode, boolean allowed) {
        Map<String, Object> data = new LinkedHashMap<>();
        putIfNonNull(data, "purpose", purpose);
        putIfNonNull(data, "provider", provider);
        putIfNonNull(data, "model", model);
        putIfNonNull(data, "why_code", whyCode);
        data.put("allowed", allowed);
        emitDebugEvent("api.spend.decision", allowed ? DebugEventLevel.INFO : DebugEventLevel.WARN,
                "api.spend.decision." + fpLabel(provider) + "." + fpLabel(whyCode), data);
    }

    private static Map<String, Object> routeData(String purpose, String provider, String model,
            String endpointClass, int attempt, Integer httpStatus, String errorClass,
            String fallbackTo, Boolean keyPresent, String keySource) {
        Map<String, Object> data = new LinkedHashMap<>();
        putIfNonNull(data, "purpose", purpose);
        putIfNonNull(data, "provider", provider);
        putIfNonNull(data, "model", model);
        putIfNonNull(data, "endpointClass", endpointClass);
        data.put("attempt", attempt);
        putIfNonNull(data, "httpStatus", httpStatus);
        putIfNonNull(data, "errorClass", errorClass);
        putIfNonNull(data, "fallbackTo", fallbackTo);
        putIfNonNull(data, "keyPresent", keyPresent);
        putIfNonNull(data, "keySource", keySource);
        return data;
    }

    private static void putIfNonNull(Map<String, Object> data, String key, Object value) {
        if (value != null) {
            data.put(key, value);
        }
    }

    private static String fpLabel(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return SafeRedactor.traceLabelOrFallback(value, "unknown");
    }

    private void emitDebugEvent(String where, DebugEventLevel level, String fingerprint,
            Map<String, Object> data) {
        try {
            DebugEventStore store = debugEventStoreProvider == null ? null
                    : debugEventStoreProvider.getIfAvailable();
            if (store == null) {
                return;
            }
            store.emit(DebugProbeType.MODEL_GUARD, level, fingerprint,
                    "[AWX][api-route] " + where + " observed", where, data, null);
        } catch (RuntimeException ex) {
            try {
                TraceStore.put("llm.gateway.debugEvent.suppressed.stage", "debugEvent.emit");
            } catch (Exception ignore) {
                // fail-soft: suppression trace itself must never break callers
            }
            traceSuppressed("debugEvent.emit");
        }
    }

    private LlmRouterProperties.ModelConfig routeConfig(String key) {
        try {
            LlmRouterProperties props = routerPropsProvider == null ? null : routerPropsProvider.getIfAvailable();
            if (props == null || key == null || key.isBlank()) {
                return null;
            }
            return props.getModels().get(key);
        } catch (Exception e) {
            traceSuppressed("route.config");
            return null;
        }
    }

    private static String providerOf(LlmRouterProperties.ModelConfig cfg) {
        if (cfg == null) {
            return null;
        }
        String provider = cfg.getProvider();
        return provider == null || provider.isBlank() ? null : provider.trim().toLowerCase(Locale.ROOT);
    }

    private static String modelOf(LlmRouterProperties.ModelConfig cfg) {
        return cfg == null ? null : cfg.getName();
    }

    private static String endpointClassOf(LlmRouterProperties.ModelConfig cfg) {
        if (cfg == null || cfg.getBaseUrl() == null) {
            return null;
        }
        try {
            java.net.URI uri = java.net.URI.create(cfg.getBaseUrl().trim());
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return null;
            }
            int port = uri.getPort();
            return port > 0 ? host.toLowerCase(Locale.ROOT) + ":" + port : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String errorClass(LlmFailureClass failureClass) {
        return failureClass == null ? "unknown" : failureClass.name().toLowerCase(Locale.ROOT);
    }

    private void emit(String type, Map<String, Object> payload) {
        try {
            SseEventPublisher publisher = ssePublisherProvider == null ? null : ssePublisherProvider.getIfAvailable();
            if (publisher != null) {
                publisher.emit(type, payload);
            }
        } catch (Exception ignore) {
            traceSuppressed("sse.emit");
        }
    }

    private static void trace(String prefix, Map<String, Object> payload) {
        try {
            for (Map.Entry<String, Object> entry : payload.entrySet()) {
                TraceStore.put(prefix + entry.getKey(), entry.getValue());
            }
        } catch (Exception ignore) {
            traceSuppressed("trace.write");
        }
    }

    private static Map<String, Object> safePayload(Map<String, Object> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (raw == null) {
            return out;
        }
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            out.put(entry.getKey(), SafeRedactor.diagnosticValue(entry.getKey(), entry.getValue()));
        }
        return out;
    }

    private static void traceSuppressed(String stage) {
        log.debug("[llm-gateway] suppressed stage={}",
                SafeRedactor.traceLabelOrFallback(stage, "unknown"));
    }
}
