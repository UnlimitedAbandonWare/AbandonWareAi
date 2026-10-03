package com.example.lms.service.rag;

import com.example.lms.search.TraceStore;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LangChainRAGServiceVectorDiagnosticsTest {

    private final List<LangChainRAGService> managedServices = new java.util.ArrayList<>();

    private LangChainRAGService managedService(EmbeddingModel model, EmbeddingStore<TextSegment> store) {
        LangChainRAGService service = new LangChainRAGService(model, store);
        managedServices.add(service);
        return service;
    }

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void embeddingFailurePreservesWebEvidenceAndContinuesRetrievalChain() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(anyString())).thenThrow(new IllegalStateException("embedding_space_unverified"));
        @SuppressWarnings("unchecked")
        EmbeddingStore<TextSegment> store = mock(EmbeddingStore.class);
        var handler = new com.example.lms.service.rag.handler.VectorDbHandler(
                managedService(model, store), "test-index", null);
        handler.linkWith(new com.example.lms.service.rag.handler.AbstractRetrievalHandler() {
            @Override protected boolean doHandle(Query query, List<Content> accumulator) {
                accumulator.add(Content.from("subsequent evidence"));
                return false;
            }
        });
        List<Content> accumulator = new java.util.ArrayList<>(List.of(Content.from("existing web evidence")));

        handler.handle(new Query("synthetic retrieval"), accumulator);

        assertEquals(List.of("existing web evidence", "subsequent evidence"),
                accumulator.stream().map(content -> content.textSegment().text()).toList());
        org.mockito.Mockito.verifyNoInteractions(store);
        assertEquals("exception", TraceStore.get("vector.retrieval.emptyReason"));
    }

    @Test
    void vectorRetrieverRecordsNoMatchesWithoutRawQuery() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        @SuppressWarnings("unchecked")
        EmbeddingStore<TextSegment> store = mock(EmbeddingStore.class);
        when(model.embed("vector starvation")).thenReturn(Response.from(Embedding.from(new float[] {1.0f})));
        when(store.search(any(EmbeddingSearchRequest.class))).thenReturn(new EmbeddingSearchResult<>(List.of()));
        LangChainRAGService service = managedService(model, store);

        List<Content> out = service.asContentRetriever("test-index").retrieve(new Query("vector starvation"));

        assertTrue(out.isEmpty());
        assertEquals(5, TraceStore.get("vector.retrieval.requestedTopK"));
        assertEquals(5, TraceStore.get("vector.retrieval.poolK"));
        assertEquals(0, TraceStore.get("vector.retrieval.rawMatchCount"));
        assertEquals(0, TraceStore.get("vector.retrieval.keptCount"));
        assertEquals("no_matches", TraceStore.get("vector.retrieval.emptyReason"));
        assertEquals("none", TraceStore.get("vector.retrieval.failureClass"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("vector starvation"));
    }

    @Test
    void vectorRetrieverRecordsFailureClassWithoutExceptionBody() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        @SuppressWarnings("unchecked")
        EmbeddingStore<TextSegment> store = mock(EmbeddingStore.class);
        when(model.embed("sensitive vector query")).thenReturn(Response.from(Embedding.from(new float[] {1.0f})));
        when(store.search(any(EmbeddingSearchRequest.class)))
                .thenThrow(new IllegalStateException("raw sensitive body"));
        LangChainRAGService service = managedService(model, store);

        List<Content> out = service.asContentRetriever("test-index").retrieve(new Query("sensitive vector query"));

        assertTrue(out.isEmpty());
        assertEquals("exception", TraceStore.get("vector.retrieval.emptyReason"));
        assertEquals("IllegalStateException", TraceStore.get("vector.retrieval.failureClass"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("sensitive vector query"));
        assertFalse(trace.contains("raw sensitive body"));
    }

    @Test
    void activeSidResolverFailureLeavesTraceStoreBreadcrumb() throws Exception {
        EmbeddingModel model = mock(EmbeddingModel.class);
        @SuppressWarnings("unchecked")
        EmbeddingStore<TextSegment> store = mock(EmbeddingStore.class);
        com.example.lms.service.vector.VectorSidService sidService =
                mock(com.example.lms.service.vector.VectorSidService.class);
        when(sidService.resolveActiveSid(anyString()))
                .thenThrow(new IllegalStateException("ownerToken=raw-sid-secret"));
        LangChainRAGService service = managedService(model, store);
        java.lang.reflect.Field field = LangChainRAGService.class.getDeclaredField("vectorSidService");
        field.setAccessible(true);
        field.set(service, sidService);
        Method method = LangChainRAGService.class.getDeclaredMethod("activeGlobalSid");
        method.setAccessible(true);

        assertEquals(LangChainRAGService.GLOBAL_SID, method.invoke(service));
        assertEquals(Boolean.TRUE, TraceStore.get("vector.retrieval.activeGlobalSid.suppressed"));
        assertEquals("IllegalStateException", TraceStore.get("vector.retrieval.activeGlobalSid.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("ownerToken=raw-sid-secret"));
    }

    @Test
    void serviceLogsDoNotUseRawThrowableMessagesOrRawSid() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/LangChainRAGService.java"),
                StandardCharsets.UTF_8);
        List<String> rawThrowableLogLines = source.lines()
                .filter(line -> line.contains("log."))
                .filter(line -> line.contains(".getMessage()") || line.contains(".toString()"))
                .filter(line -> !line.contains("SafeRedactor.safeMessage("))
                .toList();

        assertEquals(List.of(), rawThrowableLogLines);
        assertFalse(source.contains("sid={}, err={}\", sid,"));
        assertFalse(source.contains("Vector 0 matches sid={}\", sid"));
        assertTrue(source.contains("Vector 0 matches sidHash={}"));
    }

    @Test
    void vectorEmptyReasonTraceUsesTraceLabel() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/LangChainRAGService.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("TraceStore.put(\"vector.retrieval.emptyReason\", emptyReason);"));
        assertTrue(source.contains(
                "TraceStore.put(\"vector.retrieval.emptyReason\", SafeRedactor.traceLabelOrFallback(emptyReason, \"unknown\"));"));
    }

    @Test
    void vectorMetadataParsersOnlyCatchNumberFormatException() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/LangChainRAGService.java"),
                StandardCharsets.UTF_8);

        assertParserCatchNarrowed(source, "private static double resolveMinScore(Map<String, Object> meta, double def)");
        assertParserCatchNarrowed(source, "private static int metaInt(Map<String, Object> meta, String key, int def)");
    }

    @Test
    void vectorMetadataTopKParserDropsNonFiniteNumbers() throws Exception {
        Method method = LangChainRAGService.class.getDeclaredMethod(
                "metaInt", Map.class, String.class, int.class);
        method.setAccessible(true);

        assertEquals(5, method.invoke(null, Map.of("vectorTopK", Double.POSITIVE_INFINITY), "vectorTopK", 5));
        assertEquals(5, method.invoke(null, Map.of("vectorTopK", Double.NaN), "vectorTopK", 5));
    }

    @Test
    void vectorMetadataParseFallbacksLeaveRedactedTraceBreadcrumbs() throws Exception {
        Method minScore = LangChainRAGService.class.getDeclaredMethod(
                "resolveMinScore", Map.class, double.class);
        minScore.setAccessible(true);
        Method topK = LangChainRAGService.class.getDeclaredMethod(
                "metaInt", Map.class, String.class, int.class);
        topK.setAccessible(true);
        String rawSecret = "ownerToken=raw-vector-parser-secret";

        assertEquals(0.6d, ((Number) minScore.invoke(null, Map.of("vecMinScore", rawSecret), 0.6d)).doubleValue(), 1.0e-9);
        assertEquals(5, topK.invoke(null, Map.of("vectorTopK", rawSecret), "vectorTopK", 5));

        assertEquals(Boolean.TRUE, TraceStore.get("vector.retrieval.minScore.parseFallback"));
        assertEquals("invalid_number", TraceStore.get("vector.retrieval.minScore.errorType"));
        assertEquals(Boolean.TRUE, TraceStore.get("vector.retrieval.metaInt.parseFallback"));
        assertEquals("invalid_number", TraceStore.get("vector.retrieval.metaInt.errorType"));
        assertEquals("vectorTopK", TraceStore.get("vector.retrieval.metaInt.key"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(rawSecret));
    }

    @Test
    void vectorMetadataNumericFallbacksLeaveRedactedTraceBreadcrumbs() throws Exception {
        Method minScore = LangChainRAGService.class.getDeclaredMethod(
                "resolveMinScore", Map.class, double.class);
        minScore.setAccessible(true);
        Method topK = LangChainRAGService.class.getDeclaredMethod(
                "metaInt", Map.class, String.class, int.class);
        topK.setAccessible(true);

        assertEquals(0.6d, ((Number) minScore.invoke(null, Map.of("vecMinScore", Double.NaN), 0.6d)).doubleValue(), 1.0e-9);
        assertEquals(0.6d, ((Number) minScore.invoke(null, Map.of("vecMinScore", 2.0d), 0.6d)).doubleValue(), 1.0e-9);
        assertEquals(5, topK.invoke(null, Map.of("vectorTopK", Double.NEGATIVE_INFINITY), "vectorTopK", 5));

        assertEquals(Boolean.TRUE, TraceStore.get("vector.retrieval.minScore.parseFallback"));
        assertEquals("invalid_number", TraceStore.get("vector.retrieval.minScore.errorType"));
        assertEquals(Boolean.TRUE, TraceStore.get("vector.retrieval.metaInt.parseFallback"));
        assertEquals("invalid_number", TraceStore.get("vector.retrieval.metaInt.errorType"));
        assertEquals("vectorTopK", TraceStore.get("vector.retrieval.metaInt.key"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("Infinity"));
    }

    @Test
    void vectorTopKClampLeavesTraceBreadcrumb() throws Exception {
        Method method = LangChainRAGService.class.getDeclaredMethod(
                "resolveTopK", Map.class, int.class);
        method.setAccessible(true);

        assertEquals(50, method.invoke(null, Map.of("vectorTopK", 500), 5));

        assertEquals(Boolean.TRUE, TraceStore.get("vector.retrieval.topK.clamped"));
        assertEquals("max_exceeded", TraceStore.get("vector.retrieval.topK.clampReason"));
        assertEquals("vectorTopK", TraceStore.get("vector.retrieval.topK.key"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("ownerToken"));
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "vector metadata={0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"authored", "lower", "higher", "removed",
            "caller", "sid", "rebuild", "plain", "vendor_sid", "canonical_alias", "min_score", "clamped", "null_query"})
    void planSidecarControlsActualVectorStoreRequest(String control) throws Exception {
        String planId = "kg_first.v1";
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var original = mapper.readTree(Files.readString(Path.of("main/resources/plans", planId + ".yaml"), StandardCharsets.UTF_8));
        assertEquals(8, original.path("retrieval").path("topk").path("vector").asInt());
        var modified = (com.fasterxml.jackson.databind.node.ObjectNode) original.deepCopy();
        var topk = (com.fasterxml.jackson.databind.node.ObjectNode) modified.path("retrieval").path("topk");
        if (control.equals("lower")) topk.put("vector", 3);
        if (control.equals("higher")) topk.put("vector", 12);
        if (control.equals("removed")) topk.remove("vector");
        if (control.equals("clamped")) topk.put("vector", 80);
        var restored = modified.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) restored.path("retrieval").path("topk"))
                .set("vector", original.path("retrieval").path("topk").path("vector"));
        assertEquals(original, restored, "only the plan vector count changes");
        byte[] bytes = mapper.writeValueAsBytes(modified);
        var resources = new org.springframework.core.io.DefaultResourceLoader() {
            @Override public org.springframework.core.io.Resource getResource(String location) {
                if (("classpath:plans/" + planId + ".yaml").equals(location)) return new org.springframework.core.io.ByteArrayResource(bytes) {
                    @Override public String getFilename() { return planId + ".yaml"; }
                };
                return super.getResource(location);
            }
        };
        var applier = new com.example.lms.plan.PlanHintApplier(control.equals("authored")
                ? new org.springframework.core.io.DefaultResourceLoader() : resources);
        var plan = applier.load(planId); assertEquals(planId, plan.planId());
        var hints = com.example.lms.orchestration.OrchestrationHints.defaults();
        hints.setVecTopK(control.equals("caller") ? 12 : 4);
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        applier.applyToHintsAndMeta(plan, hints, metadata);
        assertEquals(3500L, hints.getVecBudgetMs()); assertEquals(3500L, metadata.get("vecBudgetMs"));
        if (control.equals("sid")) metadata.put("sid", "synthetic-session");
        if (control.equals("canonical_alias")) metadata.put("vectorTopK", 6);
        if (control.equals("min_score")) metadata.put("vecMinScore", 0.8d);
        Query query = QueryUtils.buildQuery("synthetic vector metadata", metadata);
        if (control.equals("rebuild")) query = QueryUtils.rebuild(query, "synthetic vector rebuilt");
        if (control.equals("plain")) query = new Query("synthetic vector plain");
        if (control.equals("vendor_sid")) query = new Query("synthetic vector vendor",
                dev.langchain4j.rag.query.Metadata.from(dev.langchain4j.data.message.UserMessage.from("synthetic vector vendor"),
                        "synthetic-session", List.of()));
        if (control.equals("null_query")) query = null;
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(anyString())).thenReturn(Response.from(Embedding.from(new float[] {1.0f})));
        @SuppressWarnings("unchecked") EmbeddingStore<TextSegment> store = mock(EmbeddingStore.class);
        when(store.search(any(EmbeddingSearchRequest.class))).thenReturn(new EmbeddingSearchResult<>(List.of()));
        var service = managedService(model, store);
        assertTrue(service.asContentRetriever("test-index").retrieve(query).isEmpty());
        if (query == null) { org.mockito.Mockito.verifyNoInteractions(model, store); return; }
        int expectedK = switch (control) {
            case "lower" -> 3; case "higher" -> 12; case "removed" -> 4;
            case "plain", "vendor_sid" -> 5; case "canonical_alias" -> 6;
            case "clamped" -> 50; default -> 8;
        };
        var request = org.mockito.ArgumentCaptor.forClass(EmbeddingSearchRequest.class);
        org.mockito.Mockito.verify(store).search(request.capture());
        org.mockito.Mockito.verify(model).embed(query.text());
        assertEquals(expectedK, request.getValue().maxResults());
        assertEquals(control.equals("min_score") ? 0.8d : 0.6d, request.getValue().minScore(), 1.0e-9);
        Method reader = LangChainRAGService.class.getDeclaredMethod("toMetaMap", Query.class);
        reader.setAccessible(true);
        assertEquals(QueryUtils.metadata(query), reader.invoke(null, query), "budget propagation is not deadline enforcement");
        assertFalse(String.valueOf(TraceStore.getAll()).contains("synthetic-session"));
        System.out.printf("TBL07_VECTOR_METADATA control=%s providerMaxResults=%d budgetEnforcement=not_verified%n", control, expectedK);
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "vector filter={0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"doc_type", "scope_anchor", "scope_part", "scope_relax", "doc_relax"})
    void sidecarFiltersConstrainActualStoreRequestAndKeepExistingRelaxation(String control) throws Exception {
        boolean scope = control.startsWith("scope_");
        boolean relaxed = control.endsWith("relax");
        Map<String, Object> metadata = new java.util.LinkedHashMap<>(Map.of("sid", "synthetic-session", "allowed_doc_types", "KB"));
        if (scope) metadata.put("scope_anchor_key", "alpha");
        if (control.equals("scope_part")) { metadata.put("scope_kind", "PART"); metadata.put("scope_part_key", "one"); }
        var embedding = Embedding.from(new float[] {1.0f});
        EmbeddingModel model = mock(EmbeddingModel.class); when(model.embed(anyString())).thenReturn(Response.from(embedding));
        @SuppressWarnings("unchecked") EmbeddingStore<TextSegment> store = mock(EmbeddingStore.class);
        var hit = new EmbeddingSearchResult<TextSegment>(List.of(new dev.langchain4j.store.embedding.EmbeddingMatch<>(
                0.9d, "synthetic-match", embedding, TextSegment.from("synthetic evidence"))));
        if (relaxed) when(store.search(any(EmbeddingSearchRequest.class))).thenReturn(new EmbeddingSearchResult<>(List.of()), hit);
        else when(store.search(any(EmbeddingSearchRequest.class))).thenReturn(hit);
        var service = managedService(model, store);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "docTypeFilterEnabled", true);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "docTypeAllowedCsv", "KB,MEMORY,LEGACY");
        org.springframework.test.util.ReflectionTestUtils.setField(service, "docTypeFilterMinMatches", 1);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "scopeFilterEnabled", scope);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "scopeFilterMinMatches", 1);
        assertEquals(1, service.asContentRetriever("test-index").retrieve(QueryUtils.buildQuery("synthetic filter", metadata)).size());
        var captor = org.mockito.ArgumentCaptor.forClass(EmbeddingSearchRequest.class);
        org.mockito.Mockito.verify(store, org.mockito.Mockito.times(relaxed ? 2 : 1)).search(captor.capture());
        var filter = captor.getAllValues().get(0).filter();
        var allowed = new dev.langchain4j.data.document.Metadata(Map.of("sid", "synthetic-session", "doc_type", "KB",
                "scope_anchor_key", "alpha", "scope_part_key", "one"));
        assertTrue(filter.test(allowed));
        var global = new dev.langchain4j.data.document.Metadata(allowed.toMap()).put("sid", LangChainRAGService.GLOBAL_SID);
        assertTrue(filter.test(global));
        var foreign = new dev.langchain4j.data.document.Metadata(allowed.toMap()).put("sid", "synthetic-other");
        assertFalse(filter.test(foreign));
        var otherType = new dev.langchain4j.data.document.Metadata(allowed.toMap()).put("doc_type", "MEMORY");
        assertFalse(filter.test(otherType), "query allowlist narrows the server allowlist");
        var otherAnchor = new dev.langchain4j.data.document.Metadata(allowed.toMap()).put("scope_anchor_key", "beta");
        assertEquals(!scope, filter.test(otherAnchor));
        var otherPart = new dev.langchain4j.data.document.Metadata(allowed.toMap()).put("scope_part_key", "two");
        assertEquals(!control.equals("scope_part"), filter.test(otherPart));
        if (relaxed) {
            var fallback = captor.getAllValues().get(1).filter();
            assertTrue(fallback.test(otherAnchor)); assertFalse(fallback.test(foreign));
            assertEquals(control.equals("doc_relax"), fallback.test(otherType));
        }
        assertFalse(String.valueOf(TraceStore.getAll()).contains("synthetic-session"));
        System.out.printf("TBL07_VECTOR_FILTER control=%s storeCalls=%d sidRestrictionPreserved=true%n", control, relaxed ? 2 : 1);
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
        assertTrue(method.contains("fail-soft stage={} errorType={}")
                        && method.contains("\"invalid_number\""),
                "numeric fallback parser should use stable invalid_number error label: " + signature);
    }
}
