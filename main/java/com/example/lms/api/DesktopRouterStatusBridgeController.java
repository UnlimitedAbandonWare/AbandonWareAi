package com.example.lms.api;

import com.example.lms.config.ConfigValueGuards;
import com.example.lms.health.GpuGatewayDiagnostics;
import com.example.lms.health.GpuHardwareDiagnostics;
import com.example.lms.health.LlmHealthIndicator;
import com.example.lms.llm.LocalLlmGatewaySecurity;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Redacted desktop status bridge for the external Mac mini API router.
 */
@RestController
@Profile("desktop-gpu-node")
@ConditionalOnProperty(prefix = "awx.desktop-status-bridge", name = "enabled", havingValue = "true",
        matchIfMissing = true)
@RequestMapping("/api/router")
public class DesktopRouterStatusBridgeController {

    private static final System.Logger LOG = System.getLogger(DesktopRouterStatusBridgeController.class.getName());

    private final Environment env;

    public DesktopRouterStatusBridgeController(Environment env) {
        this.env = env;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Health health = new LlmHealthIndicator(env).health();
        Map<String, Object> details = map(health.getDetails());
        Map<String, Object> runtimeNode = map(details.get("runtimeNode"));
        Map<String, Object> gpuGateway = GpuGatewayDiagnostics.snapshot(env);
        Map<String, Object> gpuHardware = GpuHardwareDiagnostics.snapshot(env);
        Map<String, Object> routes = map(details.get("llmrouter.routes"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("statusView", "desktop-bridge");
        out.put("nodeMode", "desktop");
        out.put("routerOnlyMode", false);
        out.put("nodeRole", string(runtimeNode.get("role"), prop("awx.node.role", "desktop-gpu-executor")));
        out.put("executionNode", string(runtimeNode.get("executionNode"), prop("awx.node.execution-node", "")));
        out.put("controlPlane", bool(runtimeNode.get("controlPlane"), boolProp("awx.node.control-plane", false)));
        out.put("heavyWorkloadsAllowed", bool(runtimeNode.get("heavyWorkloadsAllowed"),
                boolProp("awx.node.heavy-workloads-allowed", true)));
        out.put("learningLoopMode", string(runtimeNode.get("learningLoopMode"),
                prop("awx.node.learning-loop-mode", "")));
        out.put("routePolicy", string(gpuGateway.get("routePolicy"), "desktop-primary"));
        out.put("ownerTokenConfigured", LocalLlmGatewaySecurity.hasUsableRemoteSecret(ownerToken()));
        out.put("tokenState", tokenState());
        out.put("topology", topology());
        out.put("routes", routeView(routes));
        out.put("probeMode", "none");
        out.put("gpuGateway", gpuGateway);
        out.put("gpuHardware", gpuHardware);
        out.put("llmrouter.routes", routes);
        out.put("disabledReason", disabledReason(routes, gpuGateway));
        out.put("timestamp", Instant.now().toString());
        return out;
    }

    private String disabledReason(Map<String, Object> routes, Map<String, Object> gpuGateway) {
        Map<String, Object> macmini = map(routes.get("macmini"));
        String macminiReason = string(macmini.get("disabledReason"), "");
        if (!macminiReason.isBlank()) {
            return macminiReason;
        }
        String gatewayReason = string(gpuGateway.get("disabledReason"), "");
        return gatewayReason.isBlank() ? null : gatewayReason;
    }

    private Map<String, Object> topology() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("primaryRagServer", "demo-1");
        out.put("macminiRole", "optional-router-helper");
        out.put("desktopRole", "primary-rag-chat-control-plane");
        out.put("primary3090", endpoint("awx.gpu-gateway.primary-chat-base-url",
                firstNonBlank(prop("llm.base-url", ""), prop("awx.gpu-gateway.primary-chat-base-url", "")),
                "rtx3090", "heavy-chat-judge-coder"));
        out.put("fast3060", endpoint("awx.gpu-gateway.fast-base-url",
                firstNonBlank(prop("llm.fast.base-url", ""), prop("awx.gpu-gateway.fast-base-url", "")),
                "rtx3060", "fast-vision-helper"));
        out.put("embedding3060", endpoint("awx.gpu-gateway.embedding-base-url",
                firstNonBlank(prop("embedding.base-url", ""), prop("awx.gpu-gateway.embedding-base-url", "")),
                "rtx3060", "embedding"));
        out.put("macminiRouter", macminiRoute());
        return out;
    }

    private Map<String, Object> routeView(Map<String, Object> healthRoutes) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("light", map(healthRoutes.get("light")));
        out.put("gemma", map(healthRoutes.get("gemma")));
        out.put("judge", map(healthRoutes.get("judge")));
        out.put("coder", map(healthRoutes.get("coder")));
        out.put("vision", map(healthRoutes.get("vision")));
        out.put("macmini", macminiRoute());
        return out;
    }

    private Map<String, Object> macminiRoute() {
        String baseUrl = firstNonBlank(
                prop("llmrouter.models.macmini.base-url", ""),
                prop("MACMINI_API_ROUTER_BASE_URL", ""));
        boolean enabled = boolProp("llmrouter.models.macmini.enabled",
                boolProp("MACMINI_API_ROUTER_ENABLED", false));
        String allowedHosts = firstNonBlank(
                prop("MACMINI_API_ROUTER_ALLOWED_HOSTS", ""),
                prop("llm.provider-guard.allowed-hosts", ""));
        String ownerToken = firstNonBlank(
                prop("MACMINI_API_ROUTER_OWNER_TOKEN", ""),
                ownerToken());

        Map<String, Object> out = endpoint(
                "llmrouter.models.macmini.base-url",
                baseUrl,
                prop("llmrouter.models.macmini.device", "m4-16gb"),
                prop("llmrouter.models.macmini.workload", "optional-subserver-route"));
        out.put("routeKey", "macmini");
        out.put("enabled", enabled);
        out.put("model", prop("llmrouter.models.macmini.name", "llmrouter.auto"));
        out.put("nodeRole", prop("llmrouter.models.macmini.node-role", "macmini-router-only-node"));
        out.put("routePolicy", "optional-subserver-router");
        out.put("hasOwnerToken", LocalLlmGatewaySecurity.hasUsableRemoteSecret(ownerToken));
        out.put("hostAllowlisted", hostAllowlisted(baseUrl, allowedHosts));
        out.put("disabledReason", routeDisabledReason(
                enabled,
                baseUrl,
                allowedHosts,
                boolProp("MACMINI_API_ROUTER_REQUIRE_AUTH", true),
                ownerToken));
        return out;
    }

    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        raw.forEach((key, val) -> out.put(String.valueOf(key), val));
        return out;
    }

    private Map<String, Object> endpoint(String source, String baseUrl, String hardwareProfile, String workload) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", source);
        out.put("enabled", boolProp("awx.gpu-gateway.enabled", true));
        out.put("configured", !ConfigValueGuards.isMissing(baseUrl));
        out.put("hardwareProfile", hardwareProfile);
        out.put("workload", workload);
        out.put("endpointHost", LocalLlmGatewaySecurity.endpointHost(baseUrl));
        out.put("endpointHostPort", endpointHostPort(baseUrl));
        out.put("redactedUrl", redactedUrl(baseUrl));
        out.put("hostAllowlisted", hostAllowlisted(baseUrl, prop("awx.gpu-gateway.allowed-hosts", "")));
        return out;
    }

    private Map<String, Object> tokenState() {
        String primaryOwnerToken = ownerToken();
        String macminiOwnerToken = prop("MACMINI_API_ROUTER_OWNER_TOKEN", "");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ownerToken", credentialState(primaryOwnerToken));
        out.put("macminiOwnerToken", credentialState(macminiOwnerToken));
        out.put("ownerTokenHeaderConfigured", !ConfigValueGuards.isMissing(prop("llm.owner-token-header",
                prop("LLM_OWNER_TOKEN_HEADER", LocalLlmGatewaySecurity.DEFAULT_OWNER_TOKEN_HEADER))));
        out.put("macminiOwnerTokenHeaderConfigured", !ConfigValueGuards.isMissing(prop(
                "MACMINI_API_ROUTER_OWNER_TOKEN_HEADER", LocalLlmGatewaySecurity.DEFAULT_OWNER_TOKEN_HEADER)));
        return out;
    }

    private String routeDisabledReason(boolean enabled,
                                       String baseUrl,
                                       String allowedHosts,
                                       boolean requireAuth,
                                       String ownerToken) {
        if (!enabled) {
            return "route_disabled";
        }
        if (ConfigValueGuards.isMissing(baseUrl)) {
            return "missing_endpoint";
        }
        if (!hostAllowlisted(baseUrl, allowedHosts)) {
            return "host_not_allowlisted";
        }
        if (requireAuth && !LocalLlmGatewaySecurity.hasUsableRemoteSecret(ownerToken)) {
            return "remote_auth_missing";
        }
        return null;
    }

    private boolean hostAllowlisted(String baseUrl, String allowedHosts) {
        return !ConfigValueGuards.isMissing(baseUrl)
                && LocalLlmGatewaySecurity.shouldAttachOwnerToken(baseUrl, allowedHosts);
    }

    private String credentialState(String value) {
        if (LocalLlmGatewaySecurity.hasUsableRemoteSecret(value)) {
            return "configured";
        }
        if (value == null || value.isBlank()) {
            return "missing";
        }
        return "placeholder";
    }

    private String ownerToken() {
        return firstNonBlank(prop("llm.owner-token", ""), prop("LLM_OWNER_TOKEN", ""));
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static URI parseUri(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            String candidate = raw.matches("^[A-Za-z][A-Za-z0-9+.-]*://.*") ? raw : "http://" + raw;
            return URI.create(candidate);
        } catch (IllegalArgumentException ex) {
            traceSuppressed("desktopRouter.parseUri", ex);
            return null;
        }
    }

    private static void traceSuppressed(String stage, RuntimeException failure) {
        if (LOG.isLoggable(System.Logger.Level.DEBUG)) {
            LOG.log(System.Logger.Level.DEBUG,
                    "Desktop router status fallback stage={0} errorType={1}",
                    stage,
                    errorType(failure));
        }
    }

    private static String errorType(RuntimeException failure) {
        if (failure instanceof IllegalArgumentException) {
            return "invalid_url";
        }
        return failure == null ? "unknown" : failure.getClass().getSimpleName();
    }

    private static String endpointHostPort(String baseUrl) {
        URI uri = parseUri(baseUrl);
        if (uri == null || uri.getHost() == null) {
            return "";
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        return port > 0 ? host + ":" + port : host;
    }

    private static String redactedUrl(String baseUrl) {
        URI uri = parseUri(baseUrl);
        if (uri == null || uri.getHost() == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(uri.getScheme() == null ? "http" : uri.getScheme())
                .append("://")
                .append(uri.getHost().toLowerCase(Locale.ROOT));
        if (uri.getPort() > 0) {
            out.append(':').append(uri.getPort());
        }
        String path = uri.getPath();
        if (path != null && !path.isBlank()) {
            out.append(path);
        }
        return out.toString();
    }

    private String prop(String key, String fallback) {
        String value = env.getProperty(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private boolean boolProp(String key, boolean fallback) {
        String value = env.getProperty(key);
        return value == null || value.isBlank() ? fallback : Boolean.parseBoolean(value);
    }

    private static String string(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? fallback : text;
    }

    private static boolean bool(Object value, boolean fallback) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? fallback : Boolean.parseBoolean(text);
    }
}
