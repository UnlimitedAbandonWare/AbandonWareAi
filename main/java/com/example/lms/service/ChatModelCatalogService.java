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
            String evidence, boolean defaultChoice, List<String> capabilities, List<String> reasons,
            boolean configured, boolean runtimeVerified, Map<String,Object> metadata) {
        public Choice {
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
            capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
            reason = reason == null ? "" : reason;
            var codes = new LinkedHashSet<String>();
            if (!reason.isBlank()) codes.add(reason);
            if (reasons != null) reasons.stream().filter(Objects::nonNull).filter(s -> !s.isBlank()).forEach(codes::add);
            reasons = List.copyOf(codes);
        }
        public Choice(String id, String provider, String endpointId, String modelId,
                String status, boolean selectable, String reason, String release, String evidence,
                boolean defaultChoice, List<String> capabilities, List<String> reasons) {
            this(id,provider,endpointId,modelId,status,selectable,reason,release,evidence,defaultChoice,
                    capabilities,reasons,"configured".equals(status),false,Map.of());
        }
        public Choice(String id, String provider, String endpointId, String modelId,
                String status, boolean selectable, String reason, String release, String evidence,
                boolean defaultChoice, List<String> capabilities) {
            this(id, provider, endpointId, modelId, status, selectable, reason, release, evidence,
                    defaultChoice, capabilities, List.of());
        }
        public Choice(String id, String provider, String endpointId, String modelId,
                String status, boolean selectable, String reason, String release, String evidence) {
            this(id, provider, endpointId, modelId, status, selectable, reason, release, evidence, false, List.of());
        }
    }
    @Value("${llmrouter.api-first.enabled:false}")
    private boolean apiFirstEnabled;
    @Value("${llmrouter.api-first.route-order:}")
    private String apiFirstRouteOrder = "";
    private final CloudModelRouteClassifier cloud;
    private final ModelRuntimeHealthTracker health;
    private final RestTemplate http;
    private final String base;
    private final boolean allowRemote;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.lms.llm.ChatGptOAuthRegistration chatGptOAuth;
    @Value("${app.ai.remote-model-selection-routes:}")
    private String remoteSelectionRoutes = "";
    private final Object stateLock = new Object();
    private volatile Set<String> runtimeApprovedRoutes = Set.of();
    private long generation;
    private boolean serverRefreshing;
    private boolean publicRefreshing;
    private volatile List<Choice> cached = List.of();
    private volatile long expiresAt;
    private volatile List<Choice> publicCatalog = List.of();
    private volatile long publicExpiresAt;
    private volatile boolean publicCatalogStale;
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

    public List<Choice> choices() {
        return choices(com.example.lms.llm.RequestedModelSelection.ownerHash());
    }
    public List<Choice> choices(String ownerHash) {
        List<Choice> serverRows = serverChoices();
        List<String> oauthModels = chatGptOAuth == null ? List.of() : chatGptOAuth.models(ownerHash);
        if (oauthModels.isEmpty()) return serverRows;
        List<Choice> rows = new ArrayList<>(serverRows);
        if (chatGptOAuth != null) {
            for (String slug : oauthModels) {
                rows.add(new Choice(com.example.lms.llm.ChatGptOAuthRegistration.route(slug),
                        com.example.lms.llm.ChatGptOAuthRegistration.PROVIDER, "chatgpt-oauth", slug,
                        "configured", true, "", "account_catalog", "chatgpt_account_catalog"));
            }
        }
        return List.copyOf(rows);
    }

    /** Only the server/API-key inventory is cached; account file changes are observed per call. */
    private List<Choice> serverChoices() {
        if (System.currentTimeMillis() < expiresAt) return cached;
        final long flight;
        synchronized (stateLock) {
            if (System.currentTimeMillis() < expiresAt || serverRefreshing) return cached;
            serverRefreshing = true;
            flight = generation;
        }
        try {
        List<Choice> rows = new ArrayList<>();
        boolean observed = false;
        String failure = null;
        // No browser-supplied URL and no credentials. Remote/local gateways must use
        // their existing authenticated probe; never send an unguarded discovery call.
        if (loopback(base)) {
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
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
                    failure = "malformed";
                }
            } catch (org.springframework.web.client.HttpClientErrorException.Unauthorized
                    | org.springframework.web.client.HttpClientErrorException.Forbidden denied) {
                failure = "unauthorized";
            } catch (RuntimeException unavailable) {
                failure = "unavailable";
            }
            if (observed) {
                // A new authoritative generation grants one new bounded detail probe
                // per model; the stale-failure path must not re-open it.
                failure = null;
            } else {
                // A failed probe is not evidence that installed models were deleted.
                // Retain observations but never authorize generation from stale inventory.
                for (Choice previous : cached) {
                    if ("Ollama".equals(previous.provider()))
                        rows.add(local(previous.id(), false, "catalog_unavailable", "previously_installed"));
                }
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
                boolean permitted = allowRemote || (apiFirstEnabled
                        && !com.example.lms.llm.ChatGptOAuthRegistration.PROVIDER.equals(row.provider()))
                        || (route != null && !route.isBlank()
                        && (Arrays.stream(remoteSelectionRoutes.split(","))
                                .map(String::trim).anyMatch(route::equals)
                                || runtimeApprovedRoutes.contains(route)));
                var reasons = new LinkedHashSet<String>();
                if (!permitted) reasons.add("remote_selection_disabled");
                if (!row.eligible()) {
                    String routeReason = row.disabledReason();
                    if (routeReason != null && !routeReason.isBlank())
                        reasons.add(routeReason.matches("[a-z][a-z0-9_]*") ? routeReason : "model_unavailable");
                    Object additional = row.metadata().get("disabledReasons");
                    if (additional instanceof Collection<?> codes)
                        codes.stream().filter(String.class::isInstance).map(String.class::cast)
                                .filter(s -> s.matches("[a-z][a-z0-9_]*")).forEach(reasons::add);
                    if (reasons.isEmpty() || reasons.equals(Set.of("remote_selection_disabled")))
                        reasons.add("model_unavailable");
                }
                if (route == null || route.isBlank()) reasons.add("metadata_unverified");
                boolean gateway = "vercel-gateway".equals(row.provider());
                if (gateway) reasons.add("provider_restriction_unsupported");
                // Availability is separate from generation success; no paid route is enabled here.
                boolean ready = permitted && row.eligible() && route != null && !route.isBlank() && !gateway;
                rows.add(new Choice(id, row.provider(), route == null ? "unconfigured" : route,
                        row.modelId(), ready ? "configured" : "unavailable", ready,
                        ready ? "" : reasons.iterator().next(), release(row.modelId(), row.metadata()),
                        "server_catalog", false, row.capabilities(), ready ? List.of() : List.copyOf(reasons),
                        row.eligible(), health != null && health.snapshot(row.provider(),row.modelId())
                                .filter(s -> s.lastSuccess() && s.successCount() > 0).isPresent(),
                        auxiliaryMetadata(row.metadata())));
            }
        }
        List<Choice> snapshot = rows.stream().distinct().sorted(Comparator.comparing(Choice::provider)
                .thenComparing(Choice::modelId)).toList();
        if (apiFirstEnabled) {
            var order = Arrays.stream(apiFirstRouteOrder.split(",")).map(String::trim).toList();
            String defaultId = snapshot.stream().filter(c -> c.selectable() && c.id().startsWith("llmrouter.")
                    && !Set.of("Ollama", "local", "ollama", "local_llm",
                            com.example.lms.llm.ChatGptOAuthRegistration.PROVIDER).contains(c.provider()))
                    .min(Comparator.comparingInt(c -> {
                        int rank = order.indexOf(c.endpointId()); return rank < 0 ? 1000 : rank;
                    })).map(Choice::id).orElse("");
            snapshot = snapshot.stream().map(c -> new Choice(c.id(), c.provider(), c.endpointId(), c.modelId(),
                    c.status(), c.selectable(), c.reason(), c.release(), c.evidence(),
                    c.id().equals(defaultId), c.capabilities(), c.reasons(),
                    c.configured(), c.runtimeVerified(), c.metadata())).toList();
        }
        synchronized (stateLock) {
            if (flight == generation) {
                cached = snapshot;
                inventoryObserved = observed;
                inventoryFailure = failure;
                if (observed) detailProbeConsumed.clear();
                expiresAt = System.currentTimeMillis() + 30_000;
            }
        }
        return cached;
        } finally {
            synchronized (stateLock) { serverRefreshing = false; }
        }
    }

    /** Settings policy reads reuse this snapshot; they never initiate catalog discovery. */
    private static Map<String,Object> auxiliaryMetadata(Map<String,Object> source) {
        var result = new LinkedHashMap<String,Object>();
        for (String key : List.of("enabled","supportedRoles","hostingProvider","endpointRoute",
                "apiSurface","capabilityStatus","price","costTier","providerRestrictionSupported","dataPolicy")) {
            if (source.containsKey(key)) result.put(key,source.get(key));
        }
        return Map.copyOf(result);
    }

    public List<Choice> observedServerChoices() { return List.copyOf(cached); }

    public Optional<Choice> resolve(String id) {
        return resolve(id, com.example.lms.llm.RequestedModelSelection.ownerHash());
    }
    public Optional<Choice> resolve(String id, String ownerHash) {
        if (id == null) return Optional.empty();
        Optional<Choice> selected = choices(ownerHash).stream().filter(row -> row.id().equals(id)).findFirst();
        if (selected.isPresent() && "Ollama".equals(selected.get().provider())
                && "capability_not_observed".equals(selected.get().reason())) {
            final long flight;
            final List<Choice> before;
            synchronized (stateLock) {
                if (!detailProbeConsumed.add(id)) return selected;
                flight = generation;
                before = cached;
            }
            // At most one extra detail probe per model per observed inventory
            // generation. The slot is consumed before I/O, so concurrent and
            // repeated resolve() calls share this single attempt's outcome
            // instead of re-entering a probe loop on the same generation.
            Choice observed = inspectLocalModel(id);
            List<Choice> updated = new ArrayList<>(before);
            updated.removeIf(row -> row.id().equals(id));
            if (observed != null) updated.add(observed);
            synchronized (stateLock) {
                if (flight == generation && cached == before) {
                    cached = List.copyOf(updated);
                    return Optional.ofNullable(observed);
                }
            }
            return cached.stream().filter(row -> row.id().equals(id)).findFirst();
        }
        return selected;
    }

    /** User-triggered metadata recheck: a bounded forced inventory refresh plus
     *  this generation's single detail probe. Never downloads, warms or generates. */
    public Optional<Choice> recheck(String id) {
        return recheck(id, com.example.lms.llm.RequestedModelSelection.ownerHash());
    }
    public Optional<Choice> recheck(String id, String ownerHash) {
        if (!validId(id)) return Optional.empty();
        long now = System.currentTimeMillis();
        synchronized (stateLock) {
            if (now >= recheckReadyAt) {
                recheckReadyAt = now + RECHECK_INTERVAL_MS;
                generation++;
                expiresAt = 0L;
            }
        }
        return resolve(id, ownerHash);
    }

    /** Post-install invalidation: the next choices() call re-observes inventory
     *  and probe state. Never itself pulls, warms or generates. */
    public void expireCache() {
        synchronized (stateLock) {
            generation++;
            expiresAt = 0L;
            publicExpiresAt = 0L;
        }
    }

    /** Runtime per-route opt-in added by an approved web-install registration.
     *  Extends the configured remoteSelectionRoutes for this process only;
     *  never flips app.ai.allow-remote-model-selection and does not survive a
     *  restart unless the operator persists CHAT_REMOTE_MODEL_SELECTION_ROUTES. */
    public boolean registerApprovedRoute(String routeId) {
        if (!validRouteKey(routeId)) return false;
        synchronized (stateLock) {
            if (runtimeApprovedRoutes.contains(routeId)) return false;
            Set<String> updated = new HashSet<>(runtimeApprovedRoutes);
            updated.add(routeId);
            runtimeApprovedRoutes = Set.copyOf(updated);
            generation++;
            expiresAt = 0L;
            return true;
        }
    }

    /** True when the route is selectable-permitted by either the configured
     *  allowlist or a runtime registration. Eligibility probing is unchanged. */
    public boolean isRouteApproved(String routeId) {
        return routeId != null && (runtimeApprovedRoutes.contains(routeId)
                || Arrays.stream(remoteSelectionRoutes.split(","))
                        .map(String::trim).anyMatch(routeId::equals));
    }

    static boolean validRouteKey(String id) {
        return id != null && id.length() <= 80 && id.matches("[a-zA-Z0-9][a-zA-Z0-9._-]*");
    }

    /** Public failure code that separates confirmed absence from an
     *  inventory that could not be observed at all. */
    public String failureCode(Choice choice) {
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
    public List<Choice> choices(boolean discover) {
        return choices(discover, com.example.lms.llm.RequestedModelSelection.ownerHash());
    }
    public List<Choice> choices(boolean discover, String ownerHash) {
        var merged = new LinkedHashMap<String, Choice>();
        for (Choice row : choices(ownerHash)) merged.put(row.id(), row);
        if (!discover) return List.copyOf(merged.values());
        refreshPublicCatalog(merged.values());
        boolean stale = publicCatalogStale;
        for (Choice row : publicCatalog) {
            Choice projection = stale ? new Choice(row.id(), row.provider(), row.endpointId(), row.modelId(),
                    "stale", false, row.reason(), row.release(), "public_catalog_stale") : row;
            merged.putIfAbsent(row.id(), projection);
        }
        return List.copyOf(merged.values());
    }

    private void refreshPublicCatalog(Collection<Choice> configured) {
        if (System.currentTimeMillis() < publicExpiresAt) return;
        final long flight;
        synchronized (stateLock) {
            if (System.currentTimeMillis() < publicExpiresAt || publicRefreshing) return;
            publicRefreshing = true;
            flight = generation;
        }
        try {
            var found = new ArrayList<Choice>();
            boolean success = false;
            try {
                JsonNode response = http.getForObject(
                        "https://openrouter.ai/api/v1/models?limit=1000&output_modalities=text", JsonNode.class);
                if (response != null && response.path("data").isArray()) {
                    success = true;
                    for (JsonNode model : response.path("data")) {
                        if (found.size() >= 1000) break;
                        String id = model.path("id").asText("");
                        if (!validId(id) || !contains(model.path("architecture").path("output_modalities"), "text")) continue;
                        boolean alreadyListed = configured.stream().anyMatch(
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
            synchronized (stateLock) {
                if (flight == generation) {
                    if (success) publicCatalog = List.copyOf(found);
                    publicCatalogStale = !success;
                    publicExpiresAt = System.currentTimeMillis() + (success ? 300_000 : 30_000);
                }
            }
        } finally {
            synchronized (stateLock) { publicRefreshing = false; }
        }
    }

    private static Choice local(String id, boolean ready, String reason, String evidence) {
        return new Choice(id, "Ollama", "local-default", id, ready ? "installed" : "unavailable",
                ready, reason, "unknown", evidence, false, ready ? List.of("completion") : List.of());
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
