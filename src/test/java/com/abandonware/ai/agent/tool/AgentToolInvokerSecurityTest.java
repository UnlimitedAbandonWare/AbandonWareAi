package com.abandonware.ai.agent.tool;

import ai.abandonware.nova.orch.failpattern.FailurePatternMemoryService;
import com.abandonware.ai.agent.consent.BasicConsentService;
import com.abandonware.ai.agent.consent.ConsentService;
import com.abandonware.ai.agent.consent.ConsentToken;
import com.abandonware.ai.agent.contract.ToolManifestCatalog;
import com.abandonware.ai.agent.contract.ToolManifestEntry;
import com.abandonware.ai.agent.contract.ToolManifestSnapshot;
import com.abandonware.ai.agent.policy.ToolPolicyEnforcer;
import com.abandonware.ai.agent.tool.annotations.RequiresScopes;
import com.abandonware.ai.agent.tool.request.ToolContext;
import com.abandonware.ai.agent.tool.request.ToolRequest;
import com.abandonware.ai.agent.tool.response.ToolResponse;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.TraceContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentToolInvokerSecurityTest {
    @TempDir
    Path tempDir;

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
        TraceContext.cleanupCurrentThread();
    }

    @Test
    void internalReadToolRequiresAdminOrConsentScope() {
        AgentToolInvoker invoker = invoker(new InternalReadTool("ops.snapshot", Map.of("status", "ok")));

        ToolInvocationException ex = assertThrows(ToolInvocationException.class,
                () -> invoker.invoke("ops.snapshot", Map.of(), new ToolContext("s1", null), false));

        assertTrue(ex.code().contains("missing_consent_service"));
    }

    @Test
    void adminInvocationReportsAuthorizationBudgetAndResultValidationProvenance() {
        AgentToolInvoker invoker = invoker(new InternalReadTool("ops.snapshot", Map.of("status", "ok")));

        try (TraceContext ignored = TraceContext.attach("s1", "admin-provenance")
                .startWithBudget(Duration.ofSeconds(5))) {
            Map<String, Object> result = invoker.invoke(
                    "ops.snapshot", Map.of(), new ToolContext("s1", null), true);

            assertEquals("ALLOW", result.get("policyDecision"));
            assertEquals("ADMIN_TOKEN", result.get("authorizationSource"));
            assertEquals(Boolean.FALSE, result.get("userConsentGranted"));
            assertEquals(Boolean.TRUE, result.get("budgetBounded"));
            assertEquals("PASSED", result.get("resultValidation"));
            assertEquals(Boolean.TRUE, TraceStore.get("tool.invoke.adminAuthorized"));
            assertEquals("ADMIN_TOKEN", TraceStore.get("tool.invoke.authorizationSource"));
            assertEquals(Boolean.FALSE, TraceStore.get("tool.invoke.userConsentGranted"));
            assertEquals(Boolean.TRUE, TraceStore.get("tool.invoke.hasConsent"));
        }
    }

    @Test
    void sessionConsentGrantIsReportedSeparatelyFromAdminAuthorization() {
        BasicConsentService service = new BasicConsentService();
        service.issue("s1", Set.of(ToolScope.INTERNAL_READ), 60L);
        ObjectProvider<ConsentService> consent = mock(ObjectProvider.class);
        when(consent.getIfAvailable()).thenReturn(service);
        AgentToolInvoker invoker = invoker(
                new InternalReadTool("ops.snapshot", Map.of("status", "ok")),
                new ToolManifestCatalog(),
                consent);

        Map<String, Object> result = invoker.invoke(
                "ops.snapshot",
                Map.of(),
                new ToolContext("s1", new ConsentToken("s1")),
                false);

        assertEquals("ALLOW", result.get("policyDecision"));
        assertEquals("CONSENT_GRANT", result.get("authorizationSource"));
        assertEquals(Boolean.TRUE, result.get("userConsentGranted"));
        assertEquals(Boolean.FALSE, result.get("budgetBounded"));
        assertEquals("CONSENT_GRANT", TraceStore.get("tool.invoke.authorizationSource"));
        assertEquals(Boolean.TRUE, TraceStore.get("tool.invoke.userConsentGranted"));
        assertEquals(Boolean.FALSE, TraceStore.get("tool.invoke.adminAuthorized"));
    }

    @Test
    void ownerTokenManifestRequirementKeepsAdminAuthorizationProvenance() {
        ToolManifestEntry entry = new ToolManifestEntry(
                "ops.owner",
                true,
                "owner-only tool",
                "read_only",
                List.of(),
                true,
                true,
                65536,
                false,
                "",
                "",
                Map.of());
        AgentToolInvoker invoker = invoker(
                new PlainTool("ops.owner", Map.of("status", "ok")),
                catalogWith(entry));

        Map<String, Object> result = invoker.invoke(
                "ops.owner", Map.of(), new ToolContext("s1", null), true);

        assertEquals("ADMIN_TOKEN", result.get("authorizationSource"));
        assertEquals(Boolean.FALSE, result.get("userConsentGranted"));
        assertEquals("ADMIN_TOKEN", TraceStore.get("tool.invoke.authorizationSource"));
    }

    @Test
    void disabledManifestToolCannotBeInvokedEvenWhenRegistered() {
        AgentToolInvoker invoker = invoker(new PlainTool("message.send", Map.of("accepted", true)));

        ToolInvocationException ex = assertThrows(ToolInvocationException.class,
                () -> invoker.invoke("message.send", Map.of(), new ToolContext("s1", null), true));

        assertTrue(ex.code().contains("tool_disabled"));
    }

    @Test
    void disabledToolPolicyMasksUnsafeManifestReason() {
        ToolPolicyEnforcer policy = enabledPolicy();
        ToolManifestEntry entry = manifestEntry(
                "message.send",
                false,
                "ownerToken=sk-" + "redactioncontract1234567890");

        ToolInvocationException ex = assertThrows(ToolInvocationException.class,
                () -> policy.beforeCall("message.send", entry, true, true));

        assertTrue(ex.code().startsWith("tool_disabled:hash:"), ex.code());
        assertFalse(ex.code().contains("ownerToken"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void describeToolsMasksUnsafeDisabledReason() {
        String unsafeReason = "Authorization=Bearer sk-" + "redactioncontract1234567890";
        AgentToolInvoker invoker = invoker(
                new PlainTool("message.send", Map.of()),
                catalogWith(manifestEntry("message.send", false, unsafeReason)));

        Map<String, Object> described = invoker.describeTools();

        List<Map<String, Object>> tools = (List<Map<String, Object>>) described.get("tools");
        String rendered = String.valueOf(tools.get(0).get("disabledReason"));
        assertTrue(rendered.startsWith("hash:"), rendered);
        assertFalse(String.valueOf(described).contains("Authorization"));
    }

    @Test
    void describeToolsDoesNotReturnRawManifestResourcePath() {
        AgentToolInvoker invoker = invoker(
                new PlainTool("message.send", Map.of()),
                catalogWith(manifestEntry("message.send", true, "")));

        Map<String, Object> described = invoker.describeTools();

        assertFalse(described.containsKey("resourcePath"));
        assertTrue(String.valueOf(described.get("resourcePathHash")).length() >= 12);
        assertTrue(((Number) described.get("resourcePathLength")).intValue() > 0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void manifestValidationMasksDisabledReferenceReason() {
        String unsafeReason = "raw query with ownerToken=sk-" + "redactioncontract1234567890";
        ToolManifestCatalog catalog = catalogWith(manifestEntry("message.send", false, unsafeReason));

        Map<String, Object> report = catalog.validate(new ToolRegistry());

        List<Map<String, Object>> warnings = (List<Map<String, Object>>) report.get("warnings");
        String rendered = String.valueOf(warnings.get(0).get("detail"));
        assertTrue(rendered.startsWith("hash:"), rendered);
        assertFalse(String.valueOf(report).contains("ownerToken"));
        assertFalse(String.valueOf(report).contains("raw query"));
    }

    @Test
    void failurePatternRecordRequiresOwnerTokenOrAdminPath() {
        AgentToolInvoker invoker = invoker(new PlainTool("failure.pattern.record", Map.of("recorded", true)));

        ToolInvocationException ex = assertThrows(ToolInvocationException.class,
                () -> invoker.invoke("failure.pattern.record", Map.of(), new ToolContext("s1", null), false));

        assertTrue(ex.code().contains("owner_token_required"));
    }

    @Test
    void adminInvocationRedactsSecretLikeResponseValues() {
        AgentToolInvoker invoker = invoker(new InternalReadTool("ops.snapshot",
                Map.of("apiKey", "sk-live-secret-value", "status", "ok")));

        Map<String, Object> result = invoker.invoke("ops.snapshot", Map.of(), new ToolContext("s1", null), true);
        String rendered = result.toString();

        assertFalse(rendered.contains("sk-live-secret-value"));
        assertTrue(rendered.contains("redacted") || rendered.contains("hash"));
    }

    @Test
    void adminInvocationLeavesTraceStoreLifecycleWithoutRawInput() {
        AgentToolInvoker invoker = invoker(new InternalReadTool("ops.snapshot", Map.of("status", "ok")));

        invoker.invoke("ops.snapshot",
                Map.of("query", "private query sk-" + "redactioncontract1234567890"),
                new ToolContext("session-raw", null),
                true);

        assertEquals("ops.snapshot", TraceStore.get("tool.invoke.id"));
        assertEquals(Boolean.TRUE, TraceStore.get("tool.invoke.hasConsent"));
        assertEquals(Boolean.TRUE, TraceStore.get("tool.invoke.scopesPassed"));
        assertEquals("OK", TraceStore.get("tool.invoke.status"));
        assertTrue(((Number) TraceStore.get("tool.invoke.durationMs")).longValue() >= 0L);
        assertTrue(((Number) TraceStore.get("tool.invoke.start")).longValue() > 0L);
        assertFalse(String.valueOf(TraceStore.getAll()).contains("private query"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("sk-redactioncontract"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void adminInvocationAppendsRedactedToolTimelineBreadcrumb() {
        AgentToolInvoker invoker = invoker(new InternalReadTool("ops.snapshot", Map.of("status", "ok")));

        invoker.invoke("ops.snapshot",
                Map.of("query", "private query sk-" + "redactioncontract1234567890"),
                new ToolContext("session-raw", null),
                true);

        Object events = TraceStore.get("orch.events.v1");
        assertTrue(events instanceof List<?>);
        Map<String, Object> event = ((List<?>) events).stream()
                .filter(Map.class::isInstance)
                .map(row -> (Map<String, Object>) row)
                .filter(row -> "agent.tool".equals(row.get("kind")))
                .findFirst()
                .orElseThrow();
        Map<String, Object> data = (Map<String, Object>) event.get("data");
        Map<String, Object> rawTile = (Map<String, Object>) data.get("rawTile");

        assertEquals("invoke", event.get("phase"));
        assertEquals("ok", event.get("step"));
        assertEquals("ops.snapshot", data.get("toolId"));
        assertEquals("OK", data.get("status"));
        assertEquals(1, data.get("inputKeyCount"));
        assertEquals(1, data.get("outputKeyCount"));
        assertEquals("agent_tool_raw_tile", rawTile.get("kind"));
        assertEquals(Boolean.FALSE, rawTile.get("rawPayloadStored"));
        assertTrue(String.valueOf(rawTile.get("patternId")).startsWith("hash:"));
        String rendered = String.valueOf(events);
        assertFalse(rendered.contains("private query"));
        assertFalse(rendered.contains("sk-redactioncontract"));
        assertFalse(rendered.contains("session-raw"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void adminInvocationStoresRecallableDurableBreadcrumbMemory() throws Exception {
        Path memory = tempDir.resolve("failure-pattern-memory.jsonl");
        FailurePatternMemoryService service = new FailurePatternMemoryService(
                new ObjectMapper(), new ToolManifestCatalog(), tempDir, memory);
        ObjectProvider<FailurePatternMemoryService> memoryProvider = mock(ObjectProvider.class);
        when(memoryProvider.getIfAvailable()).thenReturn(service);
        AgentToolInvoker invoker = invoker(new InternalReadTool("ops.snapshot", Map.of("status", "ok")));
        ReflectionTestUtils.setField(invoker, "failurePatternMemory", memoryProvider);

        invoker.invoke("ops.snapshot",
                Map.of("query", "private query sk-" + "redactioncontract1234567890"),
                new ToolContext("session-raw", null),
                true);

        String line = Files.readString(memory);
        assertTrue(line.contains("\"kind\":\"agent_tool_breadcrumb\""));
        assertTrue(line.contains("\"source\":\"agent_breadcrumb\""));
        assertTrue(line.contains("\"failureClass\":\"OK\""));
        assertTrue(line.contains("\"hotspot\":\"ops.snapshot\""));
        assertFalse(line.contains("private query"));
        assertFalse(line.contains("sk-redactioncontract"));
        assertFalse(line.contains("session-raw"));
        assertEquals(Boolean.TRUE, TraceStore.get("agent.breadcrumb.memory.stored"));
        assertEquals("agent_tool_breadcrumb", TraceStore.get("agent.breadcrumb.memory.kind"));

        Map<String, Object> recalled = service.recall(Map.of(
                "kind", "agent_tool_breadcrumb",
                "source", "agent_breadcrumb",
                "failureClass", "OK",
                "hotspot", "ops.snapshot"));
        assertEquals(1, recalled.get("matchCount"));
        List<Map<String, Object>> matches = (List<Map<String, Object>>) recalled.get("matches");
        assertEquals("OK", matches.get(0).get("patchAction"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void adminInvocationAddsPriorMemoryRecallToTimelineBreadcrumb() {
        FailurePatternMemoryService service = new FailurePatternMemoryService(
                new ObjectMapper(), new ToolManifestCatalog(), tempDir, tempDir.resolve("failure-pattern-memory.jsonl"));
        service.record(Map.of(
                "kind", "agent_tool_breadcrumb",
                "source", "agent_breadcrumb",
                "failureClass", "OK",
                "hotspot", "ops.snapshot",
                "intent", "agent_tool_breadcrumb|ops.snapshot|OK",
                "evidence", "prior-success-pattern",
                "patchAction", "OK",
                "decision", "ok",
                "matrix", Map.of("m1", 1, "m2", 1)));
        ObjectProvider<FailurePatternMemoryService> memoryProvider = mock(ObjectProvider.class);
        when(memoryProvider.getIfAvailable()).thenReturn(service);
        AgentToolInvoker invoker = invoker(new InternalReadTool("ops.snapshot", Map.of("status", "ok")));
        ReflectionTestUtils.setField(invoker, "failurePatternMemory", memoryProvider);

        invoker.invoke("ops.snapshot",
                Map.of("query", "private query sk-" + "redactioncontract1234567890"),
                new ToolContext("session-raw", null),
                true);

        Object events = TraceStore.get("orch.events.v1");
        assertTrue(events instanceof List<?>);
        Map<String, Object> event = ((List<?>) events).stream()
                .filter(Map.class::isInstance)
                .map(row -> (Map<String, Object>) row)
                .filter(row -> "agent.tool".equals(row.get("kind")))
                .findFirst()
                .orElseThrow();
        Map<String, Object> data = (Map<String, Object>) event.get("data");
        Map<String, Object> recall = (Map<String, Object>) data.get("memoryRecall");

        assertEquals(1, recall.get("matchCount"));
        assertEquals("OK", recall.get("recommendedAction"));
        assertTrue(String.valueOf(recall.get("topPatternId")).length() >= 12);
        assertEquals(1, TraceStore.get("agent.breadcrumb.memory.recall.matchCount"));
        String rendered = String.valueOf(events);
        assertFalse(rendered.contains("private query"));
        assertFalse(rendered.contains("sk-redactioncontract"));
        assertFalse(rendered.contains("session-raw"));
    }

    @Test
    void executionFailureLeavesRedactedTraceStoreFailureHash() {
        AgentToolInvoker invoker = invoker(new FailingTool("ops.snapshot"));

        ToolInvocationException ex = assertThrows(ToolInvocationException.class,
                () -> invoker.invoke("ops.snapshot", Map.of(), new ToolContext("s1", null), true));

        assertTrue(ex.code().contains("tool_execution_failed"), ex.code());
        assertEquals("ops.snapshot", TraceStore.get("tool.invoke.id"));
        assertEquals("FAIL", TraceStore.get("tool.invoke.status"));
        assertEquals("tool_execution_failed", TraceStore.get("tool.invoke.failReason"));
        assertTrue(String.valueOf(TraceStore.get("tool.invoke.failMsgHash")).startsWith("hash:"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("ownerToken"));
    }

    @Test
    void nullToolResponseFailsClosedInsteadOfRecordingFalseSuccess() {
        AgentToolInvoker invoker = invoker(new NullResponseTool("ops.snapshot"));

        ToolInvocationException ex = assertThrows(ToolInvocationException.class,
                () -> invoker.invoke("ops.snapshot", Map.of(), new ToolContext("s1", null), true));

        assertEquals("tool_result_invalid", ex.code());
        assertEquals("FAIL", TraceStore.get("tool.invoke.status"));
        assertEquals("tool_result_invalid", TraceStore.get("tool.invoke.failReason"));
        assertTrue(String.valueOf(TraceStore.get("tool.invoke.failMsgHash")).startsWith("hash:"));
    }

    @Test
    void resultArrivingAfterAttachedDeadlineFailsClosed() {
        AgentToolInvoker invoker = invoker(new SlowTool("ops.snapshot", 750L));

        try (TraceContext ignored = TraceContext.attach("s1", "tool-budget")
                .startWithBudget(Duration.ofMillis(500L))) {
            ToolInvocationException ex = assertThrows(
                    ToolInvocationException.class,
                    () -> invoker.invoke("ops.snapshot", Map.of(), new ToolContext("s1", null), true));

            assertEquals("tool_budget_exhausted", ex.code());
            assertEquals("FAIL", TraceStore.get("tool.invoke.status"));
        }
    }

    @Test
    void sideEffectResultAfterDeadlineIsMarkedNonRetryableInsteadOfReturningFalse408() {
        AgentToolInvoker invoker = invoker(
                new SlowTool("side.effect", 750L),
                catalogWith(manifestEntry("side.effect", true, "")));

        try (TraceContext ignored = TraceContext.attach("s1", "side-effect-budget")
                .startWithBudget(Duration.ofMillis(500L))) {
            Map<String, Object> result = invoker.invoke(
                    "side.effect", Map.of(), new ToolContext("s1", null), true);

            assertEquals(true, result.get("budgetExceeded"));
            assertEquals(false, result.get("retryRecommended"));
            assertEquals(Boolean.TRUE, TraceStore.get("tool.invoke.budgetExceeded"));
            assertEquals("OK", TraceStore.get("tool.invoke.status"));
        }
    }

    @Test
    void oversizedResponseIsStoredAsArtifactReference() {
        Map<String, Object> payload = new LinkedHashMap<>();
        for (int i = 0; i < 120; i++) {
            payload.put("status" + i, "ok-" + i);
        }
        AgentToolInvoker invoker = invoker(new InternalReadTool("ops.snapshot", payload));
        ReflectionTestUtils.setField(invoker, "configuredMaxInlineBytes", 256);

        Map<String, Object> result = invoker.invoke("ops.snapshot", Map.of(), new ToolContext("s1", null), true);
        String rendered = result.toString();

        assertTrue(result.containsKey("artifact"));
        @SuppressWarnings("unchecked")
        Map<String, Object> artifact = (Map<String, Object>) result.get("artifact");
        assertFalse(artifact.containsKey("artifactPath"));
        assertTrue(String.valueOf(artifact.get("artifactId")).length() >= 12);
        assertTrue(String.valueOf(artifact.get("artifactPathHash")).length() >= 12);
        assertTrue(((Number) artifact.get("artifactPathLength")).intValue() > 0);
        assertFalse(rendered.contains("data:image"));
        assertFalse(rendered.contains("base64,"));
        assertFalse(rendered.contains("build/reports/abandon/tool-response/"));
    }

    @Test
    void toolExecutionFailureUsesStableOperationalCode() {
        AgentToolInvoker invoker = invoker(new FailingTool("ops.snapshot"));

        ToolInvocationException ex = assertThrows(ToolInvocationException.class,
                () -> invoker.invoke("ops.snapshot", Map.of(), new ToolContext("s1", null), true));

        assertTrue(ex.code().contains("tool_execution_failed"), ex.code());
        assertFalse(ex.code().contains("IllegalStateException"), ex.code());
        assertFalse(ex.code().contains("ownerToken"), ex.code());
    }

    @Test
    void artifactWriterFailureCodeDoesNotIncludeRawExceptionClass() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/abandonware/ai/agent/tool/AgentToolArtifactWriter.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("artifact_write_failed:\" + ex.getClass().getSimpleName()"));
        assertTrue(source.contains("ToolInvocationException.failed(\"artifact_write_failed\")"));
    }

    @Test
    void artifactWriterJsonFallbackLeavesBreadcrumb() {
        Map<String, Object> self = new LinkedHashMap<>();
        self.put("self", self);

        byte[] bytes = new AgentToolArtifactWriter().toJsonBytes(self);

        assertTrue(bytes.length > 0);
        assertTrue(Boolean.TRUE.equals(TraceStore.get("agent.toolArtifact.json.suppressed")));
        assertTrue("toJsonBytes".equals(TraceStore.get("agent.toolArtifact.json.suppressed.stage")));
        assertTrue(String.valueOf(TraceStore.get("agent.toolArtifact.json.suppressed.errorType")).length() > 0);
        assertFalse(String.valueOf(TraceStore.getAll()).contains("self=(this Map)"));
    }

    @SuppressWarnings("unchecked")
    private static AgentToolInvoker invoker(AgentTool tool) {
        return invoker(tool, new ToolManifestCatalog());
    }

    @SuppressWarnings("unchecked")
    private static AgentToolInvoker invoker(AgentTool tool, ToolManifestCatalog catalog) {
        ObjectProvider<ConsentService> consent = mock(ObjectProvider.class);
        when(consent.getIfAvailable()).thenReturn(null);
        return invoker(tool, catalog, consent);
    }

    private static AgentToolInvoker invoker(AgentTool tool,
                                            ToolManifestCatalog catalog,
                                            ObjectProvider<ConsentService> consent) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);
        ObjectProvider<DebugEventStore> debug = mock(ObjectProvider.class);
        when(debug.getIfAvailable()).thenReturn(null);
        ToolPolicyEnforcer policy = enabledPolicy();
        AgentToolInvoker invoker = new AgentToolInvoker(
                registry,
                catalog,
                policy,
                consent,
                debug,
                new AgentToolArtifactWriter());
        ReflectionTestUtils.setField(invoker, "configuredMaxInlineBytes", 65536);
        return invoker;
    }

    private static ToolPolicyEnforcer enabledPolicy() {
        ToolPolicyEnforcer policy = new ToolPolicyEnforcer();
        ReflectionTestUtils.setField(policy, "enabled", true);
        ReflectionTestUtils.setField(policy, "disabledIds", "");
        ReflectionTestUtils.setField(policy, "sideEffectRequireAdmin", true);
        return policy;
    }

    private static ToolManifestCatalog catalogWith(ToolManifestEntry entry) {
        return new ToolManifestCatalog() {
            @Override
            public ToolManifestSnapshot load() {
                return new ToolManifestSnapshot(
                        "test-manifest.json",
                        Map.of(entry.id(), entry),
                        List.of(),
                        List.of());
            }
        };
    }

    private static ToolManifestEntry manifestEntry(String id, boolean enabled, String disabledReason) {
        return new ToolManifestEntry(
                id,
                enabled,
                "test tool",
                "write_controlled",
                List.of(),
                false,
                false,
                65536,
                false,
                "",
                disabledReason,
                Map.of());
    }

    @RequiresScopes({ToolScope.INTERNAL_READ})
    private record InternalReadTool(String id, Map<String, Object> data) implements AgentTool {
        @Override
        public String description() {
            return "internal";
        }

        @Override
        public ToolResponse execute(ToolRequest request) {
            ToolResponse response = ToolResponse.ok();
            data.forEach(response::put);
            return response;
        }
    }

    private record PlainTool(String id, Map<String, Object> data) implements AgentTool {
        @Override
        public String description() {
            return "plain";
        }

        @Override
        public ToolResponse execute(ToolRequest request) {
            ToolResponse response = ToolResponse.ok();
            data.forEach(response::put);
            return response;
        }
    }

    private record FailingTool(String id) implements AgentTool {
        @Override
        public String description() {
            return "failing";
        }

        @Override
        public ToolResponse execute(ToolRequest request) {
            throw new IllegalStateException("ownerToken=fake-token");
        }
    }

    private record NullResponseTool(String id) implements AgentTool {
        @Override
        public String description() {
            return "invalid-result";
        }

        @Override
        public ToolResponse execute(ToolRequest request) {
            return null;
        }
    }

    private record SlowTool(String id, long delayMs) implements AgentTool {
        @Override
        public String description() {
            return "slow";
        }

        @Override
        public ToolResponse execute(ToolRequest request) throws InterruptedException {
            Thread.sleep(delayMs);
            return ToolResponse.ok().put("status", "late");
        }
    }
}
