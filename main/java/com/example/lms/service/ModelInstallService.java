package com.example.lms.service;

import com.example.lms.config.LocalLlmProcessManager;
import com.example.lms.llm.gateway.CloudModelRouteClassifier;
import com.example.lms.llm.gateway.CloudModelRouteClassifier.CloudModelRouteRow;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thin web-install orchestration over the existing routing seams.
 * target=local delegates to {@link LocalLlmProcessManager}'s managed Ollama
 * endpoint; target=cloud only widens the catalog's runtime per-route allowlist
 * after a route binding and manifest-enable check through the existing
 * classifier. No new provider client, catalog, factory, aspect, or OpenRouter
 * runtime is created here.
 */
@Service
public class ModelInstallService {
    private final LocalLlmProcessManager localLlm;
    private final ChatModelCatalogService catalog;
    private final CloudModelRouteClassifier cloud;
    private final AtomicBoolean localInstallWatchPending = new AtomicBoolean();

    public ModelInstallService(LocalLlmProcessManager localLlm,
            ChatModelCatalogService catalog,
            CloudModelRouteClassifier cloud) {
        this.localLlm = localLlm;
        this.catalog = catalog;
        this.cloud = cloud;
    }

    public Map<String, Object> install(String target, String model, String route) {
        String normalized = target == null ? "" : target.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "local" -> pullLocal(model);
            case "cloud" -> registerCloud(route);
            default -> result("rejected", "invalid_target", normalized, null);
        };
    }

    /** Metadata-only install status. Polling completes the post-install
     *  catalog refresh once a local pull is observed as installed. */
    public Map<String, Object> status() {
        Map<String, Object> pull = localLlm == null
                ? Map.of("status", "unavailable")
                : localLlm.installPullStatus();
        if (localInstallWatchPending.get()) {
            if ("installed".equals(pull.get("status"))) {
                catalog.expireCache();
                localInstallWatchPending.set(false);
            } else if ("failed".equals(pull.get("status"))) {
                localInstallWatchPending.set(false);
            }
        }
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("localPull", pull);
        status.put("watchPending", localInstallWatchPending.get());
        return status;
    }

    private Map<String, Object> pullLocal(String model) {
        if (localLlm == null) {
            return result("rejected", "local_manager_unavailable", "local", null);
        }
        Map<String, Object> pull = localLlm.requestModelPull(model);
        String status = String.valueOf(pull.getOrDefault("status", "rejected"));
        String reason = String.valueOf(pull.getOrDefault("reason", ""));
        Map<String, Object> result = result(status, reason, "local", null);
        if ("accepted".equals(status)) {
            localInstallWatchPending.set(true);
        }
        result.put("pull", pull);
        return result;
    }

    private Map<String, Object> registerCloud(String routeId) {
        String route = routeId == null ? "" : routeId.trim();
        if (!ChatModelCatalogService.validRouteKey(route)) {
            return result("rejected", "invalid_route", "cloud", route);
        }
        CloudModelRouteRow row = findRouteRow(route);
        if (row == null || row.capabilities().contains("model_discovery")) {
            return result("rejected", "route_not_configured", "cloud", route);
        }
        Map<String, Object> result;
        if ("cloud_manifest_disabled".equals(row.disabledReason())) {
            result = result("rejected", "cloud_manifest_disabled", "cloud", route);
        } else {
            boolean wasApproved = catalog.isRouteApproved(route);
            boolean added = catalog.registerApprovedRoute(route);
            result = result(wasApproved || !added ? "already_registered" : "registered",
                    "", "cloud", route);
            result.put("persistent", false);
            result.put("selectableNow", row.eligible());
            if (!row.eligible() && row.disabledReason() != null) {
                result.put("disabledReason", row.disabledReason());
            }
        }
        result.put("provider", row.provider());
        result.put("modelId", row.modelId());
        result.put("fallbackOnly", row.fallbackOnly());
        return result;
    }

    private CloudModelRouteRow findRouteRow(String route) {
        if (cloud == null) return null;
        for (CloudModelRouteRow row : cloud.classifyDefaultCatalog("chat")) {
            if (route.equals(row.routeKey())) return row;
        }
        return null;
    }

    private static Map<String, Object> result(String status, String reason, String target, String route) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("target", target == null ? "" : target);
        if (reason != null && !reason.isBlank()) {
            result.put("reason", reason);
        }
        if (route != null && !route.isBlank()) {
            result.put("route", route);
        }
        return result;
    }
}
