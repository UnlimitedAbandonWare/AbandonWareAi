package com.example.lms.trace;

import com.example.lms.service.trace.TraceHtmlBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.lang.reflect.Field;
import java.util.Map;

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceSnapshotStoreAgentVisibleHtmlFocusedTest {

    @Test
    void harmonyMetadataHtmlIncludesAgentVisibleExternalEvidence() {
        TraceSnapshotStore store = enabledStore();
        String rawPayload = "private harmony override ownerToken=secret";
        String generatedPanel = "<section data-trace=\"chat-harmony\" data-kind=\"metadata-only\">"
                + "<h3>Chat Harmony Trace</h3><dl><dt>Raw</dt><dd>"
                + rawPayload
                + "</dd></dl></section>";

        String id = store.captureCustom(
                "unit_test",
                "POST",
                "/api/chat",
                200,
                null,
                Map.ofEntries(
                        entry("ui.traceHtml.kind", "chatHarmonyMetadataOnly"),
                        entry("ui.traceHtml.synthetic", true),
                        entry("ui.traceHtml.length", generatedPanel.length()),
                        entry("chat.harmony.postprocess.agentVisible", true),
                        entry("chat.harmony.postprocess.decision", "fallback_evidence"),
                        entry("chat.harmony.postprocess.reason", "fallback_mode_answer"),
                        entry("chat.harmony.postprocess.weightedScore", 0.433d),
                        entry("debug.ai.metrics.nextAction", "inspect_chat_harmony_trace"),
                        entry("prompt.agentDebugEvidence.external.browser.status", "OK"),
                        entry("prompt.agentDebugEvidence.external.computerUse.status", "OK"),
                        entry("prompt.agentDebugEvidence.external.supabase.status", "WARN"),
                        entry("prompt.agentDebugEvidence.external.supabase.evidenceNeeded",
                                "supabase_project_scope_or_auth_unverified"),
                        entry("prompt.agentDebugEvidence.external.supabase.nextAction",
                                "authenticate_supabase_mcp_or_cli")),
                generatedPanel);

        assertNotNull(id);
        TraceSnapshotStore.TraceSnapshot snapshot = store.get(id).orElseThrow();
        String html = snapshot.html();

        assertEquals("supabase_project_scope_or_auth_unverified",
                snapshot.trace().get("prompt.agentDebugEvidence.external.supabase.evidenceNeeded"));
        assertEquals("authenticate_supabase_mcp_or_cli",
                snapshot.trace().get("prompt.agentDebugEvidence.external.supabase.nextAction"));
        assertTrue(html.contains("External Evidence"));
        assertTrue(html.contains("prompt.agentDebugEvidence.external.browser.status"));
        assertTrue(html.contains("prompt.agentDebugEvidence.external.computerUse.status"));
        assertTrue(html.contains("prompt.agentDebugEvidence.external.supabase.evidenceNeeded"));
        assertTrue(html.contains("supabase_project_scope_or_auth_unverified"), html);
        assertTrue(html.contains("authenticate_supabase_mcp_or_cli"), html);
        assertFalse(html.contains(rawPayload));
        assertFalse(html.contains("ownerToken"));
        assertFalse(html.contains("SUPABASE_ACCESS_TOKEN"));
        assertFalse(html.contains("Authorization"));
        assertFalse(html.contains("Cookie"));
    }

    @Test
    void harmonyMetadataHtmlIncludesAgentVisibleLocalLlmOperatorAction() {
        TraceSnapshotStore store = enabledStore();
        String rawPayload = "private harmony override ownerToken=secret";
        String generatedPanel = "<section data-trace=\"chat-harmony\" data-kind=\"metadata-only\">"
                + "<h3>Chat Harmony Trace</h3><dl><dt>Raw</dt><dd>"
                + rawPayload
                + "</dd></dl></section>";

        String id = store.captureCustom(
                "unit_test",
                "POST",
                "/api/chat",
                200,
                null,
                Map.ofEntries(
                        entry("ui.traceHtml.kind", "chatHarmonyMetadataOnly"),
                        entry("ui.traceHtml.synthetic", true),
                        entry("ui.traceHtml.length", generatedPanel.length()),
                        entry("chat.harmony.postprocess.agentVisible", true),
                        entry("chat.harmony.postprocess.decision", "fallback_evidence"),
                        entry("chat.harmony.postprocess.reason", "fallback_mode_answer"),
                        entry("prompt.agentDebugEvidence.localLlm.operatorAction.triggerReason",
                                "threshold_exceeded"),
                        entry("prompt.agentDebugEvidence.localLlm.operatorAction.failureClass",
                                "model_blank"),
                        entry("prompt.agentDebugEvidence.localLlm.operatorAction.nextAction",
                                "prefer_native_ollama_route"),
                        entry("prompt.agentDebugEvidence.localLlm.operatorAction.actionScore", 100),
                        entry("prompt.agentDebugEvidence.localLlm.operatorAction.scoreDelta", 85),
                        entry("prompt.agentDebugEvidence.localLlm.operatorAction.rawSecret",
                                "ownerToken=private-token")),
                generatedPanel);

        assertNotNull(id);
        TraceSnapshotStore.TraceSnapshot snapshot = store.get(id).orElseThrow();
        String html = snapshot.html();

        assertEquals("model_blank",
                snapshot.trace().get("prompt.agentDebugEvidence.localLlm.operatorAction.failureClass"));
        assertEquals("prefer_native_ollama_route",
                snapshot.trace().get("prompt.agentDebugEvidence.localLlm.operatorAction.nextAction"));
        assertTrue(html.contains("Local LLM Operator Action"));
        assertTrue(html.contains("prompt.agentDebugEvidence.localLlm.operatorAction.failureClass"));
        assertTrue(html.contains("model_blank"));
        assertTrue(html.contains("prefer_native_ollama_route"));
        assertFalse(html.contains(rawPayload));
        assertFalse(html.contains("private-token"));
        assertFalse(html.contains("ownerToken"));
        assertFalse(html.contains("Authorization"));
        assertFalse(html.contains("Cookie"));
    }

    private static TraceSnapshotStore enabledStore() {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        TraceSnapshotStore store = new TraceSnapshotStore(factory.getBeanProvider(TraceHtmlBuilder.class));
        setField(store, "enabled", true);
        setField(store, "maxSize", 20);
        setField(store, "maxValueLen", 1000);
        setField(store, "maxEntries", 100);
        setField(store, "allowReasonsCsv", "");
        setField(store, "denyReasonsCsv", "");
        setField(store, "allowKeysCsv", "");
        setField(store, "allowKeysMode", "any");
        setField(store, "denyKeysCsv", "");
        setField(store, "captureSample", 1.0d);
        setField(store, "minIntervalMs", 0L);
        setField(store, "maxPerTrace", 10);
        setField(store, "budgetWindowMs", 600_000L);
        setField(store, "httpStatusMin", 400);
        setField(store, "captureHttpOnDebug", true);
        setField(store, "captureHttpOnMl", true);
        setField(store, "captureHttpOnOrch", true);
        setField(store, "captureHttpOnException", true);
        setField(store, "htmlEnabled", true);
        setField(store, "htmlMaxLen", 60_000);
        return store;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("failed to set field " + name, e);
        }
    }
}
