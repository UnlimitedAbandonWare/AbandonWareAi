package com.example.lms.api;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ChatHarmonyTracePostprocessorFocusedTest {

    @Test
    void enrichRestoresExternalAgentEvidenceFromTraceStoreForSnapshotMeta() {
        TraceStore.clear();
        try {
            TraceStore.put("prompt.agentDebugEvidence.external.supabase.status", "WARN");
            TraceStore.put("prompt.agentDebugEvidence.external.supabase.evidenceNeeded",
                    "supabase_project_scope_or_auth_unverified");
            TraceStore.put("prompt.agentDebugEvidence.external.supabase.nextAction",
                    "authenticate_supabase_mcp_or_cli");

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("prompt.agentDebugEvidence.external.supabase.status",
                    "{present=true, len=4, hash12=8f6bca}");
            meta.put("prompt.agentDebugEvidence.external.supabase.evidenceNeeded",
                    "{present=true, len=41, hash12=aff5dc733072}");
            meta.put("prompt.agentDebugEvidence.external.supabase.nextAction",
                    "{present=true, len=32, hash12=25a11656a49f}");

            ChatHarmonyTracePostprocessor.enrich(meta, "A compact answer for trace visibility.", "ALL_ROUNDER");

            assertEquals("WARN", meta.get("prompt.agentDebugEvidence.external.supabase.status"));
            assertEquals("supabase_project_scope_or_auth_unverified",
                    meta.get("prompt.agentDebugEvidence.external.supabase.evidenceNeeded"));
            assertEquals("authenticate_supabase_mcp_or_cli",
                    meta.get("prompt.agentDebugEvidence.external.supabase.nextAction"));
            assertFalse(String.valueOf(meta).contains("ownerToken"));
            assertFalse(String.valueOf(meta).contains("Authorization"));
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void enrichRestoresLocalLlmOperatorActionFromTraceStoreForSnapshotMeta() {
        TraceStore.clear();
        try {
            TraceStore.put("prompt.agentDebugEvidence.localLlm.operatorAction.triggerReason",
                    "threshold_exceeded");
            TraceStore.put("prompt.agentDebugEvidence.localLlm.operatorAction.failureClass",
                    "model_blank");
            TraceStore.put("prompt.agentDebugEvidence.localLlm.operatorAction.nextAction",
                    "prefer_native_ollama_route");
            TraceStore.put("prompt.agentDebugEvidence.localLlm.operatorAction.actionScore", 100);
            TraceStore.put("prompt.agentDebugEvidence.localLlm.operatorAction.scoreDelta", 85);

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("prompt.agentDebugEvidence.localLlm.operatorAction.failureClass",
                    "{present=true, len=11, hash12=placeholder}");
            meta.put("prompt.agentDebugEvidence.localLlm.operatorAction.nextAction",
                    "{present=true, len=27, hash12=placeholder}");

            ChatHarmonyTracePostprocessor.enrich(meta, "A compact answer for trace visibility.", "ALL_ROUNDER");

            assertEquals("threshold_exceeded",
                    meta.get("prompt.agentDebugEvidence.localLlm.operatorAction.triggerReason"));
            assertEquals("model_blank",
                    meta.get("prompt.agentDebugEvidence.localLlm.operatorAction.failureClass"));
            assertEquals("prefer_native_ollama_route",
                    meta.get("prompt.agentDebugEvidence.localLlm.operatorAction.nextAction"));
            assertEquals(100, meta.get("prompt.agentDebugEvidence.localLlm.operatorAction.actionScore"));
            assertEquals(85, meta.get("prompt.agentDebugEvidence.localLlm.operatorAction.scoreDelta"));
            assertFalse(String.valueOf(meta).contains("ownerToken"));
            assertFalse(String.valueOf(meta).contains("Authorization"));
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void enrichPromotesRawLocalLlmOperatorActionIntoAgentVisibleSnapshotMeta() {
        TraceStore.clear();
        try {
            TraceStore.put("llm.localSmoke.operatorAction.triggered", true);
            TraceStore.put("llm.localSmoke.operatorAction.triggerReason", "threshold_exceeded");
            TraceStore.put("llm.localSmoke.operatorAction.failureClass", "model_blank");
            TraceStore.put("llm.localSmoke.operatorAction.nextAction", "prefer_native_ollama_route");
            TraceStore.put("llm.localSmoke.operatorAction.actionScore", 100);
            TraceStore.put("llm.localSmoke.operatorAction.scoreDelta", 85);

            Map<String, Object> meta = new LinkedHashMap<>();

            ChatHarmonyTracePostprocessor.enrich(meta, "A compact answer for trace visibility.", "ALL_ROUNDER");

            assertEquals(true, meta.get("prompt.agentDebugEvidence.localLlm.operatorAction.triggered"));
            assertEquals("threshold_exceeded",
                    meta.get("prompt.agentDebugEvidence.localLlm.operatorAction.triggerReason"));
            assertEquals("model_blank",
                    meta.get("prompt.agentDebugEvidence.localLlm.operatorAction.failureClass"));
            assertEquals("prefer_native_ollama_route",
                    meta.get("prompt.agentDebugEvidence.localLlm.operatorAction.nextAction"));
            assertEquals(100, meta.get("prompt.agentDebugEvidence.localLlm.operatorAction.actionScore"));
            assertEquals(85, meta.get("prompt.agentDebugEvidence.localLlm.operatorAction.scoreDelta"));
            assertEquals("model_blank",
                    TraceStore.get("prompt.agentDebugEvidence.localLlm.operatorAction.failureClass"));
            assertFalse(String.valueOf(meta).contains("ownerToken"));
            assertFalse(String.valueOf(meta).contains("Authorization"));
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void enrichPromotesLlmStageBoundaryInternalServerErrorToUpstreamOperatorAction() {
        TraceStore.clear();
        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("mla.breadcrumb.step.llm", Map.of(
                    "stage", "llm",
                    "failureClass", "catch",
                    "reasonCode", "internalservererror"));

            ChatHarmonyTracePostprocessor.enrich(meta, "A compact answer for trace visibility.", "FALLBACK_EVIDENCE");

            assertEquals(500, meta.get("llm.localSmoke.operatorAction.upstreamStatus"));
            assertEquals("ollama_upstream_5xx",
                    meta.get("llm.localSmoke.operatorAction.upstreamFailureClass"));
            assertEquals("inspect_ollama_runtime_capacity",
                    meta.get("llm.localSmoke.operatorAction.upstreamNextAction"));
            assertEquals("inspect_ollama_runtime_capacity",
                    meta.get("llm.localSmoke.operatorAction.nextAction"));
            assertEquals("ollama_upstream_5xx",
                    meta.get("prompt.agentDebugEvidence.localLlm.operatorAction.upstreamFailureClass"));
            assertEquals(500,
                    TraceStore.get("llm.localSmoke.operatorAction.upstreamStatus"));
            assertFalse(String.valueOf(meta).contains("ownerToken"));
            assertFalse(String.valueOf(meta).contains("Authorization"));
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void enrichCopiesVirtualMatrixHotChunkBreadcrumbsForAgentVisibleChatTrace() {
        TraceStore.clear();
        try {
            TraceStore.put("debug.ai.metrics.virtualMatrix.count", 300);
            TraceStore.put("debug.ai.metrics.virtualMatrix.chunkSize", 10);
            TraceStore.put("debug.ai.metrics.virtualMatrix.chunkCount", 30);
            TraceStore.put("debug.ai.metrics.virtualMatrix.weightedScore", 0.476d);
            TraceStore.put("debug.ai.metrics.virtualMatrix.decision", "investigate_hot_chunk");
            TraceStore.put("debug.ai.metrics.virtualMatrix.hotChunkIndex", 17);
            TraceStore.put("debug.ai.metrics.virtualMatrix.hotChunkRiskScore", 0.77d);

            Map<String, Object> meta = new LinkedHashMap<>();

            ChatHarmonyTracePostprocessor.enrich(meta, "A compact answer for trace visibility.", "ALL_ROUNDER");

            assertEquals(Boolean.TRUE, meta.get("debug.ai.metrics.virtualMatrix.agentVisible"));
            assertEquals(300, meta.get("debug.ai.metrics.virtualMatrix.count"));
            assertEquals(30, meta.get("debug.ai.metrics.virtualMatrix.chunkCount"));
            assertEquals(17, meta.get("debug.ai.metrics.virtualMatrix.hotChunkIndex"));
            assertEquals(0.77d, meta.get("debug.ai.metrics.virtualMatrix.hotChunkRiskScore"));
            assertFalse(String.valueOf(meta).contains("ownerToken"));
            assertFalse(String.valueOf(meta).contains("Authorization"));
        } finally {
            TraceStore.clear();
        }
    }
}
