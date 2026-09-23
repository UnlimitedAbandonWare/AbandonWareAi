package com.example.lms.health;

import com.example.lms.config.ConfigValueGuards;
import com.example.lms.llm.LocalLlmGatewaySecurity;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.core.env.Environment;

import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;


/**
 * Health indicator for the configured LLM provider.  This indicator exposes
 * the current provider, a masked base URL and the model name via the
  * /actuator/health endpoint.  It does not perform a live ping but
 * reflects the configuration status only.  When the provider property is
 * missing the health status is reported as UNKNOWN.
 */
public class LlmHealthIndicator implements HealthIndicator {

    private final Environment env;

    public LlmHealthIndicator(Environment env) {
        this.env = env;
    }

    @Override
    public Health health() {
        String provider = env.getProperty("llm.provider", "");
        String baseUrl  = env.getProperty("llm.base-url", "");
        String model    = env.getProperty("llm.chat-model", "");
        String ownerToken = env.getProperty("llm.owner-token", "");
        // Mask the base URL to avoid leaking secrets or paths.  Display the
        // scheme and host only when available.  If no host is present
        // fallback to an empty string.
        String masked = maskBaseUrl(baseUrl);
        boolean configured = provider != null && !provider.isBlank();
        Health.Builder builder = configured ? Health.up() : Health.unknown();
        return builder
                .withDetail("provider", provider)
                .withDetail("baseUrl", masked)
                .withDetail("endpointHost", LocalLlmGatewaySecurity.endpointHost(baseUrl))
                .withDetail("hasOwnerToken", LocalLlmGatewaySecurity.hasUsableRemoteSecret(ownerToken))
                .withDetail("model", model)
                .withDetail("runtimeNode", runtimeNode())
                .withDetail("gpuGateway", GpuGatewayDiagnostics.snapshot(env))
                .withDetail("llmrouter.routes", routerRoutes())
                .build();
    }

    private Map<String, Object> runtimeNode() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("role", env.getProperty("awx.node.role", env.getProperty("uaw.autolearn.runtime-node.role", "")));
        out.put("executionNode", env.getProperty("awx.node.execution-node",
                env.getProperty("uaw.autolearn.runtime-node.execution-node", "")));
        out.put("controlPlane", Boolean.parseBoolean(env.getProperty("awx.node.control-plane",
                env.getProperty("uaw.autolearn.runtime-node.control-plane", "false"))));
        out.put("heavyWorkloadsAllowed", Boolean.parseBoolean(env.getProperty("awx.node.heavy-workloads-allowed",
                env.getProperty("uaw.autolearn.runtime-node.heavy-workloads-allowed", "true"))));
        out.put("learningLoopMode", env.getProperty("awx.node.learning-loop-mode",
                env.getProperty("uaw.autolearn.runtime-node.learning-loop-mode", "")));
        return out;
    }

    private Map<String, Object> routerRoutes() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("light", route("light"));
        out.put("gemma", route("gemma"));
        out.put("judge", route("judge"));
        out.put("coder", route("coder"));
        out.put("vision", route("vision"));
        out.put("macmini", route("macmini"));
        out.put("external", route("external"));
        return out;
    }

    private Map<String, Object> route(String key) {
        String prefix = "llmrouter.models." + key + ".";
        boolean enabled = Boolean.parseBoolean(env.getProperty(prefix + "enabled", "true"));
        String model = env.getProperty(prefix + "name", "");
        String baseUrl = env.getProperty(prefix + "base-url", "");
        String provider = providerForBaseUrl(baseUrl);
        boolean hasKey = routeHasKey(provider);
        boolean hasOwnerToken = "local".equals(provider)
                && LocalLlmGatewaySecurity.shouldAttachOwnerToken(baseUrl, env.getProperty("llm.provider-guard.allowed-hosts", ""))
                && LocalLlmGatewaySecurity.hasUsableRemoteSecret(env.getProperty("llm.owner-token", ""));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("routeKey", key);
        out.put("enabled", enabled);
        out.put("provider", provider);
        out.put("endpointHost", LocalLlmGatewaySecurity.endpointHost(baseUrl));
        out.put("nodeRole", env.getProperty(prefix + "node-role", ""));
        out.put("device", env.getProperty(prefix + "device", ""));
        out.put("workload", env.getProperty(prefix + "workload", ""));
        out.put("hasKey", hasKey);
        out.put("hasOwnerToken", hasOwnerToken);
        out.put("disabledReason", disabledReason(enabled, model, baseUrl, provider, hasKey));
        return out;
    }

    private String disabledReason(boolean enabled, String model, String baseUrl, String provider, boolean hasKey) {
        if (!enabled) {
            return "route_disabled";
        }
        if (isBlank(model) || isBlank(baseUrl)) {
            return "missing_route_config";
        }
        if (!"local".equals(provider) && !hasKey) {
            return switch (provider) {
                case "openai" -> "missing OPENAI_API_KEY";
                case "groq" -> "missing GROQ_API_KEY";
                case "cerebras" -> "missing CEREBRAS_API_KEY";
                case "openrouter" -> "missing OPENROUTER_API_KEY";
                case "opencode" -> "missing OPENCODE_API_KEY";
                default -> "missing_provider_api_key";
            };
        }
        if ("local".equals(provider)) {
            try {
                LocalLlmGatewaySecurity.assertLocalGatewayEndpointAllowed(
                        baseUrl,
                        Boolean.parseBoolean(env.getProperty("llm.provider-guard.allow-private-remote", "false")),
                        env.getProperty("llm.provider-guard.allowed-hosts", ""),
                        Boolean.parseBoolean(env.getProperty("llm.provider-guard.require-auth-for-remote", "true")),
                        env.getProperty("llm.api-key", ""),
                        env.getProperty("llm.owner-token", ""));
            } catch (IllegalStateException ex) {
                traceSuppressed("llmHealthIndicator.routeGuard", ex);
                String m = String.valueOf(ex.getMessage()).toLowerCase(Locale.ROOT);
                if (m.contains("disabled")) {
                    return "private_remote_disabled";
                }
                if (m.contains("allowlisted")) {
                    return "host_not_allowlisted";
                }
                if (m.contains("requires")) {
                    return "remote_auth_missing";
                }
                return "local_route_guard_failed";
            }
        }
        return null;
    }

    private boolean routeHasKey(String provider) {
        return switch (provider) {
            case "openai" -> hasAnyKey("llm.api-key-openai", "llm.openai.api-key", "OPENAI_API_KEY");
            case "groq" -> hasAnyKey("llm.groq.api-key", "GROQ_API_KEY");
            case "cerebras" -> hasAnyKey("llm.cerebras.api-key", "CEREBRAS_API_KEY");
            case "openrouter" -> hasAnyKey("llm.openrouter.api-key", "OPENROUTER_API_KEY");
            case "opencode" -> hasAnyKey("llm.opencode.api-key", "OPENCODE_API_KEY");
            case "local" -> LocalLlmGatewaySecurity.hasUsableRemoteSecret(env.getProperty("llm.api-key", ""))
                    || LocalLlmGatewaySecurity.hasUsableRemoteSecret(env.getProperty("LLM_API_KEY", ""));
            default -> false;
        };
    }

    private boolean hasAnyKey(String... keys) {
        if (keys == null) {
            return false;
        }
        for (String key : keys) {
            if (!ConfigValueGuards.isMissing(env.getProperty(key))) {
                return true;
            }
        }
        return false;
    }

    private static String providerForBaseUrl(String baseUrl) {
        if (baseUrl == null) {
            return "local";
        }
        String u = baseUrl.toLowerCase(Locale.ROOT);
        if (u.contains("api.openai.com")) {
            return "openai";
        }
        if (u.contains("api.groq.com")) {
            return "groq";
        }
        if (u.contains("api.cerebras.ai")) {
            return "cerebras";
        }
        if (u.contains("openrouter.ai")) {
            return "openrouter";
        }
        if (u.contains("opencode.ai")) {
            return "opencode";
        }
        return "local";
    }

    private static String maskBaseUrl(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        try {
            java.net.URI uri = new java.net.URI(url);
            String scheme = uri.getScheme();
            String host   = uri.getHost();
            if (scheme == null || host == null) {
                return "";
            }
            return scheme + "://" + host;
        } catch (Exception e) {
            traceSuppressed("llmHealthIndicator.maskBaseUrl", e);
            return "";
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static void traceSuppressed(String stage, Throwable failure) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String errorType = errorType(failure);
        TraceStore.put("llm.health.indicator.suppressed.stage", safeStage);
        TraceStore.put("llm.health.indicator.suppressed.errorType", errorType);
        TraceStore.put("llm.health.indicator.suppressed." + safeStage, true);
        TraceStore.put("llm.health.indicator.suppressed." + safeStage + ".errorType", errorType);
    }

    private static String errorType(Throwable failure) {
        if (failure == null) {
            return "unknown";
        }
        if (failure instanceof URISyntaxException || failure instanceof IllegalArgumentException) {
            return "invalid_url";
        }
        return failure.getClass().getSimpleName();
    }
}
