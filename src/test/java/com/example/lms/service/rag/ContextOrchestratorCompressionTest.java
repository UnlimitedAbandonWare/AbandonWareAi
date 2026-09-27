package com.example.lms.service.rag;

import ai.abandonware.nova.config.NovaOrchestrationProperties;
import ai.abandonware.nova.orch.anchor.AnchorNarrower;
import ai.abandonware.nova.orch.anchor.AnchorNarrowingResult;
import ai.abandonware.nova.orch.compress.DynamicContextCompressor;
import com.example.lms.prompt.PromptBuilder;
import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.overdrive.OverdriveGuard;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContextOrchestratorCompressionTest {

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void alreadyCompressedPromptDocsSkipFinalCompositionPass() {
        RecordingPromptCompressor compressor = new RecordingPromptCompressor();
        PromptBuilder builder = (contexts, question) -> {
            assertEquals(1, contexts.size());
            assertEquals(1, contexts.get(0).web().size());
            return contexts.get(0).web().get(0).textSegment().text();
        };
        ContextOrchestrator orchestrator = new ContextOrchestrator(builder);
        ReflectionTestUtils.setField(orchestrator, "promptContextCompressor", compressor);

        String prompt = orchestrator.orchestrate(
                "keepanchor",
                List.of(),
                List.of(compressed("keepanchor already compressed evidence")),
                Map.of());

        assertEquals("keepanchor already compressed evidence", prompt);
        assertEquals(0, compressor.composeCalls);
        assertEquals("already_compressed", TraceStore.get("prompt.context.composer.skippedReason"));
        assertEquals(1, TraceStore.get("context.candidates.raw"));
        assertEquals(1, TraceStore.get("context.candidates.afterFilter"));
        assertEquals(Boolean.FALSE, TraceStore.get("context.starvation"));
        assertEquals("", TraceStore.get("context.starvation.reason"));
        assertEquals(Boolean.FALSE, TraceStore.get("context.overdrive.activated"));
        assertEquals(Boolean.FALSE, TraceStore.get("context.extremeZ.activated"));
    }

    @Test
    void compressorFailSoftCatchEmitsTraceAndRedactedDebugLog() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/ContextOrchestrator.java"));

        assertTrue(source.contains("TraceStore.put(\"orchestrator.compress.skipReason\", \"exception_fail_soft\")"));
        assertTrue(source.contains("log.debug(\"[ContextOrchestrator] composeForPrompt fail-soft. errorHash={} errorLength={}\""));
        assertTrue(source.contains("SafeRedactor.hashValue(messageOf(ex)), messageLength(ex)"));
        assertTrue(source.contains("private static String messageOf(Throwable t)"));
    }

    @Test
    void failSoftThrowableDiagnosticsUseHashAndLengthOnly() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/ContextOrchestrator.java"));

        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(ex), 120)"));
        assertTrue(source.contains("[ContextOrchestrator] compressMemoryForPrompt fail-soft. errorHash={} errorLength={}"));
        assertTrue(source.contains("[ContextOrchestrator] composeForPrompt fail-soft. errorHash={} errorLength={}"));
        assertTrue(source.contains("[ContextOrchestrator] overdrive memory guard fail-soft. errorHash={} errorLength={}"));
        assertTrue(source.contains("[ContextOrchestrator] overdrive guard fail-soft. errorHash={} errorLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(messageOf(ex)), messageLength(ex)"));
    }

    @Test
    void overdriveGuardMustApprovePromptCompositionCompression() {
        RecordingPromptCompressor compressor = new RecordingPromptCompressor();
        OverdriveGuard guard = mock(OverdriveGuard.class);
        when(guard.shouldActivate(eq("keepanchor"), anyList())).thenReturn(false);
        PromptBuilder builder = (contexts, question) -> {
            assertEquals(1, contexts.size());
            assertEquals(1, contexts.get(0).web().size());
            return contexts.get(0).web().get(0).textSegment().text();
        };
        ContextOrchestrator orchestrator = new ContextOrchestrator(builder);
        ReflectionTestUtils.setField(orchestrator, "promptContextCompressor", compressor);
        ReflectionTestUtils.setField(orchestrator, "overdriveGuard", guard);

        String prompt = orchestrator.orchestrate(
                "keepanchor",
                List.of(),
                List.of(uncompressed("keepanchor raw evidence")),
                Map.of());

        assertEquals("keepanchor raw evidence", prompt);
        assertEquals(0, compressor.composeCalls);
        assertEquals(Boolean.FALSE, TraceStore.get("overdrive.guard.decision"));
        assertEquals("guard_declined", TraceStore.get("prompt.context.composer.skippedReason"));
        assertEquals(1, TraceStore.get("context.candidates.raw"));
        assertEquals(1, TraceStore.get("context.candidates.afterFilter"));
        assertEquals(Boolean.FALSE, TraceStore.get("context.starvation"));
        assertEquals("guard_declined", TraceStore.get("context.overdrive.skipReason"));
    }

    @Test
    void overdriveActivationNarrowsPromptCandidatesAndTracesAnchorDecision() {
        RecordingPromptCompressor compressor = new RecordingPromptCompressor();
        OverdriveGuard guard = mock(OverdriveGuard.class);
        when(guard.shouldActivate(eq("GraphRAG KG"), anyList())).thenReturn(true);
        AnchorNarrower narrower = mock(AnchorNarrower.class);
        AnchorNarrowingResult result = new AnchorNarrowingResult(
                List.of("GraphRAG"),
                0.80d,
                0.20d,
                1,
                1,
                "anchor_narrowed");
        when(narrower.narrow(eq("GraphRAG KG"), anyList(), eq(3), eq(0.65d))).thenReturn(result);
        when(narrower.filterCandidates(eq("GraphRAG KG"), anyList(), eq(result), eq(0.65d)))
                .thenReturn(List.of("GraphRAG KG useful evidence"));
        PromptBuilder builder = (contexts, question) -> {
            assertEquals(1, contexts.size());
            assertEquals(1, contexts.get(0).web().size());
            return contexts.get(0).web().get(0).textSegment().text();
        };
        ContextOrchestrator orchestrator = new ContextOrchestrator(builder);
        ReflectionTestUtils.setField(orchestrator, "promptContextCompressor", compressor);
        ReflectionTestUtils.setField(orchestrator, "overdriveGuard", guard);
        ReflectionTestUtils.setField(orchestrator, "overdriveAnchorNarrower", narrower);

        String prompt = orchestrator.orchestrate(
                "GraphRAG KG",
                List.of(),
                List.of(
                        uncompressed("GraphRAG KG useful evidence"),
                        uncompressed("unrelated cooking evidence")),
                Map.of());

        assertEquals("GraphRAG KG useful evidence", prompt);
        verify(narrower).narrow(eq("GraphRAG KG"), anyList(), eq(3), eq(0.65d));
        assertEquals(Boolean.TRUE, TraceStore.get("overdrive.trigger.activated"));
        assertEquals(1, TraceStore.get("overdrive.anchor.narrowed.k"));
        assertEquals("anchor_narrowed", TraceStore.get("overdrive.anchor.narrowedReason"));
        assertEquals("", TraceStore.get("overdrive.anchor.skipReason"));
        assertEquals(2, TraceStore.get("context.candidates.raw"));
        assertEquals(1, TraceStore.get("context.candidates.afterFilter"));
        assertEquals(Boolean.FALSE, TraceStore.get("context.starvation"));
        assertEquals(Boolean.TRUE, TraceStore.get("context.overdrive.activated"));
        assertEquals(Boolean.FALSE, TraceStore.get("context.extremeZ.activated"));
    }

    @Test
    void promptCompositionMetadataIsCarriedIntoPromptContextBeforeBuilder() {
        RecordingPromptCompressor compressor = new RecordingPromptCompressor();
        compressor.contextRefinementSummary = "reason=selected,candidates=3,selected=ensemble_trace_best";
        compressor.contextRefinementSignals = Map.of("needleKeptRatio", 0.25d);
        OverdriveGuard guard = mock(OverdriveGuard.class);
        when(guard.shouldActivate(eq("GraphRAG KG"), anyList())).thenReturn(true);
        PromptBuilder builder = (contexts, question) -> {
            assertEquals(1, contexts.size());
            assertEquals("reason=selected,candidates=3,selected=ensemble_trace_best",
                    contexts.get(0).contextRefinementSummary());
            assertEquals(0.25d, contexts.get(0).contextRefinementSignals().get("needleKeptRatio"), 1e-9);
            return "metadata-carried";
        };
        ContextOrchestrator orchestrator = new ContextOrchestrator(builder);
        ReflectionTestUtils.setField(orchestrator, "promptContextCompressor", compressor);
        ReflectionTestUtils.setField(orchestrator, "overdriveGuard", guard);

        String prompt = orchestrator.orchestrate(
                "GraphRAG KG",
                List.of(),
                List.of(uncompressed("GraphRAG KG useful evidence")),
                Map.of());

        assertEquals("metadata-carried", prompt);
    }

    @Test
    void emptyCandidatesPublishContextStarvationTrace() {
        PromptBuilder builder = (contexts, question) -> "empty-context";
        ContextOrchestrator orchestrator = new ContextOrchestrator(builder);

        String prompt = orchestrator.orchestrate("GraphRAG KG", List.of(), List.of(), Map.of());

        assertEquals("empty-context", prompt);
        assertEquals(0, TraceStore.get("context.candidates.raw"));
        assertEquals(0, TraceStore.get("context.candidates.afterFilter"));
        assertEquals(Boolean.TRUE, TraceStore.get("context.starvation"));
        assertEquals("empty_candidates", TraceStore.get("context.starvation.reason"));
        assertEquals(Boolean.FALSE, TraceStore.get("context.overdrive.activated"));
        assertEquals(Boolean.FALSE, TraceStore.get("context.extremeZ.activated"));
    }

    @Test
    void overdriveGuardMustApproveMemoryCompression() {
        RecordingPromptCompressor compressor = new RecordingPromptCompressor();
        OverdriveGuard guard = mock(OverdriveGuard.class);
        when(guard.shouldActivate(eq("keepanchor"), anyList())).thenReturn(false);
        String originalMemory = "keepanchor ".repeat(240);
        PromptBuilder builder = (contexts, question) -> {
            assertEquals(1, contexts.size());
            return contexts.get(0).memory();
        };
        ContextOrchestrator orchestrator = new ContextOrchestrator(builder);
        ReflectionTestUtils.setField(orchestrator, "promptContextCompressor", compressor);
        ReflectionTestUtils.setField(orchestrator, "overdriveGuard", guard);

        String prompt = orchestrator.orchestrate(
                "keepanchor",
                List.of(),
                List.of(),
                Map.of(),
                null,
                originalMemory);

        assertEquals(originalMemory, prompt);
        assertEquals(0, compressor.memoryCompressCalls);
        assertEquals(Boolean.FALSE, TraceStore.get("overdrive.guard.memory.decision"));
        assertEquals("guard_declined", TraceStore.get("prompt.memory.composer.skippedReason"));
    }

    private static Content compressed(String text) {
        return Content.from(TextSegment.from(text, Metadata.from(Map.of(
                "_nova.compressed", "true",
                "url", "https://example.test/evidence"))));
    }

    private static Content uncompressed(String text) {
        return Content.from(TextSegment.from(text, Metadata.from(Map.of(
                "url", "https://example.test/evidence"))));
    }

    private static final class RecordingPromptCompressor extends DynamicContextCompressor {
        int composeCalls;
        int memoryCompressCalls;
        String contextRefinementSummary = "";
        Map<String, Double> contextRefinementSignals = Map.of();

        RecordingPromptCompressor() {
            super(new NovaOrchestrationProperties());
        }

        @Override
        public PromptContextComposition composeForPrompt(String query, List<Content> webDocs, List<Content> ragDocs) {
            composeCalls++;
            return new PromptContextComposition(webDocs, ragDocs,
                    new CompositionDecision(true, true, "recording", 1.0d, "test", "", 0,
                            size(webDocs), size(ragDocs), size(webDocs), size(ragDocs), Map.of(), false),
                    contextRefinementSummary,
                    contextRefinementSignals);
        }

        @Override
        public String compressMemoryForPrompt(String query, String memoryCtx) {
            memoryCompressCalls++;
            return "compressed:" + memoryCtx;
        }

        private static int size(List<Content> docs) {
            return docs == null ? 0 : docs.size();
        }
    }
}
