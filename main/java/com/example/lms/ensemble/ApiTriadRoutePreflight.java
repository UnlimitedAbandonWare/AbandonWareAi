package com.example.lms.ensemble;

import ai.abandonware.nova.config.LlmRouterProperties;
import com.example.lms.guard.KeyResolver;
import com.example.lms.llm.LocalLlmGatewaySecurity;
import com.example.lms.llm.gateway.HybridLlmGatewayProbeService;
import com.example.lms.llm.gateway.RoutingEligibility;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class ApiTriadRoutePreflight {

    private static final int REQUIRED_ROUTE_COUNT = 3;

    private final LlmRouterProperties routerProperties;
    private final HybridLlmGatewayProbeService gatewayProbe;
    private final KeyResolver keyResolver;

    public Result evaluate(List<String> requestedRoutes) {
        if (routerProperties == null || !routerProperties.isEnabled()) {
            return Result.denied("router_disabled");
        }
        if (routerProperties.isFallbackWhenOpenAiMissing()
                || gatewayProbe == null
                || gatewayProbe.cloudFallbackEnabled()) {
            return Result.denied("router_fallback_forbidden");
        }
        if (requestedRoutes == null || requestedRoutes.size() != REQUIRED_ROUTE_COUNT) {
            return Result.denied("route_count_mismatch");
        }

        List<String> providers = new ArrayList<>(REQUIRED_ROUTE_COUNT);
        Set<String> distinctRoutes = new HashSet<>();
        Set<String> distinctProviders = new HashSet<>();
        for (String requestedRoute : requestedRoutes) {
            String routeKey = normalizeRouteKey(requestedRoute);
            if (routeKey == null || !distinctRoutes.add(routeKey)) {
                return Result.denied("route_identity_invalid");
            }
            LlmRouterProperties.ModelConfig cfg = routerProperties.getModels().get(routeKey);
            if (!completeRoute(cfg)) {
                return Result.denied("route_disabled_or_incomplete");
            }
            String provider = cfg.getProvider().trim().toLowerCase(Locale.ROOT);
            if (!strictExternalEndpointShape(cfg.getBaseUrl())) {
                return Result.denied("invalid_endpoint");
            }
            if (!providerMatchesEndpoint(provider, cfg.getBaseUrl())) {
                return Result.denied("provider_endpoint_mismatch");
            }
            if (!providerPathMatches(provider, cfg.getBaseUrl())) {
                return Result.denied("invalid_endpoint");
            }
            if (cfg.getFallbackKey() != null && !cfg.getFallbackKey().isBlank()) {
                return Result.denied("route_fallback_forbidden");
            }
            RoutingEligibility eligibility = gatewayProbe.evaluate(routeKey, cfg, "chat");
            if (eligibility == null || !eligibility.eligible()) {
                return Result.denied("route_ineligible");
            }
            if (!hasCredential(provider)) {
                return Result.denied("credential_missing");
            }
            providers.add(provider);
            distinctProviders.add(provider);
        }
        if (distinctProviders.size() != REQUIRED_ROUTE_COUNT) {
            return Result.denied("provider_independence_missing");
        }
        return Result.ready(providers);
    }

    private boolean hasCredential(String provider) {
        try {
            String value = switch (provider) {
                case "openai" -> keyResolver.resolveOpenAiApiKeyStrict();
                case "groq" -> keyResolver.resolveGroqApiKeyStrict();
                case "gemini" -> keyResolver.resolveGeminiApiKeyStrict();
                default -> null;
            };
            return value != null && !value.isBlank();
        } catch (RuntimeException credentialConflict) {
            return false;
        }
    }

    private static boolean completeRoute(LlmRouterProperties.ModelConfig cfg) {
        return cfg != null
                && cfg.isEnabled()
                && cfg.getProvider() != null
                && !cfg.getProvider().isBlank()
                && cfg.getName() != null
                && !cfg.getName().isBlank()
                && cfg.getBaseUrl() != null
                && !cfg.getBaseUrl().isBlank();
    }

    private static String normalizeRouteKey(String requestedRoute) {
        if (requestedRoute == null || !requestedRoute.startsWith("llmrouter.")) {
            return null;
        }
        String key = requestedRoute.substring("llmrouter.".length()).trim();
        return key.isBlank() ? null : key;
    }

    private static boolean providerMatchesEndpoint(String provider, String baseUrl) {
        String host = LocalLlmGatewaySecurity.endpointHost(baseUrl);
        if (!LocalLlmGatewaySecurity.isKnownExternalProviderBaseUrl(baseUrl)) {
            return false;
        }
        return switch (provider) {
            case "openai" -> "api.openai.com".equals(host) || host.endsWith(".openai.com");
            case "groq" -> "api.groq.com".equals(host) || host.endsWith(".groq.com");
            case "gemini" -> "generativelanguage.googleapis.com".equals(host);
            default -> false;
        };
    }

    private static boolean strictExternalEndpointShape(String baseUrl) {
        try {
            URI uri = URI.create(baseUrl);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null
                    && !uri.getHost().isBlank()
                    && uri.getRawUserInfo() == null
                    && uri.getRawQuery() == null
                    && uri.getRawFragment() == null
                    && (uri.getPort() == -1 || uri.getPort() == 443);
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    private static boolean providerPathMatches(String provider, String baseUrl) {
        try {
            String path = URI.create(baseUrl).getPath();
            String normalizedPath = path == null || path.isBlank()
                    ? "/"
                    : path.replaceAll("/+$", "");
            return switch (provider) {
                case "openai" -> "/v1".equals(normalizedPath);
                case "groq" -> "/openai/v1".equals(normalizedPath);
                case "gemini" -> "/v1beta/openai".equals(normalizedPath);
                default -> false;
            };
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    public record Result(boolean ready, String reasonCode, List<String> providers) {
        public Result {
            reasonCode = reasonCode == null || reasonCode.isBlank() ? "unknown" : reasonCode;
            providers = providers == null ? List.of() : List.copyOf(providers);
        }

        static Result ready(List<String> providers) {
            return new Result(true, "ready", providers);
        }

        static Result denied(String reasonCode) {
            return new Result(false, reasonCode, List.of());
        }
    }
}
