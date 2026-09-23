package com.example.lms.service.vector;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.lms.service.rag.LangChainRAGService;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class VectorFailSoftRedactionContractTest {

    @Test
    void vectorFailSoftLogsDoNotUseRawThrowableMessagesOrSidValues() throws Exception {
        for (Path source : List.of(
                Path.of("main/java/com/example/lms/service/VectorStoreService.java"),
                Path.of("main/java/com/example/lms/service/EmbeddingStoreManager.java"),
                Path.of("main/java/com/example/lms/service/vector/VectorSidService.java"),
                Path.of("main/java/com/example/lms/service/vector/VectorShadowMergeDlqService.java"),
                Path.of("main/java/com/example/lms/service/vector/VectorQuarantineDlqService.java"),
                Path.of("main/java/com/example/lms/service/vector/VectorBackendHealthService.java"),
                Path.of("main/java/com/example/lms/service/vector/UpstashVectorStoreAdapter.java"),
                Path.of("main/java/ai/abandonware/nova/orch/storage/DegradedStorageDrainer.java"))) {
            String code = Files.readString(source, StandardCharsets.UTF_8);
            List<String> rawThrowableLogLines = code.lines()
                    .filter(line -> line.contains("log."))
                    .filter(line -> line.contains(".getMessage()") || line.contains(".toString()"))
                    .filter(line -> !line.contains("SafeRedactor.safeMessage("))
                    .toList();

            assertTrue(rawThrowableLogLines.isEmpty(), source + " logs raw throwable messages: " + rawThrowableLogLines);
        }

        String vectorStore = Files.readString(
                Path.of("main/java/com/example/lms/service/VectorStoreService.java"),
                StandardCharsets.UTF_8);
        String embeddingStore = Files.readString(
                Path.of("main/java/com/example/lms/service/EmbeddingStoreManager.java"),
                StandardCharsets.UTF_8);
        String upstash = Files.readString(
                Path.of("main/java/com/example/lms/service/vector/UpstashVectorStoreAdapter.java"),
                StandardCharsets.UTF_8);
        assertFalse(vectorStore.contains("sid={} err={}\", logicalSid,"));
        assertFalse(embeddingStore.contains("fail-soft sid={}: {}\", key,"));
        assertFalse(upstash.contains("Upstash query failed\", e"));
        assertFalse(Files.readString(
                Path.of("main/java/com/example/lms/service/vector/VectorSidService.java"),
                StandardCharsets.UTF_8).contains("rotated logicalSid={} prev={} next={}\", key, prev, next"));
        assertFalse(vectorStore.contains("SafeRedactor.safeMessage(String.valueOf(ex), 180)"));
        assertFalse(vectorStore.contains("sidHash={} err={}"));
        assertTrue(vectorStore.contains("[VectorStore] poison guard failed; routing to quarantine. sidHash={} errorHash={} errorLength={}"));
        assertTrue(vectorStore.contains("[VectorShadowDLQ] record staged failed (fail-soft). errorHash={} errorLength={}"));
        assertTrue(vectorStore.contains("[VectorDLQ] record quarantined failed (fail-soft). errorHash={} errorLength={}"));
        assertTrue(vectorStore.contains("SafeRedactor.hashValue(messageOf(ex)), messageLength(ex)"));
        assertFalse(embeddingStore.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(embeddingStore.contains("(lazy bootstrap) fail-soft sidHash={} errorHash={} errorLength={}"));
        assertTrue(embeddingStore.contains("loadForBootstrap fail-soft errorHash={} errorLength={}"));
        assertTrue(embeddingStore.contains("Failed to index memory snippets errorHash={} errorLength={}"));
        assertTrue(embeddingStore.contains("[VectorAdmin] KB reindex fail-soft errorHash={} errorLength={}"));
        assertTrue(embeddingStore.contains("[VectorAdmin] memory rebuild fail-soft errorHash={} errorLength={}"));
        assertTrue(embeddingStore.contains("SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));
        assertFalse(upstash.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(upstash.contains("Upstash add(embedding) failed (fail-soft) errorHash={} errorLength={}"));
        assertTrue(upstash.contains("Upstash add(id, embedding) failed (fail-soft) errorHash={} errorLength={}"));
        assertTrue(upstash.contains("Upstash add(id,embedding,segment) failed (fail-soft) errorHash={} errorLength={}"));
        assertTrue(upstash.contains("Upstash query failed errorHash={} errorLength={}"));
        assertTrue(upstash.contains("Upstash indexInfo failed (fail-soft) errorHash={} errorLength={}"));
        assertTrue(upstash.contains("Upstash listNamespaces failed (fail-soft) errorHash={} errorLength={}"));
        assertTrue(upstash.contains("Upstash upsert failed (strict_write) errorHash={} errorLength={}"));
        assertTrue(upstash.contains("Upstash upsert failed errorHash={} errorLength={}"));
        assertTrue(upstash.contains("Failed to translate LangChain4j filter to Upstash filter (fail-soft) errorHash={} errorLength={}"));
        assertTrue(upstash.contains("SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));
        assertTrue(Files.readString(
                Path.of("main/java/com/example/lms/service/vector/VectorSidService.java"),
                StandardCharsets.UTF_8).contains("rotated logicalSidHash={} prevHash={} nextHash={}"));
        assertTrue(Files.readString(
                Path.of("main/java/com/example/lms/service/vector/VectorSidService.java"),
                StandardCharsets.UTF_8).contains("SafeRedactor.hashValue(key)"));
    }

    @Test
    void embeddingStoreManagerDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/EmbeddingStoreManager.java"),
                StandardCharsets.UTF_8);

        assertFalse(Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")
                        .matcher(source)
                        .find(),
                "Embedding store manager needs fixed-stage breadcrumbs instead of exact empty catch bodies");
        assertTrue(source.contains("traceSuppressed(\"sid.activeGlobal\");"));
        assertTrue(source.contains("traceSuppressed(\"sid.resolveActive\");"));
        assertTrue(source.contains("traceSuppressed(\"poison.tm\");"));
        assertTrue(source.contains("traceSuppressed(\"scope.tm\");"));
        assertTrue(source.contains("traceSuppressed(\"quarantine.tmIdRewrite\");"));
        assertTrue(source.contains("traceSuppressed(\"poison.memorySnippet\");"));
        assertTrue(source.contains("traceSuppressed(\"scope.memorySnippet\");"));
        assertTrue(source.contains("traceSuppressed(\"quarantine.memorySnippetIdRewrite\");"));
    }

    @Test
    void vectorSmallFailSoftLogsUseHashAndLengthOnly() throws Exception {
        String sid = Files.readString(
                Path.of("main/java/com/example/lms/service/vector/VectorSidService.java"),
                StandardCharsets.UTF_8);
        String backend = Files.readString(
                Path.of("main/java/com/example/lms/service/vector/VectorBackendHealthService.java"),
                StandardCharsets.UTF_8);
        String shadow = Files.readString(
                Path.of("main/java/com/example/lms/service/vector/VectorShadowMergeDlqService.java"),
                StandardCharsets.UTF_8);
        String quarantine = Files.readString(
                Path.of("main/java/com/example/lms/service/vector/VectorQuarantineDlqService.java"),
                StandardCharsets.UTF_8);

        for (String source : List.of(sid, backend, shadow, quarantine)) {
            assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        }
        assertTrue(sid.contains("[VectorSid] load fail-soft errorHash={} errorLength={}"));
        assertTrue(sid.contains("[VectorSid] persist fail-soft errorHash={} errorLength={}"));
        assertTrue(backend.contains("[VectorBackendHealth] probe failed errorHash={} errorLength={}"));
        assertTrue(backend.contains("[AWX2AF2][rag][starvation] embeddingReady=false reasonHash={} reasonLength={}"));
        assertTrue(shadow.contains("[ShadowDLQ] record failed errorHash={} errorLength={}"));
        assertTrue(quarantine.contains("[VectorDLQ] recordQuarantined failed failSoft=true errorHash={} errorLength={}"));
        assertTrue(quarantine.contains("[VectorDLQ] claimDueLeaseTx failed errorHash={} errorLength={}"));
    }

    @Test
    void vectorAdminDlqDetailMasksErrorAndQuarantineReason() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/api/VectorAdminController.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("out.put(\"lastError\", row.getLastError());"));
        assertFalse(source.contains("out.put(\"quarantineReason\", row.getQuarantineReason());"));
        assertFalse(source.contains("out.put(\"payload\", row.getPayload());"));
        assertFalse(source.contains("out.put(\"items\", p.getContent());"));
        assertFalse(source.contains("out.put(\"originalSid\", row.getOriginalSid());"));
        assertFalse(source.contains("out.put(\"originalSidBase\", row.getOriginalSidBase());"));
        assertFalse(source.contains("out.put(\"quarantineVectorId\", row.getQuarantineVectorId());"));
        assertFalse(source.contains("out.put(\"originalVectorId\", row.getOriginalVectorId());"));
        assertFalse(source.contains("out.put(\"lockedBy\", row.getLockedBy());"));
        assertFalse(source.contains("out.put(\"metaJson\", row.getMetaJson());"));
        assertFalse(source.contains("out.put(\"lastError\", SafeRedactor.safeMessage(row.getLastError(), 240));"));
        assertTrue(source.contains("out.put(\"lastErrorPresent\", row.getLastError() != null && !row.getLastError().isBlank());"));
        assertTrue(source.contains("out.put(\"lastErrorHash\", SafeRedactor.hashValue(row.getLastError()));"));
        assertTrue(source.contains("out.put(\"lastErrorLength\", row.getLastError() == null ? 0 : row.getLastError().length());"));
        assertFalse(source.contains("out.put(\"quarantineReason\", SafeRedactor.safeMessage(row.getQuarantineReason(), 180));"));
        assertTrue(source.contains(
                "out.put(\"quarantineReason\", SafeRedactor.traceLabelOrFallback(row.getQuarantineReason(), \"unknown\"));"));
        assertTrue(source.contains("p.getContent().stream().map(VectorAdminController::toDlqSummary).toList()"));
        assertTrue(source.contains("out.put(\"originalSidHash\", SafeRedactor.hashValue(row.getOriginalSid()));"));
        assertTrue(source.contains("out.put(\"originalSidBaseHash\", SafeRedactor.hashValue(row.getOriginalSidBase()));"));
        assertTrue(source.contains("out.put(\"quarantineVectorIdHash\", SafeRedactor.hashValue(row.getQuarantineVectorId()));"));
        assertTrue(source.contains("out.put(\"originalVectorIdHash\", SafeRedactor.hashValue(row.getOriginalVectorId()));"));
        assertTrue(source.contains("out.put(\"lockedByHash\", SafeRedactor.hashValue(row.getLockedBy()));"));
        assertTrue(source.contains("out.put(\"metaJsonHash\", SafeRedactor.hashValue(row.getMetaJson()));"));
        assertTrue(source.contains("out.put(\"metaJsonLength\", row.getMetaJson() == null ? 0 : row.getMetaJson().length());"));
        assertTrue(source.contains("out.put(\"payloadHash\", SafeRedactor.hashValue(row.getPayload()));"));
        assertTrue(source.contains("out.put(\"payloadLength\", row.getPayload() == null ? 0 : row.getPayload().length());"));
    }

    @Test
    void vectorPersistedLastErrorsDoNotStoreThrowableToString() throws Exception {
        String backendHealth = Files.readString(
                Path.of("main/java/com/example/lms/service/vector/VectorBackendHealthService.java"),
                StandardCharsets.UTF_8);
        String shadowDlq = Files.readString(
                Path.of("main/java/com/example/lms/service/vector/VectorShadowMergeDlqService.java"),
                StandardCharsets.UTF_8);
        String quarantineDlq = Files.readString(
                Path.of("main/java/com/example/lms/service/vector/VectorQuarantineDlqService.java"),
                StandardCharsets.UTF_8);

        assertFalse(backendHealth.contains("lastError = e.toString();"));
        assertFalse(shadowDlq.contains("row.setLastError(limit(e.toString(), 1024));"));
        assertFalse(quarantineDlq.contains("row.setLastError(limitLen(e.toString(), 1024));"));
        assertFalse(backendHealth.contains("lastError = com.example.lms.trace.SafeRedactor.safeMessage(e.getMessage(), 240);"));
        assertFalse(shadowDlq.contains(
                "row.setLastError(limit(com.example.lms.trace.SafeRedactor.safeMessage(e.getMessage(), 1024), 1024));"));
        assertFalse(quarantineDlq.contains(
                "row.setLastError(limitLen(com.example.lms.trace.SafeRedactor.safeMessage(e.getMessage(), 1024), 1024));"));
        assertTrue(backendHealth.contains(
                "lastError = com.example.lms.trace.SafeRedactor.traceLabelOrFallback(e.getMessage(), \"\");"));
        assertTrue(shadowDlq.contains(
                "row.setLastError(com.example.lms.trace.SafeRedactor.traceLabelOrFallback(e.getMessage(), \"\"));"));
        assertTrue(quarantineDlq.contains(
                "row.setLastError(com.example.lms.trace.SafeRedactor.traceLabelOrFallback(e.getMessage(), \"\"));"));
    }

    @Test
    void vectorBackendHealthSnapshotUsesProbeHashesInsteadOfRawProbeIdentifiers() {
        String fakeKey = "sk-" + "VectorBackendSnapshotSecret123";
        String rawSid = "tenant#api_key=" + fakeKey;
        String rawId = "__SYS__:VECTOR_HEALTH_PROBE:" + fakeKey;
        EmbeddingModel model = mock(EmbeddingModel.class);
        @SuppressWarnings("unchecked")
        EmbeddingStore<TextSegment> store = mock(EmbeddingStore.class);
        when(model.embedAll(anyList()))
                .thenThrow(new IllegalStateException("embedding failed api_key=" + fakeKey));
        VectorBackendHealthService service = new VectorBackendHealthService(model, store);
        ReflectionTestUtils.setField(service, "probeSid", rawSid);
        ReflectionTestUtils.setField(service, "probeId", rawId);

        assertFalse(service.probeEmbeddingOnlyNow());

        Map<String, Object> snapshot = service.snapshot();
        String rendered = String.valueOf(snapshot);
        assertFalse(rendered.contains(fakeKey));
        assertFalse(rendered.contains(rawSid));
        assertFalse(rendered.contains(rawId));
        assertFalse(snapshot.containsKey("probeSid"));
        assertFalse(snapshot.containsKey("probeId"));
        assertTrue(snapshot.containsKey("probeSidHash"));
        assertTrue(snapshot.containsKey("probeIdHash"));
        assertTrue(snapshot.containsKey("probeSidLength"));
        assertTrue(snapshot.containsKey("probeIdLength"));
        assertNotNull(snapshot.get("lastError"));
    }

    @Test
    void vectorIngestProtectionSnapshotMasksRawFailureReason() throws Exception {
        String fakeKey = "sk-" + "VectorIngestProtectionSecret123";
        VectorIngestProtectionService service = new VectorIngestProtectionService();
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "globalOnly", true);
        ReflectionTestUtils.setField(service, "threshold", 1);

        service.recordIfMatches(LangChainRAGService.GLOBAL_SID,
                new RuntimeException("vector upsert error api_key=" + fakeKey),
                "vector-write");

        Map<String, Object> snapshot = service.snapshot();
        String rendered = String.valueOf(snapshot);
        assertFalse(rendered.contains(fakeKey));
        assertFalse(rendered.contains("vector upsert error"));
        assertFalse(rendered.contains("api_key"));
        assertTrue(rendered.contains("lastReason"));
        assertTrue(rendered.contains("vector-upsert-error"));

        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/vector/VectorIngestProtectionService.java"),
                StandardCharsets.UTF_8);
        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(t), 180)"));
        assertTrue(source.contains("SafeRedactor.traceLabelOrFallback(t.getClass().getSimpleName(), \"unknown\")"));
    }

    @Test
    void vectorSidStatusSnapshotMasksPathsAndSidMappings() throws Exception {
        String sidService = Files.readString(
                Path.of("main/java/com/example/lms/service/vector/VectorSidService.java"),
                StandardCharsets.UTF_8);
        String controller = Files.readString(
                Path.of("main/java/com/example/lms/api/VectorAdminController.java"),
                StandardCharsets.UTF_8);

        assertFalse(sidService.contains("out.put(\"statePath\", Objects.toString(statePath, \"\"));"));
        assertFalse(sidService.contains("out.put(\"mappings\","));
        assertFalse(sidService.contains("out.put(\"quarantineSid\", QUARANTINE_SID);"));
        assertTrue(sidService.contains("out.put(\"statePathHash\", SafeRedactor.hashValue(path));"));
        assertTrue(sidService.contains("out.put(\"statePathLength\", path.length());"));
        assertTrue(sidService.contains("out.put(\"mappingCount\", mappings.size());"));
        assertTrue(sidService.contains("out.put(\"mappingHashes\", hashMappings(mappings));"));
        assertTrue(sidService.contains("out.put(\"quarantineSidHash\", SafeRedactor.hashValue(QUARANTINE_SID));"));

        assertFalse(controller.contains("out.put(\"activeGlobalSid\", vectorSidService.resolveActiveSid("));
        assertFalse(controller.contains("out.put(\"quarantineSid\", vectorSidService.quarantineSid());"));
        assertTrue(controller.contains("out.put(\"activeGlobalSidHash\", SafeRedactor.hashValue(activeGlobalSid));"));
        assertTrue(controller.contains("out.put(\"activeGlobalSidPresent\", activeGlobalSid != null && !activeGlobalSid.isBlank());"));
        assertTrue(controller.contains("out.put(\"quarantineSidHash\", SafeRedactor.hashValue(quarantineSid));"));
    }

    @Test
    void vectorAdminSidAndQuarantineResponsesUseHashesInsteadOfRawValues() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/api/VectorAdminController.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("\"logicalSid\", LangChainRAGService.GLOBAL_SID"));
        assertFalse(source.contains("\"prevActiveSid\", prev"));
        assertFalse(source.contains("\"nextActiveSid\", next"));
        assertFalse(source.contains("String sessionId,"));
        assertFalse(source.contains("\n                tm == null ? null : tm.getSessionId(),"));
        assertFalse(source.contains("preview\n        );"));
        assertTrue(source.contains("\"logicalSidHash\", hashOrEmpty(LangChainRAGService.GLOBAL_SID)"));
        assertTrue(source.contains("\"prevActiveSidHash\", hashOrEmpty(prev)"));
        assertTrue(source.contains("\"nextActiveSidHash\", hashOrEmpty(next)"));
        assertTrue(source.contains("private static String hashOrEmpty(String value)"));
        assertTrue(source.contains("String hash = SafeRedactor.hashValue(value);"));
        assertTrue(source.contains("return hash == null ? \"\" : hash;"));
        assertTrue(source.contains("String sessionHash,"));
        assertTrue(source.contains("String previewHash,"));
        assertTrue(source.contains("int previewLength"));
        assertTrue(source.contains("SafeRedactor.hashValue(tm == null ? null : tm.getSessionId())"));
        assertTrue(source.contains("SafeRedactor.hashValue(preview)"));
    }
}
