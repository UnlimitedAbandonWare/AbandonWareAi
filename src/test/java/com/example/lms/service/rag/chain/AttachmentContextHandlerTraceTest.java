package com.example.lms.service.rag.chain;

import com.example.lms.dto.AttachmentDto;
import com.example.lms.prompt.PromptContext;
import com.example.lms.search.TraceStore;
import com.example.lms.service.AttachmentOwnerIdentity;
import com.example.lms.service.AttachmentService;
import com.example.lms.service.rag.chain.impl.DefaultChainContext;
import com.example.lms.telemetry.MlaBreadcrumb;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AttachmentContextHandlerTraceTest {
    private static final AttachmentOwnerIdentity OWNER =
            AttachmentOwnerIdentity.forAnonymous("chain-owner");

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void handlePublishesCihRagAttachmentTrace() {
        AttachmentService attachmentService = mock(AttachmentService.class);
        when(attachmentService.findBySession("sid-1", OWNER)).thenReturn(List.of(
                new AttachmentDto("a1", "one.txt", 12L, "text/plain", "/a1"),
                new AttachmentDto("a2", "two.txt", 15L, "text/plain", "/a2")));
        when(attachmentService.asDocumentsForSession(List.of("a1", "a2"), "sid-1", OWNER))
                .thenReturn(List.of(Document.from(
                        "ontology evidence for iqr",
                        new Metadata(Map.of("source", "attachment", "name", "one.txt")))));
        RecordingContext ctx = new RecordingContext("sid-1");
        AttachmentContextHandler handler = new AttachmentContextHandler(attachmentService);

        ChainOutcome outcome = handler.handle(ctx, next -> ChainOutcome.SUCCESS_PASS);

        assertEquals(ChainOutcome.SUCCESS_PASS, outcome);
        assertEquals(2, ctx.attachments);
        verify(attachmentService).asDocumentsForSession(List.of("a1", "a2"), "sid-1", OWNER);
        assertEquals(2, TraceStore.get("cihRag.activeFileCount"));
        assertEquals(0, TraceStore.get("cihRag.skippedFileCount"));
        assertEquals(1, TraceStore.get("cihRag.iqrIterations"));
        assertNull(TraceStore.get("cihRag.iqrDisabledReason"));
        assertEquals(1, TraceStore.get("cihRag.localDocCount"));
        assertEquals(Boolean.FALSE, TraceStore.get("cihRag.biEncoderApplied"));
        assertEquals("bi_encoder_unavailable", TraceStore.get("cihRag.biEncoderDisabledReason"));
        assertEquals(Boolean.FALSE, TraceStore.get("cihRag.onnxRerankApplied"));
        assertEquals("onnx_unavailable", TraceStore.get("cihRag.onnxRerankDisabledReason"));
        assertEquals(Boolean.FALSE, TraceStore.get("cihRag.dppApplied"));
        assertEquals("dpp_unavailable", TraceStore.get("cihRag.dppDisabledReason"));
        assertEquals(0, TraceStore.get("cihRag.mlaBreadcrumbCount"));
        assertEquals("iqr_pipeline", TraceStore.get("cihRag.implementationStage"));
    }

    @Test
    void handleTracesAttachmentSkipAndIqrEmptyReason() {
        AttachmentService attachmentService = mock(AttachmentService.class);
        when(attachmentService.findBySession("sid-2", OWNER)).thenReturn(List.of(
                new AttachmentDto("a1", "one.txt", 12L, "text/plain", "/a1"),
                new AttachmentDto("a2", "two.txt", 15L, "text/plain", "/a2")));
        when(attachmentService.asDocumentsForSession(List.of("a1"), "sid-2", OWNER)).thenReturn(List.of());
        RecordingContext ctx = new RecordingContext("sid-2", true);
        AttachmentContextHandler handler = new AttachmentContextHandler(attachmentService);

        ChainOutcome outcome = handler.handle(ctx, next -> ChainOutcome.SUCCESS_PASS);

        assertEquals(ChainOutcome.SUCCESS_PASS, outcome);
        assertEquals(1, ctx.attachments);
        assertEquals(1, TraceStore.get("cihRag.activeFileCount"));
        assertEquals(1, TraceStore.get("cihRag.skippedFileCount"));
        assertEquals("IllegalStateException", TraceStore.get("cihRag.attachment.skipReason.1"));
        verify(attachmentService).asDocumentsForSession(List.of("a1"), "sid-2", OWNER);
        assertEquals(1, TraceStore.get("cihRag.iqrIterations"));
        assertEquals("no_attachment_docs", TraceStore.get("cihRag.iqrDisabledReason"));
        assertEquals("bi_encoder_unavailable", TraceStore.get("cihRag.biEncoderDisabledReason"));
        assertEquals("onnx_unavailable", TraceStore.get("cihRag.onnxRerankDisabledReason"));
        assertEquals("dpp_unavailable", TraceStore.get("cihRag.dppDisabledReason"));
        assertEquals("iqr_pipeline_empty", TraceStore.get("cihRag.implementationStage"));
    }

    @Test
    void handleCarriesExistingMlaBreadcrumbCountIntoCihRagTrace() {
        MlaBreadcrumb.appendSseEvent("chunk", "safe payload");
        AttachmentService attachmentService = mock(AttachmentService.class);
        when(attachmentService.findBySession("sid-3", OWNER)).thenReturn(List.of());
        AttachmentContextHandler handler = new AttachmentContextHandler(attachmentService);

        ChainOutcome outcome = handler.handle(new RecordingContext("sid-3"), next -> ChainOutcome.SUCCESS_PASS);

        assertEquals(ChainOutcome.SUCCESS_PASS, outcome);
        assertEquals(0, TraceStore.get("cihRag.iqrIterations"));
        assertEquals("no_attachments", TraceStore.get("cihRag.iqrDisabledReason"));
        assertEquals(1, TraceStore.get("cihRag.mlaBreadcrumbCount"));
        assertEquals(Boolean.TRUE, TraceStore.get("cihRag.breadcrumb.queryRedacted"));
    }

    @Test
    void handleMergesAttachmentDocumentsIntoPromptContextLocalDocs() {
        AttachmentService attachmentService = mock(AttachmentService.class);
        Document existing = Document.from("existing local context");
        Document attachmentDoc = Document.from(
                "attachment evidence for prompt builder",
                new Metadata(Map.of("source", "attachment", "name", "attachment.txt")));
        when(attachmentService.findBySession("sid-local", OWNER)).thenReturn(List.of(
                new AttachmentDto("a-local", "attachment.txt", 21L, "text/plain", "/attachment")));
        when(attachmentService.asDocumentsForSession(List.of("a-local"), "sid-local", OWNER))
                .thenReturn(List.of(attachmentDoc));
        DefaultChainContext ctx = new DefaultChainContext(
                "sid-local",
                "user",
                "message",
                PromptContext.builder().localDocs(List.of(existing)).build(),
                null,
                null,
                OWNER);
        AttachmentContextHandler handler = new AttachmentContextHandler(attachmentService);

        ChainOutcome outcome = handler.handle(ctx, next -> ChainOutcome.SUCCESS_PASS);

        assertEquals(ChainOutcome.SUCCESS_PASS, outcome);
        verify(attachmentService).asDocumentsForSession(List.of("a-local"), "sid-local", OWNER);
        assertEquals(List.of(existing, attachmentDoc), ctx.promptContext().localDocs());
        assertEquals(1, TraceStore.get("cihRag.iqrIterations"));
        assertEquals(1, TraceStore.get("cihRag.localDocCount"));
        assertEquals("iqr_pipeline", TraceStore.get("cihRag.implementationStage"));
    }

    @Test
    void helperCatchBlocksLeaveScannerVisibleBreadcrumbs() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/service/rag/chain/AttachmentContextHandler.java"));

        assertTrue(source.contains("traceSuppressed(\"metadata\", ex);"));
        assertTrue(source.contains("traceSuppressed(\"contentText\", ex);"));
        assertTrue(source.contains("traceSuppressed(\"documentText\", ex);"));
        assertTrue(source.contains("TraceStore.put(\"cihRag.suppressed.stage\", safeStage);"));
        assertTrue(source.contains("TraceStore.put(\"cihRag.suppressed.errorType\", safeErrorType);"));
        assertTrue(source.contains("TraceStore.put(\"cihRag.suppressed.\" + safeStage, true);"));
        assertTrue(source.contains("TraceStore.put(\"cihRag.suppressed.\" + safeStage + \".errorType\", safeErrorType);"));
    }

    @Test
    void foreignOwnerLookupFailsClosedBeforeAttachmentExtraction() {
        AttachmentService attachmentService = mock(AttachmentService.class);
        AttachmentOwnerIdentity foreign = AttachmentOwnerIdentity.forAnonymous("foreign-owner");
        when(attachmentService.findBySession("sid-owner", foreign)).thenReturn(List.of());
        RecordingContext ctx = new RecordingContext("sid-owner", false, foreign);
        AttachmentContextHandler handler = new AttachmentContextHandler(attachmentService);

        ChainOutcome outcome = handler.handle(ctx, next -> ChainOutcome.SUCCESS_PASS);

        assertEquals(ChainOutcome.SUCCESS_PASS, outcome);
        assertEquals(0, ctx.attachments);
        verify(attachmentService).findBySession("sid-owner", foreign);
        verify(attachmentService, never()).asDocumentsForSession(
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(AttachmentOwnerIdentity.class));
    }

    @Test
    void helperSuppressedTraceIncludesSafeAggregateStageAndErrorType() throws Exception {
        Method helper = AttachmentContextHandler.class
                .getDeclaredMethod("traceSuppressed", String.class, Exception.class);
        helper.setAccessible(true);

        helper.invoke(null, "ownerToken=attachment-secret", new IllegalStateException("ownerToken=attachment-secret"));

        Object safeStage = TraceStore.get("cihRag.suppressed.stage");
        assertTrue(String.valueOf(safeStage).startsWith("hash:"), String.valueOf(safeStage));
        assertEquals("IllegalStateException", TraceStore.get("cihRag.suppressed.errorType"));
        assertEquals(Boolean.TRUE, TraceStore.get("cihRag.suppressed." + safeStage));
        assertEquals("IllegalStateException", TraceStore.get("cihRag.suppressed." + safeStage + ".errorType"));
        String trace = String.valueOf(TraceStore.getAll());
        assertTrue(!trace.contains("ownerToken"));
        assertTrue(!trace.contains("attachment-secret"));
    }

    private static final class RecordingContext implements ChainContext {
        private final String sessionId;
        private final boolean failSecondAttachment;
        private final AttachmentOwnerIdentity ownerIdentity;
        private int attachments;

        private RecordingContext(String sessionId) {
            this(sessionId, false);
        }

        private RecordingContext(String sessionId, boolean failSecondAttachment) {
            this(sessionId, failSecondAttachment, OWNER);
        }

        private RecordingContext(
                String sessionId,
                boolean failSecondAttachment,
                AttachmentOwnerIdentity ownerIdentity) {
            this.sessionId = sessionId;
            this.failSecondAttachment = failSecondAttachment;
            this.ownerIdentity = ownerIdentity;
        }

        @Override
        public String sessionId() {
            return sessionId;
        }

        @Override
        public String userId() {
            return "user";
        }

        @Override
        public AttachmentOwnerIdentity attachmentOwnerIdentity() {
            return ownerIdentity;
        }

        @Override
        public String userMessage() {
            return "message";
        }

        @Override
        public PromptContext promptContext() {
            return null;
        }

        @Override
        public ChainContext withSystemNote(String note) {
            return this;
        }

        @Override
        public ChainContext withAssistantSideNote(String note) {
            return this;
        }

        @Override
        public ChainContext withAttachment(AttachmentDto att) {
            if (failSecondAttachment && attachments > 0) {
                throw new IllegalStateException("attachment rejected");
            }
            attachments++;
            return this;
        }

        @Override
        public ChainContext putMeta(String key, String value) {
            return this;
        }

        @Override
        public void emitAssistant(String text) {
        }
    }
}
