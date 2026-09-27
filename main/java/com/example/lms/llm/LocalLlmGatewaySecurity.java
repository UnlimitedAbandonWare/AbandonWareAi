package com.example.lms.llm;

import ai.abandonware.nova.config.LlmRouterProperties;
import com.example.lms.config.ConfigValueGuards;
import com.example.lms.llm.gateway.LlmFailureClass;
import com.example.lms.llm.gateway.LlmGatewayException;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Guard helpers for local OpenAI-compatible gateways such as Ollama/vLLM.
 */
public final class LocalLlmGatewaySecurity {

    public static final String DEFAULT_OWNER_TOKEN_HEADER = "X-Owner-Token";

    private LocalLlmGatewaySecurity() {
    }

    /** Resolve operator OFF only from configured intent or an exact model/endpoint mapping. */
    public static LlmGatewayException routePolicyFailure(
            LlmRouterProperties routes, String requestedModelId, String effectiveModel, String baseUrl) {
        if (routes == null || routes.getModels() == null || routes.getModels().isEmpty()) {
            return null;
        }
        String requested = trimToEmpty(requestedModelId);
        String alias = routes.getAliases().get(requested);
        if (alias == null) {
            alias = routes.getAliases().get(requested.toLowerCase(Locale.ROOT));
        }
        String logical = alias == null || alias.isBlank() ? requested : alias.trim();
        if (logical.startsWith("llmrouter.") && !"llmrouter.auto".equals(logical)) {
            LlmRouterProperties.ModelConfig explicit = routes.getModels().get(logical.substring(10));
            if (explicit != null) {
                return explicit.isEnabled() ? null : routePolicyFailure("route_disabled");
            }
        }
        String model = ModelCapabilities.canonicalModelName(effectiveModel);
        if (model == null || model.isBlank()) {
            return null;
        }
        var matches = routes.getModels().values().stream()
                .filter(Objects::nonNull)
                .filter(cfg -> model.equals(ModelCapabilities.canonicalModelName(cfg.getName())))
                .filter(cfg -> sameRouteEndpoint(baseUrl, cfg.getBaseUrl()))
                .toList();
        if (matches.stream().noneMatch(cfg -> !cfg.isEnabled())) {
            return null;
        }
        String stage = null;
        String role = null;
        for (var cfg : matches) {
            String candidateStage = trimToNull(cfg.getStage());
            String candidateRole = trimToNull(cfg.getDeviceRole());
            if (candidateStage == null || candidateRole == null || cfg.isEnabled()
                    || (stage != null && !stage.equalsIgnoreCase(candidateStage))
                    || (role != null && !role.equalsIgnoreCase(candidateRole))) {
                return routePolicyFailure("route_mapping_unconfirmed");
            }
            stage = candidateStage;
            role = candidateRole;
        }
        return routePolicyFailure("route_disabled");
    }

    private static boolean sameRouteEndpoint(String first, String second) {
        URI left = parseUri(OpenAiCompatBaseUrl.sanitize(first));
        URI right = parseUri(OpenAiCompatBaseUrl.sanitize(second));
        if (left == null || right == null || left.getHost() == null || right.getHost() == null
                || left.getScheme() == null || right.getScheme() == null
                || !("http".equalsIgnoreCase(left.getScheme()) || "https".equalsIgnoreCase(left.getScheme()))) {
            return false;
        }
        int leftPort = left.getPort() < 0 ? ("https".equalsIgnoreCase(left.getScheme()) ? 443 : 80) : left.getPort();
        int rightPort = right.getPort() < 0 ? ("https".equalsIgnoreCase(right.getScheme()) ? 443 : 80) : right.getPort();
        return left.getScheme().equalsIgnoreCase(right.getScheme())
                && left.getHost().equalsIgnoreCase(right.getHost())
                && leftPort == rightPort && Objects.equals(left.getRawPath(), right.getRawPath());
    }

    private static LlmGatewayException routePolicyFailure(String reason) {
        TraceStore.put("llmrouter.route.enabled", false);
        TraceStore.put("llmrouter.api.disabledReason", reason);
        return new LlmGatewayException("Local LLM route is unavailable: " + reason,
                LlmFailureClass.DISABLED, reason);
    }

    public static Map<String, String> ownerTokenHeaders(String headerName, String ownerToken) {
        String token = trimToNull(ownerToken);
        if (!hasUsableRemoteSecret(token)) {
            return Map.of();
        }
        return Map.of(safeHeaderName(headerName), token);
    }

    /**
     * Backward-compatible loopback-only check. Remote gateway headers must use the
     * allowlist-aware overload.
     */
    public static boolean shouldAttachOwnerToken(String baseUrl) {
        return isLoopbackBaseUrl(baseUrl);
    }

    public static boolean shouldAttachOwnerToken(String baseUrl, String allowedHostsCsv) {
        if (isKnownExternalProviderBaseUrl(baseUrl)) {
            return false;
        }
        return isLoopbackBaseUrl(baseUrl) || isAllowedHost(baseUrl, allowedHostsCsv);
    }

    public static boolean isKnownExternalProviderBaseUrl(String baseUrl) {
        String host = endpointHost(baseUrl);
        if (host == null || host.isBlank()) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("api.openai.com")
                || h.endsWith(".openai.com")
                || h.equals("api.groq.com")
                || h.endsWith(".groq.com")
                || h.equals("api.cerebras.ai")
                || h.endsWith(".cerebras.ai")
                || h.equals("api.mistral.ai")
                || h.endsWith(".mistral.ai")
                || h.equals("api.openrouter.ai")
                || h.endsWith(".openrouter.ai")
                || h.equals("openrouter.ai")
                || h.equals("opencode.ai")
                || h.endsWith(".opencode.ai")
                || h.equals("api.anthropic.com")
                || h.endsWith(".anthropic.com")
                || h.equals("generativelanguage.googleapis.com")
                || h.endsWith(".googleapis.com")
                || h.equals("ollama.com")
                || h.endsWith(".ollama.com");
    }

    public static void assertLocalGatewayEndpointAllowed(
            String baseUrl,
            boolean allowPrivateRemote,
            String allowedHostsCsv,
            boolean requireAuthForRemote,
            String apiKey,
            String ownerToken) {

        if (isKnownExternalProviderBaseUrl(baseUrl)) {
            return;
        }
        assertLocalProviderEndpointAllowed(
                "local",
                baseUrl,
                allowPrivateRemote,
                allowedHostsCsv,
                requireAuthForRemote,
                apiKey,
                ownerToken);
    }

    public static void assertLocalProviderEndpointAllowed(
            String provider,
            String baseUrl,
            boolean allowPrivateRemote,
            String allowedHostsCsv,
            boolean requireAuthForRemote,
            String apiKey,
            String ownerToken) {

        if (!"local".equalsIgnoreCase(trimToEmpty(provider))) {
            return;
        }
        String url = trimToNull(baseUrl);
        if (url == null || isLoopbackBaseUrl(url)) {
            return;
        }

        String host = endpointHost(url);
        if (!allowPrivateRemote) {
            throw new IllegalStateException(
                    "ProviderGuard: remote local LLM endpoint disabled (host=" + safeHost(host)
                            + ", set llm.provider-guard.allow-private-remote=true and allowlist the host)");
        }
        if (!isAllowedHost(url, allowedHostsCsv)) {
            throw new IllegalStateException(
                    "ProviderGuard: remote local LLM host is not allowlisted (host=" + safeHost(host) + ")");
        }
        if (requireAuthForRemote && !hasUsableRemoteSecret(apiKey) && !hasUsableRemoteSecret(ownerToken)) {
            throw new IllegalStateException(
                    "ProviderGuard: remote local LLM requires a usable proxy bearer token or owner token (host="
                            + safeHost(host) + ")");
        }
    }

    public static boolean hasUsableRemoteSecret(String value) {
        String v = trimToNull(value);
        if (v == null) {
            return false;
        }
        return !ConfigValueGuards.isMissing(v);
    }

    public static boolean isLoopbackBaseUrl(String baseUrl) {
        String host = endpointHost(baseUrl);
        if (host == null || host.isBlank()) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        return "localhost".equals(h)
                || "127.0.0.1".equals(h)
                || h.startsWith("127.")
                || "::1".equals(h)
                || "0:0:0:0:0:0:0:1".equals(h);
    }

    public static String endpointHost(String baseUrl) {
        URI uri = parseUri(baseUrl);
        if (uri == null) {
            return "";
        }
        String host = uri.getHost();
        return host == null ? "" : host.toLowerCase(Locale.ROOT);
    }

    public static String endpointScheme(String baseUrl) {
        URI uri = parseUri(baseUrl);
        if (uri == null || uri.getScheme() == null) {
            return "";
        }
        return uri.getScheme().toLowerCase(Locale.ROOT);
    }

    public static String endpointFamily(String baseUrl) {
        if (isKnownExternalProviderBaseUrl(baseUrl)) {
            return "external-provider";
        }
        if (isLoopbackBaseUrl(baseUrl)) {
            return "local-loopback";
        }
        String host = endpointHost(baseUrl);
        return host == null || host.isBlank() ? "unknown" : "custom-remote";
    }

    static boolean isAllowedHost(String baseUrl, String allowedHostsCsv) {
        String host = endpointHost(baseUrl);
        if (host == null || host.isBlank()) {
            return false;
        }
        URI uri = parseUri(baseUrl);
        int port = uri == null ? -1 : uri.getPort();
        String hostOnly = host.toLowerCase(Locale.ROOT);
        String hostPort = port > 0 ? hostOnly + ":" + port : hostOnly;

        String csv = trimToNull(allowedHostsCsv);
        if (csv == null) {
            return false;
        }
        for (String raw : csv.split(",")) {
            String candidate = trimToNull(raw);
            if (candidate == null) {
                continue;
            }
            String normalized = normalizeAllowedHost(candidate);
            if (hostOnly.equals(normalized) || hostPort.equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeAllowedHost(String raw) {
        URI uri = parseUri(raw);
        if (uri != null && uri.getHost() != null) {
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            return uri.getPort() > 0 ? host + ":" + uri.getPort() : host;
        }
        return raw.trim().toLowerCase(Locale.ROOT);
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
            TraceStore.put("llm.localGateway.suppressed.stage", "parseUri");
            TraceStore.put("llm.localGateway.suppressed.errorType", "invalid_url");
            TraceStore.put("llm.localGateway.suppressed.parseUri", true);
            TraceStore.put("llm.localGateway.suppressed.parseUri.errorType", "invalid_url");
            return null;
        }
    }

    private static String safeHeaderName(String headerName) {
        String h = trimToNull(headerName);
        if (h == null) {
            return DEFAULT_OWNER_TOKEN_HEADER;
        }
        if (!h.matches("[A-Za-z0-9-]{1,64}")) {
            return DEFAULT_OWNER_TOKEN_HEADER;
        }
        String lower = h.toLowerCase(Locale.ROOT);
        if ("authorization".equals(lower) || "cookie".equals(lower) || "set-cookie".equals(lower)) {
            return DEFAULT_OWNER_TOKEN_HEADER;
        }
        return h;
    }

    private static String safeHost(String host) {
        String h = trimToNull(host);
        return h == null ? "(unknown)" : h;
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
