package com.abandonware.ai.agent.tool.impl.ops;

import com.abandonware.ai.agent.consent.BasicConsentService;
import com.abandonware.ai.agent.consent.ConsentService;
import com.abandonware.ai.agent.consent.ConsentToken;
import com.abandonware.ai.agent.contract.ToolManifestCatalog;
import com.abandonware.ai.agent.policy.ToolPolicyEnforcer;
import com.abandonware.ai.agent.tool.AgentTool;
import com.abandonware.ai.agent.tool.AgentToolArtifactWriter;
import com.abandonware.ai.agent.tool.AgentToolInvoker;
import com.abandonware.ai.agent.tool.ToolInvocationException;
import com.abandonware.ai.agent.tool.ToolRegistry;
import com.abandonware.ai.agent.tool.ToolScope;
import com.abandonware.ai.agent.tool.annotations.RequiresScopes;
import com.abandonware.ai.agent.tool.request.ToolContext;
import com.abandonware.ai.agent.tool.request.ToolRequest;
import com.abandonware.ai.agent.tool.response.ToolResponse;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.TraceSnapshotStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TraceSnapshotToolStoredLookupTest {
    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void storedSnapshotNeverFallsBackToTheDiagnosticRequestsCurrentTrace() {
        TraceStore.put("request.marker", "diagnostic-B");
        ToolResponse result = new TraceSnapshotTool().execute(new ToolRequest(
                Map.of("mode", "stored_snapshot", "snapshotId", UUID.randomUUID().toString()),
                new ToolContext("diagnostic-B", null)));

        assertEquals("unavailable", result.data().get("sourceStatus"));
        assertFalse(String.valueOf(result.data()).contains("diagnostic-B"));
    }

    @Test
    void storedSnapshotUsesOnlyItsRetainedTraceAndRejectsMismatchedCorrelation() {
        String id = UUID.randomUUID().toString();
        String requestHash = "hash:aaaaaaaaaaaa";
        String traceHash = "hash:bbbbbbbbbbbb";
        TraceSnapshotStore.TraceSnapshot original = new TraceSnapshotStore.TraceSnapshot(
                id, 1L, "2026-09-27T00:00:00Z", "hash:cccccccccccc", "hash:cccccccccccc",
                traceHash, requestHash, "unit_test", "POST", "/api/chat", 500, null,
                false, 1, Map.of(), Map.of("diagnostic.reason", "original-A"), Map.of(), null, false);
        TraceSnapshotStore store = mock(TraceSnapshotStore.class);
        when(store.get(id)).thenReturn(Optional.of(original), Optional.of(original), Optional.empty());
        ObjectProvider<TraceSnapshotStore> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(store);
        TraceSnapshotTool tool = new TraceSnapshotTool(provider);
        TraceStore.put("diagnostic.reason", "diagnostic-B");

        ToolResponse found = tool.execute(new ToolRequest(
                Map.of("mode", "stored_snapshot", "snapshotId", id, "requestIdHash", requestHash,
                        "traceIdHash", traceHash), new ToolContext("diagnostic-B", null)));
        assertEquals("available", found.data().get("sourceStatus"));
        assertEquals(requestHash, found.data().get("requestIdHash"));
        assertEquals(traceHash, found.data().get("traceIdHash"));
        assertTrue(String.valueOf(found.data().get("entries")).contains("original-A"));
        assertFalse(String.valueOf(found.data().get("entries")).contains("diagnostic-B"));

        ToolResponse mismatch = tool.execute(new ToolRequest(
                Map.of("mode", "stored_snapshot", "snapshotId", id,
                        "requestIdHash", "hash:dddddddddddd"), new ToolContext("diagnostic-B", null)));
        assertEquals("stale", mismatch.data().get("sourceStatus"));
        assertEquals("correlation_mismatch", mismatch.data().get("reason"));
        assertEquals(0, mismatch.data().get("returnedCount"));

        ToolResponse expired = tool.execute(new ToolRequest(
                Map.of("mode", "stored_snapshot", "snapshotId", id), new ToolContext("diagnostic-B", null)));
        assertEquals("snapshot_not_retained", expired.data().get("reason"));
        assertEquals(0, expired.data().get("returnedCount"));
    }

    @Test
    void consentCannotReadStoredSnapshotWhileCurrentRequestAndAdminRemainAvailable() {
        BasicConsentService service = new BasicConsentService();
        service.issue("s1", Set.of(ToolScope.INTERNAL_READ), 60L);
        ObjectProvider<ConsentService> consent = mock(ObjectProvider.class);
        when(consent.getIfAvailable()).thenReturn(service);
        ObjectProvider<DebugEventStore> debug = mock(ObjectProvider.class);
        AtomicInteger calls = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new CountingSnapshotTool(calls));
        ToolPolicyEnforcer policy = new ToolPolicyEnforcer();
        ReflectionTestUtils.setField(policy, "enabled", true);
        ReflectionTestUtils.setField(policy, "disabledIds", "");
        ReflectionTestUtils.setField(policy, "sideEffectRequireAdmin", true);
        AgentToolInvoker invoker = new AgentToolInvoker(registry, new ToolManifestCatalog(),
                policy, consent, debug, new AgentToolArtifactWriter());
        ReflectionTestUtils.setField(invoker, "configuredMaxInlineBytes", 65536);
        ToolContext context = new ToolContext("s1", new ConsentToken("s1"));

        ToolInvocationException denied = assertThrows(ToolInvocationException.class,
                () -> invoker.invoke("trace.snapshot", Map.of("mode", "stored_snapshot"), context, false));
        assertEquals("stored_snapshot_admin_required", denied.code());
        assertEquals(0, calls.get());
        invoker.invoke("trace.snapshot", Map.of("mode", "current_request"), context, false);
        invoker.invoke("trace.snapshot", Map.of("mode", "stored_snapshot"), context, true);
        assertEquals(2, calls.get());
    }

    @RequiresScopes({ToolScope.INTERNAL_READ})
    private record CountingSnapshotTool(AtomicInteger calls) implements AgentTool {
        @Override public String id() { return "trace.snapshot"; }
        @Override public String description() { return "test snapshot gate"; }
        @Override public ToolResponse execute(ToolRequest request) {
            calls.incrementAndGet();
            return ToolResponse.ok().put("available", true);
        }
    }
}
