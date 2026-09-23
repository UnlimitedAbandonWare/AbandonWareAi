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
    private volatile List<Choice> cached = List.of();
    private volatile long expiresAt;
    private List<Choice> publicCatalog = List.of();
    private long publicExpiresAt;

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
            try {
                JsonNode tags = http.getForObject(base + "/api/tags", JsonNode.class);
                if (tags != null && tags.path("models").isArray()) {
                    for (JsonNode tag : tags.path("models")) {
                        if (rows.size() >= 64 || System.nanoTime() >= deadline) break;
                        String id = tag.path("name").asText("");
                        if (!validId(id) || !ModelCapabilities.isLocalChatModelId(id)) continue;
                        if (!tag.path("remote_host").asText("").isBlank()) {
                            rows.add(local(id, false, "remote_model_requires_route", "installed"));
                            continue;
                        }
                        try {
                            var headers = new org.springframework.http.HttpHeaders();
                            headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
                            JsonNode detail = http.postForObject(base + "/api/show",
                                    new org.springframework.http.HttpEntity<>(Map.of("model", id), headers), JsonNode.class);
                            boolean chat = detail != null && detail.path("capabilities").isArray()
                                    && contains(detail.path("capabilities"), "completion");
                            if (!chat) continue; // embedding-only models are not chat choices
                            boolean blocked = health != null && health.snapshot("local", id).isPresent()
                                    && !health.isPromotable("local", id);
                            rows.add(local(id, !blocked, blocked ? "health_unavailable" : "", "installed_chat_capability"));
                        } catch (RuntimeException unavailable) {
                            rows.add(local(id, false, "capability_not_observed", "installed"));
                        }
                    }
                }
            } catch (RuntimeException unavailable) {
                // The response contains no upstream body, endpoint or exception text.
            }
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
                String reason = !allowRemote ? "remote_selection_disabled"
                        : row.disabledReason() == null ? "route_not_configured" : row.disabledReason();
                // Availability is separate from generation success; no paid route is enabled here.
                boolean ready = allowRemote && row.eligible() && route != null && !route.isBlank();
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

    public Optional<Choice> resolve(String id) {
        if (id == null) return Optional.empty();
        return choices().stream().filter(row -> row.id().equals(id)).findFirst();
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
