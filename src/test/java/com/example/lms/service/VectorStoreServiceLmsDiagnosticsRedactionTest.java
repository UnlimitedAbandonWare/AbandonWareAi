package com.example.lms.service;

import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.VectorPoisonGuard;
import com.example.lms.service.guard.VectorScopeGuard;
import com.example.lms.service.vector.DocumentChunkingService;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class VectorStoreServiceLmsDiagnosticsRedactionTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void lmsPrivateSidAndVectorIdAreRedactedFromTraceAndAuditSurfaces() throws Exception {
        VectorStoreService service = new VectorStoreService(
                mock(EmbeddingModel.class),
                mockEmbeddingStore());
        setInt(service, "batchSize", 1000);

        service.enqueue(
                "lms:student:10:student:stable-id",
                "lms:student:10",
                "Useful learning profile text for retrieval.",
                Map.of(VectorMetaKeys.META_DOC_TYPE, "MEMORY"));

        String trace = TraceStore.getAll().toString();
        List<VectorStoreService.IngestAuditEvent> audit = service.getIngestAudit(10);
        String auditText = audit.toString();

        assertFalse(trace.contains("lms:student:10"));
        assertFalse(trace.contains("stable-id"));
        assertFalse(auditText.contains("lms:student:10"));
        assertFalse(auditText.contains("stable-id"));
        assertTrue(String.valueOf(TraceStore.get("ml.vector.ingest.sid")).startsWith("lms:student:hash:"));
        assertTrue(String.valueOf(TraceStore.get("ml.vector.ingest.id")).startsWith("lms:student:hash:"));
        assertTrue(audit.get(0).logicalSid().startsWith("lms:student:hash:"));
        assertTrue(audit.get(0).id().startsWith("lms:student:hash:"));
    }

    @Test
    void alreadyHashedLmsSidAndVectorIdStayIdempotentInDiagnostics() throws Exception {
        VectorStoreService service = new VectorStoreService(
                mock(EmbeddingModel.class),
                mockEmbeddingStore());
        setInt(service, "batchSize", 1000);

        String hashed = "lms:student:hash:abc123def456";
        service.enqueue(
                hashed,
                hashed,
                "Useful learning profile text for retrieval.",
                Map.of(VectorMetaKeys.META_DOC_TYPE, "MEMORY"));

        assertEquals(hashed, TraceStore.get("ml.vector.ingest.sid"));
        assertEquals(hashed, TraceStore.get("ml.vector.ingest.id"));
        VectorStoreService.IngestAuditEvent audit = service.getIngestAudit(10).get(0);
        assertEquals(hashed, audit.logicalSid());
        assertEquals(hashed, audit.id());
    }

    @Test
    void genericSensitiveSidIsRedactedFromTraceAndAuditSurfaces() throws Exception {
        VectorStoreService service = new VectorStoreService(
                mock(EmbeddingModel.class),
                mockEmbeddingStore());
        setInt(service, "batchSize", 1000);
        String rawSid = "sid-token=" + "sk-" + "C".repeat(24);

        service.enqueue(
                "vector-id-safe",
                rawSid,
                "Useful vector payload.",
                Map.of(VectorMetaKeys.META_DOC_TYPE, "MEMORY"));

        String trace = String.valueOf(TraceStore.getAll());
        VectorStoreService.IngestAuditEvent audit = service.getIngestAudit(10).get(0);
        String auditText = audit.toString();

        assertFalse(trace.contains(rawSid), trace);
        assertFalse(auditText.contains(rawSid), auditText);
        assertTrue(String.valueOf(TraceStore.get("ml.vector.ingest.sid")).startsWith("hash:"));
        assertTrue(audit.logicalSid().startsWith("hash:"));
    }

    @Test
    void genericSensitiveVectorIdIsRedactedFromTraceAndAuditSurfaces() throws Exception {
        VectorStoreService service = new VectorStoreService(
                mock(EmbeddingModel.class),
                mockEmbeddingStore());
        setInt(service, "batchSize", 1000);
        String rawId = "vector-token=" + "sk-" + "D".repeat(24);

        service.enqueue(
                rawId,
                "safe-sid",
                "Useful vector payload.",
                Map.of(VectorMetaKeys.META_DOC_TYPE, "MEMORY"));

        String trace = String.valueOf(TraceStore.getAll());
        VectorStoreService.IngestAuditEvent audit = service.getIngestAudit(10).get(0);
        String auditText = audit.toString();

        assertFalse(trace.contains(rawId), trace);
        assertFalse(auditText.contains(rawId), auditText);
        assertTrue(String.valueOf(TraceStore.get("ml.vector.ingest.id")).startsWith("hash:"));
        assertTrue(audit.id().startsWith("hash:"));
    }

    @Test
    void ingestAuditScopeMetadataDoesNotExposeRawSensitiveLabels() throws Exception {
        VectorStoreService service = new VectorStoreService(
                mock(EmbeddingModel.class),
                mockEmbeddingStore());
        setInt(service, "batchSize", 1000);
        String rawAnchor = "scope-anchor-token=" + "sk-" + "E".repeat(24);
        String rawPart = "part-ownerToken=private";

        service.enqueue(
                "vector-id-safe",
                "sid-safe",
                "Useful vector payload.",
                Map.of(
                        VectorMetaKeys.META_DOC_TYPE, "MEMORY",
                        VectorMetaKeys.META_SCOPE_ANCHOR_KEY, rawAnchor,
                        VectorMetaKeys.META_SCOPE_KIND, "kind-token=" + "sk-" + "F".repeat(24),
                        VectorMetaKeys.META_SCOPE_PART_KEY, rawPart));

        VectorStoreService.IngestAuditEvent audit = service.getIngestAudit(10).get(0);
        String auditText = audit.toString();

        assertFalse(auditText.contains(rawAnchor), auditText);
        assertFalse(auditText.contains(rawPart), auditText);
        assertFalse(auditText.contains("ownerToken"), auditText);
        assertTrue(audit.scopeAnchorKey().startsWith("hash:"));
        assertTrue(audit.scopeKind().startsWith("hash:"));
        assertTrue(audit.scopePartKey().startsWith("hash:"));
    }

    @Test
    void ingestAuditGuardReasonsDoNotExposeRawSensitiveLabels() throws Exception {
        VectorStoreService service = new VectorStoreService(
                mock(EmbeddingModel.class),
                mockEmbeddingStore());
        setInt(service, "batchSize", 1000);
        String poisonReason = "poison-token=" + "sk-" + "G".repeat(24);
        String scopeReason = "scope-ownerToken=private";
        setField(service, "vectorPoisonGuard", new VectorPoisonGuard() {
            @Override
            public IngestDecision inspectIngest(String sid, String text, Map<String, Object> meta, String stage) {
                return new IngestDecision(false, text, meta, poisonReason, 0.9d);
            }
        });
        setField(service, "vectorScopeGuard", new VectorScopeGuard() {
            @Override
            public IngestDecision inspectIngest(String docType, String text, Map<String, Object> meta) {
                return new IngestDecision(false, scopeReason, Map.of());
            }
        });

        service.enqueue(
                "vector-id-safe",
                "sid-safe",
                "Useful vector payload.",
                Map.of(VectorMetaKeys.META_DOC_TYPE, "MEMORY"));

        VectorStoreService.IngestAuditEvent audit = service.getIngestAudit(10).get(0);
        String auditText = audit.toString();

        assertFalse(auditText.contains(poisonReason), auditText);
        assertFalse(auditText.contains(scopeReason), auditText);
        assertFalse(auditText.contains("ownerToken"), auditText);
        assertTrue(audit.poisonReason().startsWith("hash:"));
        assertTrue(audit.scopeReason().startsWith("hash:"));
    }

    @Test
    void fullDocumentPoisonInspectionRunsBeforeChunking() throws Exception {
        EmbeddingModel model = mock(EmbeddingModel.class);
        @SuppressWarnings("unchecked")
        EmbeddingStore<TextSegment> store = mock(EmbeddingStore.class);
        when(model.embedAll(anyList())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<TextSegment> segments = invocation.getArgument(0, List.class);
            return Response.from(segments.stream()
                    .map(ignored -> Embedding.from(new float[]{1.0f}))
                    .toList());
        });

        VectorStoreService service = new VectorStoreService(model, store);
        setInt(service, "batchSize", 1000);
        setBoolean(service, "shadowWriteEnabled", false);
        setBoolean(service, "quarantineRewriteStableId", false);

        setField(service, "vectorPoisonGuard", configuredPoisonGuard());

        DocumentChunkingService chunking = new DocumentChunkingService();
        setBoolean(chunking, "enabled", true);
        setInt(chunking, "chunkSizeChars", 1000);
        setInt(chunking, "overlapChars", 120);
        setInt(chunking, "minSplitChars", 1400);
        setField(service, "documentChunkingService", chunking);

        String generatedReport = "# Source Difficulty Report\n\n"
                + "x".repeat(1_600)
                + "\n\n## scorecard\n\n| metric | value |\n|---|---|\n| Java file | 2026 |";

        service.enqueue(
                "generated-report-id",
                "primary-session",
                generatedReport,
                Map.of(VectorMetaKeys.META_DOC_TYPE, "MEMORY"));
        service.flush();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TextSegment>> segmentsCaptor = ArgumentCaptor.forClass(List.class);
        verify(store).addAll(anyList(), anyList(), segmentsCaptor.capture());
        List<TextSegment> segments = segmentsCaptor.getValue();

        assertEquals(1, segments.size(), "the rejected whole document must not be split into primary chunks");
        Map<String, Object> metadata = segments.get(0).metadata().toMap();
        assertEquals("generated_report", metadata.get(VectorMetaKeys.META_POISON_REASON));
        assertEquals("Q", metadata.get(VectorMetaKeys.META_SID));
        assertFalse(metadata.containsKey(VectorMetaKeys.META_CHUNK_ID));
        assertEquals("quarantine", TraceStore.get("ml.vector.ingest.route"));
    }

    @Test
    void generatedArtifactPathNeverReachesVectorEmbeddingStore() throws Exception {
        EmbeddingModel model = mock(EmbeddingModel.class);
        @SuppressWarnings("unchecked")
        EmbeddingStore<TextSegment> store = mock(EmbeddingStore.class);
        VectorStoreService service = new VectorStoreService(model, store);
        setInt(service, "batchSize", 1000);
        setField(service, "vectorPoisonGuard", configuredPoisonGuard());

        service.enqueue(
                "artifact-id",
                "primary-session",
                "Ordinary artifact content without poison markers.",
                Map.of(
                        VectorMetaKeys.META_DOC_TYPE, "MEMORY",
                        VectorMetaKeys.META_SOURCE_PATH, "__patch_drop__/topic-v3.patch"));
        service.flush();

        verifyNoInteractions(model, store);
        assertEquals(Boolean.TRUE, TraceStore.get("ml.vector.ingest.drop"));
        assertEquals("generated_artifact_path", TraceStore.get("ml.vector.ingest.drop.reason"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("topic-v3.patch"), trace);
        assertFalse(trace.contains("__patch_drop__"), trace);
    }

    @Test
    void vectorFlushErrorBreadcrumbUsesSafeRedactor() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/VectorStoreService.java"));

        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(e), 240)"));
        assertFalse(source.contains(
                "TraceStore.put(\"ml.vector.flush.error\", String.valueOf(e))"));
        assertTrue(source.contains("TraceStore.put(\"ml.vector.flush.error\", String.format(\"errorHash=%s errorLength=%d\","));
        assertTrue(source.contains("SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length())"));
    }

    @Test
    void vectorStoreServiceDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/VectorStoreService.java"));

        assertEquals(0, Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}").matcher(source).results().count(),
                "VectorStoreService fail-soft paths need trace breadcrumbs instead of exact empty catch bodies");
    }

    @Test
    void vectorStoreFailSoftCatchesLeaveTraceBreadcrumbs() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/VectorStoreService.java"));
        String helper = Files.readString(Path.of("main/java/com/example/lms/service/VectorStoreTraceSuppressions.java"));

        assertTrue(helper.contains("TraceStore.put(\"ml.vector.suppressed.\" + safeStage, true);"));
        assertTrue(helper.contains("[VectorStore] suppression trace failed stage={} errorHash={} errorLength={}"));
        assertVectorStage(source, "ingest.preChunkGuard");
        assertVectorStage(source, "scope.inspectIngest");
        assertVectorStage(source, "ingestProtection.quarantineActive");
        assertVectorStage(source, "sidRotationAdvisor.recordQuarantine");
        assertVectorStage(source, "ingest.trace");
        assertVectorStage(source, "ingest.traceLogger");
        assertVectorStage(source, "ingest.audit");
        assertVectorStage(source, "flush.ingestProtection");
        assertVectorStage(source, "flush.batch");
        assertVectorStage(source, "vflushBucket.parse");
        assertVectorStage(source, "safeInt.parse");
        assertVectorStage(source, "flush.traceIdsSample");
        assertVectorStage(source, "flush.traceJsonStart");
        assertVectorStage(source, "flush.embeddingIds");
        assertVectorStage(source, "flush.traceJsonDone");
        assertVectorStage(source, "flush.group");
        assertVectorStage(source, "flush.errorBreadcrumb");
        assertVectorStage(source, "flush.errorSnapshot");
    }

    @Test
    void vectorTraceSuppressionsNormalizeNumericErrorType() {
        VectorStoreTraceSuppressions.trace("safeInt.parse", new NumberFormatException("ownerToken=secret"));

        assertEquals(Boolean.TRUE, TraceStore.get("ml.vector.suppressed.safeInt.parse"));
        assertEquals("invalid_number", TraceStore.get("ml.vector.suppressed.safeInt.parse.errorType"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("NumberFormatException"));
        assertFalse(trace.contains("ownerToken=secret"));
    }

    @Test
    void vectorTraceStoreCorrelationIdsAreHashOnly() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/VectorStoreService.java"));

        assertFalse(source.contains("TraceStore.put(\"ml.vector.ingest.traceId\", traceId0);"));
        assertFalse(source.contains("TraceStore.put(\"ml.vector.flush.traceId\", groupTraceId);"));
        assertFalse(source.contains("TraceStore.put(\"ml.vector.flush.requestId\", groupRequestId);"));
        assertFalse(source.contains("TraceStore.put(\"ml.vector.flush.requestIds.sample\", String.join(\",\", reqIds));"));
        assertFalse(source.contains("TraceStore.put(\"ml.vector.flush.traceIds.sample\", String.join(\",\", traceIds));"));

        assertTrue(source.contains("TraceStore.put(\"ml.vector.ingest.traceId\", SafeRedactor.hashValue(traceId0));"));
        assertTrue(source.contains("TraceStore.put(\"ml.vector.flush.traceId\", SafeRedactor.hashValue(groupTraceId));"));
        assertTrue(source.contains("TraceStore.put(\"ml.vector.flush.requestId\", SafeRedactor.hashValue(groupRequestId));"));
        assertTrue(source.contains("TraceStore.put(\"ml.vector.flush.requestIds.sample\", hashList(reqIds));"));
        assertTrue(source.contains("TraceStore.put(\"ml.vector.flush.traceIds.sample\", hashList(traceIds));"));
    }

    @Test
    void vectorShadowReasonDiagnosticsUseTraceLabel() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/VectorStoreService.java"));

        assertFalse(source.contains("TraceStore.put(\"ml.vector.ingest.shadow.reason\", shadowReason);"));
        assertFalse(source.contains("\"shadowReason\", (shadowReason == null ? \"\" : shadowReason),"));
        assertTrue(source.contains("String safeShadowReason = SafeRedactor.traceLabelOrFallback(shadowReason, \"unknown\");"));
        assertTrue(source.contains("TraceStore.put(\"ml.vector.ingest.shadow.reason\", safeShadowReason);"));
        assertTrue(source.contains("\"shadowReason\", safeShadowReason,"));
    }

    @Test
    void vectorNumericFallbackParsersOnlyCatchNumberFormatException() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/VectorStoreService.java"));

        assertParserCatchNarrowed(source, "private static Long tryParseVflushBucket(String traceId)");
        assertParserCatchNarrowed(source, "private static int safeInt(Object v, int def)");
    }

    @Test
    void poisonGuardErrorMessageMetadataDoesNotExposeRawSecrets() throws Exception {
        EmbeddingModel model = mock(EmbeddingModel.class);
        @SuppressWarnings("unchecked")
        EmbeddingStore<TextSegment> store = mock(EmbeddingStore.class);
        when(model.embedAll(anyList())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<TextSegment> segments = invocation.getArgument(0, List.class);
            return Response.from(segments.stream()
                    .map(ignored -> Embedding.from(new float[]{1.0f}))
                    .toList());
        });
        VectorStoreService service = new VectorStoreService(model, store);
        setInt(service, "batchSize", 1000);
        setBoolean(service, "shadowWriteEnabled", false);
        String rawSecret = "sk-" + "vectorPoisonSecret123456789012345";
        setField(service, "vectorPoisonGuard", new VectorPoisonGuard() {
            @Override
            public IngestDecision inspectIngest(String sid, String text, Map<String, Object> meta, String stage) {
                throw new IllegalStateException("guard failed Authorization Bearer " + rawSecret);
            }
        });

        service.enqueue(
                "vector-id-1",
                "sid-1",
                "Useful vector payload.",
                Map.of(VectorMetaKeys.META_DOC_TYPE, "MEMORY"));
        service.flush();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TextSegment>> segmentsCaptor = ArgumentCaptor.forClass(List.class);
        verify(store).addAll(anyList(), anyList(), segmentsCaptor.capture());
        Map<String, Object> metadata = segmentsCaptor.getValue().get(0).metadata().toMap();
        String message = String.valueOf(metadata.get("poison_guard_error_message"));

        assertTrue(metadata.containsKey("poison_guard_error_message"));
        assertTrue(message.startsWith("hash:"), message);
        assertFalse(message.contains(rawSecret));
        assertFalse(message.contains("Bearer " + rawSecret));
        assertEquals(Boolean.TRUE, TraceStore.get("ml.vector.suppressed.ingest.poisonGuard"));
        assertEquals("IllegalStateException",
                TraceStore.get("ml.vector.suppressed.ingest.poisonGuard.errorType"));
        assertFalse(TraceStore.getAll().toString().contains(rawSecret));
    }

    @SuppressWarnings("unchecked")
    private static EmbeddingStore<TextSegment> mockEmbeddingStore() {
        return mock(EmbeddingStore.class);
    }

    private static void setInt(Object target, String fieldName, int value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setInt(target, value);
    }

    private static void setBoolean(Object target, String fieldName, boolean value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setBoolean(target, value);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static VectorPoisonGuard configuredPoisonGuard() throws Exception {
        VectorPoisonGuard guard = new VectorPoisonGuard();
        setBoolean(guard, "enabled", true);
        setBoolean(guard, "blockLogLike", true);
        setBoolean(guard, "sanitizeTraceDump", true);
        setInt(guard, "maxTextChars", 20_000);
        setInt(guard, "maxLines", 400);
        setField(guard, "logLineRatioThreshold", 0.22d);
        setInt(guard, "minLogLineMatches", 3);
        setBoolean(guard, "allowLegacyNoDocType", true);
        return guard;
    }

    private static void assertVectorStage(String source, String stage) {
        assertTrue(source.contains("VectorStoreTraceSuppressions.trace(\"" + stage + "\""),
                () -> "missing VectorStore suppression stage: " + stage);
        assertTrue(source.contains("log.debug(\"[VectorStore] fail-soft stage={}\", \"" + stage + "\")"),
                () -> "missing VectorStore debug breadcrumb stage: " + stage);
    }

    private static void assertParserCatchNarrowed(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "missing parser signature: " + signature);
        int parse = source.indexOf("parse", start);
        assertTrue(parse >= start, "parser must call a numeric parse method: " + signature);
        int end = source.indexOf("\n    }", parse);
        assertTrue(end > parse, "parser method end should be found: " + signature);
        String method = source.substring(start, end);
        assertTrue(method.contains("catch (NumberFormatException"),
                "numeric fallback parser should only catch NumberFormatException: " + signature);
        assertFalse(method.contains("catch (Exception"),
                "numeric fallback parser must not swallow all Exception: " + signature);
        assertFalse(method.contains("catch (Throwable"),
                "numeric fallback parser must not swallow Throwable: " + signature);
    }
}
