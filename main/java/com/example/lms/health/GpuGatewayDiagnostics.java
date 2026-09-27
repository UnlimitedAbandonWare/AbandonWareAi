package com.example.lms.health;

import com.example.lms.config.ConfigValueGuards;
import com.example.lms.llm.LocalLlmGatewaySecurity;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Redacted diagnostics for the desktop GPU executor gateway.
 */
public final class GpuGatewayDiagnostics {

    private GpuGatewayDiagnostics() {
    }

    public static Map<String, Object> snapshot(Environment env) {
        boolean controlPlane = bool(env, "awx.node.control-plane",
                bool(env, "uaw.autolearn.runtime-node.control-plane", false));
        boolean heavyAllowed = bool(env, "awx.node.heavy-workloads-allowed",
                bool(env, "uaw.autolearn.runtime-node.heavy-workloads-allowed", true));
        boolean enabled = bool(env, "awx.gpu-gateway.enabled", heavyAllowed);
        boolean requireAuth = bool(env, "awx.gpu-gateway.require-auth-for-remote",
                bool(env, "llm.provider-guard.require-auth-for-remote", true));
        String allowedHosts = firstNonBlank(
                prop(env, "awx.gpu-gateway.allowed-hosts", ""),
                prop(env, "llm.provider-guard.allowed-hosts", ""));
        String apiKey = firstNonBlank(
                prop(env, "awx.gpu-gateway.api-key", ""),
                prop(env, "llm.api-key", ""),
                prop(env, "LLM_API_KEY", ""));
        String ownerToken = firstNonBlank(
                prop(env, "awx.gpu-gateway.owner-token", ""),
                prop(env, "llm.owner-token", ""),
                prop(env, "LLM_OWNER_TOKEN", ""));

        String primaryChat = endpointBaseUrl(env, "llm.base-url", "awx.gpu-gateway.primary-chat-base-url", heavyAllowed);
        String fastHelper = endpointBaseUrl(env, "llm.fast.base-url", "awx.gpu-gateway.fast-base-url", heavyAllowed);
        String embedding = endpointBaseUrl(env, "embedding.base-url", "awx.gpu-gateway.embedding-base-url", heavyAllowed);

        Map<String, Object> endpoints = new LinkedHashMap<>();
        endpoints.put("primaryChat", endpoint(primaryChat, "rtx3090", "primary-chat",
                enabled, allowedHosts, requireAuth, apiKey, ownerToken));
        endpoints.put("fastHelper", endpoint(fastHelper, "rtx3060", "fast-helper",
                enabled, allowedHosts, requireAuth, apiKey, ownerToken));
        endpoints.put("embedding", endpoint(embedding, "rtx3060", "embedding",
                enabled, allowedHosts, requireAuth, apiKey, ownerToken));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", enabled);
        out.put("mode", mode(controlPlane, heavyAllowed, enabled));
        out.put("routePolicy", routePolicy(controlPlane, heavyAllowed, enabled));
        out.put("targetRole", prop(env, "awx.gpu-gateway.target-role", "desktop-gpu-executor"));
        out.put("targetExecutionNode", prop(env, "awx.gpu-gateway.target-execution-node", ""));
        out.put("allowedHostsConfigured", !ConfigValueGuards.isMissing(allowedHosts));
        out.put("requireAuthForRemote", requireAuth);
        out.put("hasRemoteAuth", hasRemoteAuth(apiKey, ownerToken));
        out.put("hasOwnerToken", LocalLlmGatewaySecurity.hasUsableRemoteSecret(ownerToken));
        out.put("ownerTokenAttachable", ownerTokenAttachable(List.of(primaryChat, fastHelper, embedding), allowedHosts, ownerToken));
        out.put("endpoints", endpoints);
        out.put("disabledReason", disabledReason(enabled, endpoints));
        return out;
    }

    public static Map<String, Object> preflight(Environment env) {
        Map<String, Object> snapshot = snapshot(env);
        int timeoutMs = intProp(env, "awx.gpu-gateway.preflight.timeout-ms", 750);
        timeoutMs = Math.max(100, Math.min(5_000, timeoutMs));

        String allowedHosts = firstNonBlank(
                prop(env, "awx.gpu-gateway.allowed-hosts", ""),
                prop(env, "llm.provider-guard.allowed-hosts", ""));
        String apiKey = firstNonBlank(
                prop(env, "awx.gpu-gateway.api-key", ""),
                prop(env, "llm.api-key", ""),
                prop(env, "LLM_API_KEY", ""));
        String ownerToken = firstNonBlank(
                prop(env, "awx.gpu-gateway.owner-token", ""),
                prop(env, "llm.owner-token", ""),
                prop(env, "LLM_OWNER_TOKEN", ""));
        String ownerTokenHeader = firstNonBlank(
                prop(env, "awx.gpu-gateway.owner-token-header", ""),
                prop(env, "llm.owner-token-header", ""),
                prop(env, "LLM_OWNER_TOKEN_HEADER", ""),
                LocalLlmGatewaySecurity.DEFAULT_OWNER_TOKEN_HEADER);

        @SuppressWarnings("unchecked")
        Map<String, Object> endpoints = snapshot.get("endpoints") instanceof Map<?, ?> raw
                ? copyMap(raw)
                : Map.of();

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build();

        boolean executorNode = bool(env, "awx.node.heavy-workloads-allowed",
                bool(env, "uaw.autolearn.runtime-node.heavy-workloads-allowed", true));
        Map<String, Object> preflightEndpoints = new LinkedHashMap<>();
        preflightEndpoints.put("primaryChat", preflightEndpoint(
                client,
                "primaryChat",
                endpointBaseUrl(env, "llm.base-url", "awx.gpu-gateway.primary-chat-base-url", executorNode),
                endpointInfo(endpoints, "primaryChat"),
                timeoutMs,
                allowedHosts,
                apiKey,
                ownerToken,
                ownerTokenHeader));
        preflightEndpoints.put("fastHelper", preflightEndpoint(
                client,
                "fastHelper",
                endpointBaseUrl(env, "llm.fast.base-url", "awx.gpu-gateway.fast-base-url", executorNode),
                endpointInfo(endpoints, "fastHelper"),
                timeoutMs,
                allowedHosts,
                apiKey,
                ownerToken,
                ownerTokenHeader));
        preflightEndpoints.put("embedding", preflightEndpoint(
                client,
                "embedding",
                endpointBaseUrl(env, "embedding.base-url", "awx.gpu-gateway.embedding-base-url", executorNode),
                endpointInfo(endpoints, "embedding"),
                timeoutMs,
                allowedHosts,
                apiKey,
                ownerToken,
                ownerTokenHeader));

        int configured = 0;
        int reachable = 0;
        for (Object value : preflightEndpoints.values()) {
            if (!(value instanceof Map<?, ?> map)) {
                continue;
            }
            if (Boolean.TRUE.equals(map.get("configured"))) {
                configured++;
            }
            if (Boolean.TRUE.equals(map.get("reachable"))) {
                reachable++;
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", snapshot.getOrDefault("enabled", false));
        out.put("routePolicy", snapshot.getOrDefault("routePolicy", ""));
        out.put("targetExecutionNode", snapshot.getOrDefault("targetExecutionNode", ""));
        out.put("timeoutMs", timeoutMs);
        out.put("configuredCount", configured);
        out.put("reachableCount", reachable);
        out.put("status", preflightStatus(Boolean.TRUE.equals(snapshot.get("enabled")), configured, reachable));
        out.put("endpoints", preflightEndpoints);
        tracePreflight(out);
        return out;
    }

    /**
     * On the GPU executor node the request-routing properties are the truth and win over the
     * gpu-gateway diagnostics props. On a pure control-plane node the gpu-gateway props describe
     * the remote executor target, which is a different endpoint from this node's own llm.* props.
     */
    private static String endpointBaseUrl(Environment env, String requestProperty, String diagnosticProperty,
                                          boolean executorNode) {
        return executorNode
                ? firstNonBlank(prop(env, requestProperty, ""), prop(env, diagnosticProperty, ""))
                : firstNonBlank(prop(env, diagnosticProperty, ""), prop(env, requestProperty, ""));
    }

    private static Map<String, Object> endpoint(String baseUrl,
                                                String device,
                                                String workload,
                                                boolean gatewayEnabled,
                                                String allowedHosts,
                                                boolean requireAuth,
                                                String apiKey,
                                                String ownerToken) {
        String status = endpointStatus(baseUrl, gatewayEnabled, allowedHosts, requireAuth, apiKey, ownerToken);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("configured", !ConfigValueGuards.isMissing(baseUrl));
        out.put("endpointHost", LocalLlmGatewaySecurity.endpointHost(baseUrl));
        out.put("endpointHostPort", endpointHostPort(baseUrl));
        out.put("device", device);
        out.put("workload", workload);
        out.put("ownerTokenAttachable", "ok".equals(status)
                && LocalLlmGatewaySecurity.hasUsableRemoteSecret(ownerToken)
                && LocalLlmGatewaySecurity.shouldAttachOwnerToken(baseUrl, allowedHosts));
        out.put("status", status);
        return out;
    }

    private static String endpointStatus(String baseUrl,
                                         boolean gatewayEnabled,
                                         String allowedHosts,
                                         boolean requireAuth,
                                         String apiKey,
                                         String ownerToken) {
        if (!gatewayEnabled) {
            return "route_disabled";
        }
        if (ConfigValueGuards.isMissing(baseUrl)) {
            return "missing_endpoint";
        }
        if (LocalLlmGatewaySecurity.isKnownExternalProviderBaseUrl(baseUrl)) {
            return "external_provider_not_gpu_gateway";
        }
        try {
            LocalLlmGatewaySecurity.assertLocalGatewayEndpointAllowed(
                    baseUrl,
                    true,
                    allowedHosts,
                    requireAuth,
                    apiKey,
                    ownerToken);
            return "ok";
        } catch (IllegalStateException ex) {
            traceSuppressed("gpuGateway.guard", ex);
            String message = String.valueOf(ex.getMessage()).toLowerCase(Locale.ROOT);
            if (message.contains("allowlisted")) {
                return "host_not_allowlisted";
            }
            if (message.contains("requires")) {
                return "remote_auth_missing";
            }
            if (message.contains("disabled")) {
                return "private_remote_disabled";
            }
            return "guard_failed";
        }
    }

    private static String disabledReason(boolean enabled, Map<String, Object> endpoints) {
        if (!enabled) {
            return "disabled_by_config";
        }
        for (Object value : endpoints.values()) {
            if (!(value instanceof Map<?, ?> endpoint)) {
                continue;
            }
            Object status = endpoint.get("status");
            if (!"ok".equals(status)) {
                return String.valueOf(status);
            }
        }
        return null;
    }

    private static boolean ownerTokenAttachable(List<String> endpoints, String allowedHosts, String ownerToken) {
        if (!LocalLlmGatewaySecurity.hasUsableRemoteSecret(ownerToken)) {
            return false;
        }
        boolean sawConfigured = false;
        for (String endpoint : endpoints) {
            if (ConfigValueGuards.isMissing(endpoint)) {
                continue;
            }
            sawConfigured = true;
            if (!LocalLlmGatewaySecurity.shouldAttachOwnerToken(endpoint, allowedHosts)) {
                return false;
            }
        }
        return sawConfigured;
    }

    private static boolean hasRemoteAuth(String apiKey, String ownerToken) {
        return LocalLlmGatewaySecurity.hasUsableRemoteSecret(apiKey)
                || LocalLlmGatewaySecurity.hasUsableRemoteSecret(ownerToken);
    }

    private static Map<String, Object> preflightEndpoint(HttpClient client,
                                                         String endpointName,
                                                         String baseUrl,
                                                         Map<String, Object> endpoint,
                                                         int timeoutMs,
                                                         String allowedHosts,
                                                         String apiKey,
                                                         String ownerToken,
                                                         String ownerTokenHeader) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("configured", endpoint.getOrDefault("configured", false));
        out.put("endpointHost", endpoint.getOrDefault("endpointHost", ""));
        out.put("endpointHostPort", endpoint.getOrDefault("endpointHostPort", ""));
        out.put("device", endpoint.getOrDefault("device", ""));
        out.put("workload", endpoint.getOrDefault("workload", ""));
        out.put("guardStatus", endpoint.getOrDefault("status", ""));
        out.put("reachable", false);

        String guardStatus = String.valueOf(endpoint.getOrDefault("status", ""));
        if (!"ok".equals(guardStatus)) {
            out.put("status", "skipped_" + (guardStatus.isBlank() ? "guard_failed" : guardStatus));
            return out;
        }

        URI uri = preflightUri(endpointName, baseUrl);
        if (uri == null) {
            out.put("status", "unsupported_endpoint");
            return out;
        }
        out.put("checkPath", safePath(uri));

        long start = System.nanoTime();
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(timeoutMs))
                    .GET()
                    .header("Accept", "application/json");
            if (LocalLlmGatewaySecurity.hasUsableRemoteSecret(apiKey)) {
                builder.header("Authorization", "Bearer " + apiKey);
            }
            if (LocalLlmGatewaySecurity.shouldAttachOwnerToken(uri.toString(), allowedHosts)) {
                for (Map.Entry<String, String> header : LocalLlmGatewaySecurity.ownerTokenHeaders(ownerTokenHeader, ownerToken).entrySet()) {
                    builder.header(header.getKey(), header.getValue());
                }
            }

            HttpResponse<Void> response = client.send(builder.build(), HttpResponse.BodyHandlers.discarding());
            int statusCode = response.statusCode();
            out.put("httpStatus", statusCode);
            out.put("status", httpStatus(statusCode));
            out.put("reachable", statusCode >= 200 && statusCode < 400);
        } catch (java.net.http.HttpTimeoutException ex) {
            traceSuppressed("gpuGateway.timeout", ex);
            out.put("status", "timeout");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            traceSuppressed("gpuGateway.interrupted", ex);
            out.put("status", "interrupted");
        } catch (IOException ex) {
            traceSuppressed("gpuGateway.io", ex);
            out.put("status", ioStatus(ex));
        } catch (Exception ex) {
            traceSuppressed("gpuGateway.endpoint", ex);
            out.put("status", "error");
        } finally {
            out.put("tookMs", elapsedMs(start));
        }
        return out;
    }

    private static String preflightStatus(boolean enabled, int configured, int reachable) {
        if (!enabled) {
            return "disabled_by_config";
        }
        if (configured <= 0) {
            return "missing_endpoint";
        }
        if (reachable <= 0) {
            return "unreachable";
        }
        return reachable == configured ? "ok" : "partial";
    }

    private static String httpStatus(int statusCode) {
        if (statusCode >= 200 && statusCode < 400) {
            return "ok";
        }
        if (statusCode == 401 || statusCode == 403) {
            return "auth_failed";
        }
        if (statusCode == 404) {
            return "not_found";
        }
        if (statusCode == 429) {
            return "rate_limit";
        }
        if (statusCode >= 500) {
            return "server_error";
        }
        return "http_" + statusCode;
    }

    private static String ioStatus(IOException ex) {
        Throwable t = ex;
        while (t != null) {
            if (t instanceof UnknownHostException) {
                return "dns_failed";
            }
            if (t instanceof ConnectException) {
                return "connection_failed";
            }
            t = t.getCause();
        }
        String message = String.valueOf(ex.getMessage()).toLowerCase(Locale.ROOT);
        if (message.contains("unresolved")) {
            return "dns_failed";
        }
        if (message.contains("timed out")) {
            return "timeout";
        }
        return "connection_failed";
    }

    private static URI preflightUri(String endpointName, String baseUrl) {
        URI uri = parseUri(baseUrl);
        if (uri == null || uri.getHost() == null) {
            return null;
        }
        String origin = origin(uri);
        String path = uri.getPath() == null ? "" : uri.getPath();
        String lowerPath = path.toLowerCase(Locale.ROOT);
        if ("embedding".equals(endpointName) && lowerPath.contains("/api/embed")) {
            return URI.create(origin + "/api/tags");
        }
        int v1 = lowerPath.indexOf("/v1");
        if (v1 >= 0) {
            return URI.create(origin + path.substring(0, v1 + 3) + "/models");
        }
        return URI.create(origin + "/v1/models");
    }

    private static String origin(URI uri) {
        String scheme = uri.getScheme() == null ? "http" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        return uri.getPort() > 0 ? scheme + "://" + host + ":" + uri.getPort() : scheme + "://" + host;
    }

    private static String safePath(URI uri) {
        String path = uri == null ? "" : uri.getPath();
        return path == null ? "" : path;
    }

    private static long elapsedMs(long startNanos) {
        return Math.max(0L, Duration.ofNanos(System.nanoTime() - startNanos).toMillis());
    }

    private static void tracePreflight(Map<String, Object> preflight) {
        try {
            TraceStore.put("uaw.gpu-gateway.preflight", preflight);
            TraceStore.put("uaw.gpu-gateway.preflight.status", preflight.getOrDefault("status", ""));
            TraceStore.put("uaw.gpu-gateway.preflight.reachableCount", preflight.getOrDefault("reachableCount", 0));
        } catch (Throwable ignore) {
            tracePreflightSkipped(preflight, ignore);
        }
    }

    private static void tracePreflightSkipped(Map<String, Object> preflight, Throwable failure) {
        preflight.put("traceSkipped", true);
        preflight.put("traceReason", "trace_store_unavailable");
        preflight.put("traceErrorType", failure == null ? "unknown" : failure.getClass().getSimpleName());
    }

    private static Map<String, Object> endpointInfo(Map<String, Object> endpoints, String name) {
        Object value = endpoints == null ? null : endpoints.get(name);
        if (value instanceof Map<?, ?> map) {
            return copyMap(map);
        }
        return Map.of();
    }

    private static Map<String, Object> copyMap(Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (map != null) {
            map.forEach((key, value) -> out.put(String.valueOf(key), value));
        }
        return out;
    }

    private static String mode(boolean controlPlane, boolean heavyAllowed, boolean enabled) {
        if (!enabled) {
            return "observe_only";
        }
        if (controlPlane && !heavyAllowed) {
            return "control_plane_to_gpu_executor";
        }
        if (heavyAllowed) {
            return "local_gpu_executor";
        }
        return "observer_gateway";
    }

    private static String routePolicy(boolean controlPlane, boolean heavyAllowed, boolean enabled) {
        if (!enabled) {
            return "disabled_runtime_observe_only";
        }
        if (controlPlane && !heavyAllowed) {
            return "handoff_to_desktop_gpu";
        }
        if (heavyAllowed) {
            return "execute_on_this_node";
        }
        return "observe_and_forward_only";
    }

    private static String endpointHostPort(String baseUrl) {
        URI uri = parseUri(baseUrl);
        if (uri == null || uri.getHost() == null) {
            return "";
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        return uri.getPort() > 0 ? host + ":" + uri.getPort() : host;
    }

    private static URI parseUri(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        try {
            String candidate = value.matches("^[A-Za-z][A-Za-z0-9+.-]*://.*") ? value : "http://" + value;
            return URI.create(candidate);
        } catch (IllegalArgumentException ex) {
            traceSuppressed("gpuGateway.parseUri", ex);
            return null;
        }
    }

    private static boolean bool(Environment env, String key, boolean defaultValue) {
        String value = prop(env, key, null);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value.trim());
    }

    private static int intProp(Environment env, String key, int defaultValue) {
        String value = prop(env, key, null);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            traceSuppressed("gpuGateway.intProp", ex);
            return defaultValue;
        }
    }

    private static String prop(Environment env, String key, String defaultValue) {
        return env == null ? defaultValue : env.getProperty(key, defaultValue);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            String trimmed = trimToNull(value);
            if (trimmed != null) {
                return trimmed;
            }
        }
        return "";
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static void traceSuppressed(String stage, Throwable failure) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String errorType = errorType(safeStage, failure);
        TraceStore.put("uaw.gpu-gateway.suppressed.stage", safeStage);
        TraceStore.put("uaw.gpu-gateway.suppressed.errorType", errorType);
        TraceStore.put("uaw.gpu-gateway.suppressed." + safeStage, true);
        TraceStore.put("uaw.gpu-gateway.suppressed." + safeStage + ".errorType", errorType);
    }

    private static String errorType(String stage, Throwable failure) {
        if ("gpuGateway.parseUri".equals(stage) && failure instanceof IllegalArgumentException) {
            return "invalid_url";
        }
        if ("gpuGateway.intProp".equals(stage) && failure instanceof NumberFormatException) {
            return "invalid_number";
        }
        return failure == null ? "unknown" : SafeRedactor.traceLabelOrFallback(failure.getClass().getSimpleName(), "unknown");
    }
}
