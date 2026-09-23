package com.example.lms.api;

import com.example.lms.dto.ChatStreamEvent;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugEventTracePromotionService;
import com.example.lms.debug.ai.DebugAiMetricsService;
import com.example.lms.debug.ai.DebugAiRawTile;
import com.example.lms.llm.ModelRuntimeHealthTracker;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.StageBoundaryBreadcrumbs;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import reactor.core.publisher.Sinks;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatApiControllerTraceMetaTest {

    @Test
    void selectionEntropyStreamStartsOnlyAfterAttachReturnAndAcknowledgementGate() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/api/ChatApiController.java"));

        int attachReturn = source.indexOf(
                "return runRegistry.attachInteractiveExact(req.getSessionId(), runTokenHeader)");
        int ackGate = source.indexOf("if (clientAckRequired) {", attachReturn);
        int earlyWrite = selectionWriteIndex(source, ackGate, false);
        int earlyEmit = source.indexOf("emitSelectionEntropy(sink);", earlyWrite);
        int appendUser = source.indexOf(
                "historyService.appendMessage(session.getId(), \"user\", dto.getMessage());",
                earlyEmit);

        assertTrue(attachReturn >= 0, "exact attach return should be locatable");
        assertTrue(ackGate > attachReturn, "new-run acknowledgement gate must follow attach return");
        assertTrue(earlyWrite > ackGate && earlyEmit > earlyWrite,
                "early selection projection/event must occur only after the acknowledgement gate");
        assertTrue(appendUser > earlyEmit,
                "early projection must be visible before the owned execution starts its user turn");
    }

    @Test
    void syncReplayProjectionPrecedesTraceCaptureClearAndTenArgumentResponse() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/api/ChatApiController.java"));
        int syncCall = source.indexOf(
                "ChatResult result = chatService.continueChat(dtoForCall, __webSupplier);",
                source.indexOf("private ChatResponseDto handleChat("));
        int replayProjection = source.indexOf(
                "SelectionEntropyProjection syncSelectionEntropy = null;", syncCall);
        int replayGate = source.indexOf(
                "if (selectionEntropy.mode() == SelectionEntropyMode.REPLAY) {",
                replayProjection);
        int write = selectionWriteIndex(source, replayGate, true);
        int capture = source.indexOf("extraMeta = TraceStore.getAll();", write);
        int clear = source.indexOf("TraceStore.clear();", capture);
        int response = source.indexOf("ChatResponseDto response = new ChatResponseDto(", clear);
        int responseEnd = source.indexOf(");", response);

        assertTrue(syncCall >= 0 && replayProjection > syncCall,
                "sync replay projection must be derived only after workflow selection decisions finish");
        assertTrue(replayGate > replayProjection && write > replayGate,
                "standard sync JSON must stay null while accepted replay writes a terminal projection");
        assertTrue(capture > write && clear > capture && response > clear,
                "terminal projection must enter final trace metadata before clear and DTO assembly");
        assertTrue(responseEnd > response
                        && source.substring(response, responseEnd).contains("syncSelectionEntropy"),
                "the ten-argument response must carry the typed replay projection directly");
    }

    @Test
    void terminalSelectionEntropyPrecedesFinalAndErrorTerminalEvents() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/api/ChatApiController.java"));
        int streamCall = source.indexOf(
                "ChatResult result = chatService.continueChat(dtoForCall, __webSupplier);");
        int finalStage = source.indexOf(
                "StageBoundaryBreadcrumbs.recordFromCurrentTrace(\"final\");", streamCall);
        int normalWrite = selectionWriteIndex(source, finalStage, true);
        int traceCapture = source.indexOf(
                "java.util.Map<String, Object> extraMeta = TraceStore.getAll();", normalWrite);
        int finalEmit = source.indexOf(
                "Sinks.EmitResult finalEmitResult = emitFinalStreamEvent(", normalWrite);
        int normalProjectionEmit = source.lastIndexOf("emitSelectionEntropy(sink);", finalEmit);

        int streamCatch = source.indexOf("} catch (Exception ex) {", normalWrite);
        int selectionFailure = source.indexOf(
                "if (selectionEntropy.mode() == SelectionEntropyMode.REPLAY", streamCatch);
        int markFailure = source.indexOf(
                "selectionDecisionLedger.markFailure(selectionFailure.reason());", selectionFailure);
        int failedProjectionEmit = source.indexOf("emitSelectionEntropy(sink);", markFailure);
        int selectionError = source.indexOf(
                "sink.tryEmitNext(sse(ChatStreamEvent.error(safeFailure.code())))", markFailure);

        int genericError = source.indexOf(
                "sink.tryEmitNext(sse(ChatStreamEvent.error(errMsg)))", selectionError);
        int genericProjectionEmit = source.lastIndexOf("emitSelectionEntropy(sink);", genericError);

        assertTrue(normalWrite > finalStage && traceCapture > normalWrite,
                "terminal projection must be written into the final trace snapshot");
        assertTrue(normalProjectionEmit > traceCapture && finalEmit > normalProjectionEmit,
                "typed terminal selection event must precede the existing final event");
        assertTrue(markFailure > selectionFailure
                        && failedProjectionEmit > markFailure
                        && selectionError > failedProjectionEmit,
                "only a real selection failure may mark failed and it must emit before the error");
        assertTrue(genericProjectionEmit > selectionError && genericError > genericProjectionEmit,
                "generic failure must emit current selection state without being reclassified");
        assertTrue(source.contains(
                "SelectionEntropyProjection.from(entropy, ledger, terminal, false);"),
                "v1 transport projection must always report nondeterministic completion scheduling");
    }

    @Test
    void everyInWorkerCancellationEmitsSelectionProjectionBeforeCancelledStatus() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/api/ChatApiController.java"));

        assertSelectionBeforeCancellation(source,
                source.indexOf("if (streamCancelledFinal || isStreamRunCancelled(runContextRef.get(), finalSessionId)) {"));

        int afterFinalMeta = source.indexOf(
                "if (!finalTraceSignalEmitted && finalTraceSignal != null)");
        assertSelectionBeforeCancellation(source,
                source.indexOf("if (isStreamRunCancelled(runContextRef.get(), finalSessionId)) {", afterFinalMeta));

        assertSelectionBeforeCancellation(source, source.indexOf("if (cancelledBeforePersist) {"));
        assertSelectionBeforeCancellation(source, source.indexOf("if (!durablePersistenceAccepted) {"));
        assertSelectionBeforeCancellation(source,
                source.indexOf("if (isStreamCancellation(ex, runContextRef.get()))"));
    }

    @Test
    void preAcknowledgementCancellationBuffersCurrentProjectionBeforeCancelledStatus() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/api/ChatApiController.java"));

        assertPreAckCancellationHelper(source,
                source.indexOf("if (clientAckRequired && clientDetached.get()) {"));
        assertPreAckCancellationHelper(source,
                source.indexOf("if (runContext.isCancellationRequested()) {"));
        assertPreAckCancellationHelper(source,
                source.indexOf("if (!acknowledged) {"));

        int helper = source.indexOf("private boolean emitPreAcknowledgementCancellation(");
        int helperEnd = source.indexOf("\n    }", helper);
        String body = source.substring(helper, helperEnd);
        int projection = body.indexOf(
                "writeSelectionEntropyProjection(entropy, ledger, false)");
        int selectionEvent = body.indexOf("selectionEntropyEvent()", projection);
        int cancelledEvents = body.indexOf("streamCancelledEvents(", selectionEvent);
        int atomicCancel = body.indexOf(
                "runRegistry.cancelIfUnacknowledged(runContext, events)", cancelledEvents);
        int acceptedOnly = body.indexOf("if (!cancelled)", atomicCancel);
        int sinkEmit = body.indexOf("sink.tryEmitNext(event)", acceptedOnly);
        int terminal = body.indexOf("recordRunTerminal(runContext, \"cancelled\")", sinkEmit);

        assertTrue(helper >= 0 && projection >= 0,
                "pre-ACK cancellation helper must use the non-terminal current projection");
        assertTrue(selectionEvent > projection && cancelledEvents > selectionEvent,
                "selection event must be ordered before the separate cancelled status events");
        assertTrue(atomicCancel > cancelledEvents && acceptedOnly > atomicCancel,
                "the registry must atomically accept evidence only when unacknowledged cancellation wins");
        assertTrue(sinkEmit > acceptedOnly && terminal > sinkEmit,
                "accepted terminal evidence must be mirrored locally before terminal recording");
        assertTrue(source.contains(".doOnCancel(() -> {")
                        && source.substring(source.indexOf(".doOnCancel(() -> {") ,
                                source.indexOf(".doOnError(", source.indexOf(".doOnCancel(() -> {")))
                                .contains("emitPreAcknowledgementCancellation("),
                "the transport detach hook must supply evidence at the cancellation source");
        assertTrue(terminal > sinkEmit,
                "the run terminal marker must follow buffered cancellation evidence");
    }

    @Test
    void chatApiControllerDoesNotUseExactEmptyCatchBlocks() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/api/ChatApiController.java"));

        assertFalse(Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")
                        .matcher(source)
                        .find(),
                "ChatApiController should leave fixed-stage breadcrumbs instead of exact empty catch bodies");
    }

    @Test
    void chatApiControllerScannerSamplesUseNamedSuppressionStages() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/api/ChatApiController.java"));

        assertTrue(source.contains("logSuppressed(\"cancel.cancelSession\");"));
        assertTrue(source.contains("logSuppressed(\"state.sessionLookup\");"));
        assertTrue(source.contains("logSuppressed(\"state.metaExtract\");"));
        assertTrue(source.contains("logSuppressed(\"chat.clientIp\");"));
        assertTrue(source.contains("logSuppressed(\"chat.attachments.autoInject\");"));
        assertTrue(source.contains("logSuppressed(\"stream.clientIp\");"));
        assertTrue(source.contains("logSuppressed(\"stream.status.started\");"));
        assertTrue(source.contains("logSuppressed(\"plan.preSearch.stream\");"));
        assertTrue(source.contains("logSuppressed(\"stream.runSink.forward\");"));
        assertTrue(source.contains("logSuppressed(\"stream.runSink.error\");"));
        assertTrue(source.contains("logSuppressed(\"stream.sessionBreadcrumb.mdc\");"));
        assertTrue(source.contains("logSuppressed(\"stream.sessionBreadcrumb.trace\");"));
        assertTrue(source.contains("logSuppressed(\"stream.sessionBreadcrumb.outer\");"));
        assertTrue(source.contains("logSuppressed(\"stream.sessionReady\");"));
        assertTrue(source.contains("logSuppressed(\"stream.chainRunner\");"));
        assertTrue(source.contains("logSuppressed(\"stream.traceHtml.prefetch\");"));
        assertTrue(source.contains("logSuppressed(\"stream.guardContext.webSupplier\");"));
        assertTrue(source.contains("logSuppressed(\"stream.answerMode\");"));
        assertTrue(source.contains("logSuppressed(\"stream.failureTags\");"));
        assertTrue(source.contains("logSuppressed(\"stream.searchTraceConsole\");"));
        assertTrue(source.contains("logSuppressed(\"stream.finalTraceMeta\");"));
        assertTrue(source.contains("logSuppressed(\"stream.finalTraceMeta.clear\");"));
        assertTrue(source.contains("logSuppressed(\"stream.answerModeTracePersist\");"));
        assertTrue(source.contains("logSuppressed(\"stream.status.complete\");"));
        assertTrue(source.contains("logSuppressed(\"stream.status.error\");"));
        assertTrue(source.contains("logSuppressed(\"parse.asLong\");"));
        assertTrue(source.contains("logSuppressed(\"parse.normalizeChatSessionId\");"));
        assertTrue(source.contains("logSuppressed(\"memory.rehydrate.traceSessionNotFound\");"));
        assertTrue(source.contains("logSuppressed(\"memory.rollingSummary.previousSnapshot\");"));
        assertTrue(source.contains("logSuppressed(\"memory.rollingSummary.update\");"));
        assertTrue(source.contains("logSuppressed(\"memory.rollingSummary.shadowVector\");"));
        assertTrue(source.contains("logSuppressed(\"memory.shadowVectorQueued.trace\");"));
        assertTrue(source.contains("logSuppressed(\"sync.sessionBreadcrumb.mdc\");"));
        assertTrue(source.contains("logSuppressed(\"sync.sessionBreadcrumb.trace\");"));
        assertTrue(source.contains("logSuppressed(\"sync.sessionBreadcrumb.outer\");"));
        assertTrue(source.contains("logSuppressed(\"sync.prefetchGuardContext\");"));
        assertTrue(source.contains("logSuppressed(\"sync.webSupplierGuardContext\");"));
        assertTrue(source.contains("logSuppressed(\"sync.answerMode\");"));
        assertTrue(source.contains("logSuppressed(\"sync.failureTags\");"));
        assertTrue(source.contains("logSuppressed(\"sync.searchTraceConsole\");"));
        assertTrue(source.contains("logSuppressed(\"sync.finalTraceMeta\");"));
        assertTrue(source.contains("logSuppressed(\"sync.finalTraceMeta.clear\");"));
        assertTrue(source.contains("logSuppressed(\"sync.traceHtml.final\");"));
        assertTrue(source.contains("logSuppressed(\"sync.answerModeTracePersist\");"));
        assertTrue(source.contains("logSuppressed(\"sync.attachmentMeta.extract\");"));
        assertTrue(source.contains("logSuppressed(\"sync.attachmentMeta\");"));
    }

    @Test
    void streamCancellationBypassesErrorEmissionPath() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/api/ChatApiController.java"));

        int catchBlock = source.indexOf("} catch (Exception ex) {");
        int cancellationGuard = source.indexOf(
                "if (isStreamCancellation(ex, runContextRef.get()))", catchBlock);
        int errorLog = source.indexOf("log.error(\"[AWX][chat] stream-failed", catchBlock);

        assertTrue(catchBlock > 0, "stream catch block should exist");
        assertTrue(cancellationGuard > catchBlock, "stream catch should classify explicit cancellation first");
        assertTrue(cancellationGuard < errorLog, "explicit cancellation should not be logged as stream-failed ERROR");
        int errorEmission = source.indexOf("sink.tryEmitNext(sse(ChatStreamEvent.error(errMsg)))", cancellationGuard);
        String cancellationWindow = source.substring(cancellationGuard, Math.min(source.length(), errorEmission < 0 ? cancellationGuard + 300 : errorEmission));
        assertTrue(cancellationWindow.contains("return;"), "explicit cancellation branch must return before error SSE emission");
        assertTrue(source.contains("cursor instanceof java.util.concurrent.CancellationException"),
                "cancellation guard should match the observed CancellationException type");
    }

    @Test
    void streamCancellationRecognizesMvcClientDisconnectFailures() throws Exception {
        Method method = ChatApiController.class.getDeclaredMethod(
                "isStreamCancellation",
                Throwable.class,
                com.example.lms.service.chat.ChatRunExecutionContext.class);
        method.setAccessible(true);

        assertEquals(Boolean.TRUE, method.invoke(null,
                new AsyncRequestNotUsableException("ServletOutputStream failed to flush"), null));
        assertEquals(Boolean.TRUE, method.invoke(null,
                new IllegalStateException("AsyncContext after an error had occurred"), null));
        assertEquals(Boolean.TRUE, method.invoke(null,
                new IOException("Connection reset by peer"), null));
        assertEquals(Boolean.FALSE, method.invoke(null,
                new java.util.concurrent.CancellationException("internal preemption"), null));
        assertEquals(Boolean.FALSE, method.invoke(null,
                new IllegalStateException("real application failure"), null));
    }

    @Test
    void detachedStreamEventsBypassClientSinkButContinueReplaySink() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/api/ChatApiController.java"));
        assertTrue(source.contains("sink.asFlux().subscribe(event ->"));
        assertTrue(source.contains("runRegistry.emit(runContext, event)"));
        assertTrue(source.contains("runRegistry.beginOrJoin(session.getId())"));
        assertTrue(source.contains("chatStreamEmitter.unregisterSink(runContext, sink)"));
    }

    @Test
    void trace64DoesNotDecodeWhenTraceExposureIsOff() {
        String b64 = Base64.getEncoder().encodeToString("<b>trace</b>".getBytes(StandardCharsets.UTF_8));

        assertTrue(ChatApiController.restoreTraceMetaMessage(1L, "?TRACE64?" + b64, LocalDateTime.now(), false)
                .isEmpty());
    }

    @Test
    void traceSnapshotPointerRendersRedactedLinkOnlyWhenTraceExposureIsOn() {
        assertTrue(ChatApiController.restoreTraceMetaMessage(
                10L,
                "?TRACESNAP?snap_20260531-0821.01",
                LocalDateTime.now(),
                false).isEmpty());

        ChatApiController.MessageDto dto = ChatApiController.restoreTraceMetaMessage(
                        11L,
                        "?TRACESNAP?snap_20260531-0821.01",
                        LocalDateTime.of(2026, 5, 31, 8, 21),
                        true)
                .orElseThrow();

        assertEquals(11L, dto.turnId());
        assertEquals("system", dto.role());
        assertTrue(dto.content().contains("data-trace-snapshot-id=\"snap_20260531-0821.01\""));
        assertTrue(dto.content().contains("/api/diagnostics/trace/snapshots/snap_20260531-0821.01/html"));
        assertFalse(dto.content().contains("?TRACE64?"));
    }

    @Test
    void malformedTraceSnapshotPointersAreSkipped() {
        assertTrue(ChatApiController.restoreTraceMetaMessage(
                12L,
                "?TRACESNAP?../bad",
                LocalDateTime.now(),
                true).isEmpty());
    }

    @Test
    void invalidAndOversizedTrace64PayloadsAreSkipped() {
        assertTrue(ChatApiController.restoreTraceMetaMessage(1L, "?TRACE64?not base64!", LocalDateTime.now(), true)
                .isEmpty());
        assertTrue(ChatApiController.restoreTraceMetaMessage(1L, "?TRACE64?" + "a".repeat(64_001),
                LocalDateTime.now(), true).isEmpty());
    }

    @Test
    void exposedTrace64PayloadIsDecodedAndRedactedThroughMessageBoundary() {
        String b64 = Base64.getEncoder().encodeToString("<section>trace</section>".getBytes(StandardCharsets.UTF_8));

        ChatApiController.MessageDto dto = ChatApiController
                .restoreTraceMetaMessage(7L, "?TRACE64?" + b64, LocalDateTime.of(2026, 5, 30, 12, 0), true)
                .orElseThrow();

        assertEquals(7L, dto.turnId());
        assertEquals("system", dto.role());
        assertTrue(dto.content().contains("trace"));
    }

    @Test
    void legacyTraceHtmlPayloadsRestoreAsSummariesNotRawHtml() {
        String rawHtml = "<section>private restored trace html ownerToken=legacy-secret</section>";
        String b64 = Base64.getEncoder().encodeToString(rawHtml.getBytes(StandardCharsets.UTF_8));

        for (String content : List.of("?TRACE?" + rawHtml, "?TRACE64?" + b64)) {
            ChatApiController.MessageDto dto = ChatApiController
                    .restoreTraceMetaMessage(8L, content, LocalDateTime.of(2026, 5, 30, 12, 1), true)
                    .orElseThrow();

            assertEquals("system", dto.role());
            assertTrue(dto.content().contains("traceHtml"));
            assertTrue(dto.content().contains("hash12"));
            assertFalse(dto.content().contains(rawHtml));
            assertFalse(dto.content().contains("private restored trace html"));
            assertFalse(dto.content().contains("ownerToken"));
        }
    }

    @Test
    void productionChatApiDoesNotWriteNewTrace64Messages() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/api/ChatApiController.java"))
                + Files.readString(Path.of("main/java/com/example/lms/api/ChatTraceSnapshotPointerPersister.java"));

        assertFalse(source.contains("TRACE_META_PREFIX_B64 +"),
                "TRACE64 must remain legacy read-only compatibility, not a new writer");
        assertFalse(source.contains("String.format(\"%s%s\", TRACE_META_PREFIX_B64"),
                "TRACE64 must not be persisted through formatted system messages");
        assertFalse(source.contains("Base64.getEncoder().encodeToString"),
                "trace system messages must use snapshot ids, not new base64 payloads");
        assertTrue(source.contains("TRACE_SNAPSHOT_META_PREFIX + snapshotId"));
    }

    @Test
    void streamCompleteTransformerReusesFinalTraceMetadata() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/api/ChatApiController.java"));
        int completeSignal = source.indexOf("ChatStreamEvent.StatusSignal completeSignal = ChatStreamEvent.StatusSignal.of(");
        int transformer = source.indexOf("ChatStreamSignalBuilder.buildTransformerBlocks(", completeSignal);
        int transformerEnd = source.indexOf("))));", transformer);

        assertTrue(completeSignal >= 0, "stream complete status emission should be locatable");
        assertTrue(transformer > completeSignal, "stream complete transformer emission should follow complete status");
        assertTrue(transformerEnd > transformer, "stream complete transformer call should be bounded");
        String call = source.substring(transformer, transformerEnd);

        assertTrue(source.contains("java.util.Map<String, Object> finalTransformerMeta = java.util.Map.of();"),
                "stream path should preserve the last redacted trace metadata for final UI status");
        assertTrue(call.contains("finalTransformerMeta,"),
                "complete transformer emission must not overwrite rich LLM/DPP/CFVM/Supabase debug state with empty metadata");
        assertFalse(call.contains("java.util.Map.of(),"),
                "empty metadata at completion can collapse rich transformer rail back to default blocks");
    }

    @Test
    void streamHarmonyMetadataIsCopiedForTraceSnapshotPersistence() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/api/ChatApiController.java"));
        int harmony = source.indexOf("ChatHarmonyTracePostprocessor.enrich(extraMeta, persistableFinalText, answerModeFinal);");
        int snapshotCopy = source.indexOf("traceMetaForSnapshot = new java.util.LinkedHashMap<>(extraMeta);", harmony);
        int persist = source.indexOf("ChatTraceSnapshotPointerPersister.persist(", harmony);

        assertTrue(harmony >= 0, "stream final path should enrich chat harmony metadata");
        assertTrue(snapshotCopy > harmony,
                "stream final path should copy harmony-enriched metadata even when traceHtml is absent");
        assertTrue(persist > snapshotCopy,
                "stream snapshot persistence should receive the harmony-enriched metadata copy");
    }

    @Test
    void streamAndSyncShapeVisibleAnswerBeforeHarmonyAndPersistence() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/api/ChatApiController.java"));

        int streamShape = source.indexOf(
                "semanticFinalText = ChatHarmonyTracePostprocessor.shapeAnswerForUserInstruction(");
        int streamHarmony = source.indexOf(
                "ChatHarmonyTracePostprocessor.enrich(extraMeta, persistableFinalText, answerModeFinal);",
                streamShape);
        int streamPersist = source.indexOf(
                "persistenceSessionId, \"assistant\", persistableFinalText", streamShape);
        int syncShape = source.indexOf(
                "semanticFinalContent = ChatHarmonyTracePostprocessor.shapeAnswerForUserInstruction(");
        int syncPersist = source.indexOf("Long assistantMessageId = historyService.appendMessageReturningId(",
                syncShape);
        int syncHarmony = source.indexOf(
                "ChatHarmonyTracePostprocessor.enrich(extraMeta, persistableFinalContent, answerModeFinal);",
                syncShape);

        assertTrue(streamShape > 0 && streamHarmony > streamShape && streamPersist > streamShape,
                "stream UI, harmony metadata, and persistence must share the shaped visible answer");
        assertTrue(syncShape > 0 && syncPersist > syncShape && syncHarmony > syncShape,
                "sync persistence and harmony metadata must share the shaped visible answer");
    }

    @Test
    void finalChatMetadataSamplesDebugAiMatrixBeforeHarmonyTrace() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/api/ChatApiController.java"));

        assertTrue(source.contains("private com.example.lms.debug.ai.DebugAiMetricsService debugAiMetricsService;"),
                "Chat final metadata should optionally use the existing DebugAiMetricsService");
        assertTrue(source.contains("debugAiMetricsService.compactSnapshot(80, 300_000L);"),
                "Chat final metadata should generate the compact virtual-matrix TraceStore breadcrumbs");

        int streamMatrix = source.indexOf("attachDebugAiMatrixTrace(extraMeta, \"stream.final\");");
        int streamHarmony = source.indexOf("ChatHarmonyTracePostprocessor.enrich(extraMeta, persistableFinalText, answerModeFinal);");
        int syncMatrix = source.indexOf("attachDebugAiMatrixTrace(extraMeta, \"sync.final\");");
        int syncHarmony = source.indexOf("ChatHarmonyTracePostprocessor.enrich(extraMeta, persistableFinalContent, answerModeFinal);");

        assertTrue(streamMatrix >= 0 && streamMatrix < streamHarmony,
                "stream final path should attach matrix breadcrumbs before harmony postprocess copies metadata");
        assertTrue(syncMatrix >= 0 && syncMatrix < syncHarmony,
                "sync final path should attach matrix breadcrumbs before harmony postprocess copies metadata");
        assertTrue(source.contains("logSuppressed(stage + \".debugAiMatrixTrace\");"),
                "matrix sampling failures should leave a stable suppressed breadcrumb");
    }

    @Test
    void streamDebugFxPayloadCarriesLocalLlmOperatorActionLabelsWithoutRawPayloads() throws Exception {
        ChatStreamEvent event = ChatApiController.buildDebugFxEvent(
                java.util.Map.ofEntries(
                        java.util.Map.entry("dbg.copilot.causes", java.util.List.of(java.util.Map.of(
                                "id", "llm_health_pressure",
                                "score", 1.0d,
                                "title", "ownerToken=raw should not surface"))),
                        java.util.Map.entry("llm.localSmoke.operatorAction.triggerReason", "threshold_exceeded"),
                        java.util.Map.entry("llm.localSmoke.operatorAction.failureClass", "model_blank"),
                        java.util.Map.entry("llm.localSmoke.operatorAction.nextAction", "prefer_native_ollama_route"),
                        java.util.Map.entry("llm.localSmoke.operatorAction.actionScore", 100),
                        java.util.Map.entry("llm.localSmoke.operatorAction.scoreDelta", 85),
                        java.util.Map.entry("llm.client.rawPrompt", "Authorization=private-token should not surface"),
                        java.util.Map.entry("llm.client.rawModel", "qwen3:8b-private-owner-token")),
                null,
                ChatStreamSignalBuilder.buildPipelineSnapshot(
                        java.util.Map.of("answer.mode", "FALLBACK_EVIDENCE"),
                        "FALLBACK_EVIDENCE",
                        null,
                        null));

        assertNotNull(event);
        assertEquals("debug_fx", event.type());
        assertNotNull(event.debugFxSignal());
        assertEquals("llm_health_pressure", event.debugFxSignal().code());
        assertEquals("threshold_exceeded", event.debugFxSignal().labels().get("localLlmTriggerReason"));
        assertEquals("model_blank", event.debugFxSignal().labels().get("localLlmFailureClass"));
        assertEquals("prefer_native_ollama_route", event.debugFxSignal().labels().get("localLlmNextAction"));
        assertEquals("100", event.debugFxSignal().labels().get("localLlmActionScore"));
        assertEquals("85", event.debugFxSignal().labels().get("localLlmScoreDelta"));

        String json = new ObjectMapper().writeValueAsString(event);
        assertTrue(json.contains("\"type\":\"debug_fx\""));
        assertTrue(json.contains("\"localLlmTriggerReason\":\"threshold_exceeded\""));
        assertTrue(json.contains("\"localLlmFailureClass\":\"model_blank\""));
        assertTrue(json.contains("\"localLlmNextAction\":\"prefer_native_ollama_route\""));
        assertFalse(json.contains("private-token"), json);
        assertFalse(json.contains("Authorization"), json);
        assertFalse(json.contains("qwen3:8b"), json);
        assertFalse(json.contains("ownerToken=raw"), json);
    }

    @Test
    @SuppressWarnings("unchecked")
    void coherentVerificationFailSoftFeedsSameTurnMatrixAndDebugFxLabels() throws Throwable {
        TraceStore.clear();
        try {
            TraceStore.put("factStatusClassifier.judge.path", "judgeChatModel");
            TraceStore.put("factStatusClassifier.judge.disabledReason", "judge_call_failed");
            TraceStore.put("rawPrompt", "Authorization=private-token must not surface");
            StageBoundaryBreadcrumbs.recordFromCurrentTrace("final");
            Map<String, Object> meta = new LinkedHashMap<>(TraceStore.getAll());

            DebugEventStore store = enabledDebugEventStore();
            DebugEventTracePromotionService promotion = new DebugEventTracePromotionService(store);
            promotion.promoteStageBoundaryBreadcrumbsOnly(
                    "final", meta, "ChatApiController.stream.final");

            DebugAiMetricsService metrics = new DebugAiMetricsService(store);
            Map<String, Object> compact = metrics.compactSnapshot(80, 300_000L);
            DebugAiRawTile verificationTile = ((List<DebugAiRawTile>) compact.get("tiles")).stream()
                    .filter(tile -> "VERIFICATION_BUILD".equals(tile.tileName()))
                    .findFirst()
                    .orElseThrow();
            assertEquals(1L, verificationTile.eventCount());
            assertEquals(1L, verificationTile.warnCount());
            assertEquals("catch", verificationTile.topFailureClass());
            assertTrue(((List<Map<String, Object>>) compact.get("virtualMatrixHotChunks")).stream()
                    .anyMatch(row -> "VERIFICATION_BUILD".equals(row.get("dominantTile"))));

            MethodHandles.privateLookupIn(ChatApiController.class, MethodHandles.lookup())
                    .findStatic(
                            ChatApiController.class,
                            "mirrorDebugAiMetricsCompact",
                            MethodType.methodType(void.class, Map.class, Map.class))
                    .invoke(meta, compact);

            ChatStreamEvent.DebugFxSignal signal = ChatStreamSignalBuilder.buildDebugFxSignal(
                    meta,
                    null,
                    ChatStreamSignalBuilder.buildPipelineSnapshot(meta, null, null, null));
            ChatStreamEvent event = ChatStreamEvent.debugFx(signal);

            assertNotNull(event);
            assertNotNull(event.debugFxSignal());
            assertEquals("300", event.debugFxSignal().labels().get("debugAiMatrixCount"));
            assertEquals("30", event.debugFxSignal().labels().get("debugAiMatrixChunkCount"));
            assertEquals("fail_soft", event.debugFxSignal().labels().get("verificationStatus"));
            assertEquals("catch", event.debugFxSignal().labels().get("verificationFailureClass"));
            assertEquals("judge_call_failed", event.debugFxSignal().labels().get("verificationReason"));

            String json = new ObjectMapper().writeValueAsString(event);
            assertFalse(json.contains("private-token"), json);
            assertFalse(json.contains("Authorization"), json);
            assertFalse(json.contains("rawPrompt"), json);
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void successfulLocalModelAnswerClearsStaleLocalLlmOperatorAction() throws Exception {
        Method method = ChatApiController.class.getDeclaredMethod(
                "clearLocalLlmOperatorActionAfterVisibleSuccess",
                java.util.Map.class,
                String.class,
                String.class);
        method.setAccessible(true);
        java.util.Map<String, Object> meta = new java.util.LinkedHashMap<>();
        meta.put("llm.localSmoke.operatorAction.triggered", true);
        meta.put("llm.localSmoke.operatorAction.failureClass", "model_blank");
        meta.put("llm.localSmoke.operatorAction.nextAction", "inspect_ollama_runtime_capacity");
        meta.put("llm.localSmoke.operatorAction.actionScore", 20);

        method.invoke(null, meta, "real qwen answer", "qwen3:8b");

        assertEquals(Boolean.FALSE, meta.get("llm.localSmoke.operatorAction.triggered"));
        assertEquals("none", meta.get("llm.localSmoke.operatorAction.failureClass"));
        assertEquals("none", meta.get("llm.localSmoke.operatorAction.nextAction"));
        assertEquals(0, meta.get("llm.localSmoke.operatorAction.actionScore"));
        assertEquals("native_success", meta.get("llm.localSmoke.operatorAction.triggerReason"));
    }

    @Test
    void fallbackAnswerKeepsLocalLlmOperatorActionVisible() throws Exception {
        Method method = ChatApiController.class.getDeclaredMethod(
                "clearLocalLlmOperatorActionAfterVisibleSuccess",
                java.util.Map.class,
                String.class,
                String.class);
        method.setAccessible(true);
        java.util.Map<String, Object> meta = new java.util.LinkedHashMap<>();
        meta.put("llm.localSmoke.operatorAction.failureClass", "model_blank");

        method.invoke(null, meta, "fallback", "qwen3:8b:fallback:local-lite");

        assertEquals("model_blank", meta.get("llm.localSmoke.operatorAction.failureClass"));
    }

    @Test
    void observedRequestFallbackKeepsOperatorActionWhenModelNameLooksNative() throws Exception {
        Method method = ChatApiController.class.getDeclaredMethod(
                "clearLocalLlmOperatorActionAfterVisibleSuccess",
                java.util.Map.class,
                String.class,
                String.class);
        method.setAccessible(true);
        java.util.Map<String, Object> meta = new java.util.LinkedHashMap<>();
        meta.put("llm.localSmoke.operatorAction.triggered", true);
        meta.put("llm.localSmoke.operatorAction.failureClass", "vram_oom");
        meta.put("llm.localSmoke.operatorAction.nextAction", "use_local_device_fallback");
        meta.put(ChatStreamSignalBuilder.REQUEST_ATTEMPT_SUMMARY_KEY, List.of(
                Map.of(
                        "lane", "primary",
                        "outcome", "failed",
                        "failureClass", "vram_oom",
                        "terminal", "error",
                        "attemptObserved", true,
                        "trusted", true),
                Map.of(
                        "lane", "fallback",
                        "outcome", "success",
                        "failureClass", "none",
                        "terminal", "success",
                        "attemptObserved", true,
                        "deliveryObserved", true,
                        "trusted", true)));

        method.invoke(null, meta, "recovered answer", "qwen3:8b");

        assertEquals(Boolean.TRUE, meta.get("llm.localSmoke.operatorAction.triggered"));
        assertEquals("vram_oom", meta.get("llm.localSmoke.operatorAction.failureClass"));
        assertEquals("use_local_device_fallback", meta.get("llm.localSmoke.operatorAction.nextAction"));
    }

    @Test
    void controllerHasNoGlobalLocalSuccessShortcut() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/api/ChatApiController.java"));

        assertFalse(source.contains("recordVisibleLocalModelSuccess("));
        assertFalse(source.contains("ModelRuntimeHealthTracker.recordLocalSuccessSignal()"));
        assertTrue(source.contains("boolean syncPersistenceAccepted = assistantMessageId != null;"));
        assertTrue(source.contains("boolean syncResponseHandoffAccepted = response != null;"));
    }

    @Test
    void semanticVerificationPolicyRequiresAnExactAllowlistedReleasePair() throws Exception {
        Method method = ChatApiController.class.getDeclaredMethod(
                "semanticVerificationPolicy", java.util.Map.class);
        method.setAccessible(true);

        assertEquals(
                ModelRuntimeHealthTracker.VerificationPolicy.NOT_REQUIRED,
                method.invoke(null, java.util.Map.of(
                        "finalAnswer.releaseStatus", "NOT_REQUIRED",
                        "finalAnswer.releaseReason", "verification_not_required")));
        assertEquals(
                ModelRuntimeHealthTracker.VerificationPolicy.REQUIRED,
                method.invoke(null, java.util.Map.of(
                        "finalAnswer.releaseStatus", "APPROVE",
                        "finalAnswer.releaseReason", "verification_accepted")));
        assertNull(method.invoke(null, java.util.Map.of(
                "finalAnswer.releaseStatus", "NOT_REQUIRED")));
        assertNull(method.invoke(null, java.util.Map.of(
                "finalAnswer.releaseStatus", "APPROVE",
                "finalAnswer.releaseReason", "verification_not_required")));
        assertNull(method.invoke(null, new Object[] { null }));
    }

    @Test
    void semanticAnswerEligibilityAllowsARealRemoteModelButRejectsSyntheticStates() throws Exception {
        Method method = ChatApiController.class.getDeclaredMethod(
                "isEligibleSemanticModelAnswer", String.class, String.class);
        method.setAccessible(true);

        assertEquals(true, method.invoke(null, "visible remote answer", "gpt-5.5-pro"));
        assertEquals(false, method.invoke(null, "", "gpt-5.5-pro"));
        assertEquals(false, method.invoke(null, "fallback answer", "gpt-5.5-pro:fallback:evidence"));
        assertEquals(false, method.invoke(null, "cancelled answer", "qwen3:8b:cancelled"));
        assertEquals(false, method.invoke(null, "embedding output", "qwen3-embedding:4b"));
    }

    @Test
    void controllerSemanticBoundaryPromotesOnlyAnExplicitAcceptedFinalOutcome() throws Exception {
        Method method = ChatApiController.class.getDeclaredMethod(
                "recordVisibleModelSemanticOutcome",
                java.util.Map.class,
                String.class,
                String.class,
                String.class,
                ModelRuntimeHealthTracker.SemanticTerminalState.class,
                boolean.class,
                boolean.class,
                boolean.class);
        method.setAccessible(true);

        ModelRuntimeHealthTracker tracker = new ModelRuntimeHealthTracker();
        String acceptedTimeline = tracker.beginRequestTimeline("accepted-request", "accepted-session");
        tracker.recordRequestPhase(acceptedTimeline, "dispatch", "model-a", null, "none");
        tracker.recordRequestPhase(
                acceptedTimeline, "pending", "model-a", "https://accepted.example.test/v1", "none");
        tracker.recordRequestSelection(
                acceptedTimeline,
                "router",
                "openai",
                "accepted-route",
                "model-a",
                "https://accepted.example.test/v1",
                "openai_chat_completions",
                false,
                false);
        ModelRuntimeHealthTracker.RouteHealthKey acceptedRoute = tracker
                .requestRouteHealthKey(acceptedTimeline)
                .orElseThrow();
        ChatApiController controller = org.mockito.Mockito.mock(
                ChatApiController.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(controller, "modelRuntimeHealthTracker", tracker);

        method.invoke(
                controller,
                java.util.Map.of(
                        "finalAnswer.releaseStatus", "NOT_REQUIRED",
                        "finalAnswer.releaseReason", "verification_not_required",
                        "finalAnswer.releaseAllowed", true,
                        "finalAnswer.verificationAcceptedForMemory", false),
                "visible answer",
                "model-a",
                acceptedTimeline,
                ModelRuntimeHealthTracker.SemanticTerminalState.COMPLETED,
                true,
                true,
                false);

        assertTrue(tracker.isPromotable(acceptedRoute));

        String unknownTimeline = tracker.beginRequestTimeline("unknown-request", "unknown-session");
        tracker.recordRequestPhase(unknownTimeline, "dispatch", "model-b", null, "none");
        tracker.recordRequestPhase(
                unknownTimeline, "pending", "model-b", "https://unknown.example.test/v1", "none");
        tracker.recordRequestSelection(
                unknownTimeline,
                "router",
                "openai",
                "unknown-route",
                "model-b",
                "https://unknown.example.test/v1",
                "openai_chat_completions",
                false,
                false);
        ModelRuntimeHealthTracker.RouteHealthKey unknownRoute = tracker
                .requestRouteHealthKey(unknownTimeline)
                .orElseThrow();

        method.invoke(
                controller,
                java.util.Map.of("finalAnswer.releaseAllowed", true),
                "visible answer",
                "model-b",
                unknownTimeline,
                ModelRuntimeHealthTracker.SemanticTerminalState.COMPLETED,
                true,
                true,
                false);

        assertFalse(tracker.isPromotable(unknownRoute));
        assertTrue(tracker.snapshot(unknownRoute).isEmpty());

        String rejectedTimeline = tracker.beginRequestTimeline("rejected-request", "rejected-session");
        tracker.recordRequestPhase(rejectedTimeline, "dispatch", "model-c", null, "none");
        tracker.recordRequestPhase(
                rejectedTimeline, "pending", "model-c", "https://rejected.example.test/v1", "none");
        tracker.recordRequestSelection(
                rejectedTimeline,
                "router",
                "openai",
                "rejected-route",
                "model-c",
                "https://rejected.example.test/v1",
                "openai_chat_completions",
                false,
                false);
        ModelRuntimeHealthTracker.RouteHealthKey rejectedRoute = tracker
                .requestRouteHealthKey(rejectedTimeline)
                .orElseThrow();

        method.invoke(
                controller,
                java.util.Map.of(
                        "finalAnswer.releaseStatus", "NOT_REQUIRED",
                        "finalAnswer.releaseReason", "verification_not_required",
                        "finalAnswer.releaseAllowed", true,
                        "finalAnswer.verificationAcceptedForMemory", false),
                "visible answer",
                "model-c",
                rejectedTimeline,
                ModelRuntimeHealthTracker.SemanticTerminalState.COMPLETED,
                false,
                true,
                false);

        assertFalse(tracker.isPromotable(rejectedRoute));
        assertTrue(tracker.snapshot(rejectedRoute).isEmpty());
    }

    @Test
    void remoteAnswerKeepsLocalLlmOperatorActionVisible() throws Exception {
        Method method = ChatApiController.class.getDeclaredMethod(
                "clearLocalLlmOperatorActionAfterVisibleSuccess",
                java.util.Map.class,
                String.class,
                String.class);
        method.setAccessible(true);
        java.util.Map<String, Object> meta = new java.util.LinkedHashMap<>();
        meta.put("llm.localSmoke.operatorAction.failureClass", "model_blank");

        method.invoke(null, meta, "remote answer", "qwen/qwen3-32b");

        assertEquals("model_blank", meta.get("llm.localSmoke.operatorAction.failureClass"));
    }

    @Test
    void successfulLocalModelClearRunsAfterHarmonyBeforeDebugFxBuild() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/api/ChatApiController.java"));

        int streamChatCall = source.indexOf("ChatResult result = chatService.continueChat(dtoForCall, __webSupplier);");
        int streamCancelGuard = source.indexOf(
                "if (streamCancelledFinal || isStreamRunCancelled(runContextRef.get(), finalSessionId))",
                streamChatCall);
        int streamFallback = source.indexOf("semanticFinalText = emptyFinalTextFallback(dto.getMessage());", streamChatCall);
        int streamHarmony = source.indexOf("ChatHarmonyTracePostprocessor.enrich(extraMeta, persistableFinalText, answerModeFinal);");
        int streamFinalCancelGuard = source.indexOf(
                "if (isStreamRunCancelled(runContextRef.get(), finalSessionId))",
                streamHarmony);
        int streamAppend = source.indexOf(
                "persistenceSessionId, \"assistant\", persistableFinalText", streamFinalCancelGuard);
        int streamFinalEmit = source.indexOf(
                "Sinks.EmitResult finalEmitResult = emitFinalStreamEvent(",
                streamAppend);
        int streamRecord = source.indexOf(
                "recordVisibleModelSemanticOutcome(",
                streamFinalEmit);
        int streamTerminal = source.indexOf(
                "recordModelRequestTerminal(",
                streamRecord);
        int streamClear = source.indexOf(
                "clearLocalLlmOperatorActionAfterVisibleSuccess(extraMeta, persistableFinalText, modelUsedFinal);",
                streamHarmony);
        int streamClearCancelGuard = source.lastIndexOf(
                "if (!isStreamRunCancelled(runContextRef.get(), finalSessionId)",
                streamClear);
        int streamPromote = source.indexOf("promoteDebugEvents(\"final\", extraMeta, \"ChatApiController.stream.final\");",
                streamHarmony);
        int streamDebugFx = source.indexOf("buildDebugFxEvent(extraMeta, finalTraceSignal, finalPipelineSnapshot);",
                streamHarmony);

        int syncResponse = source.indexOf("ChatResponseDto response = new ChatResponseDto(");
        int syncRecord = source.indexOf("recordVisibleModelSemanticOutcome(", syncResponse);
        int syncTerminal = source.indexOf("recordModelRequestTerminal(", syncRecord);
        int syncHarmony = source.indexOf("ChatHarmonyTracePostprocessor.enrich(extraMeta, persistableFinalContent, answerModeFinal);");
        int syncClear = source.indexOf(
                "clearLocalLlmOperatorActionAfterVisibleSuccess(extraMeta, persistableFinalContent, modelUsedFinal);",
                syncHarmony);
        int syncPromote = source.indexOf("promoteDebugEvents(\"final\", extraMeta, \"ChatApiController.sync.final\");",
                syncHarmony);

        assertTrue(streamChatCall >= 0, "stream path should call the workflow");
        assertTrue(streamCancelGuard > streamChatCall && streamFallback > streamCancelGuard,
                "stream fallback synthesis should happen only after the first cancellation guard");
        assertTrue(streamHarmony >= 0, "stream final path should enrich harmony metadata");
        assertTrue(streamClearCancelGuard > streamHarmony && streamClearCancelGuard < streamClear,
                "stream warning clear should re-check cancellation after metadata processing");
        assertTrue(streamClear > streamHarmony,
                "stream final path should clear stale local LLM operator action after harmony postprocess");
        assertTrue(streamClear < streamPromote,
                "stream final path should clear stale local LLM operator action before debug event promotion");
        assertTrue(streamClear < streamDebugFx,
                "stream final path should clear stale local LLM operator action before Debug FX is built");
        assertTrue(streamFinalCancelGuard > streamHarmony && streamAppend > streamFinalCancelGuard,
                "stream persistence should follow the final cancellation guard");
        assertTrue(streamFinalEmit > streamAppend && streamRecord > streamFinalEmit && streamRecord < streamTerminal,
                "stream semantic outcome should be evaluated after final emit and before terminal recording");
        assertTrue(syncResponse > syncHarmony && syncRecord > syncResponse && syncRecord < syncTerminal,
                "sync semantic success should be evaluated only after response assembly and before terminal recording");
        assertTrue(syncHarmony >= 0, "sync final path should enrich harmony metadata");
        assertTrue(syncClear > syncHarmony,
                "sync final path should clear stale local LLM operator action after harmony postprocess");
        assertTrue(syncClear < syncPromote,
                "sync final path should clear stale local LLM operator action before debug event promotion");
    }

    @Test
    void traceMemoryCompactMirrorCarriesVirtualCheckpointIntoDebugFxLabels() throws Exception {
        TraceStore.clear();
        try {
            Method method = ChatApiController.class.getDeclaredMethod(
                    "mirrorDebugAiTraceMemoryCompact", java.util.Map.class, java.util.Map.class);
            method.setAccessible(true);
            java.util.Map<String, Object> meta = new java.util.LinkedHashMap<>();
            java.util.Map<String, Object> compact = java.util.Map.of(
                    "traceMemoryDiagnostics", java.util.Map.of(
                            "routeDecision", "retry_failsoft_degrade_warn_live_failsoft",
                            "cfvmOffered", true,
                            "cfvmPatternId", "1265531216",
                            "virtualCheckpointKey", "traceMemory.virtualCheckpoint.load",
                            "virtualCheckpointStage", "load",
                            "virtualCheckpointPhase", "agent_visible_debug_evidence"));

            method.invoke(null, meta, compact);

            assertEquals("traceMemory.virtualCheckpoint.load",
                    meta.get("prompt.agentDebugEvidence.traceMemory.virtualCheckpointLatestKey"));
            assertEquals("load", meta.get("debug.ai.agentDebugEvidence.traceMemory.virtualCheckpointLatestStage"));
            assertEquals("agent_visible_debug_evidence",
                    TraceStore.get("prompt.agentDebugEvidence.traceMemory.virtualCheckpointLatestPhase"));

            ChatStreamEvent event = ChatApiController.buildDebugFxEvent(meta, null,
                    ChatStreamSignalBuilder.buildPipelineSnapshot(
                            java.util.Map.of("answer.mode", "FALLBACK_EVIDENCE"),
                            "FALLBACK_EVIDENCE",
                            null,
                            null));

            assertNotNull(event);
            assertEquals("retry_failsoft_degrade_warn_live_failsoft",
                    event.debugFxSignal().labels().get("traceMemoryRouteDecision"));
            assertEquals("true", event.debugFxSignal().labels().get("traceMemoryCfvmOffered"));
            assertEquals("1265531216", event.debugFxSignal().labels().get("traceMemoryCfvmPatternId"));
            assertEquals("traceMemory.virtualCheckpoint.load",
                    event.debugFxSignal().labels().get("traceMemoryVirtualCheckpointKey"));
            assertEquals("load", event.debugFxSignal().labels().get("traceMemoryVirtualCheckpointStage"));
            assertEquals("agent_visible_debug_evidence",
                    event.debugFxSignal().labels().get("traceMemoryVirtualCheckpointPhase"));
        } finally {
            TraceStore.clear();
        }
    }

    @Test
    void streamCompletionEmitsDebugFxFromFinalTraceMetadata() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/api/ChatApiController.java"));
        int extraMeta = source.indexOf("java.util.Map<String, Object> extraMeta = TraceStore.getAll();");
        int debugFx = source.indexOf("ChatStreamEvent finalDebugFxEvent =", extraMeta);
        int debugFxCall = source.indexOf("buildDebugFxEvent(", debugFx);
        int emit = source.indexOf("sink.tryEmitNext(sse(finalDebugFxEvent));", debugFxCall);

        assertTrue(extraMeta >= 0, "stream completion must capture TraceStore metadata");
        assertTrue(debugFx > extraMeta, "debug_fx payload must be built after final TraceStore metadata is captured");
        assertTrue(debugFxCall > debugFx, "debug_fx payload must call the shared event builder");
        assertTrue(emit > debugFxCall, "debug_fx payload must be emitted as SSE after it is built");
        String window = source.substring(debugFxCall, Math.min(source.length(), emit + 80));
        assertTrue(window.contains("extraMeta,"));
        assertTrue(window.contains("finalTraceSignal,"));
        assertTrue(window.contains("finalPipelineSnapshot"));
        assertFalse(window.contains("java.util.Map.of(),"));
    }

    @Test
    void streamEmitsPreLlmDebugFxBeforeWaitingOnChatService() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/api/ChatApiController.java"));
        int waitStatus = source.indexOf(
                "emitDefaultModelWaitStatus(sink, __capturedBudget, __streamStartedNs);");
        int preLlm = source.indexOf("ChatStreamEvent preLlmDebugFxEvent =", waitStatus);
        int chatCall = source.indexOf("chatService.continueChat(dtoForCall, __webSupplier)", waitStatus);

        assertTrue(waitStatus >= 0, "LLM wait status emission should be locatable");
        assertTrue(preLlm > waitStatus, "pre-LLM debug_fx should be built after wait status is emitted");
        assertTrue(chatCall > preLlm, "pre-LLM debug_fx must be emitted before blocking chatService.continueChat");
        String window = source.substring(waitStatus, Math.min(source.length(), chatCall));
        assertTrue(window.contains("debugCopilotService.maybeEnrichTrace()"));
        assertTrue(window.contains("attachDebugAiMatrixTrace(preLlmMeta, \"stream.preLlm\")"),
                "pre-LLM debug_fx should include the agent-visible 300-matrix summary before chatService blocks");
        assertTrue(window.contains("buildDebugFxEvent(preLlmMeta,"));
        assertTrue(window.contains("sink.tryEmitNext(sse(preLlmDebugFxEvent));"));
        assertTrue(window.contains("stream.preLlmDebugFx"));
        assertFalse(window.contains("java.util.Map.of(),"));
    }

    @Test
    void streamCancellationAfterChatServiceBypassesFinalPersistence() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/api/ChatApiController.java"));

        int chatCall = source.indexOf("ChatResult result = chatService.continueChat(dtoForCall, __webSupplier);");
        int cancelGuard = source.indexOf(
                "isStreamRunCancelled(runContextRef.get(), finalSessionId)", chatCall);
        int appendAssistant = source.indexOf(
                "persistenceSessionId, \"assistant\", persistableFinalText", chatCall);

        assertTrue(chatCall > 0, "stream path should call chatService.continueChat");
        assertTrue(cancelGuard > chatCall,
                "stream path should re-check explicit Stop cancellation after chatService returns");
        assertTrue(cancelGuard < appendAssistant,
                "explicit Stop cancellation must bypass final token/history persistence");
        assertTrue(source.contains("emitStreamCancelledStatus(sink, __capturedBudget, __streamStartedNs)"),
                "stream cancellation should emit a bounded cancellation status");
        assertTrue(source.contains("committingRun.tryBeginTranscriptCommit()"),
                "stream persistence should use the exact run's atomic commit boundary");
    }

    @Test
    void syncPromotesPreLlmDebugEventBeforeWaitingOnChatService() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/api/ChatApiController.java"));
        int syncStart = source.indexOf("private ChatResponseDto handleChat(");
        int preLlm = source.indexOf("promoteDebugEvents(\"pre_llm\", preLlmMeta, \"ChatApiController.sync.preLlm\");",
                syncStart);
        int enrich = source.lastIndexOf("debugCopilotService.maybeEnrichTrace()", preLlm);
        int chatCall = source.indexOf("ChatResult result = chatService.continueChat(dtoForCall, __webSupplier);",
                syncStart);

        assertTrue(syncStart >= 0, "sync handleChat should be locatable");
        assertTrue(preLlm > syncStart, "sync pre-LLM DebugEvent promotion should be locatable");
        assertTrue(enrich > syncStart && enrich < preLlm, "sync pre-LLM promotion should use enriched TraceStore metadata");
        assertTrue(chatCall > preLlm, "sync pre-LLM DebugEvent promotion must happen before chatService.continueChat");
        String window = source.substring(enrich, Math.min(source.length(), chatCall));
        assertTrue(window.contains("TraceStore.getAll()"));
        assertTrue(window.contains("debugCopilotService.maybeEnrichTrace()"));
        assertTrue(window.contains("logSuppressed(\"sync.preLlmDebugEvent\")"));
        assertFalse(window.contains("java.util.Map.of(),"));
    }

    @Test
    void streamPreAndFinalSnapshotsRecordStageBoundaryBeforeTraceStoreGetAll() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/api/ChatApiController.java"));
        String preCall = "StageBoundaryBreadcrumbs.recordFromCurrentTrace(\"pre_llm\");";
        String finalCall = "StageBoundaryBreadcrumbs.recordFromCurrentTrace(\"final\");";
        int streamStart = source.indexOf(
                "emitDefaultModelWaitStatus(sink, __capturedBudget, __streamStartedNs);");
        int preHook = source.indexOf(preCall, streamStart);
        int preSnapshot = source.indexOf(
                "java.util.Map<String, Object> preLlmMeta = TraceStore.getAll();", streamStart);
        int chatCall = source.indexOf(
                "ChatResult result = chatService.continueChat(dtoForCall, __webSupplier);", streamStart);
        int emptyFinalFact = source.indexOf("tracePut(\"chatApi.emptyFinalText\", true);", chatCall);
        int finalHook = source.indexOf(finalCall, chatCall);
        int finalSnapshot = source.indexOf(
                "java.util.Map<String, Object> extraMeta = TraceStore.getAll();", chatCall);

        assertEquals(2, source.split(Pattern.quote(preCall), -1).length - 1,
                "exactly one pre-LLM hook should exist per stream/sync route");
        assertEquals(2, source.split(Pattern.quote(finalCall), -1).length - 1,
                "exactly one final hook should exist per stream/sync route");
        assertTrue(preHook > streamStart && preHook < preSnapshot,
                "stream pre-LLM breadcrumb must be recorded before its TraceStore snapshot");
        assertTrue(emptyFinalFact > chatCall && emptyFinalFact < finalHook,
                "stream final breadcrumb must observe the empty-final failure fact when it is recorded");
        assertTrue(finalHook > chatCall && finalHook < finalSnapshot,
                "stream final breadcrumb must be recorded before its TraceStore snapshot");
    }

    @Test
    void streamFinalProjectsAttemptSummaryBeforeBuildingTransformerBlocks() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/api/ChatApiController.java"));
        int chatCall = source.indexOf(
                "ChatResult result = chatService.continueChat(dtoForCall, __webSupplier);");
        int snapshot = source.indexOf(
                "java.util.Map<String, Object> extraMeta = TraceStore.getAll();", chatCall);
        int projection = source.indexOf(
                "attachModelRequestAttemptSummary(modelRuntimeHealthTracker, extraMeta, requestTimelineId);",
                snapshot);
        int transformer = source.indexOf("ChatStreamSignalBuilder.buildTransformerBlocks(", projection);

        assertTrue(chatCall >= 0 && snapshot > chatCall, "stream final metadata must follow the model call");
        assertTrue(projection > snapshot, "redacted attempt summary must be attached after the final TraceStore snapshot");
        assertTrue(transformer > projection, "attempt summary must be available before transformer status is built");
    }

    @Test
    void modelAttemptSummaryBridgeDropsLedgerIdentifiersAndPayloadProofHashes() throws Exception {
        ModelRuntimeHealthTracker tracker = org.mockito.Mockito.mock(ModelRuntimeHealthTracker.class);
        org.mockito.Mockito.when(tracker.redactedRequestAttemptLedger("timeline-internal"))
                .thenReturn(List.of(Map.ofEntries(
                        Map.entry("timelineId", "timeline-internal"),
                        Map.entry("requestHash", "request-private"),
                        Map.entry("sessionHash", "session-private"),
                        Map.entry("promptHash", "prompt-private"),
                        Map.entry("responseHash", "response-private"),
                        Map.entry("sequence", 1),
                        Map.entry("role", "fallback"),
                        Map.entry("outcome", "success"),
                        Map.entry("failureClass", "none"),
                        Map.entry("terminalClass", "success"),
                        Map.entry("elapsedMs", 21L),
                        Map.entry("modelAdapterAttemptObserved", true),
                        Map.entry("clientHttpExchangeObserved", false),
                        Map.entry("clientHttpResponseObserved", false),
                        Map.entry("providerAttemptObserved", false),
                        Map.entry("wireAttemptObserved", false),
                        Map.entry("responseObserved", true))));
        Map<String, Object> meta = new LinkedHashMap<>();

        ChatApiController.attachModelRequestAttemptSummary(tracker, meta, "timeline-internal");

        Object summary = meta.get(ChatStreamSignalBuilder.REQUEST_ATTEMPT_SUMMARY_KEY);
        assertNotNull(summary);
        String json = new ObjectMapper().writeValueAsString(summary);
        assertTrue(json.contains("\"lane\":\"fallback\""), json);
        assertTrue(json.contains("\"attemptObserved\":true"), json);
        assertTrue(json.contains("\"wireAttemptObserved\":false"), json);
        assertFalse(json.contains("private"), json);
        assertFalse(json.contains("timeline"), json);
        assertFalse(json.contains("requestHash"), json);
        assertFalse(json.contains("sessionHash"), json);
        assertFalse(json.contains("promptHash"), json);
        assertFalse(json.contains("responseHash"), json);
    }

    @Test
    void syncFinalProjectsAttemptSummaryBeforeTracePersistence() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/api/ChatApiController.java"));
        int syncStart = source.indexOf("private ChatResponseDto handleChat(");
        int chatCall = source.indexOf("ChatResult result = chatService.continueChat(dtoForCall, __webSupplier);", syncStart);
        int snapshot = source.indexOf("extraMeta = TraceStore.getAll();", chatCall);
        int projection = source.indexOf(
                "attachModelRequestAttemptSummary(modelRuntimeHealthTracker, extraMeta, requestTimelineId);",
                snapshot);
        int persistence = source.indexOf("ChatTraceSnapshotPointerPersister.persist(", snapshot);

        assertTrue(syncStart >= 0 && chatCall > syncStart && snapshot > chatCall,
                "sync final metadata must follow its model call");
        assertTrue(projection > snapshot, "sync final metadata must include the redacted attempt summary");
        assertTrue(persistence > projection, "sync attempt summary must be present before trace persistence");
    }

    @Test
    void syncPreAndFinalSnapshotsRecordStageBoundaryBeforeTraceStoreGetAll() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/api/ChatApiController.java"));
        int syncStart = source.indexOf("private ChatResponseDto handleChat(");
        int preHook = source.indexOf(
                "StageBoundaryBreadcrumbs.recordFromCurrentTrace(\"pre_llm\");", syncStart);
        int preSnapshot = source.indexOf(
                "java.util.Map<String, Object> preLlmMeta = TraceStore.getAll();", syncStart);
        int chatCall = source.indexOf(
                "ChatResult result = chatService.continueChat(dtoForCall, __webSupplier);", syncStart);
        int finalHook = source.indexOf(
                "StageBoundaryBreadcrumbs.recordFromCurrentTrace(\"final\");", chatCall);
        int finalSnapshot = source.indexOf("extraMeta = TraceStore.getAll();", chatCall);

        assertTrue(preHook > syncStart && preHook < preSnapshot,
                "sync pre-LLM breadcrumb must be recorded before its TraceStore snapshot");
        assertTrue(finalHook > chatCall && finalHook < finalSnapshot,
                "sync final breadcrumb must be recorded before its TraceStore snapshot");
    }

    @Test
    void streamPreLlmPromotesBreadcrumbBeforeSameTurnMatrixSnapshot() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/api/ChatApiController.java"));
        int streamStart = source.indexOf(
                "emitDefaultModelWaitStatus(sink, __capturedBudget, __streamStartedNs);");
        int snapshot = source.indexOf(
                "java.util.Map<String, Object> preLlmMeta = TraceStore.getAll();", streamStart);
        int boundaryPromotion = source.indexOf(
                "promoteStageBoundaryDebugEvents(\"pre_llm\", preLlmMeta, \"ChatApiController.stream.preLlm\");",
                snapshot);
        int fullPromotion = source.indexOf(
                "promoteDebugEvents(\"pre_llm\", preLlmMeta, \"ChatApiController.stream.preLlm\");", snapshot);
        int matrix = source.indexOf(
                "attachDebugAiMatrixTrace(preLlmMeta, \"stream.preLlm\");", snapshot);
        int debugFx = source.indexOf("ChatStreamEvent preLlmDebugFxEvent =", snapshot);

        assertTrue(snapshot > streamStart, "stream pre-LLM TraceStore snapshot must be locatable");
        assertTrue(boundaryPromotion > snapshot && boundaryPromotion < matrix,
                "stream pre-LLM boundary-only promotion must precede the same-turn AI matrix snapshot");
        assertTrue(matrix < fullPromotion && fullPromotion < debugFx,
                "stream pre-LLM full promotion must retain its existing post-matrix semantics");
    }

    @Test
    void streamFinalPromotesBreadcrumbBeforeSameTurnMatrixSnapshot() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/api/ChatApiController.java"));
        int chatCall = source.indexOf("ChatResult result = chatService.continueChat(dtoForCall, __webSupplier);");
        int snapshot = source.indexOf(
                "java.util.Map<String, Object> extraMeta = TraceStore.getAll();", chatCall);
        int boundaryPromotion = source.indexOf(
                "promoteStageBoundaryDebugEvents(\"final\", extraMeta, \"ChatApiController.stream.final\");",
                snapshot);
        int fullPromotion = source.indexOf(
                "promoteDebugEvents(\"final\", extraMeta, \"ChatApiController.stream.final\");", snapshot);
        int matrix = source.indexOf(
                "attachDebugAiMatrixTrace(extraMeta, \"stream.final\");", snapshot);
        int harmony = source.indexOf("ChatHarmonyTracePostprocessor.enrich(extraMeta", snapshot);
        int visibleSuccessClear = source.indexOf(
                "clearLocalLlmOperatorActionAfterVisibleSuccess(extraMeta", harmony);
        int debugFx = source.indexOf("ChatStreamEvent finalDebugFxEvent =", snapshot);

        assertTrue(snapshot > chatCall, "stream final TraceStore snapshot must follow the chat result");
        assertTrue(boundaryPromotion > snapshot && boundaryPromotion < matrix,
                "stream final boundary-only promotion must precede the same-turn AI matrix snapshot");
        assertTrue(matrix < harmony && harmony < visibleSuccessClear && visibleSuccessClear < fullPromotion,
                "stream final matrix must feed Harmony while full promotion remains after stale-action cleanup");
        assertTrue(fullPromotion < debugFx,
                "stream final full promotion must complete before its Debug FX payload is built");
    }

    @Test
    void syncFinalPromotesBreadcrumbBeforeSameTurnMatrixSnapshot() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/api/ChatApiController.java"));
        int syncStart = source.indexOf("private ChatResponseDto handleChat(");
        int chatCall = source.indexOf(
                "ChatResult result = chatService.continueChat(dtoForCall, __webSupplier);", syncStart);
        int snapshot = source.indexOf("extraMeta = TraceStore.getAll();", chatCall);
        int boundaryPromotion = source.indexOf(
                "promoteStageBoundaryDebugEvents(\"final\", extraMeta, \"ChatApiController.sync.final\");",
                snapshot);
        int fullPromotion = source.indexOf(
                "promoteDebugEvents(\"final\", extraMeta, \"ChatApiController.sync.final\");", snapshot);
        int matrix = source.indexOf(
                "attachDebugAiMatrixTrace(extraMeta, \"sync.final\");", snapshot);
        int harmony = source.indexOf("ChatHarmonyTracePostprocessor.enrich(extraMeta", snapshot);
        int visibleSuccessClear = source.indexOf(
                "clearLocalLlmOperatorActionAfterVisibleSuccess(extraMeta", harmony);
        int traceClear = source.indexOf("TraceStore.clear();", fullPromotion);

        assertTrue(snapshot > chatCall, "sync final TraceStore snapshot must follow the chat result");
        assertTrue(boundaryPromotion > snapshot && boundaryPromotion < matrix,
                "sync final boundary-only promotion must precede the same-turn AI matrix snapshot");
        assertTrue(matrix < harmony && harmony < visibleSuccessClear && visibleSuccessClear < fullPromotion,
                "sync final matrix must feed Harmony while full promotion remains after stale-action cleanup");
        assertTrue(fullPromotion < traceClear,
                "sync final full promotion must complete before request-local TraceStore cleanup");
    }

    @Test
    void streamDefaultModelWaitTransformerCarriesWaitMetadata() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/api/ChatApiController.java"));
        int waitSignal = source.indexOf("ChatStreamEvent.StatusSignal waitSignal = ChatStreamEvent.StatusSignal.of(");
        int transformer = source.indexOf("ChatStreamSignalBuilder.buildTransformerBlocks(", waitSignal);
        int transformerEnd = source.indexOf("))));", transformer);

        assertTrue(waitSignal >= 0, "default model wait status emission should be locatable");
        assertTrue(transformer > waitSignal, "default model wait transformer emission should follow wait status");
        assertTrue(transformerEnd > transformer, "default model wait transformer call should be bounded");
        String call = source.substring(transformer, transformerEnd);

        assertTrue(source.contains("java.util.Map<String, Object> waitTransformerMeta = java.util.Map.of("),
                "default model wait path should expose a redacted UI metadata seam");
        assertTrue(source.contains("\"llm.defaultModel.waitStatus\""),
                "wait metadata should use an LLM-scoped diagnostic key");
        assertTrue(call.contains("waitTransformerMeta,"),
                "wait transformer emission must carry model wait metadata into the UI rail");
        assertFalse(call.contains("java.util.Map.of(),"),
                "empty metadata during model wait keeps the UI from showing why the model is pending");
    }

    @Test
    void syncChatResponseReturnsTraceTurnIdInDto() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/api/ChatApiController.java"));

        assertTrue(Pattern.compile("new\\s+ChatResponseDto\\(\\s*visibleFinalContent,\\s*completedSession\\.getId\\(\\),\\s*modelUsedFinal,\\s*result\\.ragUsed\\(\\),\\s*answerModeFinal,\\s*traceTurnId,\\s*learningContextMeta,\\s*result\\.evidenceMetadata\\(\\),\\s*syncPipelineSnapshot,\\s*syncSelectionEntropy\\s*\\)")
                        .matcher(source)
                        .find(),
                "sync /api/chat response must carry traceTurnId and pipelineSnapshot so frontend can open the current trace immediately");
    }

    @Test
    void streamDebugStatusAndThoughtMessagesDoNotExposeMojibakePlaceholders() throws IOException {
        String broken = "broken status * ... *&#47;";

        assertEquals("debug stream update", ChatStreamEvent.status(broken).data());
        assertEquals("debug stream update", ChatStreamEvent.thought(broken).data());
        assertEquals("Running web search", ChatStreamEvent.status("Running web search").data());
    }

    @Test
    void stateTraceHtmlUsesSummaryBoundary() throws IOException {
        String controller = Files.readString(Path.of("main/java/com/example/lms/api/ChatApiController.java"));
        String restorer = Files.readString(Path.of("main/java/com/example/lms/api/ChatTraceMetaMessageRestorer.java"));

        assertFalse(controller.contains("SafeRedactor.safeMessage(traceHtml, 12000)"));
        assertFalse(restorer.contains("SafeRedactor.safeMessage(html, 12000)"));
        assertTrue(controller.contains("SafeRedactor.diagnosticText(\"traceHtml\", traceHtml, 12000)"));
        assertTrue(restorer.contains("SafeRedactor.diagnosticText(\"traceHtml\", html, 12000)"));
    }

    @Test
    void chatApiLogsDoNotUseRawThrowableMessagesOrSessionIds() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/api/ChatApiController.java"))
                + Files.readString(Path.of("main/java/com/example/lms/api/ChatRequestSettingsMerger.java"));
        List<String> rawThrowableLogLines = source.lines()
                .filter(line -> line.contains("log."))
                .filter(line -> line.contains(".getMessage()") || line.contains(".toString()"))
                .filter(line -> !line.contains("SafeRedactor.safeMessage("))
                .toList();

        assertEquals(List.of(), rawThrowableLogLines);
        assertFalse(source.contains("session {}: {}\", sessionId,"));
        assertFalse(source.contains("session {}: {}\", session.getId(),"));
        assertFalse(source.contains("sessionId={}): {}\", currentSessionId.get(),"));
        assertFalse(source.contains("Failed to authorize /cancel for session {}: {}\", resolvedSessionId"));
        assertFalse(source.contains("attachments from session {}\", ids.size(), sid"));
        assertFalse(source.contains("SSE stream detached by client (sessionId={}, resumePreserved=true)\", sid"));
        assertFalse(source.contains("tracePutIfAbsent(\"trace.id\", __capturedTrace);"));
        assertFalse(source.contains("TraceStore.putIfAbsent(\"http.path\", __httpPath);"));
        assertTrue(source.contains("tracePutIfAbsent(\"trace.id\", SafeRedactor.hashValue(__capturedTrace));"));
        assertTrue(source.contains("tracePutIfAbsent(\"http.path\", SafeRedactor.diagnosticValue(\"http.path\", __httpPath));"));
        assertTrue(source.contains("Failed to authorize /cancel for sessionHash={}: {}"));
        assertTrue(source.contains("sessionHash"));
    }

    @Test
    void samplingAdjustmentLogsDoNotWriteRawModelIdentifiers() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/api/ChatApiController.java"))
                + Files.readString(Path.of("main/java/com/example/lms/api/ChatRequestSettingsMerger.java"));

        assertFalse(source.contains("for model={}\""));
        assertFalse(source.contains("sanitizedTemperature, effectiveModel);"));
        assertFalse(source.contains("sanitizedTopP, effectiveModel);"));
        assertFalse(source.contains("sanitizedFrequencyPenalty, effectiveModel);"));
        assertFalse(source.contains("sanitizedPresencePenalty, effectiveModel);"));
        assertTrue(source.contains("modelHash={} modelLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(effectiveModel)"));
    }

    @Test
    void numericRequestBodyParserOnlyCatchesNumberFormatException() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/api/ChatApiController.java"));
        int start = source.indexOf("private static Long asLong");
        int parse = source.indexOf("Long.parseLong", start);
        int end = source.indexOf("\n    }", parse);
        assertTrue(start >= 0 && parse > start && end > parse, "asLong parser should be locatable");
        String helper = source.substring(start, end);

        assertFalse(helper.contains("catch (Exception"),
                "numeric fallback parser must not swallow all Exception");
        assertTrue(helper.contains("catch (NumberFormatException"),
                "numeric fallback parser should only catch NumberFormatException");
    }

    @Test
    void asLongFallbackLeavesStableInvalidNumberBreadcrumb() throws Exception {
        TraceStore.clear();
        try {
            Method method = ChatApiController.class.getDeclaredMethod("asLong", Object.class);
            method.setAccessible(true);
            String raw = "ownerToken=raw-secret";

            Object parsed = method.invoke(null, raw);

            assertNull(parsed);
            assertEquals(Boolean.TRUE, TraceStore.get("chat.api.suppressed.parse.asLong"));
            assertEquals("invalid_number", TraceStore.get("chat.api.suppressed.parse.asLong.errorType"));
            String trace = String.valueOf(TraceStore.getAll());
            assertFalse(trace.contains(raw), trace);
            assertFalse(trace.contains("NumberFormatException"), trace);
        } finally {
            TraceStore.clear();
        }
    }

    private static DebugEventStore enabledDebugEventStore() {
        DebugEventStore store = new DebugEventStore();
        ReflectionTestUtils.setField(store, "enabled", true);
        ReflectionTestUtils.setField(store, "maxSize", 20);
        ReflectionTestUtils.setField(store, "windowMs", 60_000L);
        ReflectionTestUtils.setField(store, "maxPerWindow", 20L);
        ReflectionTestUtils.setField(store, "flushIntervalMs", 15_000L);
        ReflectionTestUtils.setField(store, "ndjsonEnabled", false);
        return store;
    }

    private static void assertSelectionBeforeCancellation(String source, int branchStart) {
        assertTrue(branchStart >= 0, "cancellation branch should be locatable");
        int branchReturn = source.indexOf("return;", branchStart);
        assertTrue(branchReturn > branchStart, "cancellation branch should return without generic error");
        String branch = source.substring(branchStart, branchReturn);
        int selection = branch.indexOf("emitSelectionEntropy(sink);");
        int cancelled = branch.indexOf(
                "emitStreamCancelledStatus(sink, __capturedBudget, __streamStartedNs);");
        assertTrue(selection >= 0 && cancelled > selection,
                "selection projection/event must precede cancelled status in branch: " + branch);
    }

    private static void assertPreAckCancellationHelper(String source, int branchStart) {
        assertTrue(branchStart >= 0, "pre-ACK cancellation branch should be locatable");
        int branchReturn = source.indexOf("return;", branchStart);
        assertTrue(branchReturn > branchStart, "pre-ACK cancellation branch should return");
        String branch = source.substring(branchStart, branchReturn);
        assertTrue(branch.contains("emitPreAcknowledgementCancellation("),
                "pre-ACK cancellation must buffer selection and cancelled status: " + branch);
    }

    private static int selectionWriteIndex(String source, int fromIndex, boolean terminal) {
        Pattern call = Pattern.compile(
                "writeSelectionEntropyProjection\\(\\s*selectionEntropy,\\s*"
                        + "selectionDecisionLedger,\\s*" + terminal + "\\s*\\);");
        java.util.regex.Matcher matcher = call.matcher(source);
        return matcher.find(Math.max(0, fromIndex)) ? matcher.start() : -1;
    }
}
