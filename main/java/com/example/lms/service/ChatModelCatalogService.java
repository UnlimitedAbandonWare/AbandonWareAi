package com.example.lms.service;

import com.example.lms.llm.ModelCapabilities;
import com.example.lms.llm.ModelRuntimeHealthTracker;
import com.example.lms.llm.gateway.CloudModelRouteClassifier;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import java.net.URI;
import java.time.Duration;
import java.util.*;

/** Read-only picker projection. Discovery never enables routes or generates text. */
@Service
public class ChatModelCatalogService {
    public record Choice(String id, String provider, String endpointId, String modelId,
            String status, boolean selectable, String reason, String release,
            String evidence) {}
    private final CloudModelRouteClassifier cloud;
    private final ModelRuntimeHealthTracker health;
    private final RestTemplate http;
    private final String base;
    private final boolean allowRemote;
    @Value("${app.ai.remote-model-selection-routes:}")
    private String remoteSelectionRoutes = "";
    private volatile List<Choice> cached = List.of();
    private volatile long expiresAt;
    private List<Choice> publicCatalog = List.of();
    private long publicExpiresAt;
    // Local inventory observation state: a failed or malformed /api/tags response
    // is not evidence that installed models were deleted.
    private volatile boolean inventoryObserved;
    private volatile String inventoryFailure;
    private final Set<String> detailProbeConsumed = new HashSet<>();
    private long recheckReadyAt;
    private static final long RECHECK_INTERVAL_MS = 5_000L;

    public ChatModelCatalogService(CloudModelRouteClassifier cloud, ModelRuntimeHealthTracker health,
            RestTemplateBuilder builder,
            @Value("${llm.base-url:${llm.ollama.base-url:http://localhost:11434/v1}}") String base,
            @Value("${app.ai.allow-remote-model-selection:false}") boolean allowRemote) {
        this.cloud = cloud;
        this.health = health;
        this.http = builder.setConnectTimeout(Duration.ofMillis(600))
                .setReadTimeout(Duration.ofMillis(900)).build();
        this.base = base.replaceAll("/+$", "").replaceFirst("/v1$", "");
        this.allowRemote = allowRemote;
    }

    public synchronized List<Choice> choices() {
        if (System.currentTimeMillis() < expiresAt) return cached;
        List<Choice> rows = new ArrayList<>();
        // No browser-supplied URL and no credentials. Remote/local gateways must use
        // their existing authenticated probe; never send an unguarded discovery call.
        if (loopback(base)) {
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            boolean observed = false;
            try {
                JsonNode tags = http.getForObject(base + "/api/tags", JsonNode.class);
                if (tags != null && tags.path("models").isArray()) {
                    for (JsonNode tag : tags.path("models")) {
                        if (rows.size() >= 64) break;
                        String id = tag.path("name").asText("");
                        if (!validId(id) || !ModelCapabilities.isLocalChatModelId(id)) continue;
                        if (!tag.path("remote_host").asText("").isBlank()) {
                            rows.add(local(id, false, "remote_model_requires_route", "installed"));
                            continue;
                        }
                        Choice observedChoice = System.nanoTime() >= deadline
                                ? local(id, false, "capability_not_observed", "installed")
                                : inspectLocalModel(id);
                        if (observedChoice != null) rows.add(observedChoice);
                    }
                    observed = true;
                } else {
                    // HTTP 200 without an inventory body is an observation failure,
                    // not proof of an empty installation.
                    inventoryFailure = "malformed";
                }
            } catch (org.springframework.web.client.HttpClientErrorException.Unauthorized
                    | org.springframework.web.client.HttpClientErrorException.Forbidden denied) {
                inventoryFailure = "unauthorized";
            } catch (RuntimeException unavailable) {
                inventoryFailure = "unavailable";
            }
            if (observed) {
                // A new authoritative generation grants one new bounded detail probe
                // per model; the stale-failure path must not re-open it.
                inventoryObserved = true;
                inventoryFailure = null;
                detailProbeConsumed.clear();
            } else {
                // A failed probe is not evidence that installed models were deleted.
                // Retain observations but never authorize generation from stale inventory.
                inventoryObserved = false;
                for (Choice previous : cached) {
                    if ("Ollama".equals(previous.provider()))
                        rows.add(local(previous.id(), false, "catalog_unavailable", "previously_installed"));
                }
            }
        } else {
            inventoryObserved = false;
        }
        if (cloud != null) {
            for (var row : cloud.classifyDefaultCatalog("chat")) {
                if (!validId(row.modelId())) continue;
                String route = row.routeKey();
                // Unverified manifest placeholders are not real selectable model IDs.
                if (row.capabilities().contains("model_discovery")
                        || ((route == null || route.isBlank())
                        && "attachment_unverified".equals(row.metadata().get("catalogTrust")))) continue;
                String id = route == null || route.isBlank()
                        ? "catalog:" + row.provider() + ":" + row.modelId() : "llmrouter." + route;
                boolean permitted = allowRemote || (route != null && !route.isBlank()
                        && Arrays.stream(remoteSelectionRoutes.split(","))
                                .map(String::trim).anyMatch(route::equals));
                String reason = !permitted ? "remote_selection_disabled"
                        : row.disabledReason() == null ? "route_not_configured" : row.disabledReason();
                // Availability is separate from generation success; no paid route is enabled here.
                boolean ready = permitted && row.eligible() && route != null && !route.isBlank();
                rows.add(new Choice(id, row.provider(), route == null ? "unconfigured" : route,
                        row.modelId(), ready ? "configured" : "unavailable", ready,
                        ready ? "" : reason, release(row.modelId(), row.metadata()),
                        "server_catalog"));
            }
        }
        cached = rows.stream().distinct().sorted(Comparator.comparing(Choice::provider)
                .thenComparing(Choice::modelId)).toList();
        expiresAt = System.currentTimeMillis() + 30_000;
        return cached;
    }

    public synchronized Optional<Choice> resolve(String id) {
        if (id == null) return Optional.empty();
        Optional<Choice> selected = choices().stream().filter(row -> row.id().equals(id)).findFirst();
        if (selected.isPresent() && "Ollama".equals(selected.get().provider())
                && "capability_not_observed".equals(selected.get().reason())
                && detailProbeConsumed.add(id)) {
            // At most one extra detail probe per model per observed inventory
            // generation. The slot is consumed before I/O, so concurrent and
            // repeated resolve() calls share this single attempt's outcome
            // instead of re-entering a probe loop on the same generation.
            Choice observed = inspectLocalModel(id);
            List<Choice> updated = new ArrayList<>(cached);
            updated.removeIf(row -> row.id().equals(id));
            if (observed != null) updated.add(observed);
            cached = List.copyOf(updated);
            return Optional.ofNullable(observed);
        }
        return selected;
    }

    /** User-triggered metadata recheck: a bounded forced inventory refresh plus
     *  this generation's single detail probe. Never downloads, warms or generates. */
    public synchronized Optional<Choice> recheck(String id) {
        if (!validId(id)) return Optional.empty();
        long now = System.currentTimeMillis();
        if (now >= recheckReadyAt) {
            recheckReadyAt = now + RECHECK_INTERVAL_MS;
            expiresAt = 0L;
        }
        return resolve(id);
    }

    /** Public failure code that separates confirmed absence from an
     *  inventory that could not be observed at all. */
    public synchronized String failureCode(Choice choice) {
        if (choice == null) {
            if ("unauthorized".equals(inventoryFailure)) return "provider_unauthorized";
            return inventoryObserved ? "model_unavailable" : "backend_unavailable";
        }
        return selectionFailureCode(choice);
    }

    private Choice inspectLocalModel(String id) {
        try {
            var headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            JsonNode detail = http.postForObject(base + "/api/show",
                    new org.springframework.http.HttpEntity<>(Map.of("model", id), headers), JsonNode.class);
            if (detail == null || !detail.path("capabilities").isArray())
                return local(id, false, "capability_not_observed", "installed");
            if (!contains(detail.path("capabilities"), "completion")) return null;
            boolean blocked = health != null && health.snapshot("local", id).isPresent()
                    && !health.isPromotable("local", id);
            return local(id, !blocked, blocked ? "health_unavailable" : "", "installed_chat_capability");
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound missing) {
            return null;
        } catch (org.springframework.web.client.HttpClientErrorException.Unauthorized
                | org.springframework.web.client.HttpClientErrorException.Forbidden denied) {
            return local(id, false, "provider_unauthorized", "installed");
        } catch (RuntimeException unavailable) {
            return local(id, false, "capability_not_observed", "installed");
        }
    }

    public static String selectionFailureCode(Choice choice) {
        if (choice == null) return "model_unavailable";
        if (choice.reason().contains("unauthorized")) return "provider_unauthorized";
        if (choice.reason().contains("disabled") || choice.reason().contains("requires_route")
                || choice.reason().contains("not_configured")) return "provider_not_configured";
        return "backend_unavailable";
    }

    /** Explicit user catalog browsing only: public metadata GET, no credentials or generation. */
    public synchronized List<Choice> choices(boolean discover) {
        var merged = new LinkedHashMap<String, Choice>();
        for (Choice row : choices()) merged.put(row.id(), row);
        if (!discover) return List.copyOf(merged.values());
        if (System.currentTimeMillis() >= publicExpiresAt) {
            var found = new ArrayList<Choice>();
            try {
                JsonNode response = http.getForObject(
                        "https://openrouter.ai/api/v1/models?limit=1000&output_modalities=text", JsonNode.class);
                if (response != null && response.path("data").isArray()) {
                    for (JsonNode model : response.path("data")) {
                        if (found.size() >= 1000) break;
                        String id = model.path("id").asText("");
                        if (!validId(id) || !contains(model.path("architecture").path("output_modalities"), "text")) continue;
                        boolean alreadyListed = merged.values().stream().anyMatch(
                                row -> "openrouter".equals(row.provider()) && id.equals(row.modelId()));
                        if (alreadyListed) continue;
                        found.add(new Choice("catalog:openrouter:" + id, "openrouter", "unconfigured",
                                id, "unavailable", false, "route_not_configured",
                                release(id, Map.of()), "public_catalog_only"));
                    }
                }
            } catch (RuntimeException unavailable) {
                // Keep existing configured/local choices; never invent model IDs on lookup failure.
            }
            publicCatalog = List.copyOf(found);
            publicExpiresAt = System.currentTimeMillis() + 300_000;
        }
        for (Choice row : publicCatalog) merged.putIfAbsent(row.id(), row);
        return List.copyOf(merged.values());
    }

    private static Choice local(String id, boolean ready, String reason, String evidence) {
        return new Choice(id, "Ollama", "local-default", id, ready ? "installed" : "unavailable",
                ready, reason, "unknown", evidence);
    }
    static boolean loopback(String base) {
        try {
            URI uri = URI.create(base);
            return Set.of("http", "https").contains(uri.getScheme()) && uri.getUserInfo() == null
                    && uri.getQuery() == null && uri.getFragment() == null
                    && Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(uri.getHost());
        } catch (RuntimeException invalid) { return false; }
    }
    static boolean validId(String id) {
        return id != null && id.length() <= 180 && id.matches("[a-zA-Z0-9][a-zA-Z0-9._/:+-]*");
    }
    private static boolean contains(JsonNode values, String expected) {
        for (JsonNode value : values) if (expected.equals(value.asText())) return true;
        return false;
    }
    private static String release(String model, Map<String, Object> metadata) {
        Object lifecycle = metadata.get("releaseStatus");
        if (lifecycle != null && Set.of("stable", "preview").contains(lifecycle.toString())) return lifecycle.toString();
        return model.toLowerCase(Locale.ROOT).contains("preview") ? "preview" : "unknown";
    }
}
