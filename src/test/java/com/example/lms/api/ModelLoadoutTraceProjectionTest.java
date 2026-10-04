package com.example.lms.api;

import com.example.lms.prompt.pose.ModelLoadoutResolver;
import com.example.lms.service.trace.TraceHtmlBuilder;
import com.example.lms.trace.SafeRedactor;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ModelLoadoutTraceProjectionTest {
    private static Map<String, Object> fixture() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("prompt.loadout.role", "MAIN_HIGH");
        meta.put("prompt.loadout.id", "compatible");
        meta.put("prompt.loadout.version", "1");
        meta.put("prompt.loadout.skills", List.of(ModelLoadoutResolver.initialSkills().get(0).ref()));
        meta.put("prompt.loadout.policy", "existing");
        meta.put("prompt.loadout.retrieval", "CURRENT");
        meta.put("prompt.loadout.reasons", List.of("COMPATIBLE_LOADOUT"));
        meta.put("prompt.loadout.inputTokens", 512L);
        meta.put("prompt.loadout.countMethod", "CL100K_ESTIMATE");
        meta.put("prompt.loadout.applied", true);
        meta.put("prompt.loadout.observation", "NO_OBSERVATION");
        return meta;
    }

    private static String durableHeader() {
        return "reason=scored\nmethod=rule\nstorageMode=durable_fallback\npathHash=none\nassistantMessageId=1\n";
    }

    @Test
    void exactTypedFieldsSurviveWithoutGrantingArbitraryPromptAuthority() {
        fixture().forEach((key, value) -> {
            assertTrue(SafeRedactor.isTypedDiagnostic(key, value), key);
            assertEquals(value, SafeRedactor.diagnosticValue(key, value), key);
        });
        String canary = "private trace canary raw question";
        for (String key : List.of("prompt.loadout.rawPrompt", "prompt.loadout.id", "prompt.body")) {
            assertFalse(SafeRedactor.isTypedDiagnostic(key, canary), key);
            assertFalse(String.valueOf(SafeRedactor.diagnosticValue(key, canary)).contains(canary), key);
        }
        assertFalse(SafeRedactor.isTypedDiagnostic("prompt.loadout.inputTokens", "512"));
        assertFalse(SafeRedactor.isTypedDiagnostic("prompt.loadout.applied", "true"));
        assertFalse(SafeRedactor.isTypedDiagnostic("prompt.loadout.inputTokens", -1));
        assertFalse(SafeRedactor.isTypedDiagnostic("prompt.loadout.inputTokens", 1_000_001));
    }

    @Test
    void forgedAndUnboundedReferencesAreNotPromoted() {
        assertFalse(SafeRedactor.isTypedDiagnostic("prompt.loadout.skills", List.of("evidence-summary@1#forged")));
        assertFalse(SafeRedactor.isTypedDiagnostic("prompt.loadout.reasons", List.of("PRIVATE_USER_NAME")));
        assertFalse(SafeRedactor.isTypedDiagnostic("prompt.loadout.reasons", java.util.Collections.nCopies(9, "COMPATIBLE_LOADOUT")));
        assertFalse(SafeRedactor.isTypedDiagnostic("prompt.loadout.skills", java.util.Collections.nCopies(4,
                ModelLoadoutResolver.initialSkills().get(0).ref())));
        assertFalse(SafeRedactor.isTypedDiagnostic("prompt.loadout.version", "2"));
    }

    @Test
    void htmlShowsLoadoutOutsideGenericPromptLimitAndKeepsRawCanaryHidden() {
        Map<String, Object> meta = fixture();
        for (int i = 0; i < 30; i++) meta.put("prompt.a" + i, "private generic prompt canary");
        meta.put("prompt.loadout.rawPrompt", "private loadout prompt canary");
        String html = new TraceHtmlBuilder(null).buildSnapshotHtml("snapshot-loadout", "2026-10-03T00:00:00Z",
                "sid-hash", "trace-hash", "request-hash", "unit_test", "POST", "/api/chat/stream", 200,
                null, meta, Map.of());
        assertTrue(html.contains("prompt.loadout.role"));
        assertTrue(html.contains("MAIN_HIGH"));
        assertTrue(html.contains("prompt.loadout.inputTokens"));
        assertTrue(html.contains("COMPATIBLE_LOADOUT"));
        assertTrue(html.contains(ModelLoadoutResolver.initialSkills().get(0).ref()));
        assertFalse(html.contains("private generic prompt canary"));
        assertFalse(html.contains("private loadout prompt canary"));
    }

    @Test
    void durableProjectionRoundTripsExactScalarAndReferenceTypes() {
        Map<String, Object> meta = fixture();
        meta.put("prompt.loadout.rawPrompt", "private durable canary");
        meta.put("observedModel", "fixture-observed-model");
        Map<String, String> projection = ChatTraceMetaMessageRestorer.projectDiagnostics(meta);
        assertEquals(fixture().size() + 1, projection.size());
        String envelope = durableHeader() + String.join("\n", projection.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList());
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(envelope.getBytes(StandardCharsets.UTF_8));
        var restored = ChatTraceMetaMessageRestorer.parseSnapshotPointer("?TRACESNAP?snapshot-loadout|v3|" + encoded, 1L).orElseThrow();
        fixture().forEach((key, value) -> assertEquals(value, restored.diagnostics().get(key), key));
        assertEquals("fixture-observed-model", restored.diagnostics().get("observedModel"));
        assertFalse(restored.diagnostics().containsKey("prompt.loadout.rawPrompt"));
        assertFalse(envelope.contains("private durable canary"));
    }

    @Test
    void emptySkillsRoundTripAndForgedDurableFieldsFailClosed() {
        Map<String, Object> meta = fixture();
        meta.put("prompt.loadout.skills", List.of());
        var fields = ChatTraceMetaMessageRestorer.projectDiagnostics(meta);
        assertEquals("l:", fields.get("diag.prompt.loadout.skills"));
        String empty = Base64.getUrlEncoder().withoutPadding().encodeToString(
                (durableHeader() + "diag.prompt.loadout.skills=l:\n").getBytes(StandardCharsets.UTF_8));
        var restored = ChatTraceMetaMessageRestorer.parseSnapshotPointer("?TRACESNAP?snapshot-empty|v3|" + empty, 2L).orElseThrow();
        assertEquals(List.of(), restored.diagnostics().get("prompt.loadout.skills"));
        for (String field : List.of("diag.prompt.loadout.reasons=l:PRIVATE_USER_NAME",
                "diag.prompt.loadout.skills=l:evidence-summary@1#forged", "diag.prompt.loadout.rawPrompt=s:PRIVATE_USER_NAME")) {
            String forged = Base64.getUrlEncoder().withoutPadding().encodeToString((durableHeader() + field).getBytes(StandardCharsets.UTF_8));
            assertTrue(ChatTraceMetaMessageRestorer.parseSnapshotPointer("?TRACESNAP?snapshot-forged|v3|" + forged, 3L)
                    .orElseThrow().diagnostics().isEmpty());
        }
    }
}
