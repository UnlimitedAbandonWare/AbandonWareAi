package com.example.lms.api;

import com.example.lms.dto.ChatStreamEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatStreamSignalBuilderFocusedTest {

    @Test
    void debugFxSurfacesVirtualMatrixHotChunkBreadcrumbs() {
        ChatStreamEvent.DebugFxSignal signal = ChatStreamSignalBuilder.buildDebugFxSignal(
                Map.ofEntries(
                        Map.entry("debug.ai.metrics.virtualMatrix.count", 300),
                        Map.entry("debug.ai.metrics.virtualMatrix.chunkCount", 30),
                        Map.entry("debug.ai.metrics.virtualMatrix.weightedScore", 0.476d),
                        Map.entry("debug.ai.metrics.virtualMatrix.decision", "investigate_hot_chunk"),
                        Map.entry("debug.ai.metrics.virtualMatrix.hotChunkIndex", 17),
                        Map.entry("debug.ai.metrics.virtualMatrix.hotChunkRiskScore", 0.77d)),
                null,
                null);

        assertEquals("300", signal.labels().get("debugAiMatrixCount"));
        assertEquals("30", signal.labels().get("debugAiMatrixChunkCount"));
        assertEquals("0.476", signal.labels().get("debugAiMatrixScore"));
        assertEquals("investigate_hot_chunk", signal.labels().get("debugAiMatrixDecision"));
        assertEquals("17", signal.labels().get("debugAiMatrixHotChunkIndex"));
        assertEquals("0.77", signal.labels().get("debugAiMatrixHotChunkRiskScore"));
    }

    @Test
    void debugFxFallsBackToPromptAgentVisibleVirtualMatrixWhenDebugAiTraceMissing() {
        ChatStreamEvent.DebugFxSignal signal = ChatStreamSignalBuilder.buildDebugFxSignal(
                Map.ofEntries(
                        Map.entry("prompt.agentDebugEvidence.virtualMatrix.count", 300),
                        Map.entry("prompt.agentDebugEvidence.virtualMatrix.chunkCount", 30),
                        Map.entry("prompt.agentDebugEvidence.virtualMatrix.weightedScore", 0.312d),
                        Map.entry("prompt.agentDebugEvidence.virtualMatrix.decision", "observe"),
                        Map.entry("prompt.agentDebugEvidence.virtualMatrix.hotChunkIndex", 4),
                        Map.entry("prompt.agentDebugEvidence.virtualMatrix.hotChunkRiskScore", 0.22d)),
                null,
                null);

        assertEquals("300", signal.labels().get("debugAiMatrixCount"));
        assertEquals("30", signal.labels().get("debugAiMatrixChunkCount"));
        assertEquals("0.312", signal.labels().get("debugAiMatrixScore"));
        assertEquals("observe", signal.labels().get("debugAiMatrixDecision"));
        assertEquals("4", signal.labels().get("debugAiMatrixHotChunkIndex"));
        assertEquals("0.22", signal.labels().get("debugAiMatrixHotChunkRiskScore"));
    }

    @Test
    void debugFxSurfacesLocalLlmUpstreamFailureBreadcrumbs() {
        ChatStreamEvent.DebugFxSignal signal = ChatStreamSignalBuilder.buildDebugFxSignal(
                Map.of(
                        "llm.localSmoke.operatorAction.failureClass", "model_blank",
                        "llm.localSmoke.operatorAction.upstreamStatus", 500,
                        "llm.localSmoke.operatorAction.upstreamFailureClass", "ollama_upstream_5xx",
                        "llm.localSmoke.operatorAction.upstreamNextAction", "inspect_ollama_runtime_capacity"),
                null,
                null);

        assertEquals("model_blank", signal.labels().get("localLlmFailureClass"));
        assertEquals("500", signal.labels().get("localLlmUpstreamStatus"));
        assertEquals("ollama_upstream_5xx", signal.labels().get("localLlmUpstreamFailureClass"));
        assertEquals("inspect_ollama_runtime_capacity", signal.labels().get("localLlmUpstreamNextAction"));
    }

    @Test
    void debugFxSurfacesAgentVisibleExternalAndDbContextBreadcrumbsWithoutRawPayloads() throws Exception {
        Map<String, Object> meta = Map.ofEntries(
                Map.entry("chat.harmony.postprocess.applied", true),
                Map.entry("chat.harmony.postprocess.agentVisible", true),
                Map.entry("chat.harmony.postprocess.decision", "smooth_chat"),
                Map.entry("prompt.agentDebugEvidence.external.browser.status", "OK"),
                Map.entry("prompt.agentDebugEvidence.external.browser.evidenceNeeded", "none"),
                Map.entry("prompt.agentDebugEvidence.external.browser.nextAction", "continue_browser_ui_smoke"),
                Map.entry("prompt.agentDebugEvidence.external.computerUse.status", "OK"),
                Map.entry("prompt.agentDebugEvidence.external.computerUse.evidenceNeeded", "none"),
                Map.entry("prompt.agentDebugEvidence.external.computerUse.nextAction", "continue_gui_count_only_smoke"),
                Map.entry("prompt.agentDebugEvidence.external.supabase.status", "WARN"),
                Map.entry("prompt.agentDebugEvidence.external.supabase.evidenceNeeded", "project_ref_missing"),
                Map.entry("prompt.agentDebugEvidence.external.supabase.nextAction",
                        "provide_supabase_project_ref_and_authenticated_readonly_mcp_or_cli_for_schema_advisor_snapshot"),
                Map.entry("prompt.agentDebugEvidence.agentDbContext.status", "DISABLED"),
                Map.entry("prompt.agentDebugEvidence.agentDbContext.reason", "agent_db_context_disabled"),
                Map.entry("prompt.agentDebugEvidence.agentDbContext.nextAction",
                        "enable_agent_db_context_for_full_pipeline_health"),
                Map.entry("prompt.agentDebugEvidence.external.browser.rawSecret",
                        "Authorization=private-token should not surface"),
                Map.entry("prompt.agentDebugEvidence.agentDbContext.rawSecret",
                        "ownerToken=private-token should not surface"));

        ChatStreamEvent.DebugFxSignal signal = ChatStreamSignalBuilder.buildDebugFxSignal(
                meta,
                null,
                ChatStreamSignalBuilder.buildPipelineSnapshot(meta, "ALL_ROUNDER", null, null));

        assertEquals("OK", signal.labels().get("browserStatus"));
        assertEquals("none", signal.labels().get("browserEvidenceNeeded"));
        assertEquals("continue_browser_ui_smoke", signal.labels().get("browserNextAction"));
        assertEquals("OK", signal.labels().get("computerUseStatus"));
        assertEquals("none", signal.labels().get("computerUseEvidenceNeeded"));
        assertEquals("continue_gui_count_only_smoke", signal.labels().get("computerUseNextAction"));
        assertEquals("WARN", signal.labels().get("supabaseStatus"));
        assertEquals("project_ref_missing", signal.labels().get("supabaseEvidenceNeeded"));
        assertEquals("provide_supabase_project_ref_and_authenticated_readonly_mcp_or_cli_for_schema_advisor_snapshot",
                signal.labels().get("supabaseNextAction"));
        assertEquals("DISABLED", signal.labels().get("agentDbContextStatus"));
        assertEquals("agent_db_context_disabled", signal.labels().get("agentDbContextReason"));
        assertEquals("enable_agent_db_context_for_full_pipeline_health",
                signal.labels().get("agentDbContextNextAction"));

        ChatStreamEvent event = ChatStreamEvent.debugFx(signal);
        String json = new ObjectMapper().writeValueAsString(ServerSentEvent.<ChatStreamEvent>builder(event)
                .event(event.type())
                .build()
                .data());
        assertFalse(signal.toString().contains("private-token"), signal.toString());
        assertFalse(signal.toString().contains("Authorization"), signal.toString());
        assertFalse(signal.toString().contains("ownerToken"), signal.toString());
        assertFalse(json.contains("private-token"), json);
        assertFalse(json.contains("Authorization"), json);
        assertFalse(json.contains("ownerToken"), json);
        assertFalse(json.contains("rawSecret"), json);
    }

    @Test
    void debugFxFallsBackToRawSupabaseEvidenceLaneWhenPromptMirrorIsMissing() {
        ChatStreamEvent.DebugFxSignal signal = ChatStreamSignalBuilder.buildDebugFxSignal(
                Map.of(
                        "chat.harmony.postprocess.applied", true,
                        "chat.harmony.postprocess.agentVisible", true,
                        "supabase.evidenceNeeded", "external_evidence_lane",
                        "supabase.readOnly", true,
                        "supabase.serviceRole", "Authorization=private-token should not surface"),
                null,
                null);

        assertEquals("WARN", signal.labels().get("supabaseStatus"));
        assertEquals("external_evidence_lane", signal.labels().get("supabaseEvidenceNeeded"));
        assertEquals("collect_supabase_readonly_evidence", signal.labels().get("supabaseNextAction"));
        assertFalse(signal.toString().contains("private-token"), signal.toString());
        assertFalse(signal.toString().contains("Authorization"), signal.toString());
    }

    @Test
    void debugFxKeepsFallbackLabelsWhenFinalAgentVisibleEvidenceMirrorIsPartial() {
        ChatStreamEvent.DebugFxSignal signal = ChatStreamSignalBuilder.buildDebugFxSignal(
                Map.of(
                        "prompt.agentDebugEvidence.external.browser.status", "OK",
                        "prompt.agentDebugEvidence.external.computerUse.status", "OK",
                        "chat.harmony.postprocess.applied", true,
                        "chat.harmony.postprocess.agentVisible", true,
                        "chat.harmony.postprocess.decision", "fallback_evidence"),
                null,
                null);

        assertEquals("WARN", signal.labels().get("supabaseStatus"));
        assertEquals("supabase_project_scope_or_auth_unverified", signal.labels().get("supabaseEvidenceNeeded"));
        assertEquals("authenticate_supabase_mcp_or_cli", signal.labels().get("supabaseNextAction"));
        assertEquals("DISABLED", signal.labels().get("agentDbContextStatus"));
        assertEquals("agent_db_context_disabled", signal.labels().get("agentDbContextReason"));
        assertEquals("300", signal.labels().get("debugAiMatrixCount"));
        assertEquals("30", signal.labels().get("debugAiMatrixChunkCount"));
        assertEquals("unavailable", signal.labels().get("debugAiMatrixDecision"));
    }

    @Test
    void debugFxSignalKeepsExtendedAgentVisibleLabelBudget() {
        LinkedHashMap<String, String> labels = new LinkedHashMap<>();
        for (int i = 0; i < 20; i++) {
            labels.put("existingLabel" + i, "v" + i);
        }
        labels.put("supabaseStatus", "WARN");
        labels.put("agentDbContextStatus", "DISABLED");
        labels.put("debugAiMatrixCount", "300");

        ChatStreamEvent.DebugFxSignal signal = new ChatStreamEvent.DebugFxSignal(
                "pipeline",
                "ok",
                "diagnostics",
                "pipeline diagnostics updated",
                null,
                labels);

        assertEquals("WARN", signal.labels().get("supabaseStatus"));
        assertEquals("DISABLED", signal.labels().get("agentDbContextStatus"));
        assertEquals("300", signal.labels().get("debugAiMatrixCount"));
    }

    @Test
    void streamFinalDebugFxMirrorsAgentVisibleEvidenceIntoFinalMetaBeforeBuild() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/api/ChatApiController.java"));
        int extraMeta = source.indexOf("java.util.Map<String, Object> extraMeta = TraceStore.getAll();");
        int mirror = source.indexOf("mirrorAgentVisibleDebugEvidenceForStream(extraMeta,", extraMeta);
        int attach = source.indexOf("attachDebugAiMatrixTrace(extraMeta,", extraMeta);
        int harmony = source.indexOf("ChatHarmonyTracePostprocessor.enrich(extraMeta", extraMeta);
        int debugFx = source.indexOf("ChatStreamEvent finalDebugFxEvent =", extraMeta);
        int ensure = source.indexOf("ensureFinalAgentVisibleDebugFxFallbacks(finalDebugFxEvent, extraMeta)", debugFx);

        assertTrue(extraMeta >= 0, "stream final TraceStore snapshot must be present");
        assertTrue(mirror > extraMeta, "stream final meta must mirror Supabase and DB evidence after snapshot");
        assertTrue(attach > extraMeta, "stream final meta must receive virtual matrix compact snapshot after snapshot");
        assertTrue(attach < harmony, "matrix breadcrumbs must be copied before harmony postprocess enrichment");
        assertTrue(mirror < debugFx, "agent-visible evidence mirror must happen before final debug_fx build");
        assertTrue(ensure > debugFx, "final debug_fx event must keep fallback labels immediately before emit");
    }
}
