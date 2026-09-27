package com.example.lms.api;

import com.example.lms.dto.ChatStreamEvent;
import com.example.lms.dto.LearningContextMetadata;
import com.example.lms.infra.selection.ReplaySelectionEntropy;
import com.example.lms.infra.selection.SelectionEntropyProjection;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SelectionEntropyTraceSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ChatStreamSignalBuilderTest {

    @Test void gatewayDebugExposesBoundedHealthAndFallbackLabelsWithoutPrivatePayloads() throws Exception {
        var meta=new LinkedHashMap<String,Object>();
        meta.put("llm.localEndpoint.state","OPEN");meta.put("llm.localEndpoint.reason","gpu_device_lost");
        meta.put("llm.localEndpoint.gpuState","unknown");meta.put("llm.localEndpoint.modelState","unloaded");
        meta.put("llm.localEndpoint.latencyMs",12L);meta.put("llm.gateway.fallback.selectedRoute","cloud-b");
        meta.put("llm.gateway.fallback.count",2);meta.put("llm.gateway.fallback.remainingMs",850L);
        var debug=ChatStreamSignalBuilder.buildDebugFxSignal(meta,null,null);
        assertNotNull(debug);String json=new ObjectMapper().writeValueAsString(debug);
        assertTrue(json.contains("gatewayLocalState"));assertTrue(json.contains("cloud-b"));assertTrue(json.contains("gatewayFallbackCount"));
        var blocks=ChatStreamSignalBuilder.buildTransformerBlocks(meta,null,null,null,null,debug);
        assertTrue(blocks.stream().anyMatch(block->block.id().equals("gateway")&&block.status().equals("warn")));
        meta.put("llm.gateway.fallback.succeeded",true);
        assertTrue(ChatStreamSignalBuilder.buildTransformerBlocks(meta,null,null,null,null,debug).stream()
                .anyMatch(block->block.id().equals("gateway")&&block.status().equals("done")));
        meta.put("llm.localEndpoint.reason","Bearer private-secret PRIVATE_PROMPT");
        meta.put("llm.gateway.fallback.selectedRoute","https://private.invalid/?api_key=PRIVATE_KEY");
        meta.put("llm.gateway.fallback.count","PRIVATE_PROMPT");
        json=new ObjectMapper().writeValueAsString(ChatStreamSignalBuilder.buildDebugFxSignal(meta,null,null));
        assertFalse(json.contains("PRIVATE_PROMPT"));assertFalse(json.contains("PRIVATE_KEY"));assertFalse(json.contains("private-secret"));
        json=new ObjectMapper().writeValueAsString(ChatStreamSignalBuilder.buildTransformerBlocks(meta,null,null,null,null,debug));
        assertFalse(json.contains("PRIVATE_PROMPT"));assertFalse(json.contains("PRIVATE_KEY"));assertFalse(json.contains("private-secret"));
    }

    @Test void gatewayDebugCountsPreselectionBypassSeparatelyFromHttpAttempts() throws Exception {
        var meta = new LinkedHashMap<String,Object>();
        meta.put("llm.localEndpoint.state", "open");
        meta.put("llm.gateway.preselectionFallbackCount", 1);
        meta.put("llm.gateway.fallback.count", 1);
        meta.put("llm.gateway.attemptCount", 2);
        var debug = new ObjectMapper().valueToTree(ChatStreamSignalBuilder.buildDebugFxSignal(meta,null,null));
        assertEquals("2", debug.path("labels").path("gatewayFallbackCount").asText());
        assertEquals("2", debug.path("labels").path("gatewayAttemptCount").asText());
    }

    private static String fakeSupabaseSecret() {
        return "sb_" + "secret_" + "A".repeat(24);
    }

    @Test
    void selectionEntropySignalUsesOnlyValidatedProjectionFields() throws Exception {
        SelectionEntropyProjection projection = replayProjection();
        TraceStore.clear();
        try {
            SelectionEntropyTraceSupport.write(projection);
            ChatStreamEvent.SelectionEntropySignal signal =
                    ChatStreamSignalBuilder.buildSelectionEntropySignal(TraceStore.getAll());

            assertNotNull(signal);
            assertEquals(projection.schema(), signal.schema());
            assertEquals(projection.mode(), signal.mode());
            assertEquals(projection.seedFingerprint(), signal.replayReference());
            assertEquals(projection.decisionDigest(), signal.decisionDigest());
            assertEquals(projection.drawCount(), signal.drawCount());
            assertEquals(projection.completionOrderDeterministic(),
                    signal.completionOrderDeterministic());

            ChatStreamEvent event = ChatStreamEvent.selectionEntropy(signal);
            String json = new ObjectMapper().writeValueAsString(event);
            assertEquals("selection_entropy", event.type());
            assertEquals(signal, event.selectionEntropySignal());
            assertTrue(json.contains("\"replayReference\":\"012345abcdef\""), json);
            assertFalse(json.contains("seedFingerprint"), json);
            assertFalse(json.contains("X-AWX-Selection-Seed"), json);
            assertFalse(json.contains("raw-replay-seed"), json);
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void selectionEntropySignalRejectsUnknownPrefixAndLegacyConstructorStaysCompatible() {
        LinkedHashMap<String, Object> invalid = new LinkedHashMap<>();
        invalid.put(SelectionEntropyTraceSupport.SCHEMA, "awx.selection-entropy.v1");
        invalid.put("selectionEntropy.rawSeed", "raw-replay-seed");

        assertNull(ChatStreamSignalBuilder.buildSelectionEntropySignal(invalid));

        ChatStreamEvent legacy = new ChatStreamEvent(
                "status",
                "ok",
                null,
                null,
                null,
                null,
                null,
                null,
                LearningContextMetadata.empty(),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                List.of());
        assertNull(legacy.selectionEntropySignal());
    }

    @Test
    void traceSignalHashesIdentifiersAndKeepsStageCounts() {
        ChatStreamEvent.TraceSignal signal = ChatStreamSignalBuilder.buildTraceSignal(
                Map.of(
                        "orch.events.v1", List.of("a", "b"),
                        "rag.eval.stageCounts", Map.of("web", "3"),
                        "failureClass", "timeout",
                        "reasonCode", "rate-limit"),
                "trace-raw",
                "request-raw",
                "session-raw");

        assertEquals(2, signal.eventCount());
        assertEquals(3, signal.stageCounts().get("web"));
        assertEquals("timeout", signal.failureClass());
        assertEquals("rate-limit", signal.reasonCode());
        assertNotEquals("trace-raw", signal.traceIdHash());
        assertNotEquals("request-raw", signal.requestIdHash());
        assertNotEquals("session-raw", signal.sessionIdHash());
    }

    @Test
    void pipelineSnapshotDerivesFinalContextCountAndRedactsDisabledReason() {
        ChatStreamEvent.PipelineSnapshot snapshot = ChatStreamSignalBuilder.buildPipelineSnapshot(
                Map.of(
                        "webCount", 2,
                        "vector.count", "4",
                        "disabledReason", "Authorization=secret-token",
                        "rag.route", "hybrid"),
                "rag",
                null,
                null);

        assertNotNull(snapshot);
        assertEquals(2, snapshot.webCount());
        assertEquals(4, snapshot.vectorCount());
        assertEquals(6, snapshot.finalContextCount());
        assertEquals("hybrid", snapshot.route());
        assertFalse(snapshot.disabledReason().contains("secret-token"));
    }

    @Test
    void traceSignalInfersCancellationFromProviderTaxonomyWhenFailureClassMissing() {
        ChatStreamEvent.TraceSignal signal = ChatStreamSignalBuilder.buildTraceSignal(
                Map.of(
                        "web.brave.cancelled", true,
                        "web.brave.exceptionType", "cancelled"),
                "trace-raw",
                "request-raw",
                "session-raw");

        assertEquals("cancelled", signal.failureClass());
    }

    @Test
    void pipelineSnapshotInfersCancellationFromProviderTaxonomyWhenFailureClassMissing() {
        ChatStreamEvent.PipelineSnapshot snapshot = ChatStreamSignalBuilder.buildPipelineSnapshot(
                Map.of(
                        "web.serpapi.cancelled", true,
                        "web.serpapi.exceptionType", "cancelled",
                        "webCount", 1),
                "rag",
                null,
                null);

        assertNotNull(snapshot);
        assertEquals("cancelled", snapshot.failureClass());
    }

    @Test
    void pipelineSnapshotUsesTavilyCanonicalDisabledReason() {
        ChatStreamEvent.PipelineSnapshot snapshot = ChatStreamSignalBuilder.buildPipelineSnapshot(
                Map.of(
                        "web.tavily.disabledReasonCanonical", "missing_tavily_api_key",
                        "webCount", 1),
                "rag",
                null,
                null);

        assertNotNull(snapshot);
        assertEquals("missing_tavily_api_key", snapshot.disabledReason());
    }

    private static SelectionEntropyProjection replayProjection() {
        return new SelectionEntropyProjection(
                "awx.selection-entropy.v1",
                "replay",
                ReplaySelectionEntropy.ALGORITHM_VERSION,
                true,
                "matched",
                "012345abcdef",
                "a".repeat(64),
                4,
                3,
                1,
                0,
                1,
                1,
                1,
                false,
                "");
    }

    @Test
    void pipelineSnapshotExposesRequestBudgetSkipAsAReasonNotModelFailure() {
        Map<String, Object> meta = Map.of(
                "llm.final.skipped", "request_budget_exhausted",
                "web.tavily.disabledReasonCanonical", "missing_tavily_api_key");
        ChatStreamEvent.PipelineSnapshot snapshot = ChatStreamSignalBuilder.buildPipelineSnapshot(
                meta,
                "FALLBACK_EVIDENCE",
                null,
                null);

        assertNotNull(snapshot);
        assertEquals("request_budget_exhausted", snapshot.disabledReason());
        assertNull(snapshot.failureClass());
        var modelBlock = ChatStreamSignalBuilder.buildTransformerBlocks(
                        meta,
                        ChatStreamEvent.StatusSignal.of("stream", "complete", "stream complete", 0L, 1L, false),
                        snapshot,
                        null,
                        null,
                        null).stream()
                .filter(block -> "model".equals(block.id()))
                .findFirst()
                .orElseThrow();
        assertEquals("skipped", modelBlock.status());
        assertEquals("request_budget_exhausted", modelBlock.reason());
    }

    @Test
    void transformerBlocksExposeOrderedRedactedRuntimeStatus() {
        ChatStreamEvent.TraceSignal traceSignal = ChatStreamSignalBuilder.buildTraceSignal(
                Map.of(
                        "rag.eval.stageCounts", Map.of("web", 2, "vector", 1),
                        "reasonCode", "ownerToken=private-token"),
                "trace-raw",
                "request-raw",
                "session-raw");
        ChatStreamEvent.PipelineSnapshot snapshot = ChatStreamSignalBuilder.buildPipelineSnapshot(
                Map.of(
                        "plan.id", "safe.v1",
                        "rag.route", "hybrid",
                        "webCount", 2,
                        "vectorCount", 1,
                        "disabledReason", "api_key=secret-value"),
                "rag",
                8L,
                traceSignal);

        var blocks = ChatStreamSignalBuilder.buildTransformerBlocks(
                Map.of("llm.model", "qwen3:8b"),
                ChatStreamEvent.StatusSignal.of("stream", "complete", "stream complete", 10L, 44L, false),
                snapshot,
                traceSignal,
                null,
                null);

        assertEquals(6, blocks.size());
        assertEquals("intake", blocks.get(0).id());
        assertEquals("done", blocks.get(0).status());
        assertEquals("route", blocks.get(1).id());
        assertEquals("safe.v1", blocks.get(1).reason());
        assertEquals("retrieve", blocks.get(2).id());
        assertEquals("done", blocks.get(2).status());
        assertEquals("recover", blocks.get(5).id());
        assertEquals("warn", blocks.get(5).status());
        assertFalse(blocks.toString().contains("private-token"), blocks.toString());
        assertFalse(blocks.toString().contains("secret-value"), blocks.toString());
    }

    @Test
    void completedStreamWithoutObservedModelAttemptDoesNotReportModelDone() {
        var blocks = ChatStreamSignalBuilder.buildTransformerBlocks(
                Map.of("llm.model", "qwen3:8b", "disabledReason", "ownerToken=private-token"),
                ChatStreamEvent.StatusSignal.of("stream", "complete", "stream complete", 0L, 128L, false),
                ChatStreamSignalBuilder.buildPipelineSnapshot(
                        Map.of("disabledReason", "api_key=secret-value"),
                        "chat",
                        17L,
                        null),
                null,
                null,
                null);

        assertEquals("done", blocks.get(0).status());
        assertEquals("route", blocks.get(1).id());
        assertEquals("skipped", blocks.get(1).status());
        assertEquals("skipped", blocks.get(2).status());
        assertEquals("skipped", blocks.get(3).status());
        assertEquals("warn", blocks.get(4).status());
        assertEquals("model_attempt_not_observed", blocks.get(4).reason());
        assertEquals("warn", blocks.get(5).status());
        assertFalse(blocks.toString().contains("private-token"), blocks.toString());
        assertFalse(blocks.toString().contains("secret-value"), blocks.toString());
    }

    @Test
    void finalizingStreamWithoutObservedModelAttemptDoesNotRemainRunning() {
        var model = modelBlock(Map.of("llm.model", "qwen3:8b"), "finalizing");

        assertEquals("warn", model.status());
        assertEquals("model_attempt_not_observed", model.reason());
    }

    @Test
    void finalizingPipelineClosesIntakeAndSkipsUnobservedOptionalStages() {
        var blocks = ChatStreamSignalBuilder.buildTransformerBlocks(
                Map.of("llm.model", "qwen3:8b"),
                ChatStreamEvent.StatusSignal.of("stream", "finalizing", "answer ready", 0L, 10L, false),
                ChatStreamSignalBuilder.buildPipelineSnapshot(Map.of(), "chat", 17L, null),
                null, null, null);

        assertEquals("done", blocks.get(0).status());
        for (String id : List.of("route", "retrieve", "compose")) {
            assertEquals("skipped", blocks.stream().filter(block -> id.equals(block.id()))
                    .findFirst().orElseThrow().status(), id);
        }
        assertEquals("warn", blocks.get(4).status());
        assertEquals("model_attempt_not_observed", blocks.get(4).reason());
    }

    @Test
    void runningPipelineKeepsUnobservedOptionalStagesQueued() {
        var blocks = ChatStreamSignalBuilder.buildTransformerBlocks(
                Map.of(),
                ChatStreamEvent.StatusSignal.of("stream", "running", "working", 0L, 10L, false),
                null, null, null, null);

        assertEquals("running", blocks.get(0).status());
        for (String id : List.of("route", "retrieve", "compose")) {
            assertEquals("queued", blocks.stream().filter(block -> id.equals(block.id()))
                    .findFirst().orElseThrow().status(), id);
        }
    }

    @Test
    void finalizingRichPipelineClosesIntakeWithoutInventingRetrievalOrModelProof() {
        var blocks = ChatStreamSignalBuilder.buildTransformerBlocks(
                Map.of("overdrive.stagesApplied", 1),
                ChatStreamEvent.StatusSignal.of("stream", "finalizing", "answer ready", 0L, 10L, false),
                null, null, null, null);

        assertTrue(blocks.stream().anyMatch(block -> "anchor".equals(block.id())));
        assertEquals("done", blocks.get(0).status());
        for (String id : List.of("plan", "retrieve", "compose")) {
            assertEquals("skipped", blocks.stream().filter(block -> id.equals(block.id()))
                    .findFirst().orElseThrow().status(), id);
        }
        var model = blocks.stream().filter(block -> "model".equals(block.id())).findFirst().orElseThrow();
        assertEquals("warn", model.status());
        assertEquals("model_attempt_not_observed", model.reason());
    }

    @Test
    void observedSuccessfulModelAttemptReportsDoneWithoutInventingWireProof() {
        var model = modelBlock(Map.of(
                "llm.requestAttempt.summary.v1", List.of(attemptRow(
                        "primary", "success", "none", "success",
                        true, false, true, true))), "complete");

        assertEquals("done", model.status());
        assertEquals("model_attempt_succeeded", model.reason());
    }

    @Test
    void observedAttemptWithoutDeliveryDoesNotReportDone() {
        var model = modelBlock(Map.of(
                "llm.requestAttempt.summary.v1", List.of(attemptRow(
                        "primary", "success", "none", "success",
                        true, true, false, true))), "complete");

        assertEquals("warn", model.status());
        assertEquals("model_delivery_not_observed", model.reason());
    }

    @Test
    void explicitModelFailureIsNotPromotedByStreamCompletion() {
        var model = modelBlock(Map.of(
                "llm.requestAttempt.summary.v1", List.of(attemptRow(
                        "primary", "failed", "provider_error", "error",
                        true, true, false, true))), "complete");

        assertEquals("warn", model.status());
        assertEquals("model_attempt_failed", model.reason());
    }

    @Test
    void cancelledModelAttemptIsNotPromotedByStreamCompletion() {
        var model = modelBlock(Map.of(
                "llm.requestAttempt.summary.v1", List.of(attemptRow(
                        "primary", "cancelled", "cancelled_neutral", "cancelled",
                        true, true, false, true))), "complete");

        assertEquals("warn", model.status());
        assertEquals("model_attempt_cancelled", model.reason());
    }

    @Test
    void laterSameLaneCancellationSupersedesEarlierSuccess() {
        Map<String, Object> laterCancellation = new java.util.LinkedHashMap<>(attemptRow(
                "primary", "cancelled", "cancelled_neutral", "cancelled",
                true, true, false, true));
        laterCancellation.put("sequence", 2);
        var model = modelBlock(Map.of(
                "llm.requestAttempt.summary.v1", List.of(
                        attemptRow("primary", "success", "none", "success",
                                true, false, true, true),
                        laterCancellation)), "complete");

        assertEquals("warn", model.status());
        assertEquals("model_attempt_cancelled", model.reason());
    }

    @Test
    void cancelledStreamDoesNotKeepSuccessfulModelReason() {
        var model = modelBlock(Map.of(
                "llm.requestAttempt.summary.v1", List.of(attemptRow(
                        "primary", "success", "none", "success",
                        true, false, true, true))), "cancelled");

        assertEquals("warn", model.status());
        assertEquals("model_stream_cancelled", model.reason());
    }

    @Test
    void fallbackOnlySuccessRetainsUnobservedPrimaryState() {
        var model = modelBlock(Map.of(
                "llm.requestAttempt.summary.v1", List.of(attemptRow(
                        "fallback", "success", "none", "success",
                        true, false, true, true))), "complete");

        assertEquals("done", model.status());
        assertEquals("fallback_done_primary_not_observed", model.reason());
    }

    @Test
    void primaryFailureAndFallbackSuccessRemainDistinct() {
        var model = modelBlock(Map.of(
                "llm.requestAttempt.summary.v1", List.of(
                        attemptRow("primary", "failed", "provider_error", "error",
                                true, true, false, true),
                        attemptRow("fallback", "success", "none", "success",
                                true, false, true, true))), "complete");

        assertEquals("done", model.status());
        assertEquals("primary_failed_fallback_done", model.reason());
    }

    @Test
    void observedFallbackRecoveryOutranksGenericLlmBoundaryFailure() {
        Map<String, Object> meta = Map.of(
                "llm.requestAttempt.summary.v1", List.of(
                        attemptRow("primary", "failed", "vram_oom", "error",
                                true, false, false, true),
                        attemptRow("fallback", "success", "none", "success",
                                true, false, true, true)),
                "mla.breadcrumb.step.llm", Map.of(
                        "stage", "llm",
                        "failureClass", "catch",
                        "reasonCode", "notfound"));

        var blocks = ChatStreamSignalBuilder.buildTransformerBlocks(
                meta,
                ChatStreamEvent.StatusSignal.of("stream", "complete", "stream terminal", 0L, 10L, false),
                null,
                null,
                null,
                null);
        var model = blocks.stream().filter(block -> "model".equals(block.id())).findFirst().orElseThrow();
        var recover = blocks.stream().filter(block -> "recover".equals(block.id())).findFirst().orElseThrow();

        assertEquals("done", model.status());
        assertEquals("primary_failed_fallback_done", model.reason());
        assertEquals("done", recover.status());
        assertEquals("primary_failed_fallback_done", recover.reason());
    }

    @Test
    void untrustedSyntheticAttemptCannotBecomeObservedWireSuccess() {
        Map<String, Object> synthetic = new java.util.LinkedHashMap<>(attemptRow(
                "primary", "success", "none", "success",
                true, true, true, false));
        synthetic.put("trusted", false);

        var model = modelBlock(Map.of("llm.requestAttempt.summary.v1", List.of(synthetic)), "complete");

        assertEquals("warn", model.status());
        assertEquals("model_attempt_not_observed", model.reason());
    }

    @Test
    void requestAttemptProjectionKeepsOnlyBoundedObservedFields() throws Exception {
        Map<String, Object> rawLedgerRow = Map.ofEntries(
                Map.entry("timelineId", "timeline-private"),
                Map.entry("requestHash", "request-private"),
                Map.entry("sessionHash", "session-private"),
                Map.entry("promptHash", "prompt-private"),
                Map.entry("responseHash", "response-private"),
                Map.entry("endpointLabel", "private.provider.example"),
                Map.entry("rawPrompt", "Authorization=private-token"),
                Map.entry("sequence", 2),
                Map.entry("role", "primary"),
                Map.entry("outcome", "success"),
                Map.entry("failureClass", "none"),
                Map.entry("terminalClass", "success"),
                Map.entry("elapsedMs", 17L),
                Map.entry("modelAdapterAttemptObserved", true),
                Map.entry("clientHttpExchangeObserved", false),
                Map.entry("clientHttpResponseObserved", false),
                Map.entry("providerAttemptObserved", false),
                Map.entry("wireAttemptObserved", false),
                Map.entry("responseObserved", true));

        List<Map<String, Object>> summary =
                ChatStreamSignalBuilder.summarizeRequestAttempts(List.of(rawLedgerRow));

        assertEquals(1, summary.size());
        assertEquals(Set.of(
                        "sequence", "lane", "outcome", "failureClass", "terminal",
                        "modelAdapterAttemptObserved", "clientHttpExchangeObserved",
                        "clientHttpResponseObserved", "providerAttemptObserved",
                        "attemptObserved", "wireAttemptObserved", "deliveryObserved",
                        "trusted", "observedAtElapsedMs"),
                summary.get(0).keySet());
        assertEquals(Boolean.TRUE, summary.get(0).get("attemptObserved"));
        assertEquals(Boolean.FALSE, summary.get(0).get("wireAttemptObserved"));
        assertEquals(Boolean.TRUE, summary.get(0).get("deliveryObserved"));
        String json = new ObjectMapper().writeValueAsString(summary);
        assertFalse(json.contains("private"), json);
        assertFalse(json.contains("Authorization"), json);
        assertFalse(json.contains("timelineId"), json);
        assertFalse(json.contains("requestHash"), json);
        assertFalse(json.contains("promptHash"), json);
        assertFalse(json.contains("responseHash"), json);
        assertFalse(json.contains("endpointLabel"), json);
        assertFalse(json.contains("rawPrompt"), json);
        assertTrue(ChatStreamSignalBuilder.summarizeRequestAttempts(List.of(Map.of(
                "role", "primary",
                "outcome", "success",
                "failureClass", "none",
                "terminalClass", "success"))).isEmpty(),
                "incomplete rows must not manufacture trusted false/zero observations");
    }

    private static ChatStreamEvent.TransformerBlockSignal modelBlock(
            Map<String, Object> meta,
            String streamCode) {
        return ChatStreamSignalBuilder.buildTransformerBlocks(
                        meta,
                        ChatStreamEvent.StatusSignal.of("stream", streamCode, "stream terminal", 0L, 10L, false),
                        null,
                        null,
                        null,
                        null).stream()
                .filter(block -> "model".equals(block.id()))
                .findFirst()
                .orElseThrow();
    }

    private static Map<String, Object> attemptRow(
            String role,
            String outcome,
            String failureClass,
            String terminalClass,
            boolean attemptObserved,
            boolean wireAttemptObserved,
            boolean deliveryObserved,
            boolean trusted) {
        return Map.ofEntries(
                Map.entry("sequence", 1),
                Map.entry("lane", role),
                Map.entry("outcome", outcome),
                Map.entry("failureClass", failureClass),
                Map.entry("terminal", terminalClass),
                Map.entry("attemptObserved", attemptObserved),
                Map.entry("wireAttemptObserved", wireAttemptObserved),
                Map.entry("deliveryObserved", deliveryObserved),
                Map.entry("trusted", trusted),
                Map.entry("observedAtElapsedMs", 9L));
    }

    @Test
    void transformerBlocksExposeCoreDebugLanesFromTraceMetadata() {
        ChatStreamEvent.TraceSignal traceSignal = ChatStreamSignalBuilder.buildTraceSignal(
                Map.of(
                        "rag.eval.stageCounts", Map.of("web", 2, "dpp", 1),
                        "reasonCode", "Authorization=private-token"),
                "trace-raw",
                "request-raw",
                "session-raw");
        ChatStreamEvent.PipelineSnapshot snapshot = ChatStreamSignalBuilder.buildPipelineSnapshot(
                Map.of(
                        "plan.id", "plan-dsl.safe",
                        "rag.route", "hybrid",
                        "webCount", 2,
                        "vectorCount", 1,
                        "finalContextCount", 3),
                "rag",
                9L,
                traceSignal);

        var blocks = ChatStreamSignalBuilder.buildTransformerBlocks(
                Map.ofEntries(
                        Map.entry("llm.model", "gemma4:26b"),
                        Map.entry("overdrive.stagesApplied", 1),
                        Map.entry("overdrive.finalCandidateCount", 5),
                        Map.entry("rag.anchor.reason", "anchor_seed"),
                        Map.entry("dpp.rerank.outputCount", 4),
                        Map.entry("hypernova.dppApplied", true),
                        Map.entry("cfvm.failureRecorder", "recorded"),
                        Map.entry("cfvm.boltzmannTemp", 0.7d),
                        Map.entry("supabase.evidenceNeeded", "project_ref_missing"),
                        Map.entry("supabase.service_role", fakeSupabaseSecret())),
                ChatStreamEvent.StatusSignal.of("stream", "complete", "stream complete", 0L, 144L, false),
                snapshot,
                traceSignal,
                null,
                null);

        assertEquals(List.of("intake", "plan", "anchor", "retrieve", "rerank", "compose", "model", "cfvm", "recover", "supabase"),
                blocks.stream().map(ChatStreamEvent.TransformerBlockSignal::id).toList());
        assertEquals("done", blocks.get(1).status());
        assertEquals("plan-dsl.safe", blocks.get(1).reason());
        assertEquals("done", blocks.get(2).status());
        assertEquals("anchor_seed", blocks.get(2).reason());
        assertEquals("done", blocks.get(4).status());
        assertEquals("selected:4", blocks.get(4).reason());
        assertEquals("warn", blocks.get(7).status());
        assertEquals("recorded", blocks.get(7).reason());
        assertEquals("warn", blocks.get(9).status());
        assertEquals("project_ref_missing", blocks.get(9).reason());
        assertFalse(blocks.toString().contains("private-token"), blocks.toString());
        assertFalse(blocks.toString().contains(fakeSupabaseSecret()), blocks.toString());
    }

    @Test
    void transformerBlocksExposeQueryRewriteSuperTokensWhenPresent() {
        var blocks = ChatStreamSignalBuilder.buildTransformerBlocks(
                Map.ofEntries(
                        Map.entry("queryTransformer.subQueries.superTokens.enabled", true),
                        Map.entry("queryTransformer.subQueries.superTokens.branchCount", 3),
                        Map.entry("queryTransformer.subQueries.superTokens.tokenCount", 3),
                        Map.entry("queryTransformer.subQueries.superTokens.subModelCount", 3),
                        Map.entry("queryTransformer.subQueries.superTokens.subModelAssignmentCount", 3),
                        Map.entry("queryTransformer.subQueries.superTokens.branchTitleCount", 3),
                        Map.entry("queryTransformer.subQueries.superTokens.branchTitleHashCount", 2),
                        Map.entry("queryTransformer.subQueries.superTokens.axisCount", 3),
                        Map.entry("queryTransformer.subQueries.superTokens.axes",
                                List.of("ownerToken=private-axis")),
                        Map.entry("queryTransformer.subQueries.superTokens.branchTitleHashes",
                                List.of("aaaaaaaaaaaa", "bbbbbbbbbbbb", "ownerToken=private-title-hash")),
                        Map.entry("queryTransformer.subQueries.refined.paddedCount", 2),
                        Map.entry("web.query.rewrite.verificationLaneCount", 2),
                        Map.entry("web.query.rewrite.explorationLaneCount", 3),
                        Map.entry("web.query.rewrite.validationTemperature", 0.15d),
                        Map.entry("web.query.rewrite.explorationTemperature", 0.7d),
                        Map.entry("web.query.rewrite.temperatureProfile", "balanced"),
                        Map.entry("queryTransformer.subQueries.superTokens.titlePresent", true),
                        Map.entry("queryTransformer.subQueries.superTokens.titleHash12", "abc123safehash"),
                        Map.entry("queryTransformer.subQueries.superTokens.titleLength", 42),
                        Map.entry("queryTransformer.subQueries.superTokens.rawTitle", "ownerToken=private-title")),
                ChatStreamEvent.StatusSignal.of("stream", "complete", "stream complete", 0L, 144L, false),
                ChatStreamSignalBuilder.buildPipelineSnapshot(
                        Map.of("answer.mode", "rag"),
                        "rag",
                        9L,
                        null),
                null,
                null,
                null);

        assertEquals(List.of("intake", "plan", "rewrite", "anchor", "retrieve", "rerank", "compose", "model", "cfvm", "recover", "supabase"),
                blocks.stream().map(ChatStreamEvent.TransformerBlockSignal::id).toList());
        ChatStreamEvent.TransformerBlockSignal rewrite = blocks.get(2);
        assertEquals("rewrite", rewrite.id());
        assertEquals("done", rewrite.status());
        assertEquals("models:3_supers:3_branches:3_axes:3_lanes:2x3_temp:0.15x0.7_profile:balanced",
                rewrite.reason());
        assertFalse(blocks.toString().contains("private-title"), blocks.toString());
        assertFalse(blocks.toString().contains("ownerToken"), blocks.toString());
    }

    @Test
    void transformerBlocksUseRequestedQueryRewriteProfileBeforeProviderTrace() {
        var blocks = ChatStreamSignalBuilder.buildTransformerBlocks(
                Map.ofEntries(
                        Map.entry("queryTransformer.subQueries.superTokens.enabled", true),
                        Map.entry("queryTransformer.subQueries.superTokens.branchCount", 3),
                        Map.entry("queryTransformer.subQueries.superTokens.tokenCount", 3),
                        Map.entry("queryTransformer.subQueries.superTokens.subModelCount", 3),
                        Map.entry("queryTransformer.subQueries.superTokens.axisCount", 3),
                        Map.entry("web.query.rewrite.requestedVerificationLaneCount", 1),
                        Map.entry("web.query.rewrite.requestedExplorationLaneCount", 2),
                        Map.entry("web.query.rewrite.requestedValidationTemperature", 0.15d),
                        Map.entry("web.query.rewrite.requestedExplorationTemperature", 0.55d),
                        Map.entry("web.query.rewrite.requestedTemperatureProfile", "balanced")),
                ChatStreamEvent.StatusSignal.of("stream", "running", "stream running", 0L, 12L, false),
                ChatStreamSignalBuilder.buildPipelineSnapshot(
                        Map.of("answer.mode", "rag"),
                        "rag",
                        9L,
                        null),
                null,
                null,
                null);

        ChatStreamEvent.TransformerBlockSignal rewrite = blocks.get(2);
        assertEquals("models:3_supers:3_branches:3_axes:3_lanes:1x2_temp:0.15x0.55_profile:balanced",
                rewrite.reason());
    }

    @Test
    void transformerBlocksExposeModelTimeoutFastBailAsUiDebugState() {
        var blocks = ChatStreamSignalBuilder.buildTransformerBlocks(
                Map.ofEntries(
                        Map.entry("llm.fastBailTimeout", true),
                        Map.entry("llm.fastBailTimeout.timeoutHits", 2),
                        Map.entry("llm.error.code", "timeout"),
                        Map.entry("llm.error.message", "Authorization=private-token")),
                ChatStreamEvent.StatusSignal.of("stream", "complete", "stream complete", 0L, 75_000L, false),
                ChatStreamSignalBuilder.buildPipelineSnapshot(
                        Map.of("answer.mode", "FALLBACK_EVIDENCE"),
                        "FALLBACK_EVIDENCE",
                        null,
                        null),
                null,
                null,
                null);

        assertEquals(List.of("intake", "plan", "anchor", "retrieve", "rerank", "compose", "model", "cfvm", "recover", "supabase"),
                blocks.stream().map(ChatStreamEvent.TransformerBlockSignal::id).toList());
        ChatStreamEvent.TransformerBlockSignal model = blocks.get(6);
        assertEquals("model", model.id());
        assertEquals("warn", model.status());
        assertEquals("timeout-fast-bail:2", model.reason());
        assertFalse(blocks.toString().contains("private-token"), blocks.toString());
    }

    @Test
    void transformerBlocksExposeDefaultModelWaitAsUiDebugState() {
        var blocks = ChatStreamSignalBuilder.buildTransformerBlocks(
                Map.ofEntries(
                        Map.entry("llm.defaultModel.waitStatus", true),
                        Map.entry("llm.defaultModel.waitStatus.code", "waiting_for_default_model")),
                ChatStreamEvent.StatusSignal.of("llm", "waiting_for_default_model",
                        "waiting for default model response", 15_000L, 250L, false),
                null,
                null,
                null,
                null);

        assertEquals(List.of("intake", "plan", "anchor", "retrieve", "rerank", "compose", "model", "cfvm", "recover", "supabase"),
                blocks.stream().map(ChatStreamEvent.TransformerBlockSignal::id).toList());
        ChatStreamEvent.TransformerBlockSignal model = blocks.get(6);
        assertEquals("model", model.id());
        assertEquals("running", model.status());
        assertEquals("waiting_for_default_model", model.reason());
    }

    @Test
    void transformerBlocksFlagSlowDefaultModelCompletionAsWarn() {
        var blocks = ChatStreamSignalBuilder.buildTransformerBlocks(
                Map.ofEntries(
                        Map.entry("llm.defaultModel.route", "local"),
                        Map.entry("llm.model", "gemma4:26b"),
                        Map.entry("llm.requestAttempt.summary.v1", List.of(attemptRow(
                                "primary", "success", "none", "success",
                                true, false, true, true)))),
                ChatStreamEvent.StatusSignal.of("stream", "complete", "stream complete", 0L, 75_000L, false),
                ChatStreamSignalBuilder.buildPipelineSnapshot(
                        Map.of("answer.mode", "FALLBACK_EVIDENCE"),
                        "FALLBACK_EVIDENCE",
                        null,
                        null),
                null,
                null,
                null);

        ChatStreamEvent.TransformerBlockSignal model = blocks.get(6);
        assertEquals("model", model.id());
        assertEquals("warn", model.status());
        assertEquals("slow-model-ms:75000", model.reason());
    }

    @Test
    void failureTagsSurfaceFallbackEvidenceInResilienceBlock() {
        var blocks = ChatStreamSignalBuilder.buildTransformerBlocks(
                Map.ofEntries(
                        Map.entry("failureTags", List.of(
                                "ANSWER_MODE:FALLBACK_EVIDENCE",
                                fakeSupabaseSecret())),
                        Map.entry("llm.defaultModel.route", "local"),
                        Map.entry("llm.model", "gemma4:26b")),
                ChatStreamEvent.StatusSignal.of("stream", "complete", "stream complete", 0L, 200L, false),
                ChatStreamSignalBuilder.buildPipelineSnapshot(
                        Map.of("answer.mode", "FALLBACK_EVIDENCE"),
                        "FALLBACK_EVIDENCE",
                        null,
                        null),
                null,
                null,
                null);

        ChatStreamEvent.TransformerBlockSignal recover = blocks.get(8);
        assertEquals("recover", recover.id());
        assertEquals("warn", recover.status());
        assertEquals("ANSWER_MODE:FALLBACK_EVIDENCE", recover.reason());
        assertFalse(blocks.toString().contains(fakeSupabaseSecret()), blocks.toString());
    }

    @Test
    void copilotLlmHealthPressureSurfacesInModelAndResilienceBlocks() {
        var blocks = ChatStreamSignalBuilder.buildTransformerBlocks(
                Map.ofEntries(
                        Map.entry("dbg.copilot.causes", List.of(Map.of(
                                "id", "llm_health_pressure",
                                "score", 0.82d,
                                "title", "LLM runtime health pressure exceeded threshold"))),
                        Map.entry("llm.gateway.route.healthFailurePressure", 0.82d),
                        Map.entry("llm.gateway.route.healthRoutingHint", "llm_route_degrade"),
                        Map.entry("llm.gateway.route.healthFailureCount", 4),
                        Map.entry("llm.client.blank", true),
                        Map.entry("llm.client.promptHash", "hash:prompt-safe"),
                        Map.entry("llm.client.rawPrompt", "Authorization=private-token should not surface")),
                ChatStreamEvent.StatusSignal.of("stream", "complete", "stream complete", 0L, 240L, false),
                ChatStreamSignalBuilder.buildPipelineSnapshot(
                        Map.of("answer.mode", "FALLBACK_EVIDENCE"),
                        "FALLBACK_EVIDENCE",
                        null,
                        null),
                null,
                null,
                null);

        assertEquals(List.of("intake", "plan", "anchor", "retrieve", "rerank", "compose", "model", "cfvm", "recover", "supabase"),
                blocks.stream().map(ChatStreamEvent.TransformerBlockSignal::id).toList());
        ChatStreamEvent.TransformerBlockSignal model = blocks.get(6);
        assertEquals("model", model.id());
        assertEquals("warn", model.status());
        assertEquals("llm_route_degrade", model.reason());
        ChatStreamEvent.TransformerBlockSignal recover = blocks.get(8);
        assertEquals("recover", recover.id());
        assertEquals("warn", recover.status());
        assertEquals("llm_health_pressure", recover.reason());
        assertFalse(blocks.toString().contains("private-token"), blocks.toString());
        assertFalse(blocks.toString().contains("Authorization"), blocks.toString());
    }

    @Test
    void copilotCauseSurfacesInDebugFxWithoutRawPromptOrToken() {
        ChatStreamEvent.DebugFxSignal signal = ChatStreamSignalBuilder.buildDebugFxSignal(
                Map.ofEntries(
                        Map.entry("dbg.copilot.causes", List.of(Map.of(
                                "id", "llm_health_pressure",
                                "title", "private prompt ownerToken=raw should not surface"))),
                        Map.entry("llm.client.rawPrompt", "Authorization=private-token should not surface")),
                null,
                ChatStreamSignalBuilder.buildPipelineSnapshot(
                        Map.of("answer.mode", "FALLBACK_EVIDENCE"),
                        "FALLBACK_EVIDENCE",
                        null,
                        null));

        assertEquals("llm_health_pressure", signal.code());
        assertEquals("resilience", signal.effect());
        assertEquals("llm_health_pressure", signal.labels().get("debugCause"));
        assertFalse(signal.toString().contains("private-token"), signal.toString());
        assertFalse(signal.toString().contains("ownerToken=raw"), signal.toString());
    }

    @Test
    void localLlmOperatorActionSurfacesInDebugFxLabelsWithoutRawPayloads() throws Exception {
        ChatStreamEvent.DebugFxSignal signal = ChatStreamSignalBuilder.buildDebugFxSignal(
                Map.ofEntries(
                        Map.entry("dbg.copilot.causes", List.of(Map.of(
                                "id", "llm_health_pressure",
                                "score", 1.0d,
                                "title", "LLM runtime health pressure"))),
                        Map.entry("llm.localSmoke.operatorAction.triggerReason", "threshold_exceeded"),
                        Map.entry("llm.localSmoke.operatorAction.failureClass", "model_blank"),
                        Map.entry("llm.localSmoke.operatorAction.nextAction", "prefer_native_ollama_route"),
                        Map.entry("llm.localSmoke.operatorAction.actionScore", 100),
                        Map.entry("llm.localSmoke.operatorAction.scoreDelta", 85),
                        Map.entry("llm.ollamaNative.route", true),
                        Map.entry("llm.ollamaNative.promptLength", 21632),
                        Map.entry("llm.ollamaNative.maxTokens", 160),
                        Map.entry("llm.call.timeout.workerTerminationEvidence", "not_observed"),
                        Map.entry("llm.client.rawPrompt", "Authorization=private-token should not surface"),
                        Map.entry("llm.client.rawModel", "qwen3:8b-private-owner-token")),
                null,
                ChatStreamSignalBuilder.buildPipelineSnapshot(
                        Map.of("answer.mode", "FALLBACK_EVIDENCE"),
                        "FALLBACK_EVIDENCE",
                        null,
                        null));

        assertEquals("llm_health_pressure", signal.code());
        assertEquals("threshold_exceeded", signal.labels().get("localLlmTriggerReason"));
        assertEquals("model_blank", signal.labels().get("localLlmFailureClass"));
        assertEquals("prefer_native_ollama_route", signal.labels().get("localLlmNextAction"));
        assertEquals("100", signal.labels().get("localLlmActionScore"));
        assertEquals("85", signal.labels().get("localLlmScoreDelta"));
        assertEquals("true", signal.labels().get("ollamaNativeRoute"));
        assertEquals("21632", signal.labels().get("ollamaNativePromptLength"));
        assertEquals("160", signal.labels().get("ollamaNativeMaxTokens"));
        assertEquals("not_observed", signal.labels().get("llmTimeoutWorkerTermination"));

        ChatStreamEvent event = ChatStreamEvent.debugFx(signal);
        ServerSentEvent<ChatStreamEvent> sse = ServerSentEvent.<ChatStreamEvent>builder(event)
                .event(event.type())
                .build();
        String json = new ObjectMapper().writeValueAsString(sse.data());
        JsonNode root = new ObjectMapper().readTree(json);
        JsonNode labels = root.path("debugFxSignal").path("labels");
        assertEquals("debug_fx", sse.event());
        assertEquals("debug_fx", root.path("type").asText());
        assertEquals("threshold_exceeded", labels.path("localLlmTriggerReason").asText());
        assertEquals("model_blank", labels.path("localLlmFailureClass").asText());
        assertEquals("prefer_native_ollama_route", labels.path("localLlmNextAction").asText());
        assertEquals("true", labels.path("ollamaNativeRoute").asText());
        assertEquals("21632", labels.path("ollamaNativePromptLength").asText());
        assertEquals("160", labels.path("ollamaNativeMaxTokens").asText());
        assertEquals("not_observed", labels.path("llmTimeoutWorkerTermination").asText());
        assertFalse(signal.toString().contains("private-token"), signal.toString());
        assertFalse(signal.toString().contains("Authorization"), signal.toString());
        assertFalse(signal.toString().contains("qwen3:8b"), signal.toString());
        assertFalse(json.contains("private-token"), json);
        assertFalse(json.contains("Authorization"), json);
        assertFalse(json.contains("qwen3:8b"), json);
        assertFalse(json.contains("rawPrompt"), json);
        assertFalse(json.contains("rawModel"), json);
    }

    @Test
    void debugFxExternalProofActionsUseExistingRefreshScriptLabels() throws Exception {
        ChatStreamEvent.DebugFxSignal signal = ChatStreamSignalBuilder.buildDebugFxSignal(
                Map.ofEntries(
                        Map.entry("prompt.agentDebugEvidence.external.browser.nextAction",
                                "run_browser_local_ui_smoke"),
                        Map.entry("prompt.agentDebugEvidence.external.computerUse.nextAction",
                                "run_computer_use_lightweight_smoke"),
                        Map.entry("prompt.agentDebugEvidence.external.supabase.nextAction",
                                "inspect_supabase_shadow_snapshot")),
                null,
                ChatStreamSignalBuilder.buildPipelineSnapshot(
                        Map.of("answer.mode", "FALLBACK_EVIDENCE"),
                        "FALLBACK_EVIDENCE",
                        null,
                        null));

        assertEquals("refresh_local_interaction_smokes.ps1:browser",
                signal.labels().get("browserNextAction"));
        assertEquals("refresh_local_interaction_smokes.ps1:computer",
                signal.labels().get("computerUseNextAction"));
        assertEquals("supabase_context_probe:evidence_needed",
                signal.labels().get("supabaseNextAction"));

        ChatStreamEvent event = ChatStreamEvent.debugFx(signal);
        String json = new ObjectMapper().writeValueAsString(event);
        assertFalse(json.contains("run_browser_local_ui_smoke"), json);
        assertFalse(json.contains("run_computer_use_lightweight_smoke"), json);
        assertFalse(json.contains("inspect_supabase_shadow_snapshot"), json);
    }

    @Test
    void denseDebugFxKeepsMlaStageBoundaryLabelsWithinBoundedSseMap() throws Exception {
        Map<String, Object> meta = Map.ofEntries(
                Map.entry("failureClass", "model_timeout"),
                Map.entry("disabledReason", "local_model_timeout"),
                Map.entry("dbg.copilot.causes", List.of(Map.of("id", "llm_health_pressure"))),
                Map.entry("llm.localSmoke.operatorAction.triggerReason", "cpu_fallback_retry"),
                Map.entry("llm.localSmoke.operatorAction.failureClass", "model_timeout"),
                Map.entry("llm.localSmoke.operatorAction.nextAction", "inspect_model_route"),
                Map.entry("llm.localSmoke.operatorAction.actionScore", 100),
                Map.entry("llm.localSmoke.operatorAction.scoreDelta", 85),
                Map.entry("llm.localSmoke.operatorAction.upstreamStatus", 500),
                Map.entry("llm.localSmoke.operatorAction.upstreamFailureClass", "runner_terminated_cpu_probe"),
                Map.entry("llm.localSmoke.operatorAction.upstreamNextAction", "inspect_ollama_runtime_capacity"),
                Map.entry("llm.ollamaNative.gpuMode", "cpu_fallback_retry"),
                Map.entry("llm.ollamaNative.numGpu", 0),
                Map.entry("llm.ollamaNative.promptLength", 21632),
                Map.entry("llm.ollamaNative.maxTokens", 160),
                Map.entry("chat.harmony.postprocess.applied", true),
                Map.entry("chat.harmony.postprocess.agentVisible", true),
                Map.entry("chat.harmony.postprocess.degraded", true),
                Map.entry("chat.harmony.postprocess.decision", "evidence_limited"),
                Map.entry("chat.harmony.postprocess.reason", "fallback_evidence"),
                Map.entry("chat.harmony.postprocess.weightedScore", 0.36d),
                Map.entry("chat.harmony.postprocess.evidenceCount", 0),
                Map.entry("prompt.agentDebugEvidence.traceMemory.routeDecision", "retry_failsoft_degrade"),
                Map.entry("prompt.agentDebugEvidence.traceMemory.virtualCheckpointLatestKey", "traceMemory.virtualCheckpoint.final"),
                Map.entry("prompt.agentDebugEvidence.traceMemory.virtualCheckpointLatestStage", "final"),
                Map.entry("prompt.agentDebugEvidence.traceMemory.virtualCheckpointLatestPhase", "post_load"),
                Map.entry("traceMemory.cfvm.offered", true),
                Map.entry("traceMemory.cfvm.patternId", 944214805),
                Map.entry("prompt.agentDebugEvidence.external.browser.status", "SUPPORTING_EVIDENCE_MISSING"),
                Map.entry("prompt.agentDebugEvidence.external.browser.evidenceNeeded", "browser_ui_smoke_missing"),
                Map.entry("prompt.agentDebugEvidence.external.browser.nextAction", "run_browser_local_ui_smoke"),
                Map.entry("prompt.agentDebugEvidence.external.computerUse.status", "SUPPORTING_EVIDENCE_MISSING"),
                Map.entry("prompt.agentDebugEvidence.external.computerUse.evidenceNeeded", "computer_use_smoke_missing"),
                Map.entry("prompt.agentDebugEvidence.external.computerUse.nextAction", "run_computer_use_lightweight_smoke"),
                Map.entry("prompt.agentDebugEvidence.external.supabase.status", "EVIDENCE_NEEDED"),
                Map.entry("prompt.agentDebugEvidence.external.supabase.evidenceNeeded", "project_ref_missing"),
                Map.entry("prompt.agentDebugEvidence.external.supabase.nextAction", "inspect_supabase_shadow_snapshot"),
                Map.entry("mla.breadcrumb.step.orchestration", Map.of(
                        "stage", "orchestration",
                        "failureClass", "fallback",
                        "reasonCode", "query_transformer_bypassed",
                        "redacted", true)));

        ChatStreamEvent.DebugFxSignal signal = ChatStreamSignalBuilder.buildDebugFxSignal(
                meta,
                null,
                ChatStreamSignalBuilder.buildPipelineSnapshot(
                        meta,
                        "FALLBACK_EVIDENCE",
                        null,
                        null));
        String json = new ObjectMapper().writeValueAsString(ChatStreamEvent.debugFx(signal));
        JsonNode labels = new ObjectMapper().readTree(json).path("debugFxSignal").path("labels");

        assertTrue(labels.size() <= 32, labels.toString());
        assertEquals("orchestration", labels.path("stageBoundaryStage").asText());
        assertEquals("fallback", labels.path("stageBoundaryFailureClass").asText());
        assertEquals("query_transformer_bypassed", labels.path("stageBoundaryReason").asText());
        assertEquals("21632", labels.path("ollamaNativePromptLength").asText());
        assertEquals("160", labels.path("ollamaNativeMaxTokens").asText());
        assertFalse(json.contains("rawPrompt"), json);
        assertFalse(json.contains("rawQuery"), json);
    }

    @Test
    void primaryBoundaryPrefersConcreteLlmTimeoutOverSupportingOrchestrationFallback() {
        Map<String, Object> meta = Map.of(
                "mla.breadcrumb.step.orchestration", Map.of(
                        "stage", "orchestration",
                        "failureClass", "fallback",
                        "reasonCode", "query_transformer_bypassed",
                        "redacted", true),
                "mla.breadcrumb.step.llm", Map.of(
                        "stage", "llm",
                        "failureClass", "timeout",
                        "reasonCode", "timeout",
                        "redacted", true));
        ChatStreamEvent.PipelineSnapshot pipeline = ChatStreamSignalBuilder.buildPipelineSnapshot(
                meta,
                "FALLBACK_EVIDENCE",
                null,
                null);

        ChatStreamEvent.DebugFxSignal debugFx = ChatStreamSignalBuilder.buildDebugFxSignal(
                meta,
                null,
                pipeline);
        var blocks = ChatStreamSignalBuilder.buildTransformerBlocks(
                meta,
                ChatStreamEvent.StatusSignal.of("stream", "complete", "stream complete", 0L, 144L, false),
                pipeline,
                null,
                null,
                debugFx);
        ChatStreamEvent.TransformerBlockSignal recover = blocks.stream()
                .filter(block -> "recover".equals(block.id()))
                .findFirst()
                .orElseThrow();

        assertEquals("llm", debugFx.labels().get("stageBoundaryStage"));
        assertEquals("timeout", debugFx.labels().get("stageBoundaryFailureClass"));
        assertEquals("timeout", debugFx.labels().get("stageBoundaryReason"));
        assertEquals("timeout", recover.reason());
    }

    @Test
    void debugFxPhaseUsesSelectedMlaBoundaryPhase() {
        for (String expectedPhase : List.of("pre_llm", "final")) {
            Map<String, Object> meta = Map.of(
                    "mla.breadcrumb.step.llm", Map.of(
                            "phase", expectedPhase,
                            "stage", "llm",
                            "failureClass", "timeout",
                            "reasonCode", "timeout",
                            "redacted", true));
            ChatStreamEvent.PipelineSnapshot pipeline = ChatStreamSignalBuilder.buildPipelineSnapshot(
                    meta,
                    "FALLBACK_EVIDENCE",
                    null,
                    null);

            ChatStreamEvent.DebugFxSignal signal = ChatStreamSignalBuilder.buildDebugFxSignal(
                    meta,
                    null,
                    pipeline);

            assertEquals(expectedPhase, signal.phase());
        }
    }

    @Test
    void stageBoundaryBreadcrumbsSurfaceInSseTransformerBlocksWithoutRawPayloads() throws Exception {
        Map<String, Object> meta = Map.ofEntries(
                Map.entry("mla.breadcrumb.step.search", Map.of(
                        "stage", "search",
                        "failureClass", "after-filter-starvation",
                        "reasonCode", "after_filter_starvation",
                        "returnedCount", 5,
                        "afterFilterCount", 0,
                        "queryHash12", "abcdef123456",
                        "rawQuery", "ownerToken=private-token")),
                Map.entry("finalContextCount", 0),
                Map.entry("rawPrompt", "Authorization=private-token"));

        var blocks = ChatStreamSignalBuilder.buildTransformerBlocks(
                meta,
                ChatStreamEvent.StatusSignal.of("stream", "complete", "stream complete", 0L, 144L, false),
                ChatStreamSignalBuilder.buildPipelineSnapshot(meta, "FALLBACK_EVIDENCE", null, null),
                null,
                null,
                null);

        ChatStreamEvent.TransformerBlockSignal retrieve = blocks.stream()
                .filter(block -> "retrieve".equals(block.id()))
                .findFirst()
                .orElseThrow();
        assertEquals("warn", retrieve.status());
        assertEquals("after-filter-starvation", retrieve.reason());

        ChatStreamEvent event = ChatStreamEvent.transformer(blocks);
        ServerSentEvent<ChatStreamEvent> sse = ServerSentEvent.<ChatStreamEvent>builder(event)
                .event(event.type())
                .build();
        String json = new ObjectMapper().writeValueAsString(sse.data());
        JsonNode root = new ObjectMapper().readTree(json);

        assertEquals("transformer", sse.event());
        assertEquals("transformer", root.path("type").asText());
        assertTrue(json.contains("after-filter-starvation"), json);
        assertFalse(blocks.toString().contains("private-token"), blocks.toString());
        assertFalse(blocks.toString().contains("ownerToken"), blocks.toString());
        assertFalse(json.contains("private-token"), json);
        assertFalse(json.contains("Authorization"), json);
        assertFalse(json.contains("rawPrompt"), json);
        assertFalse(json.contains("rawQuery"), json);
    }

    @Test
    void answerShapeRespectedDoesNotOverrideLowHarmonyScore() {
        Map<String, Object> meta = Map.ofEntries(
                Map.entry("chat.harmony.postprocess.applied", true),
                Map.entry("chat.harmony.postprocess.agentVisible", true),
                Map.entry("chat.harmony.postprocess.degraded", false),
                Map.entry("chat.harmony.postprocess.decision", "smooth_chat"),
                Map.entry("chat.harmony.postprocess.reason", "answer_shape_respected"),
                Map.entry("chat.harmony.postprocess.weightedScore", 0.2d),
                Map.entry("chat.harmony.postprocess.evidenceCount", 0),
                Map.entry("debug.ai.metrics.virtualMatrix.decision", "probe_required"));

        ChatStreamEvent.DebugFxSignal debugFx = ChatStreamSignalBuilder.buildDebugFxSignal(
                meta,
                null,
                ChatStreamSignalBuilder.buildPipelineSnapshot(meta, "ALL_ROUNDER", null, null));
        var blocks = ChatStreamSignalBuilder.buildTransformerBlocks(
                meta,
                ChatStreamEvent.StatusSignal.of("stream", "complete", "stream complete", 0L, 20L, false),
                ChatStreamSignalBuilder.buildPipelineSnapshot(meta, "ALL_ROUNDER", null, null),
                null,
                null,
                debugFx);

        ChatStreamEvent.TransformerBlockSignal harmony = blocks.stream()
                .filter(block -> "harmony".equals(block.id()))
                .findFirst()
                .orElseThrow();
        assertEquals("warn", harmony.status());
        assertEquals("answer_shape_respected", harmony.reason());
    }

    @Test
    void chatHarmonyPostprocessSurfacesInSseTransformerAndDebugFxWithoutRawPayloads() throws Exception {
        Map<String, Object> meta = Map.ofEntries(
                Map.entry("chat.harmony.postprocess.applied", true),
                Map.entry("chat.harmony.postprocess.agentVisible", true),
                Map.entry("chat.harmony.postprocess.degraded", false),
                Map.entry("chat.harmony.postprocess.decision", "smooth_chat"),
                Map.entry("chat.harmony.postprocess.reason", "answer_flow_balanced"),
                Map.entry("chat.harmony.postprocess.weightedScore", 0.84d),
                Map.entry("chat.harmony.postprocess.evidenceCount", 3),
                Map.entry("debug.ai.metrics.virtualMatrix.count", 300),
                Map.entry("debug.ai.metrics.virtualMatrix.chunkCount", 30),
                Map.entry("debug.ai.metrics.virtualMatrix.weightedScore", 0.476d),
                Map.entry("debug.ai.metrics.virtualMatrix.decision", "investigate_hot_chunk"),
                Map.entry("debug.ai.metrics.virtualMatrix.hotChunkIndex", 17),
                Map.entry("debug.ai.metrics.virtualMatrix.hotChunkRiskScore", 0.77d),
                Map.entry("prompt.agentDebugEvidence.traceMemory.routeDecision", "retry_failsoft_degrade_warn_live_failsoft"),
                Map.entry("prompt.agentDebugEvidence.traceMemory.virtualCheckpointLatestKey", "traceMemory.virtualCheckpoint.second_refinement"),
                Map.entry("prompt.agentDebugEvidence.traceMemory.virtualCheckpointLatestStage", "second_refinement"),
                Map.entry("prompt.agentDebugEvidence.traceMemory.virtualCheckpointLatestPhase", "post_load"),
                Map.entry("traceMemory.cfvm.offered", true),
                Map.entry("traceMemory.cfvm.patternId", 944214805),
                Map.entry("chat.harmony.rawAnswer", "Authorization=private-token should not surface"),
                Map.entry("chat.harmony.rawUserQuery", "ownerToken=private-token should not surface"));

        ChatStreamEvent.DebugFxSignal debugFx = ChatStreamSignalBuilder.buildDebugFxSignal(
                meta,
                null,
                ChatStreamSignalBuilder.buildPipelineSnapshot(Map.of("answer.mode", "ALL_ROUNDER"), "ALL_ROUNDER", null, null));
        var blocks = ChatStreamSignalBuilder.buildTransformerBlocks(
                meta,
                ChatStreamEvent.StatusSignal.of("stream", "complete", "stream complete", 0L, 144L, false),
                ChatStreamSignalBuilder.buildPipelineSnapshot(meta, "ALL_ROUNDER", null, null),
                null,
                null,
                debugFx);

        ChatStreamEvent.TransformerBlockSignal harmony = blocks.stream()
                .filter(block -> "harmony".equals(block.id()))
                .findFirst()
                .orElseThrow();
        assertEquals("warn", harmony.status());
        assertEquals("answer_flow_balanced", harmony.reason());
        assertEquals("smooth_chat", debugFx.labels().get("chatHarmonyDecision"));
        assertEquals("answer_flow_balanced", debugFx.labels().get("chatHarmonyReason"));
        assertEquals("0.84", debugFx.labels().get("chatHarmonyScore"));
        assertEquals("300", debugFx.labels().get("debugAiMatrixCount"));
        assertEquals("30", debugFx.labels().get("debugAiMatrixChunkCount"));
        assertEquals("0.476", debugFx.labels().get("debugAiMatrixScore"));
        assertEquals("investigate_hot_chunk", debugFx.labels().get("debugAiMatrixDecision"));
        assertEquals("17", debugFx.labels().get("debugAiMatrixHotChunkIndex"));
        assertEquals("0.77", debugFx.labels().get("debugAiMatrixHotChunkRiskScore"));
        assertEquals("true", debugFx.labels().get("chatHarmonyInputPresent"));
        assertEquals("false", debugFx.labels().get("chatHarmonyDegraded"));
        assertEquals("retry_failsoft_degrade_warn_live_failsoft", debugFx.labels().get("traceMemoryRouteDecision"));
        assertEquals("traceMemory.virtualCheckpoint.second_refinement",
                debugFx.labels().get("traceMemoryVirtualCheckpointKey"));
        assertEquals("second_refinement", debugFx.labels().get("traceMemoryVirtualCheckpointStage"));
        assertEquals("post_load", debugFx.labels().get("traceMemoryVirtualCheckpointPhase"));
        assertEquals("true", debugFx.labels().get("traceMemoryCfvmOffered"));
        assertEquals("944214805", debugFx.labels().get("traceMemoryCfvmPatternId"));
        assertEquals("investigate_debug_ai_hot_chunk", debugFx.labels().get("debugAiNextAction"));
        assertEquals("investigate_hot_chunk", debugFx.labels().get("debugAiNextReason"));

        ChatStreamEvent event = ChatStreamEvent.transformer(blocks);
        ServerSentEvent<ChatStreamEvent> sse = ServerSentEvent.<ChatStreamEvent>builder(event)
                .event(event.type())
                .build();
        String json = new ObjectMapper().writeValueAsString(sse.data());

        assertTrue(json.contains("\"id\":\"harmony\""), json);
        assertTrue(json.contains("answer_flow_balanced"), json);
        assertFalse(blocks.toString().contains("private-token"), blocks.toString());
        assertFalse(blocks.toString().contains("ownerToken"), blocks.toString());
        assertFalse(json.contains("private-token"), json);
        assertFalse(json.contains("Authorization"), json);
        assertFalse(json.contains("rawAnswer"), json);
        assertFalse(json.contains("rawUserQuery"), json);
    }

    @Test
    void chatHarmonyInspectionOverridesMatrixObserveActionInDebugFxWithoutRawPayloads() {
        Map<String, Object> meta = Map.ofEntries(
                Map.entry("chat.harmony.postprocess.applied", true),
                Map.entry("chat.harmony.postprocess.agentVisible", true),
                Map.entry("chat.harmony.postprocess.degraded", true),
                Map.entry("chat.harmony.postprocess.decision", "evidence_limited"),
                Map.entry("chat.harmony.postprocess.reason", "fallback_evidence"),
                Map.entry("chat.harmony.postprocess.weightedScore", 0.36d),
                Map.entry("debug.ai.metrics.virtualMatrix.count", 300),
                Map.entry("debug.ai.metrics.virtualMatrix.decision", "observe"),
                Map.entry("chat.harmony.rawAnswer", "Authorization=private-token should not surface"),
                Map.entry("chat.harmony.rawUserQuery", "ownerToken=private-token should not surface"));

        ChatStreamEvent.DebugFxSignal debugFx = ChatStreamSignalBuilder.buildDebugFxSignal(
                meta,
                null,
                ChatStreamSignalBuilder.buildPipelineSnapshot(Map.of("answer.mode", "FALLBACK_EVIDENCE"),
                        "FALLBACK_EVIDENCE",
                        null,
                        null));

        assertEquals("true", debugFx.labels().get("chatHarmonyInputPresent"));
        assertEquals("true", debugFx.labels().get("chatHarmonyDegraded"));
        assertEquals("evidence_limited", debugFx.labels().get("chatHarmonyDecision"));
        assertEquals("inspect_chat_harmony_trace", debugFx.labels().get("debugAiNextAction"));
        assertEquals("chat_harmony.fallback_evidence", debugFx.labels().get("debugAiNextReason"));
        assertFalse(debugFx.toString().contains("private-token"), debugFx.toString());
        assertFalse(debugFx.toString().contains("Authorization"), debugFx.toString());
        assertFalse(debugFx.toString().contains("rawAnswer"), debugFx.toString());
        assertFalse(debugFx.toString().contains("rawUserQuery"), debugFx.toString());
    }

    @Test
    void numericSignalParsersOnlyCatchNumberFormatException() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/api/ChatStreamSignalBuilder.java"));

        assertParserCatchNarrowed(source, "private static Double asDouble(Object value, Double fallback)");
        assertParserCatchNarrowed(source, "private static Long asLong(Object value)");
        assertTrue(source.contains("traceSuppressed(\"signal.asDouble\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"signal.asLong\", ignore);"));
        assertTrue(source.contains("private static String errorType(RuntimeException failure)"));
        assertTrue(source.contains("failure instanceof NumberFormatException"));
        assertTrue(source.contains("return \"invalid_number\";"));
        assertTrue(source.contains("String safeErrorType = errorType(failure);"));
        assertTrue(source.contains("TraceStore.put(\"chat.stream.signal.suppressed.stage\", safeStage);"));
        assertTrue(source.contains("TraceStore.put(\"chat.stream.signal.suppressed.errorType\", safeErrorType);"));
        assertTrue(source.contains("TraceStore.put(\"chat.stream.signal.suppressed.\" + safeStage, true);"));
        assertTrue(source.contains("TraceStore.put(\"chat.stream.signal.suppressed.\" + safeStage + \".errorType\", safeErrorType);"));
        assertFalse(source.contains("failure == null ? \"unknown\" : failure.getClass().getSimpleName()"));
    }

    @Test
    void numericSignalFallbacksLeaveTraceBreadcrumbsWithoutRawValues() throws Exception {
        String raw = "Authorization=secret-not-a-number";
        Method asDouble = ChatStreamSignalBuilder.class.getDeclaredMethod("asDouble", Object.class, Double.class);
        Method asLong = ChatStreamSignalBuilder.class.getDeclaredMethod("asLong", Object.class);
        asDouble.setAccessible(true);
        asLong.setAccessible(true);

        TraceStore.clear();
        assertEquals(0.25d, asDouble.invoke(null, raw, 0.25d));
        assertEquals("signal.asDouble", TraceStore.get("chat.stream.signal.suppressed.stage"));
        assertEquals("invalid_number", TraceStore.get("chat.stream.signal.suppressed.errorType"));
        assertEquals(Boolean.TRUE, TraceStore.get("chat.stream.signal.suppressed.signal.asDouble"));
        assertEquals("invalid_number", TraceStore.get("chat.stream.signal.suppressed.signal.asDouble.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(raw));

        TraceStore.clear();
        assertNull(asLong.invoke(null, raw));
        assertEquals(Boolean.TRUE, TraceStore.get("chat.stream.signal.suppressed.signal.asLong"));
        assertEquals("invalid_number", TraceStore.get("chat.stream.signal.suppressed.signal.asLong.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(raw));

        TraceStore.clear();
    }

    private static void assertParserCatchNarrowed(String source, String signature) {
        int start = source.indexOf(signature);
        assertNotEquals(-1, start, "missing parser signature: " + signature);
        int parse = source.indexOf("parse", start);
        assertNotEquals(-1, parse, "parser must call a numeric parse method: " + signature);
        int end = source.indexOf("\n    }", parse);
        assertNotEquals(-1, end, "parser method end should be found: " + signature);
        String method = source.substring(start, end);
        assertNotEquals(-1, method.indexOf("catch (NumberFormatException"),
                "numeric fallback parser should only catch NumberFormatException: " + signature);
        assertEquals(-1, method.indexOf("catch (Exception"),
                "numeric fallback parser must not swallow all Exception: " + signature);
        assertEquals(-1, method.indexOf("catch (Throwable"),
                "numeric fallback parser must not swallow Throwable: " + signature);
    }
}
