package com.example.lms.service.diagnostic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeToolkitProjectionTest {
    @Test void configuredHostEvidencePathIsReadWithoutAcceptingInvalidProof(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path localState) throws Exception {
        var service = new RuntimeDiagnosticsService(null, null, null);
        var proof = localState.resolve("toolkit-current.json");
        var env = new org.springframework.mock.env.MockEnvironment()
                .withProperty("runtime-toolkit.evidence-path", proof.toString());
        org.springframework.test.util.ReflectionTestUtils.setField(service, "environment", env);
        java.nio.file.Files.writeString(proof, "invalid fixture json");
        var invalid = service.toolkitSnapshot();
        assertEquals("client_proof_invalid", invalid.get("clientProofReason"));
        assertEquals(false, invalid.get("FULL_LOAD_READY"));
        java.nio.file.Files.delete(proof);
        assertEquals("client_proof_missing", service.toolkitSnapshot().get("clientProofReason"));
    }

    private com.fasterxml.jackson.databind.node.ObjectNode timestampedClientProof(long now) {
        var evidence = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        evidence.put("schemaVersion", "awx.runtime.client-proof.v1");
        evidence.put("observedAtEpochMs", now);
        evidence.put("manifestHash", "fixture-manifest");
        var client = evidence.putObject("stdio");
        client.put("protocolFamily", "MCP_STYLE");
        for (String key : pipe().keySet()) client.put(key, true);
        client.put("smokeVerifiedAtEpochMs", now);
        client.put("manifestHash", "fixture-manifest");
        return evidence;
    }

    @Test void freshPublicationCannotRenewOldOrMalformedSmokeEvidence() throws Exception {
        long now = System.currentTimeMillis();
        var evidence = timestampedClientProof(now);
        var client = (com.fasterxml.jackson.databind.node.ObjectNode) evidence.path("stdio");
        assertTrue(RuntimeDiagnosticsService.clientContractValid(evidence));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (String value : List.of("null", "true", "\"" + now + "\"", "-1", "0", "1.5",
                Long.toString(now - 60001), Long.toString(now + 60000), "999999999999999999999")) {
            client.set("smokeVerifiedAtEpochMs", mapper.readTree(value));
            assertFalse(RuntimeDiagnosticsService.clientContractValid(evidence), value);
        }
        client.remove("smokeVerifiedAtEpochMs");
        assertFalse(RuntimeDiagnosticsService.clientContractValid(evidence));
    }

    @Test void currentManifestHashCannotRelabelOldCatalogProof() {
        var evidence = timestampedClientProof(System.currentTimeMillis());
        evidence.put("manifestHash", "changed-manifest");
        assertFalse(RuntimeDiagnosticsService.clientContractValid(evidence));
        ((com.fasterxml.jackson.databind.node.ObjectNode) evidence.path("stdio")).put("manifestHash", "changed-manifest");
        assertTrue(RuntimeDiagnosticsService.clientContractValid(evidence));
    }

    @Test void acceptedHealthDurationIsMeasuredWithoutTurningMissingEvidenceIntoZero() throws Exception {
        var service = new RuntimeDiagnosticsService(null, null, null);
        {
            var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
            service.bindTo(registry);
            var gauge = registry.get("runtime.toolkit.health.duration").tag("service", "spring-app").gauge();
            assertTrue(Double.isNaN(gauge.value()));
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            for (String value : List.of("{}", "{\"healthProbeElapsedMs\":-1}", "{\"healthProbeElapsedMs\":60001}", "{\"healthProbeElapsedMs\":\"37\"}"))
                org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "observeClientTelemetry", mapper.readTree(value), 123L);
            assertTrue(Double.isNaN(gauge.value()));
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "observeClientTelemetry", mapper.readTree("{\"healthProbeElapsedMs\":37}"), 123L);
            assertEquals(37, gauge.value());
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "observeClientTelemetry", mapper.readTree("{}"), 123L);
            assertEquals(37, gauge.value(), "latest accepted observation is not a new sample");
        }
    }

    @Test void normalizedReadinessDistinguishesOptionalMissingAndRequiredFailureAndRecovery() {
        var service = new RuntimeDiagnosticsService(null, null, null);
        var rows = (List<?>) RuntimeDiagnosticsService.projectToolkit(Map.of("enabled", false), pipe(), definitions(), true).get("services");
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "observeRuntimeStates", rows);
        assertEquals(true, com.example.lms.search.TraceStore.get("runtime.service.ready.spring-app"));
        assertEquals(false, com.example.lms.search.TraceStore.get("runtime.service.degraded.ollama-local"));
        rows = (List<?>) RuntimeDiagnosticsService.projectToolkit(Map.of("enabled", true, "state", "COOLDOWN"), pipe(), definitions(), true).get("services");
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "observeRuntimeStates", rows);
        assertEquals(true, com.example.lms.search.TraceStore.get("runtime.service.degraded.ollama-local"));
        rows = (List<?>) RuntimeDiagnosticsService.projectToolkit(Map.of("enabled", true, "state", "READY", "warmupVerified", true, "warmupEvidenceAgeMs", 0), pipe(), definitions(), true).get("services");
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "observeRuntimeStates", rows);
        assertEquals(true, com.example.lms.search.TraceStore.get("runtime.service.ready.ollama-local"));
        assertEquals(false, com.example.lms.search.TraceStore.get("runtime.service.degraded.ollama-local"));
    }

    @Test void manifestDeclarationsAndUnavailableInventoryFieldsSurviveHeartbeat() {
        var service = new RuntimeDiagnosticsService(null, null, null);
        var snapshot = service.toolkitSnapshot();
        var rows = (List<?>) snapshot.get("services");
        assertFalse(rows.isEmpty());
        var spring = rows.stream().map(value -> (Map<?, ?>) value).filter(row -> "spring-app".equals(row.get("serviceId"))).findFirst().orElseThrow();
        var stdio = rows.stream().map(value -> (Map<?, ?>) value).filter(row -> "awx-stdio".equals(row.get("serviceId"))).findFirst().orElseThrow();
        assertEquals(true, spring.get("requiredAtBoot"));
        assertEquals("existing-chat-ui-listener", spring.get("startupOwner"));
        assertEquals("/api/chat/ui-heartbeat", spring.get("healthCheck"));
        assertEquals(false, stdio.get("requiredAtBoot"));
        assertEquals("client-owned-pipe", stdio.get("startupOwner"));
        assertEquals("MCP_STYLE", stdio.get("protocolFamily"));
        assertEquals("2024-11-05", stdio.get("protocolVersion"));
        for (Object value : rows) {
            var row = (Map<?, ?>) value;
            for (String key : List.of("startCommand", "workingDirectory", "restartPolicy", "stopPolicy"))
                assertEquals("NOT_OBSERVED", row.get(key), key);
            assertTrue(row.get("evidence") instanceof Map);
            assertNotNull(row.get("configuredHost"));
            assertNotNull(row.get("actualHost"));
            assertNotNull(row.get("processStartedAt"));
        }
        assertEquals("NOT_OBSERVED", spring.get("actualHost"), "non-web invocation has no serving address");
        assertEquals("NOT_APPLICABLE", stdio.get("portEvidence"));
        assertEquals(false, snapshot.get("FULL_LOAD_READY"));
    }

    @Test void configuredExternalHostDoesNotBecomeObservedLoopbackOrOwnedIdentity() {
        var snapshot = RuntimeDiagnosticsService.projectToolkit(Map.of("enabled", true, "host", "example.invalid",
                "port", 11435, "serviceResponding", false, "pid", 1234), Map.of(), definitions(), false);
        var row = (Map<?, ?>) ((List<?>) snapshot.get("services")).get(1);
        assertEquals("example.invalid", row.get("configuredHost"));
        assertEquals("NOT_OBSERVED", row.get("actualHost"));
        assertEquals("NOT_OBSERVED", row.get("processStartedAt"));
        assertEquals("NOT_OBSERVED", row.get("networkExposure"));
        assertEquals(false, row.get("processOwned"));
    }

    @Test void warmupDimensionFailureRetainsCountOnlyEvidence() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/", exchange -> {
            String response = exchange.getRequestURI().getPath().endsWith("tags")
                    ? "{\"models\":[{\"name\":\"fixture\"}]}" : "{\"embeddings\":[[0.1,0.2]]}";
            byte[] bytes = response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getRequestBody().readAllBytes(); exchange.sendResponseHeaders(200, bytes.length);
            try (var stream = exchange.getResponseBody()) { stream.write(bytes); }
        });
        server.start();
        try {
            var manager = new com.example.lms.config.LocalLlmProcessManager();
            org.springframework.test.util.ReflectionTestUtils.setField(manager, "ollamaHost", "127.0.0.1:" + server.getAddress().getPort());
            org.springframework.test.util.ReflectionTestUtils.setField(manager, "warmupModel", "fixture");
            org.springframework.test.util.ReflectionTestUtils.setField(manager, "warmupEnabled", true);
            org.springframework.test.util.ReflectionTestUtils.setField(manager, "warmupEmbed", true);
            org.springframework.test.util.ReflectionTestUtils.setField(manager, "warmupDimensions", 3);
            assertThrows(Exception.class, () -> org.springframework.test.util.ReflectionTestUtils.invokeMethod(manager, "warmupOnce"));
            var result = manager.diagnostics();
            assertEquals("embedding_dimension_mismatch", result.get("warmupReason"));
            assertEquals(3, result.get("warmupTargetDim"));
            assertEquals(2, result.get("warmupReturnedDim"));
            assertEquals(false, result.get("warmupVerified"));
        } finally { server.stop(0); }
    }
    @Test void repeatedHeartbeatDoesNotDoubleCountClientEvents() throws Exception {
        var service = new RuntimeDiagnosticsService(null, null, null);
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        service.bindTo(registry);
        var client = new com.fasterxml.jackson.databind.ObjectMapper().readTree("{\"telemetry\":{\"toolCancelled\":2}}");
        for (int i = 0; i < 2; i++) org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "observeClientTelemetry", client, 123L);
        assertEquals(2, registry.get("runtime.toolkit.events").tag("event", "toolCancelled").counter().count());
    }
    @Test void nonServingSpringCannotBeFullReady() {
        var snapshot = RuntimeDiagnosticsService.projectToolkit(Map.of("enabled", false), pipe(), definitions(), false);
        assertEquals(false, snapshot.get("FULL_LOAD_READY"));
        assertEquals(false, snapshot.get("dependenciesReady"));
        var spring = (Map<?, ?>) ((List<?>) snapshot.get("services")).get(0);
        assertEquals("NOT_OBSERVED", spring.get("status"));
    }
    @Test void pipeOwnershipAndSpringIdentityAreDistinct() {
        var proof = new java.util.HashMap<>(pipe()); proof.put("processPid", 4321);
        var snapshot = RuntimeDiagnosticsService.projectToolkit(Map.of("enabled", false), proof, definitions(), true);
        var rows = (List<?>) snapshot.get("services");
        assertEquals(0, ((Map<?, ?>) rows.get(0)).get("processPid"));
        assertEquals(4321, ((Map<?, ?>) rows.get(2)).get("processPid"));
        assertEquals(true, ((Map<?, ?>) rows.get(2)).get("processOwned"));
    }
    @Test void stdioRestartEvidenceReachesItsServiceWithoutLeakingToOtherRows() {
        var proof = new java.util.HashMap<>(pipe()); proof.put("restartCount", 2L);
        var snapshot = RuntimeDiagnosticsService.projectToolkit(Map.of("enabled", false), proof, definitions(), true);
        var rows = (List<?>) snapshot.get("services");
        assertEquals(2L, ((Map<?, ?>) rows.get(2)).get("restartCount"));
        assertEquals(0, ((Map<?, ?>) rows.get(0)).get("restartCount"));
        assertEquals(0, ((Map<?, ?>) rows.get(1)).get("restartCount"));
        var unobserved = RuntimeDiagnosticsService.projectToolkit(Map.of("enabled", false), pipe(), definitions(), true);
        assertEquals("NOT_OBSERVED", ((Map<?, ?>) ((List<?>) unobserved.get("services")).get(2)).get("restartCount"));
    }
    @Test void acceptedClientCountKeepsMalformedTelemetryOutOfDiagnostics() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (String value : List.of("null", "true", "\"2\"", "-1", "1.5", "1000000001", "999999999999999999999")) {
            var count = org.springframework.test.util.ReflectionTestUtils.invokeMethod(RuntimeDiagnosticsService.class,
                    "acceptedClientCount", mapper.readTree(value));
            assertEquals("NOT_OBSERVED", count, value);
        }
        Object accepted = org.springframework.test.util.ReflectionTestUtils.invokeMethod(RuntimeDiagnosticsService.class,
                "acceptedClientCount", mapper.readTree("2"));
        assertEquals(2L, accepted);
    }
    @Test void clientProofRequiresVersionAndBooleanTypes() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var evidence = mapper.createObjectNode();
        evidence.put("schemaVersion", "awx.runtime.client-proof.v1");
        var client = evidence.putObject("stdio"); client.put("protocolFamily", "MCP_STYLE");
        for (String key : pipe().keySet()) client.put(key, true);
        client.put("smokeVerifiedAtEpochMs", System.currentTimeMillis());
        evidence.put("manifestHash", "fixture-manifest"); client.put("manifestHash", "fixture-manifest");
        assertTrue(RuntimeDiagnosticsService.clientContractValid(evidence));
        client.put("sessionAlive", "true");
        assertFalse(RuntimeDiagnosticsService.clientContractValid(evidence));
        client.put("sessionAlive", true); evidence.put("schemaVersion", "future");
        assertFalse(RuntimeDiagnosticsService.clientContractValid(evidence));
    }
    private List<Map<String, Object>> definitions() {
        return List.of(Map.of("serviceId", "spring-app", "role", "RUNTIME_REQUIRED", "dependencies", List.of()),
                Map.of("serviceId", "ollama-local", "role", "RUNTIME_REQUIRED", "dependencies", List.of("spring-app")),
                Map.of("serviceId", "awx-stdio", "role", "ON_DEMAND", "requiredForProfile", List.of("local-toolkit"), "dependencies", List.of("spring-app")));
    }
    private Map<String, Object> pipe() {
        return Map.of("protocolProbe", true, "catalogValidated", true, "smokeVerified", true, "sessionAlive", true);
    }
    @Test void optionalDisabledDoesNotFailRequiredSet() {
        var snapshot = RuntimeDiagnosticsService.projectToolkit(Map.of("enabled", false, "state", "DISABLED"), pipe(), definitions(), true);
        assertEquals(2, snapshot.get("requiredTotal"));
        assertEquals(1, snapshot.get("optionalTotal"));
        assertEquals(true, snapshot.get("FULL_LOAD_READY"));
    }
    @ParameterizedTest
    @ValueSource(strings = {"GPU_UNAVAILABLE", "INSUFFICIENT_MEMORY"})
    void requiredFailureKeepsFallbackDistinct(String reason) {
        var snapshot = RuntimeDiagnosticsService.projectToolkit(Map.of("enabled", true, "state", "COOLDOWN", "fallbackUsed", true,
                "reasonCode", reason), pipe(), definitions(), true);
        assertEquals(false, snapshot.get("FULL_LOAD_READY"));
        var local = (Map<?, ?>) ((List<?>) snapshot.get("services")).get(1);
        assertEquals("COOLDOWN", local.get("status"));
        assertEquals("FALLBACK_ACTIVE", local.get("functionalStatus"));
        assertEquals("NOT_READY", local.get("primaryPathStatus"));
        assertEquals(reason, local.get("reasonCode"));
    }
    @Test void recoveryClearsActiveFallbackWithoutErasingHistoricalManagerData() {
        var snapshot = RuntimeDiagnosticsService.projectToolkit(Map.of("enabled", true, "state", "READY", "warmupVerified", true, "warmupEvidenceAgeMs", 0L,
                "fallbackUsed", true), pipe(), definitions(), true);
        assertEquals(true, snapshot.get("FULL_LOAD_READY"));
        var local = (Map<?, ?>) ((List<?>) snapshot.get("services")).get(1);
        assertEquals(false, local.get("fallbackUsed"));
        assertEquals("VERIFIED", local.get("functionalStatus"));
    }
    @Test void listenerAndInstalledModelsDoNotProveFunction() {
        var snapshot = RuntimeDiagnosticsService.projectToolkit(Map.of("enabled", true, "state", "READY", "modelPresent", true,
                "serviceResponding", true, "warmupVerified", false), pipe(), definitions(), true);
        assertEquals(false, snapshot.get("FULL_LOAD_READY"));
    }
    @Test void expiredOrMissingWarmupProofCannotRemainFullReady() {
        for (long age : new long[]{-1, 60001}) {
            var snapshot = RuntimeDiagnosticsService.projectToolkit(Map.of("enabled", true, "state", "READY", "warmupVerified", true,
                    "warmupEvidenceAgeMs", age), pipe(), definitions(), true);
            assertEquals(false, snapshot.get("FULL_LOAD_READY"));
            var local = (Map<?, ?>) ((List<?>) snapshot.get("services")).get(1);
            assertEquals("warmup_evidence_stale_or_missing", local.get("reasonCode"));
        }
    }
    @Test void missingPipeOrEmptySetNeverReady() {
        assertEquals(false, RuntimeDiagnosticsService.projectToolkit(Map.of("enabled", false), Map.of(), definitions(), true).get("FULL_LOAD_READY"));
        assertEquals(false, RuntimeDiagnosticsService.projectToolkit(Map.of("enabled", false), pipe(), List.of(), true).get("FULL_LOAD_READY"));
    }
    @Test void unknownRequiredServiceCannotBeIgnored() {
        var definitions = new java.util.ArrayList<>(definitions());
        definitions.add(Map.of("serviceId", "future-required", "role", "RUNTIME_REQUIRED", "dependencies", List.of()));
        assertEquals(false, RuntimeDiagnosticsService.projectToolkit(Map.of("enabled", false), pipe(), definitions, true).get("FULL_LOAD_READY"));
    }
    @Test void missingRequiredDependencyAndRestartLimitBlock() {
        var definitions = new java.util.ArrayList<>(definitions());
        definitions.set(0, Map.of("serviceId", "spring-app", "role", "RUNTIME_REQUIRED", "dependencies", List.of("absent")));
        assertEquals(false, RuntimeDiagnosticsService.projectToolkit(Map.of("enabled", false), pipe(), definitions, true).get("FULL_LOAD_READY"));
        var limited = new java.util.HashMap<>(pipe()); limited.put("restartLimited", true);
        assertEquals(false, RuntimeDiagnosticsService.projectToolkit(Map.of("enabled", false), limited, definitions(), true).get("FULL_LOAD_READY"));
    }
}
