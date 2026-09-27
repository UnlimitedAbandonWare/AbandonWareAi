package com.example.lms.service.diagnostic;

import ai.abandonware.nova.orch.storage.DegradedStorage;
import ai.abandonware.nova.orch.storage.DegradedStorageWithAck;
import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.service.VectorStoreService;
import com.example.lms.trace.SafeRedactor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Aggregates "운영 중 복구/차단 상태" signals into one payload.
 *
 * Intentionally avoids any user-content payloads; counters/state only.
 */
@Service
public class RuntimeDiagnosticsService implements io.micrometer.core.instrument.binder.MeterBinder {
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.lms.config.LocalLlmProcessManager localLlmProcessManager;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.core.env.Environment environment;
    private volatile Map<String, Object> lastToolkit = Map.of();
    private final Map<String, String> runtimeStates = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile io.micrometer.core.instrument.MeterRegistry meterRegistry;
    private volatile double lastAcceptedHealthDurationMs = Double.NaN;
    private long telemetryOwner;
    private final Map<String, Long> telemetryCounts = new LinkedHashMap<>();


    private final ObjectProvider<VectorStoreService> vectorStoreService;
    private final ObjectProvider<NightmareBreaker> nightmareBreaker;
    private final ObjectProvider<DegradedStorage> outboxStorage;

    public RuntimeDiagnosticsService(
            ObjectProvider<VectorStoreService> vectorStoreService,
            ObjectProvider<NightmareBreaker> nightmareBreaker,
            @Qualifier("outboxStorage") ObjectProvider<DegradedStorage> outboxStorage
    ) {
        this.vectorStoreService = vectorStoreService;
        this.nightmareBreaker = nightmareBreaker;
        this.outboxStorage = outboxStorage;
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("timestamp", Instant.now().toString());

        VectorStoreService vs = vectorStoreService.getIfAvailable();
        if (vs != null) {
            root.put("vectorStore", vs.bufferStats());
        } else {
            root.put("vectorStore", Map.of("available", false));
        }

        NightmareBreaker nb = nightmareBreaker.getIfAvailable();
        if (nb != null) {
            root.put("nightmareBreaker", nb.snapshot());
        } else {
            root.put("nightmareBreaker", Map.of("available", false));
        }

        DegradedStorage outbox = outboxStorage.getIfAvailable();
        if (outbox instanceof DegradedStorageWithAck withAck) {
            root.put("outbox", safeOutboxStats(withAck.stats()));
        } else if (outbox != null) {
            root.put("outbox", Map.of(
                    "available", true,
                    "type", outbox.getClass().getName(),
                    "withAck", false
            ));
        } else {
            root.put("outbox", Map.of("available", false));
        }

        root.put("runtimeToolkit", toolkitSnapshot());
        return root;
    }

    public Map<String, Object> toolkitSnapshot() {
        Map<String, Object> local = localLlmProcessManager == null ? Map.of() : localLlmProcessManager.diagnostics();
        try (var input = getClass().getResourceAsStream("/mcp/awx-control-tower-tools.json")) {
            if (input == null) return Map.of("FULL_LOAD_READY", false, "reasonCode", "runtime_manifest_missing");
            byte[] manifestBytes = input.readAllBytes();
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var manifest = mapper.readTree(manifestBytes);
            String manifestHash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(manifestBytes));
            Map<String, Object> pipe = Map.of();
            long proofAgeMs = -1;
            long publicationAgeMs = -1;
            String proofReason = "client_proof_missing";
            String evidencePath = environment == null ? "var/codex-runtime/toolkit-current.json"
                    : environment.getProperty("runtime-toolkit.evidence-path", "var/codex-runtime/toolkit-current.json");
            var path = java.nio.file.Path.of(evidencePath).normalize();
            try {
            if (java.nio.file.Files.isRegularFile(path) && java.nio.file.Files.size(path) <= 32768) {
                var evidence = mapper.readTree(java.nio.file.Files.readAllBytes(path));
                long age = System.currentTimeMillis() - evidence.path("observedAtEpochMs").asLong(0);
                publicationAgeMs = age;
                proofReason = "client_proof_stale_or_identity_invalid";
                var owner = ProcessHandle.of(evidence.path("ownerPid").asLong(0));
                boolean ownerMatches = owner.filter(ProcessHandle::isAlive).flatMap(p -> p.info().startInstant())
                        .map(t -> Math.abs(t.toEpochMilli() - evidence.path("ownerStartedAtEpochMs").asLong(0)) < 1000).orElse(false);
                var child = evidence.path("stdio");
                var verifiedAt = child.path("smokeVerifiedAtEpochMs");
                if (verifiedAt.isIntegralNumber() && verifiedAt.canConvertToLong() && verifiedAt.asLong() > 0
                        && verifiedAt.asLong() <= System.currentTimeMillis())
                    proofAgeMs = System.currentTimeMillis() - verifiedAt.asLong();
                boolean childMatches = ProcessHandle.of(child.path("processPid").asLong(0)).filter(ProcessHandle::isAlive)
                        .flatMap(p -> p.info().startInstant()).map(t -> Math.abs(t.toEpochMilli()
                                - child.path("processStartedAtEpochMs").asLong(0)) < 1000).orElse(false);
                if (clientContractValid(evidence) && age >= 0 && age <= 60000 && ownerMatches && childMatches && manifestHash.equals(evidence.path("manifestHash").asText())
                        && evidence.path("springPid").asLong(0) == ProcessHandle.current().pid()) {
                    proofReason = "client_proof_verified";
                    var client = evidence.path("stdio");
                    pipe = Map.of("protocolProbe", client.path("protocolProbe").asBoolean(false),
                            "catalogValidated", client.path("catalogValidated").asBoolean(false),
                            "smokeVerified", client.path("smokeVerified").asBoolean(false),
                            "sessionAlive", client.path("sessionAlive").asBoolean(false),
                            "processPid", client.path("processPid").asLong(0),
                            "processStartedAtEpochMs", client.path("processStartedAtEpochMs").asLong(0),
                            "toolCount", client.path("toolCount").asInt(0),
                            "restartCount", acceptedClientCount(client.path("telemetry").path("processRestarts")),
                            "restartLimited", client.path("restartLimited").asBoolean(false));
                    observeClientTelemetry(client, evidence.path("ownerStartedAtEpochMs").asLong(0));
                }
            }
            } catch (Exception invalidClientEvidence) {
                pipe = Map.of();
                proofReason = "client_proof_invalid";
            }
            var definitions = new java.util.ArrayList<Map<String, Object>>();
            for (var node : manifest.path("runtimeToolkit").path("services")) {
                Map<String, Object> definition = new LinkedHashMap<>();
                definition.put("serviceId", node.path("serviceId").asText());
                definition.put("role", node.path("role").asText("UNKNOWN"));
                definition.put("transport", node.path("transport").asText("UNKNOWN"));
                definition.put("dependencies", mapper.convertValue(node.path("dependencies"), java.util.List.class));
                definition.put("requiredForProfile", node.has("requiredForProfile")
                        ? mapper.convertValue(node.path("requiredForProfile"), java.util.List.class) : java.util.List.of());
                definition.put("protocolVersion", node.path("protocolVersion").asText(""));
                for (String field : java.util.List.of("startupOwner", "healthCheck", "requiredWhen", "protocolFamily"))
                    definition.put(field, node.path(field).asText("NOT_OBSERVED"));
                definition.put("requiredAtBoot", node.path("requiredAtBoot").isBoolean()
                        ? node.path("requiredAtBoot").asBoolean() : "NOT_OBSERVED");
                definitions.add(definition);
            }
            var attributes = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            var request = attributes instanceof org.springframework.web.context.request.ServletRequestAttributes servlet ? servlet.getRequest() : null;
            boolean servingSpring = request != null && request.getLocalPort() > 0
                    && ("127.0.0.1".equals(request.getLocalAddr()) || "0:0:0:0:0:0:0:1".equals(request.getLocalAddr()) || "::1".equals(request.getLocalAddr()));
            var snapshot = projectToolkit(local, pipe, definitions, servingSpring);
            snapshot.put("springPid", ProcessHandle.current().pid());
            snapshot.put("configuredPort", environment == null ? 0 : environment.getProperty("server.port", Integer.class, 8080));
            snapshot.put("clientProofAgeMs", proofAgeMs);
            snapshot.put("clientPublicationAgeMs", publicationAgeMs);
            snapshot.put("clientProofReason", proofReason);
            for (Object value : (java.util.List<?>) snapshot.get("services")) {
                @SuppressWarnings("unchecked") var row = (Map<String, Object>) value;
                if ("spring-app".equals(row.get("serviceId"))) {
                    row.put("processPid", ProcessHandle.current().pid());
                    row.put("processStartedAt", ProcessHandle.current().info().startInstant().map(Instant::toString).orElse("NOT_OBSERVED"));
                    row.put("configuredPort", snapshot.get("configuredPort"));
                    String configuredHost = environment == null ? "" : environment.getProperty("server.address", "");
                    row.put("configuredHost", java.util.List.of("127.0.0.1", "::1").contains(configuredHost) ? configuredHost
                            : configuredHost.isBlank() ? "NOT_OBSERVED" : "NON_LOOPBACK_CONFIGURED");
                    row.put("actualPort", servingSpring ? request.getLocalPort() : 0);
                    row.put("actualHost", servingSpring ? request.getLocalAddr() : "NOT_OBSERVED");
                    row.put("portEvidence", servingSpring ? "SERVING_HTTP_REQUEST" : "NOT_OBSERVED");
                    row.put("networkExposure", servingSpring && "127.0.0.1".equals(configuredHost) ? "LOOPBACK" : "NOT_OBSERVED");
                }
                @SuppressWarnings("unchecked") var provenance = new LinkedHashMap<>((Map<String, Object>) row.get("evidence"));
                provenance.put("manifestHash", manifestHash);
                if ("awx-stdio".equals(row.get("serviceId"))) {
                    provenance.put("clientProofReason", proofReason);
                    provenance.put("clientProofAgeMs", proofAgeMs);
                }
                row.put("evidence", Map.copyOf(provenance));
            }
            boolean loopback = environment != null && "127.0.0.1".equals(environment.getProperty("server.address"));
            snapshot.put("loopbackConfigured", loopback);
            if (!loopback) {
                snapshot.put("FULL_LOAD_READY", false);
                snapshot.put("reasonCode", "loopback_listener_evidence_needed");
            }
            snapshot.put("manifestHash", manifestHash);
            snapshot.put("observedAt", Instant.now().toString());
            lastToolkit = Map.copyOf(snapshot);
            com.example.lms.search.TraceStore.put("runtime.inventory.serviceCount", definitions.size());
            com.example.lms.search.TraceStore.put("runtime.inventory.requiredCount", snapshot.get("requiredTotal"));
            observeRuntimeStates((java.util.List<?>) snapshot.get("services"));
            return lastToolkit;
        } catch (Exception ex) {
            // A missing/stale/malformed client snapshot must never promote readiness.
            return Map.of("FULL_LOAD_READY", false, "reasonCode", "runtime_evidence_unavailable", "services", java.util.List.of());
        }
    }

    private void observeRuntimeStates(java.util.List<?> rows) {
        for (Object value : rows) {
            Map<?, ?> row = (Map<?, ?>) value;
            String id = String.valueOf(row.get("serviceId"));
            String state = String.valueOf(row.get("status"));
            boolean ready = "READY".equals(row.get("primaryPathStatus"));
            com.example.lms.search.TraceStore.put("runtime.service.ready." + id, ready);
            com.example.lms.search.TraceStore.put("runtime.service.degraded." + id, Boolean.TRUE.equals(row.get("required")) && !ready);
            if (!state.equals(runtimeStates.put(id, state))) {
                com.example.lms.search.TraceStore.put("runtime.service.stateChanged." + id, state);
                if (meterRegistry != null) meterRegistry.counter("runtime.toolkit.state.transitions", "service", id).increment();
            }
        }
    }

    private synchronized void observeClientTelemetry(com.fasterxml.jackson.databind.JsonNode client, long owner) {
        if (owner != telemetryOwner) { telemetryOwner = owner; telemetryCounts.clear(); }
        for (String field : java.util.List.of("processStarts", "processRestarts", "restartLimited", "smokeSuccess", "smokeFailure", "toolTimeout", "toolCancelled", "toolFailed")) {
            if (!(acceptedClientCount(client.path("telemetry").path(field)) instanceof Long count)) continue;
            long previous = telemetryCounts.getOrDefault(field, 0L);
            com.example.lms.search.TraceStore.put("runtime.toolkit." + field, count);
            if (meterRegistry != null && count > previous) meterRegistry.counter("runtime.toolkit.events", "event", field).increment(count - previous);
            telemetryCounts.put(field, Math.max(count, previous));
        }
        for (String field : java.util.List.of("toolCount", "resourceCount", "promptCount")) {
            var node = client.path(field);
            if (node.isIntegralNumber() && node.asLong() >= 0 && node.asLong() <= 1_000_000_000L)
                com.example.lms.search.TraceStore.put("runtime.toolkit." + field, node.asLong());
        }
        var duration = client.path("healthProbeElapsedMs");
        if (duration.isIntegralNumber() && duration.canConvertToLong() && duration.asLong() >= 0 && duration.asLong() <= 60000) {
            lastAcceptedHealthDurationMs = duration.asLong();
            com.example.lms.search.TraceStore.put("runtime.toolkit.healthProbeElapsedMs", duration.asLong());
        }
    }

    static boolean clientContractValid(com.fasterxml.jackson.databind.JsonNode evidence) {
        if (!"awx.runtime.client-proof.v1".equals(evidence.path("schemaVersion").asText())) return false;
        var client = evidence.path("stdio");
        if (!"MCP_STYLE".equals(client.path("protocolFamily").asText())) return false;
        for (String field : java.util.List.of("protocolProbe", "catalogValidated", "smokeVerified", "sessionAlive")) {
            if (!client.path(field).isBoolean()) return false;
        }
        if (client.path("smokeVerified").asBoolean()) {
            var verifiedAt = client.path("smokeVerifiedAtEpochMs");
            long now = System.currentTimeMillis();
            if (!verifiedAt.isIntegralNumber() || !verifiedAt.canConvertToLong() || verifiedAt.asLong() <= 0
                    || verifiedAt.asLong() > now || now - verifiedAt.asLong() > 60000) return false;
            if (!client.path("manifestHash").isTextual()
                    || !client.path("manifestHash").asText().equals(evidence.path("manifestHash").asText())) return false;
        }
        return !client.has("restartLimited") || client.path("restartLimited").isBoolean();
    }

    private static Object acceptedClientCount(com.fasterxml.jackson.databind.JsonNode node) {
        return node.isIntegralNumber() && node.canConvertToLong() && node.asLong() >= 0 && node.asLong() <= 1_000_000_000L
                ? node.asLong() : "NOT_OBSERVED";
    }

    static Map<String, Object> projectToolkit(Map<String, Object> local, Map<String, Object> pipe,
                                              java.util.List<Map<String, Object>> definitions, boolean servingSpring) {
        var rows = new java.util.ArrayList<Map<String, Object>>();
        int required = 0, ready = 0, optional = 0, optionalReady = 0;
        boolean localRequired = Boolean.TRUE.equals(local.get("enabled"));
        long localEvidenceAge = ((Number) local.getOrDefault("warmupEvidenceAgeMs", -1L)).longValue();
        boolean localReady = "READY".equals(local.get("state")) && Boolean.TRUE.equals(local.get("warmupVerified"))
                && localEvidenceAge >= 0 && localEvidenceAge <= 60000;
        boolean pipeReady = java.util.List.of("protocolProbe", "catalogValidated", "smokeVerified", "sessionAlive")
                .stream().allMatch(k -> Boolean.TRUE.equals(pipe.get(k))) && !Boolean.TRUE.equals(pipe.get("restartLimited"));
        for (Map<String, Object> definition : definitions) {
            var row = new LinkedHashMap<String, Object>(definition);
            String id = String.valueOf(definition.get("serviceId"));
            boolean isRequired = ("RUNTIME_REQUIRED".equals(definition.get("role"))
                    || ((java.util.List<?>) definition.getOrDefault("requiredForProfile", java.util.List.of())).contains("local-toolkit"))
                    && (!"ollama-local".equals(id) || localRequired);
            boolean isReady = "spring-app".equals(id) && servingSpring || "awx-stdio".equals(id) && pipeReady || "ollama-local".equals(id) && localReady;
            String state = "spring-app".equals(id) ? (servingSpring ? "READY" : "NOT_OBSERVED") : "awx-stdio".equals(id) ? (pipeReady ? "READY" : "NOT_OBSERVED")
                    : "ollama-local".equals(id) ? String.valueOf(local.getOrDefault("state", "NOT_OBSERVED")) : "NOT_OBSERVED";
            boolean fallback = "ollama-local".equals(id) && !localReady && Boolean.TRUE.equals(local.get("fallbackUsed"));
            row.put("required", isRequired);
            row.put("status", state);
            row.put("primaryPathStatus", isReady ? "READY" : "NOT_READY");
            row.put("functionalStatus", fallback ? "FALLBACK_ACTIVE" : isReady ? "VERIFIED" : "NOT_OBSERVED");
            row.put("fallbackUsed", fallback);
            row.put("reasonCode", "ollama-local".equals(id) ? "READY".equals(state) && !localReady ? "warmup_evidence_stale_or_missing" : local.getOrDefault("reasonCode", "manager_not_observed")
                    : "awx-stdio".equals(id) && !pipeReady ? "client_pipe_evidence_needed"
                    : "spring-app".equals(id) && !servingSpring ? "serving_http_evidence_needed" : "");
            row.put("processOwned", "awx-stdio".equals(id) && Boolean.TRUE.equals(pipe.get("sessionAlive"))
                    || "ollama-local".equals(id) && Boolean.TRUE.equals(local.get("owned")));
            row.put("processPid", "ollama-local".equals(id) ? local.getOrDefault("pid", 0) : "awx-stdio".equals(id) ? pipe.getOrDefault("processPid", 0) : 0);
            long startedAt = "awx-stdio".equals(id) ? ((Number) pipe.getOrDefault("processStartedAtEpochMs", 0L)).longValue() : 0;
            row.put("processStartedAt", startedAt > 0 ? Instant.ofEpochMilli(startedAt).toString() : "NOT_OBSERVED");
            row.put("configuredHost", "ollama-local".equals(id) ? configuredHostOnly(local.get("host"))
                    : "awx-stdio".equals(id) ? "NOT_APPLICABLE" : "NOT_OBSERVED");
            row.put("actualHost", "awx-stdio".equals(id) ? "NOT_APPLICABLE" : "NOT_OBSERVED");
            for (String field : java.util.List.of("startCommand", "workingDirectory", "restartPolicy", "stopPolicy"))
                row.putIfAbsent(field, "NOT_OBSERVED");
            row.put("configuredPort", "ollama-local".equals(id) ? local.getOrDefault("port", 0) : 0);
            row.put("restartCount", "ollama-local".equals(id) ? local.getOrDefault("attempt", 0)
                    : "awx-stdio".equals(id) ? pipe.getOrDefault("restartCount", "NOT_OBSERVED") : 0);
            if ("ollama-local".equals(id)) {
                for (String field : java.util.List.of("warmupStatus", "warmupReason", "warmupTargetDim", "warmupReturnedDim", "warmupElapsedMs", "warmupEvidenceAgeMs", "cooldownRemainingMs")) {
                    if (local.containsKey(field)) row.put(field, local.get(field));
                }
            }
            row.put("toolCount", "awx-stdio".equals(id) ? pipe.getOrDefault("toolCount", 0) : 0);
            row.put("ownership", "awx-stdio".equals(id) && Boolean.TRUE.equals(pipe.get("sessionAlive")) ? "OWNED_BY_TOOLKIT_CLIENT"
                    : "spring-app".equals(id) ? "CURRENT_SPRING_PROCESS"
                    : "ollama-local".equals(id) && Boolean.TRUE.equals(local.get("owned")) ? "OWNED_BY_SPRING" : "ADOPTED_EXTERNAL_OR_UNKNOWN");
            // The configured Ollama target may differ from the health URL or
            // listener binding. A healthy endpoint alone supplies no bind proof.
            row.put("actualPort", 0);
            row.put("portEvidence", "awx-stdio".equals(id) ? "NOT_APPLICABLE" : "NOT_OBSERVED");
            row.put("smokeResult", isReady ? "VERIFIED" : "NOT_OBSERVED");
            long verifiedAt = ((Number) local.getOrDefault("warmupVerifiedAtEpochMs", -1L)).longValue();
            row.put("lastHealthyAt", "ollama-local".equals(id) && verifiedAt > 0 ? Instant.ofEpochMilli(verifiedAt).toString() : "NOT_OBSERVED");
            row.put("networkExposure", "awx-stdio".equals(id) ? "CLIENT_PIPE" : "NOT_OBSERVED");
            row.put("evidence", Map.of("source", "ollama-local".equals(id) && !local.isEmpty() ? "LOCAL_LLM_MANAGER"
                    : "awx-stdio".equals(id) && !pipe.isEmpty() ? "VALIDATED_CLIENT_PIPE"
                    : "spring-app".equals(id) && servingSpring ? "SERVING_HTTP_REQUEST" : "NOT_OBSERVED",
                    "functionVerified", isReady));
            rows.add(row);
            if (isRequired) { required++; if (isReady) ready++; }
            else { optional++; if (isReady) optionalReady++; }
        }
        boolean dependenciesReady = rows.stream().filter(r -> Boolean.TRUE.equals(r.get("required"))).allMatch(r ->
                ((java.util.List<?>) r.getOrDefault("dependencies", java.util.List.of())).stream().allMatch(dependency ->
                        rows.stream().anyMatch(d -> dependency.equals(d.get("serviceId")) && "READY".equals(d.get("primaryPathStatus")))));
        boolean full = required > 0 && ready == required && dependenciesReady && !local.isEmpty() && !Boolean.TRUE.equals(pipe.get("restartLimited"));
        var result = new LinkedHashMap<String, Object>();
        result.put("profile", "local-toolkit"); result.put("services", rows);
        result.put("requiredTotal", required); result.put("requiredReady", ready);
        result.put("optionalTotal", optional); result.put("optionalReady", optionalReady);
        result.put("dependenciesReady", dependenciesReady); result.put("FULL_LOAD_READY", full);
        result.put("registeredTotal", rows.size());
        result.put("degradedTotal", rows.stream().filter(r -> Boolean.TRUE.equals(r.get("required")) && !"READY".equals(r.get("primaryPathStatus"))).count());
        result.put("ownedTotal", rows.stream().filter(r -> Boolean.TRUE.equals(r.get("processOwned"))).count());
        result.put("externalTotal", rows.stream().filter(r -> "ADOPTED_EXTERNAL_OR_UNKNOWN".equals(r.get("ownership")) && ((Number) r.get("processPid")).longValue() > 0).count());
        result.put("catalogToolTotal", pipe.getOrDefault("toolCount", 0));
        result.put("reasonCode", full ? "verified" : "required_contract_evidence_needed");
        return result;
    }

    private static String configuredHostOnly(Object value) {
        if (!(value instanceof String host) || host.isBlank()) return "NOT_OBSERVED";
        try {
            var uri = java.net.URI.create(host.contains("://") ? host : "http://" + host);
            return uri.getHost() == null ? "NOT_OBSERVED" : uri.getHost();
        } catch (IllegalArgumentException ignored) { return "NOT_OBSERVED"; }
    }

    @Override
    public void bindTo(io.micrometer.core.instrument.MeterRegistry registry) {
        meterRegistry = registry;
        io.micrometer.core.instrument.Gauge.builder("runtime.toolkit.health.duration", this,
                service -> service.lastAcceptedHealthDurationMs).baseUnit("milliseconds")
                .description("Latest accepted client heartbeat duration; NaN until observed")
                .tag("service", "spring-app").register(registry);
        for (String field : java.util.List.of("requiredTotal", "requiredReady", "optionalTotal", "optionalReady", "registeredTotal", "degradedTotal", "ownedTotal", "externalTotal", "catalogToolTotal")) {
            io.micrometer.core.instrument.Gauge.builder("runtime.toolkit.services", this,
                    service -> ((Number) service.lastToolkit.getOrDefault(field, 0)).doubleValue())
                    .tag("state", field).register(registry);
        }
    }

    private static Map<String, Object> safeOutboxStats(DegradedStorageWithAck.OutboxStats stats) {
        if (stats == null) {
            return Map.of("available", false);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", stats.enabled());
        out.put("mode", SafeRedactor.traceLabelOrFallback(stats.mode(), ""));
        out.put("pathHash", SafeRedactor.hashValue(stats.path()));
        out.put("pathLength", stats.path() == null ? 0 : stats.path().length());
        out.put("pendingCount", stats.pendingCount());
        out.put("inflightCount", stats.inflightCount());
        out.put("totalBytes", stats.totalBytes());
        out.put("oldestCreatedAt", stats.oldestCreatedAt());
        out.put("newestCreatedAt", stats.newestCreatedAt());
        out.put("maxFiles", stats.maxFiles());
        out.put("maxBytes", stats.maxBytes());
        out.put("ttlSeconds", stats.ttlSeconds());
        out.put("inflightStaleSeconds", stats.inflightStaleSeconds());
        out.put("ackTotal", stats.ackTotal());
        out.put("nackTotal", stats.nackTotal());
        out.put("releaseTotal", stats.releaseTotal());
        out.put("droppedExpiredTotal", stats.droppedExpiredTotal());
        out.put("droppedByLimitTotal", stats.droppedByLimitTotal());
        out.put("parseErrorTotal", stats.parseErrorTotal());
        out.put("lastSweepEpochMs", stats.lastSweepEpochMs());
        return out;
    }
}
