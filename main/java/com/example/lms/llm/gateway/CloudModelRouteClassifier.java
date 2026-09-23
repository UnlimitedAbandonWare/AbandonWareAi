package com.example.lms.llm.gateway;

import ai.abandonware.nova.config.LlmRouterProperties;
import com.example.lms.llm.spec.CloudModelCatalogLoader;
import com.example.lms.llm.spec.ModelSpecSnapshot;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Component
public class CloudModelRouteClassifier {

    private final CloudModelCatalogLoader catalogLoader;
    private final LlmRouterProperties routerProperties;
    private final HybridLlmGatewayProbeService gatewayProbeService;

    public CloudModelRouteClassifier(
            CloudModelCatalogLoader catalogLoader,
            LlmRouterProperties routerProperties,
            HybridLlmGatewayProbeService gatewayProbeService) {
        this.catalogLoader = catalogLoader;
        this.routerProperties = routerProperties;
        this.gatewayProbeService = gatewayProbeService;
    }

    public List<CloudModelRouteRow> classifyDefaultCatalog(String stage) {
        if (catalogLoader == null) {
            return List.of();
        }
        return classify(catalogLoader.loadDefaultCatalog(), stage);
    }

    public List<CloudModelRouteRow> classify(Collection<ModelSpecSnapshot> snapshots, String stage) {
        if (snapshots == null || snapshots.isEmpty()) {
            return List.of();
        }
        Map<String, LlmRouterProperties.ModelConfig> routes = routerProperties == null
                ? Map.of()
                : routerProperties.getModels();
        return snapshots.stream()
                .filter(Objects::nonNull)
                .map(snapshot -> row(snapshot, stage, routes))
                .toList();
    }

    private CloudModelRouteRow row(
            ModelSpecSnapshot snapshot,
            String stage,
            Map<String, LlmRouterProperties.ModelConfig> routes) {
        Map.Entry<String, LlmRouterProperties.ModelConfig> route = matchingRoute(snapshot, routes);
        if (route == null || gatewayProbeService == null) {
            return CloudModelRouteRow.fromSnapshot(
                    null,
                    snapshot,
                    stage,
                    0,
                    false,
                    false,
                    List.of(LlmFailureClass.DISABLED),
                    disabledReason(snapshot));
        }
        if (manifestDisabled(snapshot)) {
            return CloudModelRouteRow.fromSnapshot(
                    route.getKey(),
                    snapshot,
                    stage,
                    0,
                    false,
                    route.getValue().isFallbackOnly(),
                    List.of(LlmFailureClass.DISABLED),
                    "cloud_manifest_disabled");
        }
        RoutingEligibility eligibility = gatewayProbeService.evaluate(route.getKey(), route.getValue(), stage);
        return CloudModelRouteRow.fromEligibility(route.getKey(), snapshot, eligibility);
    }

    private static boolean manifestDisabled(ModelSpecSnapshot snapshot) {
        return snapshot != null && Boolean.FALSE.equals(snapshot.metadata().get("enabled"));
    }

    private static Map.Entry<String, LlmRouterProperties.ModelConfig> matchingRoute(
            ModelSpecSnapshot snapshot,
            Map<String, LlmRouterProperties.ModelConfig> routes) {
        if (snapshot == null || routes == null || routes.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, LlmRouterProperties.ModelConfig> entry : routes.entrySet()) {
            LlmRouterProperties.ModelConfig cfg = entry.getValue();
            if (cfg == null) {
                continue;
            }
            if (same(snapshot.model(), cfg.getName()) && (same(snapshot.provider(), cfg.getProvider())
                    || same(snapshot.endpointHost(), endpointHost(cfg.getBaseUrl())))) {
                return entry;
            }
        }
        return null;
    }

    private static boolean same(String left, String right) {
        return left != null && right != null && left.trim().equalsIgnoreCase(right.trim());
    }

    private static String endpointHost(String rawBaseUrl) {
        if (rawBaseUrl == null || rawBaseUrl.isBlank()) {
            return null;
        }
        try {
            String host = java.net.URI.create(rawBaseUrl.trim()).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (Exception ex) {
            TraceStore.put("llm.gateway.cloud.routeClassifier.suppressed.endpointHost", true);
            TraceStore.put("llm.gateway.cloud.routeClassifier.suppressed.endpointHost.errorType", errorType(ex));
            return null;
        }
    }

    private static String errorType(Throwable error) {
        if (error instanceof IllegalArgumentException) {
            return "invalid_url";
        }
        return error == null ? "unknown" : SafeRedactor.traceLabelOrFallback(error.getClass().getSimpleName(), "unknown");
    }

    private static String disabledReason(ModelSpecSnapshot snapshot) {
        if (snapshot == null) {
            return "route_not_configured";
        }
        Object routingDecision = snapshot.metadata().get("routingDecision");
        if (routingDecision != null && String.valueOf(routingDecision).contains("hold")) {
            return "route_not_configured";
        }
        if (manifestDisabled(snapshot)) {
            return "cloud_manifest_disabled";
        }
        return "route_not_configured";
    }

    public record CloudModelRouteRow(
            String routeKey,
            String provider,
            String modelId,
            String apiSurface,
            Integer contextTokens,
            Integer maxOutputTokens,
            List<String> capabilities,
            String credentialEnv,
            String routingDecision,
            int routeScore,
            boolean eligible,
            boolean fallbackOnly,
            List<LlmFailureClass> failureClasses,
            String disabledReason,
            Map<String, Object> metadata) {

        public CloudModelRouteRow {
            capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
            failureClasses = failureClasses == null ? List.of() : List.copyOf(failureClasses);
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }

        static CloudModelRouteRow fromEligibility(
                String routeKey,
                ModelSpecSnapshot snapshot,
                RoutingEligibility eligibility) {
            String disabledReason = null;
            if (eligibility.safeMeta().get("disabledReason") != null) {
                disabledReason = String.valueOf(eligibility.safeMeta().get("disabledReason"));
            }
            return fromSnapshot(
                    routeKey,
                    snapshot,
                    eligibility.stage(),
                    eligibility.score(),
                    eligibility.eligible(),
                    eligibility.fallbackOnly(),
                    eligibility.failureClasses(),
                    disabledReason);
        }

        static CloudModelRouteRow fromSnapshot(
                String routeKey,
                ModelSpecSnapshot snapshot,
                String stage,
                int routeScore,
                boolean eligible,
                boolean fallbackOnly,
                List<LlmFailureClass> failureClasses,
                String disabledReason) {
            Map<String, Object> metadata = new LinkedHashMap<>(snapshot.metadata());
            if (stage != null && !stage.isBlank()) {
                metadata.put("stage", stage);
            }
            return new CloudModelRouteRow(
                    routeKey,
                    snapshot.provider(),
                    snapshot.model(),
                    text(metadata.get("apiSurface")),
                    snapshot.contextTokens(),
                    intValue(metadata.get("maxOutputTokens")),
                    snapshot.capabilities(),
                    text(metadata.get("credentialEnv")),
                    text(metadata.get("routingDecision")),
                    routeScore,
                    eligible,
                    fallbackOnly,
                    failureClasses,
                    disabledReason,
                    metadata);
        }

        private static Integer intValue(Object value) {
            if (value instanceof Number number) {
                return number.intValue();
            }
            return null;
        }

        private static String text(Object value) {
            if (value == null) {
                return null;
            }
            String text = String.valueOf(value).trim();
            return text.isEmpty() ? null : text;
        }
    }
}
