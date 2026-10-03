package com.example.lms.routing;

import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.*;

/** Immutable, separately parsed routing and spend documents. No selection or I/O to providers. */
@Component
public final class ApiRoutingPolicySnapshot {
    public record Route(String id, String tier, List<String> models) {}
    private record Documents(Map<String, Object> routing, Map<String, Object> spend,
                             Map<String, String> hashes) {}
    private static final class Defaults {
        private static final Documents DOCUMENTS = readDocuments();
    }

    private final Environment environment;
    private final List<String> order;
    private final Map<String, List<Route>> routes;
    private final List<String> chatModels;
    private final Map<String, List<String>> roleModels;
    private final Map<String, String> aliases;
    private final Map<String, Object> activation;
    private final Map<String, Object> spendPolicy;
    private final Map<String, String> hashes;

    public ApiRoutingPolicySnapshot(Environment environment) {
        this.environment = environment == null ? new StandardEnvironment() : environment;
        Documents documents = Defaults.DOCUMENTS;
        order = strings(map(documents.routing().get("policy")).get("order"));
        if (!order.equals(List.of("free_local", "low_cost", "paid_quality"))) {
            throw new IllegalStateException("api_routing_tier_order_invalid");
        }
        Map<String, List<Route>> parsed = new LinkedHashMap<>();
        map(documents.routing().get("routes")).forEach((purpose, rows) -> {
            List<Route> candidates = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            if (!(rows instanceof List<?> list)) throw new IllegalStateException("api_routing_routes_invalid");
            for (Object row : list) {
                Map<String, Object> values = map(row);
                String id = text(values.get("id"));
                String tier = text(values.get("tier"));
                if (id.isBlank() || !ids.add(id) || !order.contains(tier)) {
                    throw new IllegalStateException("api_routing_candidate_invalid");
                }
                candidates.add(new Route(id, tier, strings(values.get("model_pref"))));
            }
            candidates.sort(Comparator.comparingInt(route -> order.indexOf(route.tier())));
            parsed.put(purpose, List.copyOf(candidates));
        });
        routes = Map.copyOf(parsed);
        Map<String, Object> ollama = map(documents.routing().get("ollama"));
        chatModels = strings(map(ollama.get("installed_models")).get("chat"));
        Map<String, List<String>> roles = new LinkedHashMap<>();
        map(ollama.get("installed_models")).forEach((role, models) -> roles.put(role, strings(models)));
        roleModels = Map.copyOf(roles);
        Map<String, String> aliasCopy = new LinkedHashMap<>();
        map(ollama.get("alias_to_installed")).forEach((key, value) -> aliasCopy.put(key, text(value)));
        aliases = Map.copyOf(aliasCopy);
        activation = map(documents.spend().get("activation"));
        spendPolicy = map(documents.spend().get("policy"));
        hashes = documents.hashes();
    }

    public List<Route> routes(String purpose) { return routes.getOrDefault(purpose, List.of()); }
    public Map<String, String> sourceHashes() { return hashes; }
    public boolean productionHardCaps() { return Boolean.TRUE.equals(spendPolicy.get("production_hard_caps")); }
    public int maxClassifiedRetries() {
        Object value = spendPolicy.get("max_classified_retries");
        return value instanceof Number number ? Math.max(0, number.intValue()) : 0;
    }
    public boolean dedupeVerification() {
        return Boolean.TRUE.equals(spendPolicy.get("dedupe_identical_probes"))
                && Boolean.TRUE.equals(spendPolicy.get("skip_successful_verification_replay"));
    }
    public boolean agentModeActive() {
        if (truthy(environment.getProperty("awx.agent.spend-guard"))
                || truthy(System.getProperty("awx.agent.spend-guard"))) return true;
        for (String name : strings(activation.get("env_any_true"))) {
            if (truthy(environment.getProperty(name))) return true;
        }
        for (String name : strings(activation.get("env_present"))) {
            if (!text(environment.getProperty(name)).isBlank()) return true;
        }
        return Arrays.stream(environment.getActiveProfiles())
                .anyMatch(strings(activation.get("spring_profiles"))::contains);
    }
    public boolean explicitPaidOverride() {
        return truthy(environment.getProperty(text(spendPolicy.get("allow_explicit_override_env"))));
    }
    public boolean staleAutoModel(String model) {
        return strings(spendPolicy.get("block_stale_auto_models_in_agent_mode")).contains(model);
    }
    public String canonicalModel(String model) { return aliases.getOrDefault(model, model); }
    public boolean chatModelAllowed(String model) { return chatModels.contains(canonicalModel(model)); }
    public int tier(String purpose, String provider) {
        return routes(purpose).stream().filter(route -> route.id().equals(provider))
                .mapToInt(route -> order.indexOf(route.tier())).findFirst().orElse(Integer.MAX_VALUE);
    }
    public String tierName(int tier) { return tier >= 0 && tier < order.size() ? order.get(tier) : "unlisted"; }
    public boolean automaticModelAllowed(String provider, String model, boolean local) {
        return automaticModelAllowed(provider, model, local, "chat");
    }
    public boolean automaticModelAllowed(String provider, String model, boolean local, String stage) {
        if (local) return roleModels.getOrDefault(stage == null ? "chat" : stage, chatModels).contains(canonicalModel(model));
        int tier = tier("llm", provider);
        return tier != Integer.MAX_VALUE && (!agentModeActive() || explicitPaidOverride()
                || (!staleAutoModel(model) && tier < order.indexOf("paid_quality")));
    }

    private static Documents readDocuments() {
        Map<String, String> hashes = new LinkedHashMap<>();
        Map<String, Object> routing = read("configs/api-routing.yaml", hashes);
        Map<String, Object> spend = read("configs/agent-api-spend-guard.yaml", hashes);
        return new Documents(routing, spend, Map.copyOf(hashes));
    }
    private static Map<String, Object> read(String path, Map<String, String> hashes) {
        try (var in = new ClassPathResource(path).getInputStream()) {
            byte[] bytes = in.readAllBytes();
            hashes.put(path, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            return map(new Yaml(new SafeConstructor(options)).load(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)));
        } catch (IOException | java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("api_policy_resource_unavailable", error);
        }
    }
    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) throw new IllegalStateException("api_policy_mapping_invalid");
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> result.put(String.valueOf(key), item));
        return Collections.unmodifiableMap(result);
    }
    private static List<String> strings(Object value) {
        return value instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
    }
    private static String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private static boolean truthy(String value) {
        return value != null && Set.of("1", "true", "yes", "on").contains(value.trim().toLowerCase(Locale.ROOT));
    }
}
