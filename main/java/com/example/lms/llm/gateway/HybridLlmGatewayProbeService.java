package com.example.lms.llm.gateway;

import ai.abandonware.nova.config.LlmRouterProperties;
import com.example.lms.config.ConfigValueGuards;
import com.example.lms.llm.ModelRuntimeHealthTracker;
import com.example.lms.search.TraceStore;
import com.example.lms.llm.spec.ModelSpecRegistry;
import com.example.lms.llm.spec.ModelSpecSnapshot;
import com.example.lms.trace.SafeRedactor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Component
public class HybridLlmGatewayProbeService {

    private static final java.util.regex.Pattern ENV_NAME =
            java.util.regex.Pattern.compile("[A-Z_][A-Z0-9_]{1,127}");

    private final LlmGatewayProperties properties;
    private final ModelRuntimeHealthTracker healthTracker;
    private final ModelSpecRegistry specRegistry;
    private final LlmRouteScorer scorer;
    private final Environment environment;
    private final org.springframework.web.reactive.function.client.WebClient healthClient =
            org.springframework.web.reactive.function.client.WebClient.builder().build();
    private static final com.fasterxml.jackson.databind.ObjectMapper HEALTH_JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    @Autowired
    public HybridLlmGatewayProbeService(
            LlmGatewayProperties properties,
            ModelRuntimeHealthTracker healthTracker,
            ModelSpecRegistry specRegistry,
            LlmRouteScorer scorer,
            Environment environment) {
        this.properties = properties;
        this.healthTracker = healthTracker;
        this.specRegistry = specRegistry;
        this.scorer = scorer;
        this.environment = environment;
    }

    public HybridLlmGatewayProbeService(
            LlmGatewayProperties properties,
            ModelRuntimeHealthTracker healthTracker,
            ModelSpecRegistry specRegistry,
            LlmRouteScorer scorer) {
        this(properties, healthTracker, specRegistry, scorer, null);
    }

    public RoutingEligibility evaluate(String routeKey, LlmRouterProperties.ModelConfig cfg, String stage) {
        String provider = provider(routeKey, cfg);
        String model = cfg == null ? null : cfg.getName();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("enforcement", properties == null ? "observe" : properties.getEnforcement().name().toLowerCase(Locale.ROOT));
        meta.put("probeEnabled", properties == null || properties.getProbe().isEnabled());
        meta.put("endpointHost", endpointHost(cfg == null ? null : cfg.getBaseUrl()));

        if (properties == null || !properties.isEnabled() || cfg == null) {
            return RoutingEligibility.eligible(routeKey, provider, model, stage, 100, false, meta);
        }

        List<LlmFailureClass> failures = new ArrayList<>();
        if (!cfg.isEnabled()) {
            failures.add(LlmFailureClass.DISABLED);
        }
        if (!StringUtils.hasText(cfg.getName()) || !StringUtils.hasText(cfg.getBaseUrl())) {
            failures.add(LlmFailureClass.DISABLED);
            meta.put("disabledReason", "missing_route_config");
        }
        if (cfg.isEnabled()
                && StringUtils.hasText(cfg.getName())
                && StringUtils.hasText(cfg.getBaseUrl())
                && !isLocalProvider(provider)) {
            String credentialEnv = credentialEnv(provider, cfg);
            String safeCredentialEnv = safeCredentialEnv(credentialEnv);
            if (StringUtils.hasText(safeCredentialEnv)) {
                meta.put("credentialEnv", safeCredentialEnv);
            }
            if (!hasUsableCredential(credentialEnv)) {
                failures.add(LlmFailureClass.AUTH_MISSING);
                meta.put("disabledReason", isEnvName(credentialEnv)
                        ? "missing " + credentialEnv.trim()
                        : "missing_provider_api_key");
            }
        }
        if (cfg.isManagedFileSearch() && isLocalProvider(provider)) {
            failures.add(LlmFailureClass.LOCAL_UNSUPPORTED_MANAGED_RAG);
        }

        Optional<ModelSpecSnapshot> spec = specRegistry == null
                ? Optional.empty()
                : specRegistry.snapshot(provider, model);
        if (spec.isPresent()) {
            ModelSpecSnapshot snapshot = spec.get();
            meta.put("specObserved", true);
            meta.put("capabilities", snapshot.capabilities());
            if (snapshot.contextTokens() != null) {
                meta.put("contextTokens", snapshot.contextTokens());
            }
            if (snapshot.embeddingDim() != null) {
                meta.put("embeddingDim", snapshot.embeddingDim());
            }
            if (cfg.getMinContextTokens() != null && snapshot.contextTokens() != null
                    && snapshot.contextTokens() < cfg.getMinContextTokens()) {
                failures.add(LlmFailureClass.CONTEXT_TOO_SMALL);
            }
            if (cfg.getEmbeddingDim() != null && snapshot.embeddingDim() != null
                    && !cfg.getEmbeddingDim().equals(snapshot.embeddingDim())) {
                failures.add(LlmFailureClass.EMBEDDING_DIM_MISMATCH);
            }
        } else {
            meta.put("specObserved", false);
        }

        if (healthTracker != null) {
            healthTracker.snapshot(provider, model).ifPresent(snapshot -> {
                meta.put("healthLastSuccess", snapshot.lastSuccess());
                meta.put("healthFailureCount", snapshot.failureCount());
                Map<String, Object> publicHealth = healthTracker.redactedSnapshot(provider, model);
                putIfPresent(meta, "healthSampleCount", publicHealth.get("sampleCount"));
                putIfPresent(meta, "healthFailurePressure", publicHealth.get("failurePressure"));
                putIfPresent(meta, "healthRoutingHint", publicHealth.get("routingHint"));
                // Enabled local circuits own expiry/recovery; a historical model failure must not veto probes forever.
                if (!snapshot.lastSuccess() && !(isLocalProvider(provider)
                        && properties.getLocalDeviceFailover().isEnabled())) {
                    failures.add(toFailure(snapshot.lastReason()));
                }
            });
            applyEndpointQuarantine(provider, cfg, failures, meta);
        }

        int minScore = cfg.getMinRouteScore() == null ? properties.getMinRouteScore() : cfg.getMinRouteScore();
        int score = scorer.score(cfg, failures);
        boolean eligible = scorer.eligible(score, minScore, failures);
        meta.put("minRouteScore", minScore);

        return eligible
                ? RoutingEligibility.eligible(routeKey, provider, model, stage, score, cfg.isFallbackOnly(), meta)
                : RoutingEligibility.blocked(routeKey, provider, model, stage, score, cfg.isFallbackOnly(), failures, meta);
    }

    public boolean isEnforce() {
        return properties != null && properties.isEnforce();
    }

    public boolean cloudFallbackEnabled() {
        return properties != null && properties.getCloud().isEnabled();
    }

    public String cloudRouteKey() {
        return properties == null ? null : properties.getCloud().getRouteKey();
    }

    public boolean localFailoverEnabled() {
        return properties != null && properties.isEnforce() && properties.getLocalDeviceFailover().isEnforce();
    }

    /** Effective bound values only. No credential, endpoint URL, or conversation enters diagnostics. */
    public Map<String, Object> failoverDiagnostics() {
        if (properties == null) return Map.of("enabled", false);
        var policy = properties.getLocalDeviceFailover().toEndpointQuarantinePolicy();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", localFailoverEnabled());
        out.put("enforcement", properties.getEnforcement().name());
        out.put("cloudEnabled", cloudFallbackEnabled());
        out.put("cloudRoute", SafeRedactor.traceLabelOrFallback(cloudRouteKey(), "none"));
        out.put("failureThreshold", policy.transientFailureThreshold());
        out.put("failureWindowMs", policy.runnerFailureWindowMs());
        out.put("transientCooldownMs", policy.transientCooldownMs());
        out.put("hardCooldownMs", policy.hardCooldownMs());
        out.put("recoverySuccesses", policy.recoverySuccesses());
        out.put("recoveryStableMs", policy.recoveryStableMs());
        out.put("probeTimeoutMs", policy.probeTimeoutMs());
        out.put("healthSampleIntervalMs", policy.healthSampleIntervalMs());
        return Map.copyOf(out);
    }

    /** One guard at the actual local dispatch boundary, shared by native and compatible adapters. */
    public dev.langchain4j.model.chat.ChatModel guardLocalModel(dev.langchain4j.model.chat.ChatModel model,
            String baseUrl, String modelName) {
        return guardLocalModel(model, baseUrl, modelName, null);
    }

    public dev.langchain4j.model.chat.ChatModel guardLocalModel(dev.langchain4j.model.chat.ChatModel model,
            String baseUrl, String modelName, String deviceRole) {
        if (model == null || properties == null || healthTracker == null
                || !properties.getLocalDeviceFailover().isEnabled()) return model;
        var policy = properties.getLocalDeviceFailover().toEndpointQuarantinePolicy();
        return new dev.langchain4j.model.chat.ChatModel() {
            @Override public dev.langchain4j.model.chat.response.ChatResponse doChat(
                    dev.langchain4j.model.chat.request.ChatRequest request) {
                return doGuarded(request.messages(), request);
            }
            private dev.langchain4j.model.chat.response.ChatResponse doGuarded(
                    java.util.List<dev.langchain4j.data.message.ChatMessage> messages,
                    dev.langchain4j.model.chat.request.ChatRequest request) {
                com.example.lms.service.chat.ChatRunExecutionContext.throwIfCancelled();
                String endpoint = healthTracker.resolveServiceEndpoint(baseUrl);
                var access = healthTracker.acquireEndpointAccess("local", endpoint, policy, System.currentTimeMillis());
                TraceStore.put("llm.localEndpoint.selectionDecision", access.decision());
                if (!access.allowed()) {
                    publishLocalEndpointSnapshot(endpoint);
                    TraceStore.put("llm.localEndpoint.latencyMs", 0L);
                    throw new LlmGatewayException("Local circuit open", LlmFailureClass.GPU_DEVICE_LOST, "local_endpoint_open");
                }
                long started = System.nanoTime();
                try {
                    dev.langchain4j.model.chat.response.ChatResponse response;
                    if (request == null || messages == null || messages.isEmpty()) {
                        response = model.chat(messages);
                    } else {
                        try {
                            response = model.chat(request);
                        } catch (RuntimeException failure) {
                            if (isMissingDoChatContract(failure)) {
                                response = model.chat(messages);
                            } else {
                                throw failure;
                            }
                        }
                    }
                    com.example.lms.service.chat.ChatRunExecutionContext.throwIfCancelled();
                    if (response == null || response.aiMessage() == null || (!response.aiMessage().hasToolExecutionRequests()
                            && (response.aiMessage().text() == null || response.aiMessage().text().isBlank()))) throw new LlmGatewayException(
                                    "Local model returned no answer", LlmFailureClass.PROVIDER_ERROR, "empty_response");
                    healthTracker.recordEndpointModelSuccess("local", endpoint, modelName);
                    boolean gpuVerified = !access.halfOpenPermit() || gpuRecoveryVerified(endpoint, modelName, deviceRole);
                    com.example.lms.service.chat.ChatRunExecutionContext.throwIfCancelled();
                    healthTracker.completeEndpointAccess(access, gpuVerified, policy, System.currentTimeMillis());
                    return response;
                } catch (RuntimeException failure) {
                    var reason = new LlmGatewayFailureClassifier().classify(failure);
                    if (LlmGatewayFailureClassifier.isCancellation(failure)
                            || reason == LlmFailureClass.CANCELLED_NEUTRAL) {
                        healthTracker.releaseEndpointAccess(access);
                        throw failure;
                    }
                    TraceStore.put("llm.localEndpoint.failureClass", reason.name().toLowerCase(Locale.ROOT));
                    if (reason == LlmFailureClass.GPU_DEVICE_LOST) {
                        healthTracker.recordEndpointDeviceLoss("local", endpoint, policy, System.currentTimeMillis());
                    } else {
                        healthTracker.recordEndpointTransientFailure("local", endpoint, modelName, reason, policy,
                                System.currentTimeMillis());
                    }
                    if (reason == LlmFailureClass.GPU_DEVICE_LOST || reason == LlmFailureClass.HEALTH_DOWN
                            || reason == LlmFailureClass.TIMEOUT_SOFT || reason == LlmFailureClass.VRAM_OOM
                            || reason == LlmFailureClass.PROVIDER_ERROR)
                        healthTracker.completeEndpointAccess(access, false, policy, System.currentTimeMillis());
                    else healthTracker.releaseEndpointAccess(access);
                    throw failure;
                } finally {
                    healthTracker.releaseEndpointAccess(access);
                    TraceStore.put("llm.localEndpoint.latencyMs", Math.max(0, (System.nanoTime() - started) / 1_000_000));
                    publishLocalEndpointSnapshot(endpoint);
                }
            }
        };
    }

    private static boolean isMissingDoChatContract(RuntimeException failure) {
        if (failure == null
                || failure.getClass() != RuntimeException.class
                || !"Not implemented".equals(failure.getMessage())) {
            return false;
        }
        // Only the interface-default doChat produces this exact throw before any
        // transport; a provider error raised inside a real doChat must propagate.
        StackTraceElement[] frames = failure.getStackTrace();
        return frames.length > 0
                && "dev.langchain4j.model.chat.ChatModel".equals(frames[0].getClassName())
                && "doChat".equals(frames[0].getMethodName());
    }

    private void publishLocalEndpointSnapshot(String endpoint) {
        healthTracker.endpointSnapshot("local", endpoint, System.currentTimeMillis()).ifPresent(snapshot -> {
            TraceStore.put("llm.localEndpoint.state", snapshot.state().name().toLowerCase(Locale.ROOT));
            TraceStore.put("llm.localEndpoint.endpointHash", snapshot.endpointHash());
            TraceStore.put("llm.localEndpoint.reason", snapshot.lastReason());
            TraceStore.put("llm.localEndpoint.retryAfterMs", snapshot.retryAfterMs());
            TraceStore.put("llm.localEndpoint.failureCount", snapshot.failureCount());
            TraceStore.put("llm.localEndpoint.gpuPrimarySuccessCount", snapshot.consecutiveGpuPrimarySuccesses());
        });
    }

    protected Map<String, Object> gpuHardwareSnapshot() {
        return com.example.lms.health.GpuHardwareDiagnostics.snapshot(environment);
    }

    /** Post-generation, read-only corroboration; an HTTP answer or CPU allocation alone never proves recovery. */
    protected boolean gpuRecoveryVerified(String endpoint, String modelName) {
        return gpuRecoveryVerified(endpoint, modelName, null);
    }

    protected boolean gpuRecoveryVerified(String endpoint, String modelName, String deviceRole) {
        long started = System.nanoTime();
        var budget = com.abandonware.ai.addons.budget.TimeBudgetContext.get();
        long allowed = budget == null ? 2_000 : Math.min(2_000, budget.remainingMillis());
        TraceStore.put("llm.localEndpoint.gpuRecoveryVerified", false);
        TraceStore.put("llm.localEndpoint.modelState", "unknown");
        if (allowed <= 0) return false;
        try {
            Map<String, Object> hardware = gpuHardwareSnapshot();
            long age = hardware.get("observationAgeMs") instanceof Number n ? n.longValue() : Long.MAX_VALUE;
            TraceStore.put("llm.localEndpoint.healthAgeMs", age == Long.MAX_VALUE ? -1 : Math.max(0, age));
            boolean gpuAvailable = "ok".equals(hardware.get("status"))
                    && Boolean.TRUE.equals(hardware.get("available")) && age >= 0 && age <= 10_000;
            if ("rtx3090".equalsIgnoreCase(deviceRole)) gpuAvailable &= Boolean.TRUE.equals(hardware.get("hasRtx3090"));
            if ("rtx3060".equalsIgnoreCase(deviceRole)) gpuAvailable &= Boolean.TRUE.equals(hardware.get("hasRtx3060"));
            TraceStore.put("llm.localEndpoint.gpuState", gpuAvailable ? "available" : "unknown");
            if (!gpuAvailable) return false;
            long remaining = allowed - (System.nanoTime() - started) / 1_000_000;
            if (remaining <= 0) return false;
            URI uri = URI.create(endpoint);
            URI ps = new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), "/api/ps", null, null);
            var request = healthClient.get().uri(ps);
            if (environment != null && com.example.lms.llm.LocalLlmGatewaySecurity.shouldAttachOwnerToken(
                    endpoint, environment.getProperty("llm.provider-guard.allowed-hosts", ""))) {
                var headers = com.example.lms.llm.LocalLlmGatewaySecurity.ownerTokenHeaders(
                        environment.getProperty("llm.owner-token-header", "X-Owner-Token"),
                        environment.getProperty("llm.owner-token", ""));
                headers.forEach(request::header);
            }
            String body = request.retrieve().bodyToMono(String.class).block(java.time.Duration.ofMillis(remaining));
            TraceStore.put("llm.localEndpoint.serverState", "reachable");
            if (body == null || body.length() > 262_144) return false;
            var models = HEALTH_JSON.readTree(body).path("models");
            if (!models.isArray()) return false;
            for (var loaded : models) {
                String name = loaded.path("name").asText(loaded.path("model").asText(""));
                if (name.equalsIgnoreCase(modelName) && loaded.path("size_vram").asLong(0) > 0) {
                    TraceStore.put("llm.localEndpoint.modelState", "gpu_loaded");
                    TraceStore.put("llm.localEndpoint.gpuRecoveryVerified", true);
                    return true;
                }
            }
            TraceStore.put("llm.localEndpoint.modelState", "gpu_allocation_unverified");
        } catch (Exception failure) {
            if (LlmGatewayFailureClassifier.isCancellation(failure)) Thread.currentThread().interrupt();
            TraceStore.put("llm.localEndpoint.serverState", "unverified");
        }
        return false;
    }

    private void applyEndpointQuarantine(
            String provider,
            LlmRouterProperties.ModelConfig cfg,
            List<LlmFailureClass> failures,
            Map<String, Object> meta) {
        if (properties == null
                || healthTracker == null
                || cfg == null
                || !isLocalProvider(provider)
                || !properties.getLocalDeviceFailover().isEnabled()) {
            return;
        }
        ModelRuntimeHealthTracker.EndpointQuarantinePolicy policy =
                properties.getLocalDeviceFailover().toEndpointQuarantinePolicy();
        publishLocalEndpointSnapshot(cfg.getBaseUrl());
        long observedAtEpochMs = System.currentTimeMillis();
        healthTracker.endpointSnapshot(provider, cfg.getBaseUrl(), observedAtEpochMs)
                .ifPresent(snapshot -> {
                    boolean cooldownOpen = snapshot.state() == ModelRuntimeHealthTracker.EndpointState.OPEN
                            && snapshot.retryAfterMs() > 0L;
                    boolean halfOpenBusy = snapshot.state() == ModelRuntimeHealthTracker.EndpointState.HALF_OPEN
                            && snapshot.halfOpenProbeInFlight();
                    boolean wouldBlock = cooldownOpen || halfOpenBusy;
                    meta.put("endpointHash", snapshot.endpointHash());
                    meta.put("endpointState", snapshot.state().name().toLowerCase(Locale.ROOT));
                    meta.put("endpointWouldBlock", wouldBlock);
                    meta.put("endpointRetryAfterMs", snapshot.retryAfterMs());
                    meta.put("endpointSelectionDecision", wouldBlock
                            ? (policy.enforce() ? "open_blocked" : "open_observed")
                            : "half_open_candidate");
                    if (wouldBlock && policy.enforce()) {
                        failures.add(LlmFailureClass.GPU_DEVICE_LOST);
                    }
                });
    }

    private static LlmFailureClass toFailure(String reason) {
        if (reason == null || reason.isBlank()) {
            return LlmFailureClass.UNKNOWN;
        }
        String r = reason.toLowerCase(Locale.ROOT);
        if (r.contains("model_not_found") || r.contains("http_404") || r.contains("not_found")) {
            return LlmFailureClass.MODEL_MISSING;
        }
        if (r.contains("auth") || r.contains("401") || r.contains("403")) {
            return LlmFailureClass.AUTH_MISSING;
        }
        if (r.contains("oom") || r.contains("vram")) {
            return LlmFailureClass.VRAM_OOM;
        }
        if (r.contains("timeout")) {
            return LlmFailureClass.TIMEOUT_SOFT;
        }
        return LlmFailureClass.HEALTH_DOWN;
    }

    private static String provider(String routeKey, LlmRouterProperties.ModelConfig cfg) {
        if (cfg != null && StringUtils.hasText(cfg.getProvider())) {
            return cfg.getProvider().trim().toLowerCase(Locale.ROOT);
        }
        String baseUrl = cfg == null ? null : cfg.getBaseUrl();
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
        if (u.contains("generativelanguage.googleapis.com") || u.contains("aiplatform.googleapis.com")) {
            return "gemini";
        }
        if (u.contains("api.mistral.ai")) {
            return "mistral";
        }
        if (u.contains("api.anthropic.com")) {
            return "anthropic";
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
        if (routeKey != null && routeKey.toLowerCase(Locale.ROOT).contains("macmini")) {
            return "local";
        }
        return "local";
    }

    private static boolean isLocalProvider(String provider) {
        return provider == null || provider.isBlank() || "local".equalsIgnoreCase(provider) || "ollama".equalsIgnoreCase(provider);
    }

    private boolean hasUsableCredential(String credentialEnv) {
        if (!isEnvName(credentialEnv) || environment == null) {
            return false;
        }
        return !ConfigValueGuards.isMissing(environment.getProperty(credentialEnv.trim()));
    }

    private static String credentialEnv(String provider, LlmRouterProperties.ModelConfig cfg) {
        if (cfg != null && StringUtils.hasText(cfg.getCredentialEnv())) {
            return cfg.getCredentialEnv().trim();
        }
        return switch (provider == null ? "" : provider.toLowerCase(Locale.ROOT)) {
            case "openai" -> "OPENAI_API_KEY";
            case "groq" -> "GROQ_API_KEY";
            case "gemini" -> "GEMINI_API_KEY";
            case "mistral" -> "MISTRAL_API_KEY";
            case "anthropic" -> "ANTHROPIC_API_KEY";
            case "cerebras" -> "CEREBRAS_API_KEY";
            case "openrouter" -> "OPENROUTER_API_KEY";
            case "opencode" -> "OPENCODE_API_KEY";
            default -> null;
        };
    }

    private static String safeCredentialEnv(String credentialEnv) {
        if (!StringUtils.hasText(credentialEnv)) {
            return null;
        }
        return isEnvName(credentialEnv) ? credentialEnv.trim() : "(redacted)";
    }

    private static boolean isEnvName(String credentialEnv) {
        return StringUtils.hasText(credentialEnv) && ENV_NAME.matcher(credentialEnv.trim()).matches();
    }

    private static void putIfPresent(Map<String, Object> meta, String key, Object value) {
        if (meta != null && value != null) {
            meta.put(key, value);
        }
    }

    private static String endpointHost(String rawBaseUrl) {
        if (rawBaseUrl == null || rawBaseUrl.isBlank()) {
            return null;
        }
        try {
            String host = URI.create(rawBaseUrl.trim()).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (Exception ignore) {
            TraceStore.put("llm.gateway.probe.suppressed.endpointHost", true);
            TraceStore.put("llm.gateway.probe.suppressed.endpointHost.errorType", errorType(ignore));
            return null;
        }
    }

    private static String errorType(Throwable error) {
        if (error instanceof IllegalArgumentException) {
            return "invalid_url";
        }
        return error == null ? "unknown" : SafeRedactor.traceLabelOrFallback(error.getClass().getSimpleName(), "unknown");
    }
}
