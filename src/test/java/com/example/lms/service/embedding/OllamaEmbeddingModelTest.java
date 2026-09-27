package com.example.lms.service.embedding;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.example.lms.search.TraceStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OllamaEmbeddingModelTest {

    @Test
    void unknownBackupIdentityCannotSupplyQueryOrDocumentVectorsEvenAtSameDimension() {
        TraceStore.clear();
        OllamaEmbeddingModel model = newModel(false, false);
        ReflectionTestUtils.setField(model, "provider", "ollama");
        ReflectionTestUtils.setField(model, "model", "model-a");
        ReflectionTestUtils.setField(model, "dimensions", 2);
        var backup = org.mockito.Mockito.mock(dev.langchain4j.model.embedding.EmbeddingModel.class);
        var vector = dev.langchain4j.data.embedding.Embedding.from(new float[]{0.6f, 0.8f});
        org.mockito.Mockito.when(backup.embed("probe")).thenReturn(dev.langchain4j.model.output.Response.from(vector));
        org.mockito.Mockito.when(backup.embedAll(org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(dev.langchain4j.model.output.Response.from(List.of(vector)));
        ReflectionTestUtils.setField(model, "backupModel", backup);

        assertThrows(IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(model, "callBackupVector", "probe", "fallback"));
        assertThrows(IllegalStateException.class, () -> ReflectionTestUtils.invokeMethod(model,
                "callBackupBatch", List.of(dev.langchain4j.data.segment.TextSegment.from("probe")), "fallback"));
        org.mockito.Mockito.verifyNoInteractions(backup);
        assertEquals("embedding_space_unverified", TraceStore.get("embed.failover.blockedReason"));
    }

    @Test
    void verifiedSameLocalModelBackupKeepsVectorSpaceAndRejectsDifferentModelOrPreprocessing() {
        OllamaEmbeddingModel model = newModel(false, false);
        OllamaEmbeddingModel backup = org.mockito.Mockito.mock(OllamaEmbeddingModel.class);
        for (OllamaEmbeddingModel endpoint : List.of(model, backup)) {
            ReflectionTestUtils.setField(endpoint, "provider", "ollama");
            ReflectionTestUtils.setField(endpoint, "model", "model-a");
            ReflectionTestUtils.setField(endpoint, "dimensions", 2);
            ReflectionTestUtils.setField(endpoint, "normalizationMode", "SLICE_TO_CONFIGURED_DIM");
        }
        ReflectionTestUtils.setField(model, "backupModel", backup);
        var vector = dev.langchain4j.data.embedding.Embedding.from(new float[]{0.6f, 0.8f});
        org.mockito.Mockito.when(backup.embed("probe")).thenReturn(dev.langchain4j.model.output.Response.from(vector));
        float[] result = ReflectionTestUtils.invokeMethod(model, "callBackupVector", "probe", "fallback");
        assertEquals(2, result.length);
        ReflectionTestUtils.setField(backup, "model", "model-b");
        assertThrows(IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(model, "callBackupVector", "probe", "fallback"));
        ReflectionTestUtils.setField(backup, "model", "model-a");
        ReflectionTestUtils.setField(backup, "normalizationMode", "NONE");
        assertThrows(IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(model, "callBackupVector", "probe", "fallback"));
        ReflectionTestUtils.setField(backup, "normalizationMode", "SLICE_TO_CONFIGURED_DIM");
        ReflectionTestUtils.setField(backup, "backupModel", model);
        assertThrows(IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(model, "callBackupVector", "probe", "fallback"));
        org.mockito.Mockito.verify(backup, org.mockito.Mockito.times(1)).embed("probe");
    }

    @Test
    void diagnosticLogsAndEventsDoNotWriteRawModelUrlOrTag() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/embedding/OllamaEmbeddingModel.java"));

        assertFalse(source.contains("(dim={}, model={}, url={})"));
        assertFalse(source.contains("dimension mismatch (configured={}, actual={}, tag={}, model={})"));
        assertFalse(source.contains("meta.put(\"model\", model);"));
        assertFalse(source.contains("meta.put(\"url\", safeUrl(url));"));
        assertFalse(source.contains("\"embedding.dimension_mismatch.\" + model"));
        assertFalse(source.contains("\"tag\", tag"));
        assertFalse(source.contains("\"embed.matryoshka.tag\", tag"));
        assertFalse(source.contains("retry on alternate url: {}"));
        assertFalse(source.contains("\"model not found in /api/tags: \" + model"));
        assertTrue(source.contains("modelHash"));
        assertTrue(source.contains("\"model not found in /api/tags modelHash=\" + hashOrEmpty(model) + \" modelLength=\""));
        assertTrue(source.contains("tagHash"));
        assertTrue(source.contains("\"embed.matryoshka.tagHash\""));
        assertTrue(source.contains("\"embed.matryoshka.tagLength\""));
        assertTrue(source.contains("urlHost"));
        assertTrue(source.contains("urlHash"));
        assertTrue(source.contains("retry on alternate urlHost={} urlHash={}"));
        assertTrue(source.contains("hashOrEmpty(model)"));
        assertTrue(source.contains("endpointHost(url)"));
    }

    @Test
    void ollamaEmbeddingModelDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/embedding/OllamaEmbeddingModel.java"));

        assertEquals(0, Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}").matcher(source).results().count(),
                "embedding fail-soft blocks need trace breadcrumbs instead of exact empty catch bodies");
    }

    @Test
    void ollamaEmbeddingFailSoftCatchesLeaveStageBreadcrumbs() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/embedding/OllamaEmbeddingModel.java"));

        assertOllamaStage(source, "batch");
        assertOllamaStage(source, "single");
        assertOllamaStage(source, "ollama.embedProbeDebugEvent");
        assertOllamaStage(source, "blankInput.trace");
        assertOllamaStage(source, "endpointHost");
        assertOllamaStage(source, "backupVector.failoverTrace");
        assertOllamaStage(source, "backupBatch.failoverTrace");
        assertOllamaStage(source, "fastFail.localOkTrace");
        assertOllamaStage(source, "fastFail.localFailTrace");
        assertOllamaStage(source, "fastFail.trippedTrace");
        assertOllamaStage(source, "health.cacheHitTrace");
        assertOllamaStage(source, "health.concurrentWait");
        assertOllamaStage(source, "health.concurrentSkipTrace");
        assertOllamaStage(source, "health.concurrentLocalProbeTrace");
        assertOllamaStage(source, "health.okTrace");
        assertOllamaStage(source, "health.failTrace");
        assertOllamaStage(source, "embedProbe");
        assertOllamaStage(source, "ollama.postAttemptTrace");
        assertOllamaStage(source, "ollama.postOkTrace");
        assertOllamaStage(source, "dimensionsOption.suppressedTrace");
        assertOllamaStage(source, "dimensionsOption.debugEvent");
        assertOllamaStage(source, "ollama.retryNoDimensionsTrace");
        assertOllamaStage(source, "ollama.retryNoDimensionsOkTrace");
        assertOllamaStage(source, "ollama.retryNoDimensions");
        assertOllamaStage(source, "ollama.postFailTrace");
        assertOllamaStage(source, "dimensionsOption.parse");
        assertOllamaStage(source, "flatten.messageTrace");
        assertOllamaStage(source, "flatten.bodyTrace");
        assertOllamaStage(source, "getJsonWithFallback");
        assertOllamaStage(source, "matryoshka.trace");
    }

    @Test
    void dimensionsParserOnlyCatchesNumberFormatException() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/embedding/OllamaEmbeddingModel.java"));
        int start = source.indexOf("private static Integer extractDimensionsOption");
        int parse = source.indexOf("Integer.parseInt", start);
        int end = source.indexOf("\n    }", parse);
        assertTrue(start >= 0 && parse > start && end > parse, "extractDimensionsOption helper should be locatable");
        String helper = source.substring(start, end);

        assertFalse(helper.contains("catch (Exception"),
                "numeric fallback parser must not swallow all Exception");
        assertTrue(helper.contains("catch (NumberFormatException"),
                "numeric fallback parser should only catch NumberFormatException");
    }

    @Test
    void backupFailureLogsUseHashOnlyThrowableSummary() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/embedding/OllamaEmbeddingModel.java"));

        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(source.contains("backup embedding failed (stage={}): {}\", stage, shortErr(e)"));
        assertTrue(source.contains("backup embedAll failed (stage={}): {}\", stage, shortErr(e)"));
    }

    @Test
    void fallbackDisabledKeepsOnlyPrimaryUrl() {
        OllamaEmbeddingModel model = newModel(false, false);

        List<String> urls = model.buildCandidateUrls(
                "http://localhost:11435/api/embed",
                "http://localhost:11434/api/embed");

        assertEquals(List.of("http://localhost:11435/api/embed"), urls);
    }

    @Test
    void portFallbackEnabledAllowsAlternatePort() {
        OllamaEmbeddingModel model = newModel(true, false);

        List<String> urls = model.buildCandidateUrls(
                "http://localhost:11435/api/embed",
                "");

        assertTrue(urls.contains("http://localhost:11435/api/embed"));
        assertTrue(urls.contains("http://localhost:11434/api/embed"));
    }

    @Test
    void crossGpuFallbackEnabledAllowsExplicitSecondaryUrl() {
        OllamaEmbeddingModel model = newModel(false, true);

        List<String> urls = model.buildCandidateUrls(
                "http://localhost:11435/api/embed",
                "http://localhost:11434/api/embed");

        assertTrue(urls.contains("http://localhost:11435/api/embed"));
        assertTrue(urls.contains("http://localhost:11434/api/embed"));
    }

    @Test
    void qwen4bRaw2560VectorIsSlicedToConfigured1536Contract() {
        OllamaEmbeddingModel model = newModel(false, false);
        ReflectionTestUtils.setField(model, "dimensions", 1536);
        ReflectionTestUtils.setField(model, "providerRawDimensions", 2560);
        ReflectionTestUtils.setField(model, "normalizationMode", "SLICE_TO_CONFIGURED_DIM");
        ReflectionTestUtils.setField(model, "dimensionsOptionEnabled", true);
        ReflectionTestUtils.setField(model, "dimensionGuardMode", "WARN_ONLY");
        ReflectionTestUtils.setField(model, "logDimensionMismatch", false);

        float[] raw = new float[2560];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = i;
        }

        float[] normalized = ReflectionTestUtils.invokeMethod(model, "normalizeEmbedding", raw, "test");

        assertEquals(1536, normalized.length);
        assertEquals(raw[0], normalized[0]);
        assertEquals(raw[1535], normalized[1535]);
        assertEquals(1536, model.indexDimensions());
        assertEquals(2560, TraceStore.get("embed.sourceDim"));
        assertEquals(2560, TraceStore.get("embed.matryoshka.rawDim"));
        assertEquals(1536, TraceStore.get("embed.matryoshka.targetDim"));
        assertEquals("40pct", TraceStore.get("embed.matryoshka.dimensionReduction"));
        assertEquals(2560, TraceStore.get("embed.matryoshka.slice.actual"));
        assertEquals(1536, TraceStore.get("embed.matryoshka.slice.target"));
        assertEquals(0.4d, TraceStore.get("embed.matryoshka.slice.reductionRatio"));
        assertEquals(0.6d, TraceStore.get("embed.matryoshka.slice.expectedDistanceOpsRatio"));
        assertEquals(1.6667d, TraceStore.get("embed.matryoshka.slice.expectedDistanceOpsSpeedup"));
        assertEquals("MRL_PREFIX", TraceStore.get("embed.sliceMethod"));
        assertEquals(Boolean.TRUE, TraceStore.get("embed.normalizeApplied"));
        assertEquals("MRL", TraceStore.get("embed.sliceReason"));
        assertEquals(2560, TraceStore.get("embedding.rawDimension"));
        assertEquals(1536, TraceStore.get("embedding.slicedDimension"));
        assertEquals(Boolean.TRUE, TraceStore.get("embedding.matryoshkaSliced"));
        assertEquals("", TraceStore.get("embedding.sliceSkipReason"));

        Map<String, Object> diagnostics = model.diagnosticsSnapshot();
        assertEquals(2560, diagnostics.get("rawProviderDim"));
        assertEquals(1536, diagnostics.get("targetDim"));
        assertEquals("SLICE_TO_CONFIGURED_DIM", diagnostics.get("normalizationMode"));
        assertEquals("unknown", diagnostics.get("dimensionsOptionEffective"));
    }

    @Test
    void strictModeRejectsRaw2560Configured1536Mismatch() {
        OllamaEmbeddingModel model = newModel(false, false);
        ReflectionTestUtils.setField(model, "dimensions", 1536);
        ReflectionTestUtils.setField(model, "dimensionGuardMode", "STRICT");

        float[] raw = new float[2560];

        assertThrows(IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(model, "normalizeEmbedding", raw, "test"));
    }

    @Test
    void strictModeRejectsUnderDimConfigured1536Mismatch() {
        OllamaEmbeddingModel model = newModel(false, false);
        ReflectionTestUtils.setField(model, "dimensions", 1536);
        ReflectionTestUtils.setField(model, "dimensionGuardMode", "STRICT");

        float[] raw = new float[768];

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(model, "normalizeEmbedding", raw, "test"));
        assertTrue(ex.getMessage().contains("underflow"));
    }

    @Test
    void nonLocalProviderRejectsUnderDimPaddingByDefault() {
        OllamaEmbeddingModel model = newModel(false, false);
        ReflectionTestUtils.setField(model, "dimensions", 1536);
        ReflectionTestUtils.setField(model, "dimensionGuardMode", "WARN_ONLY");
        ReflectionTestUtils.setField(model, "provider", "remote-openai-compatible");

        float[] raw = new float[768];

        assertThrows(IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(model, "normalizeEmbedding", raw, "test"));
    }

    @Test
    void emptyEmbeddingVectorFailsInsteadOfPaddingToConfiguredDimension() {
        OllamaEmbeddingModel model = newModel(false, false);
        ReflectionTestUtils.setField(model, "dimensions", 1536);
        ReflectionTestUtils.setField(model, "dimensionGuardMode", "WARN_ONLY");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(model, "normalizeEmbedding", new float[0], "private-query"));

        assertTrue(ex.getMessage().contains("empty vector"));
        assertFalse(ex.getMessage().contains("private-query"));
        assertEquals(0, TraceStore.get("embedding.rawDimension"));
        assertEquals(1536, TraceStore.get("embedding.slicedDimension"));
        assertEquals(Boolean.FALSE, TraceStore.get("embedding.matryoshkaSliced"));
        assertEquals("empty_vector_rejected", TraceStore.get("embedding.sliceSkipReason"));
    }

    @Test
    void blankEmbeddingInputFailsInsteadOfReturningEmptyVector() {
        TraceStore.clear();
        OllamaEmbeddingModel model = newModel(false, false);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> model.embed("   "));

        assertTrue(ex.getMessage().contains("blank"));
        assertEquals(1L, TraceStore.getLong("embed.blank_input.rejected"));
        assertEquals("single", TraceStore.get("embed.blank_input.stage"));
        TraceStore.clear();
    }

    @Test
    void allowlistedRemoteEmbeddingEndpointGetsOwnerTokenHeader() {
        OllamaEmbeddingModel model = newModel(false, false);
        ReflectionTestUtils.setField(model, "ownerToken", "owner-secret-value");
        ReflectionTestUtils.setField(model, "ownerTokenHeader", "X-Owner-Token");
        ReflectionTestUtils.setField(model, "allowedHosts", "macmini-ollama.internal");

        Map<String, String> headers = model.gatewayHeadersForUrl("https://macmini-ollama.internal/api/embed");

        assertEquals("owner-secret-value", headers.get("X-Owner-Token"));
    }

    @Test
    void externalProviderEndpointDoesNotReceiveOwnerTokenHeader() {
        OllamaEmbeddingModel model = newModel(false, false);
        ReflectionTestUtils.setField(model, "ownerToken", "owner-secret-value");
        ReflectionTestUtils.setField(model, "ownerTokenHeader", "X-Owner-Token");
        ReflectionTestUtils.setField(model, "allowedHosts", "api.openai.com");

        Map<String, String> headers = model.gatewayHeadersForUrl("https://api.openai.com/v1/embeddings");

        assertTrue(headers.isEmpty());
    }

    @Test
    void remoteEmbeddingEndpointFailsClosedWithoutAllowlistAndToken() {
        OllamaEmbeddingModel model = newModel(false, false);
        ReflectionTestUtils.setField(model, "allowPrivateRemote", false);
        ReflectionTestUtils.setField(model, "requireAuthForRemote", true);
        ReflectionTestUtils.setField(model, "llmApiKey", "sk-local");
        ReflectionTestUtils.setField(model, "ownerToken", "");

        assertThrows(IllegalStateException.class,
                () -> model.assertGatewayAllowedForUrl("https://macmini-ollama.internal/api/embed"));
    }

    @Test
    void embedBodyUsesTopLevelDimensionsAndRetryStripRemovesThem() {
        OllamaEmbeddingModel model = newModel(false, false);
        ReflectionTestUtils.setField(model, "model", "qwen3-embedding:4b");
        ReflectionTestUtils.setField(model, "dimensionsOptionEnabled", true);

        Map<String, Object> body = ReflectionTestUtils.invokeMethod(model, "buildEmbedBody", "ping", 1536, "");
        assertEquals("qwen3-embedding:4b", body.get("model"));
        assertEquals(1536, body.get("dimensions"));

        Map<String, Object> stripped = ReflectionTestUtils.invokeMethod(OllamaEmbeddingModel.class, "stripDimensionsOption", body);
        assertFalse(stripped.containsKey("dimensions"));
    }

    @Test
    void missingBackupFailsExplicitlyInsteadOfReturningEmptyVector() {
        OllamaEmbeddingModel model = newModel(false, false);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(model, "callBackupVector", "ping", "health"));

        assertTrue(ex.getMessage().contains("backup model is missing"));
    }

    @Test
    void missingBatchBackupFailsExplicitlyInsteadOfReturningEmptyEmbeddings() {
        OllamaEmbeddingModel model = newModel(false, false);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ReflectionTestUtils.invokeMethod(model, "callBackupBatch", List.of(), "health"));

        assertTrue(ex.getMessage().contains("backup model is missing"));
    }

    @Test
    void concurrentHealthCheckWithoutBackupAllowsLocalProbeInsteadOfMissingBackup() {
        TraceStore.clear();
        OllamaEmbeddingModel model = newModel(false, false);
        ReflectionTestUtils.setField(model, "fastFailEnabled", true);
        ReflectionTestUtils.setField(model, "fastFailHealthEnabled", true);
        ReflectionTestUtils.setField(model, "fastFailHealthConcurrentGuard", true);

        AtomicBoolean healthInFlight = (AtomicBoolean) ReflectionTestUtils.getField(model, "healthInFlight");
        healthInFlight.set(true);

        Boolean healthy = ReflectionTestUtils.invokeMethod(model, "ensureLocalHealthy", "batch");

        assertTrue(Boolean.TRUE.equals(healthy));
        assertEquals(1L, TraceStore.getLong("embed.fastfail.health.concurrent_local_probe"));
        TraceStore.clear();
    }

    @Test
    void dimensionsInspectionMasksSecretLikeErrorBodyValues() {
        String fakeApiKey = "sk-" + "ollama-secret-1234567890";
        String body = "{\"error\":\"dimensions invalid option api_key=" + fakeApiKey + " Authorization: " + "Bearer " + "raw-owner-token-123456\"}";
        WebClientResponseException ex = WebClientResponseException.create(
                400,
                "Bad Request",
                HttpHeaders.EMPTY,
                body.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        String flat = ReflectionTestUtils.invokeMethod(OllamaEmbeddingModel.class, "flattenForInspection", ex);

        assertTrue(flat.toLowerCase().contains("dimensions"));
        assertFalse(flat.contains(fakeApiKey));
        assertFalse(flat.contains("raw-owner-token"));
    }

    @Test
    void dimensionsInspectionSummarizesNonSecretErrorBodyText() {
        String privateText = "dimensions invalid option for private corpus patient-42";
        String body = "{\"error\":\"" + privateText + "\"}";
        WebClientResponseException ex = WebClientResponseException.create(
                400,
                "Bad Request",
                HttpHeaders.EMPTY,
                body.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        String flat = ReflectionTestUtils.invokeMethod(OllamaEmbeddingModel.class, "flattenForInspection", ex);

        assertTrue(flat.toLowerCase().contains("dimensions"));
        assertFalse(flat.contains(body));
        assertFalse(flat.contains(privateText));
        assertTrue(flat.contains("bodyHash"));
        assertTrue(flat.contains("bodyLength"));
    }

    @Test
    void shortErrUsesThrowableHashAndLengthOnly() {
        String fakeApiKey = "sk-" + "ollama-shorterr-secret-1234567890";
        RuntimeException ex = new RuntimeException("embed failure api_key=" + fakeApiKey);

        String safe = ReflectionTestUtils.invokeMethod(OllamaEmbeddingModel.class, "shortErr", ex);

        assertTrue(safe.contains("errorType=RuntimeException"));
        assertTrue(safe.contains("errorHash="));
        assertTrue(safe.contains("errorLength="));
        assertFalse(safe.contains(fakeApiKey));
        assertFalse(safe.contains("embed failure"));
    }

    private static void assertOllamaStage(String source, String stage) {
        assertTrue(source.contains("log.debug(\"[OllamaEmbeddingModel] fail-soft stage={}\", \"" + stage + "\")"),
                () -> "missing Ollama fail-soft stage: " + stage);
    }

    private static OllamaEmbeddingModel newModel(boolean portFallback, boolean crossGpuFallback) {
        OllamaEmbeddingModel model = new OllamaEmbeddingModel(WebClient.builder().build());
        ReflectionTestUtils.setField(model, "portFallbackEnabled", portFallback);
        ReflectionTestUtils.setField(model, "crossGpuFallbackEnabled", crossGpuFallback);
        return model;
    }
}
