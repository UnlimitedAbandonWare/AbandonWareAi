package com.example.lms.transform;

import ai.abandonware.nova.orch.trace.OrchTrace;
import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.rag.model.QueryDomain;
import com.example.lms.search.TraceStore;
import com.example.lms.search.probe.EvidenceSignals;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.trace.SafeRedactor;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryTransformerNeedleTraceTest {

    @BeforeEach
    void setUp() {
        TraceStore.clear();
        GuardContextHolder.clear();
    }

    @AfterEach
    void tearDown() {
        TraceStore.clear();
        GuardContextHolder.clear();
    }

    @Test
    void suggestAuthoritySitesStoresOnlyCountAndHashes() throws Exception {
        QueryTransformer transformer = transformer("""
                example.com
                docs.example.org
                """);

        List<String> sites = transformer.suggestAuthoritySites(
                "raw private prompt with internal anchor",
                QueryDomain.GENERAL,
                Locale.ENGLISH,
                2);

        assertEquals(List.of("example.com", "docs.example.org"), sites);
        assertNull(TraceStore.get("probe.needle.llm.sites"));
        assertEquals(2, TraceStore.get("probe.needle.llm.siteCount"));
        assertEquals(List.of(
                SafeRedactor.hash12("example.com"),
                SafeRedactor.hash12("docs.example.org")), TraceStore.get("probe.needle.llm.siteHashes"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("raw private prompt"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("example.com"));
    }

    @Test
    void generateNeedleProbeQueriesStoresOnlyCountAndHashes() throws Exception {
        QueryTransformer transformer = transformer("""
                exact anchor site:example.com
                corroborating source site:docs.example.org
                """);

        List<String> queries = transformer.generateNeedleProbeQueries(
                "raw private prompt with internal anchor",
                QueryDomain.GENERAL,
                EvidenceSignals.empty(),
                List.of("example.com", "docs.example.org"),
                List.of(),
                Locale.ENGLISH,
                2);

        assertEquals(List.of(
                "exact anchor site:example.com",
                "corroborating source site:docs.example.org"), queries);
        assertNull(TraceStore.get("probe.needle.llm.queries"));
        assertEquals(2, TraceStore.get("probe.needle.llm.queryCount"));
        assertEquals(List.of(
                SafeRedactor.hash12("exact anchor site:example.com"),
                SafeRedactor.hash12("corroborating source site:docs.example.org")),
                TraceStore.get("probe.needle.llm.queryHashes"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("raw private prompt"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("exact anchor site:example.com"));
    }

    @Test
    void needlePlanningHelpersLiveOutsideQueryTransformerLargeFile() throws Exception {
        var transformerPath = java.nio.file.Path.of("main/java/com/example/lms/transform/QueryTransformer.java");
        var helperPath = java.nio.file.Path.of("main/java/com/example/lms/transform/QueryTransformerNeedlePlanner.java");

        String transformer = java.nio.file.Files.readString(transformerPath);

        assertTrue(java.nio.file.Files.exists(helperPath),
                "needle probe parsing and trace helpers should live outside QueryTransformer");
        String helper = java.nio.file.Files.readString(helperPath);
        assertTrue(transformer.contains("QueryTransformerNeedlePlanner.suggestAuthoritySites("));
        assertTrue(transformer.contains("QueryTransformerNeedlePlanner.generateNeedleProbeQueries("));
        assertFalse(transformer.contains("private static void putHashedListTrace("));
        assertFalse(transformer.contains("private static List<String> parseLines("));
        assertFalse(transformer.contains("private static String normalizeDomain("));
        assertTrue(helper.contains("final class QueryTransformerNeedlePlanner"));
        assertTrue(helper.contains("static List<String> generateNeedleProbeQueries("));
        assertTrue(helper.contains("traceSuppressed(\"hashedListTrace\", ignore)"));
        assertTrue(helper.contains("TraceStore.put(\"qtx.needle.suppressed.stage\", safeStage)"));
        assertTrue(helper.contains("TraceStore.put(\"qtx.needle.suppressed.errorType\", safeErrorType)"));
        assertTrue(helper.contains("TraceStore.put(\"qtx.needle.suppressed.\" + safeStage, true)"));
        assertTrue(helper.contains("TraceStore.put(\"qtx.needle.suppressed.\" + safeStage + \".errorType\", safeErrorType)"));
    }

    @Test
    void needleSuppressedTraceIncludesSafeAggregateStageAndErrorType() throws Exception {
        Method helper = QueryTransformerNeedlePlanner.class
                .getDeclaredMethod("traceSuppressed", String.class, Throwable.class);
        helper.setAccessible(true);

        helper.invoke(null, "ownerToken=needle-secret", new IllegalStateException("ownerToken=needle-secret"));

        Object safeStage = TraceStore.get("qtx.needle.suppressed.stage");
        assertTrue(String.valueOf(safeStage).startsWith("hash:"), String.valueOf(safeStage));
        assertEquals("IllegalStateException", TraceStore.get("qtx.needle.suppressed.errorType"));
        assertEquals(Boolean.TRUE, TraceStore.get("qtx.needle.suppressed." + safeStage));
        assertEquals("IllegalStateException", TraceStore.get("qtx.needle.suppressed." + safeStage + ".errorType"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("ownerToken"), trace);
        assertFalse(trace.contains("needle-secret"), trace);
    }

    @Test
    void variantSupportHelpersLiveOutsideQueryTransformerLargeFile() throws Exception {
        var transformerPath = java.nio.file.Path.of("main/java/com/example/lms/transform/QueryTransformer.java");
        var helperPath = java.nio.file.Path.of("main/java/com/example/lms/transform/QueryTransformerVariantSupport.java");

        String transformer = java.nio.file.Files.readString(transformerPath);

        assertTrue(java.nio.file.Files.exists(helperPath),
                "variant fallback and protected-term helpers should live outside QueryTransformer");
        String helper = java.nio.file.Files.readString(helperPath);
        assertTrue(transformer.contains("QueryTransformerVariantSupport.cheapVariantsFallback("));
        assertTrue(transformer.contains("QueryTransformerVariantSupport.filterUnwantedVariants("));
        assertFalse(transformer.contains("private List<String> cheapVariantsFallback("));
        assertFalse(transformer.contains("private boolean isEntitySafeVariant("));
        assertFalse(transformer.contains("private List<String> filterUnwantedVariants("));
        assertTrue(helper.contains("final class QueryTransformerVariantSupport"));
        assertTrue(helper.contains("static List<String> cheapVariantsFallback("));
        assertTrue(helper.contains("traceSuppressed(\"recoveredOrchTrace\", ignore)"));
        assertTrue(helper.contains("traceSuppressed(\"recoverContext\", ignore)"));
        assertTrue(helper.contains("traceSuppressed(\"protectedTerms\", ignored)"));
        assertTrue(helper.contains("TraceStore.put(\"qtx.variant.suppressed.stage\", safeStage)"));
        assertTrue(helper.contains("TraceStore.put(\"qtx.variant.suppressed.errorType\", safeErrorType)"));
        assertTrue(helper.contains("TraceStore.put(\"qtx.variant.suppressed.\" + safeStage + \".errorType\", safeErrorType)"));
    }

    @Test
    void queryTransformerDoesNotCarryDuplicateImports() throws Exception {
        String source = java.nio.file.Files.readString(
                java.nio.file.Path.of("main/java/com/example/lms/transform/QueryTransformer.java"));

        assertSingleImport(source, "import ai.abandonware.nova.orch.anchor.AnchorNarrower;");
        assertSingleImport(source, "import com.example.lms.service.guard.GuardContext;");
        assertSingleImport(source, "import com.example.lms.service.guard.GuardContextHolder;");
    }

    @Test
    void suppressedTraceHelperWritesRedactedTraceStoreBreadcrumb() throws Exception {
        Method helper = QueryTransformer.class.getDeclaredMethod("traceSuppressed", String.class);
        helper.setAccessible(true);

        helper.invoke(null, "ownerToken=raw-secret stage");

        Object safeStage = TraceStore.get("queryTransformer.suppressed.stage");
        assertEquals(Boolean.TRUE, TraceStore.get("queryTransformer.suppressed"));
        assertTrue(String.valueOf(safeStage).startsWith("hash:"), String.valueOf(safeStage));
        assertEquals(Boolean.TRUE, TraceStore.get("queryTransformer.suppressed." + safeStage));
        assertEquals(safeStage, TraceStore.get("queryTransformer.suppressed.errorType"));
        assertEquals(safeStage, TraceStore.get("queryTransformer.suppressed." + safeStage + ".errorType"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("raw-secret"), trace);
        assertFalse(trace.contains("ownerToken"), trace);
    }

    @Test
    void suppressedTraceHelperCanStoreActualExceptionClassWithoutRawStageOrMessage() throws Exception {
        Method helper = QueryTransformer.class.getDeclaredMethod("traceSuppressed", String.class, Throwable.class);
        helper.setAccessible(true);

        helper.invoke(null,
                "ownerToken=raw-secret stage",
                new IllegalStateException("api_key=" + "sk-" + "querytransformersecret1234567890"));

        Object safeStage = TraceStore.get("queryTransformer.suppressed.stage");
        assertEquals(Boolean.TRUE, TraceStore.get("queryTransformer.suppressed"));
        assertTrue(String.valueOf(safeStage).startsWith("hash:"), String.valueOf(safeStage));
        assertEquals("IllegalStateException", TraceStore.get("queryTransformer.suppressed.errorType"));
        assertEquals("IllegalStateException", TraceStore.get("queryTransformer.suppressed." + safeStage + ".errorType"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("raw-secret"), trace);
        assertFalse(trace.contains("ownerToken"), trace);
        assertFalse(trace.contains("api_key="), trace);
        assertFalse(trace.contains("querytransformersecret"), trace);
    }

    @Test
    void recoveredQueryTimelineStoresOnlyHashAndLength() throws Exception {
        String rawQuery = "private recovered query with internal anchor";
        GuardContext context = new GuardContext();
        context.setUserQuery(rawQuery);
        GuardContextHolder.set(context);

        QueryTransformer transformer = transformer("");

        List<String> variants = transformer.transform("", " ");

        assertFalse(variants.isEmpty());
        Object lastAux = TraceStore.get("orch.events.v1.last.aux");
        assertTrue(lastAux instanceof Map<?, ?>);
        Object data = ((Map<?, ?>) lastAux).get("data");
        assertTrue(data instanceof Map<?, ?>);
        Map<?, ?> eventData = (Map<?, ?>) data;

        assertEquals("guardContext.userQuery", eventData.get("source"));
        assertEquals(SafeRedactor.hash12(rawQuery), eventData.get("queryHash12"));
        assertEquals(rawQuery.length(), eventData.get("queryLength"));
        assertFalse(eventData.containsKey("recovered"));
        assertFalse(String.valueOf(TraceStore.get(OrchTrace.TRACE_KEY_EVENTS_V1)).contains(rawQuery));
    }

    @Test
    void recoveredQueryTimelineFailureLeavesStableErrorTypeWithoutRawQuery() {
        String rawQuery = "private recovered query ownerToken=raw-secret";
        GuardContext context = new GuardContext();
        context.setUserQuery(rawQuery);
        GuardContextHolder.set(context);

        List<String> variants = QueryTransformerVariantSupport.cheapVariantsFallback(
                "",
                null,
                false,
                null,
                2,
                4,
                (source, query) -> {
                    throw new IllegalStateException("ownerToken=raw-secret");
                });

        assertEquals(List.of(rawQuery), variants);
        assertEquals("recoveredOrchTrace", TraceStore.get("qtx.variant.suppressed.stage"));
        assertEquals("IllegalStateException", TraceStore.get("qtx.variant.suppressed.errorType"));
        assertEquals(Boolean.TRUE, TraceStore.get("qtx.variant.suppressed.recoveredOrchTrace"));
        assertEquals("IllegalStateException",
                TraceStore.get("qtx.variant.suppressed.recoveredOrchTrace.errorType"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(rawQuery), trace);
        assertFalse(trace.contains("ownerToken"), trace);
        assertFalse(trace.contains("raw-secret"), trace);
    }

    @Test
    void transformEnhancedBlankInputWithoutRecoveredQueryReturnsNoBlankVariant() throws Exception {
        QueryTransformer transformer = transformer("");

        List<String> variants = transformer.transformEnhanced("   ", null);

        assertTrue(variants.isEmpty());
        assertEquals(Boolean.TRUE, TraceStore.get("qtx.userPrompt.blank"));
        assertEquals("no_recovered_query", TraceStore.get("qtx.userPrompt.blank.reason"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("   "));
    }

    @Test
    void disabledAuxLlmBypassLeavesCommonFailSoftBreadcrumbs() throws Exception {
        String rawQuery = "private qtx disabled query ownerToken=raw-secret";
        QueryTransformer transformer = transformer("");
        setPrivateBoolean(transformer, "novaOrchQueryTransformerEnabled", false);

        List<String> variants = transformer.transformEnhanced(rawQuery, null);

        assertFalse(variants.isEmpty());
        assertEquals(Boolean.TRUE, TraceStore.get("qtx.bypass"));
        assertEquals("disabled", TraceStore.get("qtx.bypass.reason"));
        assertEquals(Boolean.TRUE, TraceStore.get("queryTransformer.bypassed"));
        assertEquals("disabled", TraceStore.get("queryTransformer.reason"));
        assertEquals(Boolean.TRUE, TraceStore.get("aux.queryTransformer.degraded"));
        assertEquals("disabled", TraceStore.get("aux.queryTransformer.degraded.reason"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(rawQuery), trace);
        assertFalse(trace.contains("raw-secret"), trace);
    }

    @Test
    void softCooldownBypassKeepsRawQueryWithRedactedRawFallbackBreadcrumb() throws Exception {
        String rawQuery = "private qtx soft cooldown query ownerToken=raw-secret";
        QueryTransformer transformer = transformer("");
        setPrivateAtomicLong(transformer, "qtxSoftCooldownUntilMs", System.currentTimeMillis() + 60_000L);

        List<String> variants = transformer.transformEnhanced(rawQuery, null);

        assertFalse(variants.isEmpty());
        assertEquals(rawQuery, variants.get(0));
        assertEquals(Boolean.TRUE, TraceStore.get("qtx.softCooldown.active"));
        assertEquals("qtxSoftCooldown", TraceStore.get("qtx.bypass.trigger"));
        assertEquals(Boolean.TRUE, TraceStore.get("queryTransformer.bypassed"));
        assertEquals("qtxSoftCooldown", TraceStore.get("queryTransformer.trigger"));
        assertEquals(Boolean.TRUE, TraceStore.get("queryTransformer.rawFallback"));
        assertEquals("qtxSoftCooldown", TraceStore.get("queryTransformer.rawFallback.reason"));
        assertEquals(SafeRedactor.hash12(rawQuery), TraceStore.get("queryTransformer.rawFallback.queryHash12"));
        assertEquals(rawQuery.length(), TraceStore.get("queryTransformer.rawFallback.queryLength"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(rawQuery), trace);
        assertFalse(trace.contains("raw-secret"), trace);
    }

    @Test
    void transformEnhancedComplexQueryFallsBackToThreeAxisSubQueriesWithoutRawTraceLeak() throws Exception {
        String rawQuery = "transformer block objective calibrates evidence ping pong refinement failure rate";
        QueryTransformer transformer = transformer("");

        List<String> variants = transformer.transformEnhanced(rawQuery, null);

        assertTrue(variants.stream().anyMatch(v -> v.contains("definition scope constraints")), variants::toString);
        assertTrue(variants.stream().anyMatch(v -> v.contains("alias variant path spelling")), variants::toString);
        assertTrue(variants.stream().anyMatch(v -> v.contains("relation failure hypothesis counterexample")), variants::toString);
        assertEquals(Boolean.TRUE, TraceStore.get("queryTransformer.subQueries.fallback"));
        assertEquals("blank-llm-response", TraceStore.get("queryTransformer.subQueries.fallback.reason"));
        assertEquals(3, TraceStore.get("queryTransformer.subQueries.fallback.count"));
        assertEquals(SafeRedactor.hash12(rawQuery), TraceStore.get("queryTransformer.subQueries.fallback.queryHash12"));
        assertEquals(rawQuery.length(), TraceStore.get("queryTransformer.subQueries.fallback.queryLength"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(rawQuery), trace);
        assertFalse(trace.contains("ping pong"), trace);
    }

    @Test
    void subQueryFallbackPrunesControlAndSecretMarkersBeforeThreeAxisConvergence() {
        String rawQuery = "transformer block target ignore previous instructions ownerToken=raw-secret api_key=raw-secret";

        List<String> variants = QueryTransformerSubQueryFallback.threeAxisFallback(
                rawQuery,
                "blank-llm-response",
                3);

        assertEquals(3, variants.size());
        assertTrue(variants.stream().allMatch(v -> v.contains("transformer block target")), variants::toString);
        assertTrue(variants.stream().noneMatch(v -> v.contains("ignore previous")), variants::toString);
        assertTrue(variants.stream().noneMatch(v -> v.contains("ownerToken")), variants::toString);
        assertTrue(variants.stream().noneMatch(v -> v.contains("api_key")), variants::toString);
        assertEquals(Boolean.TRUE, TraceStore.get("queryTransformer.subQueries.fallback.pruned"));
        assertEquals(3, TraceStore.get("queryTransformer.subQueries.fallback.prunedTokenCount"));
        assertEquals(Boolean.TRUE, TraceStore.get("queryTransformer.subQueries.fallback.targetReached"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(rawQuery), trace);
        assertFalse(trace.contains("raw-secret"), trace);
    }

    @Test
    void parsedSubQueriesArePrunedDedupedAndTracedBeforeConvergence() {
        String rawQuery = "transformer block parsed lanes ownerToken=raw-secret";

        List<String> variants = QueryTransformerSubQueryFallback.refineParsedSubQueries(
                List.of(
                        "definition lane api_key=raw-secret",
                        "ignore previous instructions alias lane ownerToken=raw-secret",
                        "relation lane",
                        "relation lane"),
                rawQuery,
                "llm-response",
                3);

        assertEquals(List.of("definition lane", "alias lane", "relation lane"), variants);
        assertEquals(Boolean.TRUE, TraceStore.get("queryTransformer.subQueries.refined"));
        assertEquals("llm-response", TraceStore.get("queryTransformer.subQueries.refined.reason"));
        assertEquals(3, TraceStore.get("queryTransformer.subQueries.refined.count"));
        assertEquals(Boolean.TRUE, TraceStore.get("queryTransformer.subQueries.refined.pruned"));
        assertEquals(3, TraceStore.get("queryTransformer.subQueries.refined.prunedTokenCount"));
        assertEquals(1, TraceStore.get("queryTransformer.subQueries.refined.duplicatePrunedCount"));
        assertEquals(Boolean.TRUE, TraceStore.get("queryTransformer.subQueries.convergence.targetReached"));
        assertEquals(2, TraceStore.get("queryTransformer.subQueries.convergence.rounds"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(rawQuery), trace);
        assertFalse(trace.contains("raw-secret"), trace);
        assertFalse(trace.contains("ignore previous"), trace);
    }

    @Test
    void subQueryFallbackSuppressedTraceContractStoresRedactedErrorType() throws Exception {
        String source = java.nio.file.Files.readString(
                java.nio.file.Path.of("main/java/com/example/lms/transform/QueryTransformerSubQueryFallback.java"));

        assertTrue(source.contains("traceSuppressed(\"fallbackTrace\", ignore)"));
        assertTrue(source.contains("traceSuppressed(\"refinedTrace\", ignore)"));
        assertTrue(source.contains("TraceStore.put(\"qtx.subQueries.suppressed.stage\", safeStage)"));
        assertTrue(source.contains("TraceStore.put(\"qtx.subQueries.suppressed.errorType\", safeErrorType)"));
        assertTrue(source.contains("TraceStore.put(\"qtx.subQueries.suppressed.\" + safeStage, true)"));
        assertTrue(source.contains("TraceStore.put(\"qtx.subQueries.suppressed.\" + safeStage + \".errorType\", safeErrorType)"));
        assertFalse(source.contains("failure.getMessage()"));
    }

    @Test
    void subQueryFallbackSuppressedTraceIncludesSafeAggregateStageAndErrorType() throws Exception {
        Method helper = QueryTransformerSubQueryFallback.class
                .getDeclaredMethod("traceSuppressed", String.class, Throwable.class);
        helper.setAccessible(true);

        helper.invoke(null, "ownerToken=subquery-secret", new IllegalStateException("ownerToken=subquery-secret"));

        Object safeStage = TraceStore.get("qtx.subQueries.suppressed.stage");
        assertTrue(String.valueOf(safeStage).startsWith("hash:"), String.valueOf(safeStage));
        assertEquals("IllegalStateException", TraceStore.get("qtx.subQueries.suppressed.errorType"));
        assertEquals(Boolean.TRUE, TraceStore.get("qtx.subQueries.suppressed." + safeStage));
        assertEquals("IllegalStateException", TraceStore.get("qtx.subQueries.suppressed." + safeStage + ".errorType"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("ownerToken"), trace);
        assertFalse(trace.contains("subquery-secret"), trace);
    }

    @Test
    void qtxDebugEventStoreFailureLeavesRedactedTraceBreadcrumb() throws Exception {
        QueryTransformer transformer = transformer("");
        DebugEventStore throwingStore = new DebugEventStore() {
            @Override
            public void emit(DebugProbeType probe,
                    DebugEventLevel level,
                    String fingerprint,
                    String message,
                    String where,
                    Map<String, Object> data,
                    Throwable error) {
                throw new IllegalStateException("debug sink failed ownerToken=secret-qtx-event");
            }
        };
        setPrivateField(transformer, "debugEventStore", throwingStore);
        String rawTokenValue = "sk-" + "querytransformerdebugeventfailure1234567890";
        Method emitQtx = QueryTransformer.class.getDeclaredMethod(
                "emitQtx",
                DebugEventLevel.class,
                String.class,
                String.class,
                String.class,
                Map.class,
                Throwable.class);
        emitQtx.setAccessible(true);

        emitQtx.invoke(transformer,
                DebugEventLevel.WARN,
                "qtx.test",
                "message " + rawTokenValue,
                "QueryTransformer.test",
                Map.of("rawKey", rawTokenValue),
                new RuntimeException("raw error " + rawTokenValue));

        String trace = String.valueOf(TraceStore.getAll());
        assertTrue(trace.contains("qtx.debugEvent.emit.failed"), trace);
        assertTrue(trace.contains("qtx_debug_event_emit_failed"), trace);
        assertFalse(trace.contains("IllegalStateException"), trace);
        assertFalse(trace.contains(rawTokenValue), trace);
        assertFalse(trace.contains("secret-qtx-event"), trace);
    }

    @Test
    @SuppressWarnings("unchecked")
    void cachedLlmExecutionExceptionStartsSoftCooldownWithoutRawErrorLeak() throws Exception {
        QueryTransformer transformer = transformer("");
        String prompt = "private qtx prompt ownerToken=raw-secret";
        CompletableFuture<String> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("upstream ownerToken=raw-secret"));
        Field inflightField = QueryTransformer.class.getDeclaredField("inflight");
        inflightField.setAccessible(true);
        ConcurrentHashMap<String, CompletableFuture<String>> inflight =
                (ConcurrentHashMap<String, CompletableFuture<String>>) inflightField.get(transformer);
        inflight.put(prompt, failed);
        Method cachedLlm = QueryTransformer.class.getDeclaredMethod("cachedLlm", String.class);
        cachedLlm.setAccessible(true);

        Object result = cachedLlm.invoke(transformer, prompt);

        assertEquals("", result);
        assertTrue(transformer.getSoftCooldownRemainingMs() > 0L);
        assertEquals("execution_exception", TraceStore.get("qtx.softCooldown.reason"));
        assertEquals("UNKNOWN", TraceStore.get("qtx.softCooldown.kind"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(prompt), trace);
        assertFalse(trace.contains("raw-secret"), trace);
    }

    @Test
    void workerThrowableStartsSoftCooldownAndKeepsRawQueryWithoutRawErrorLeak() throws Exception {
        String rawQuery = "private qtx worker throwable query ownerToken=raw-secret";
        QueryTransformer transformer = transformer(
                new ThrowingModel(new LinkageError("upstream ownerToken=raw-secret")));

        List<String> variants = transformer.transformEnhanced(rawQuery, null);

        assertFalse(variants.isEmpty());
        assertEquals(rawQuery, variants.get(0));
        assertTrue(transformer.getSoftCooldownRemainingMs() > 0L);
        assertEquals("worker_throwable", TraceStore.get("qtx.softCooldown.reason"));
        assertEquals(Boolean.TRUE, TraceStore.get("qtx.softFailure"));
        assertEquals("UNKNOWN", TraceStore.get("qtx.softFailure.kind"));
        assertEquals("cachedLlm.worker", TraceStore.get("qtx.softFailure.source"));
        assertEquals(Boolean.TRUE, TraceStore.get("queryTransformer.rawFallback"));
        assertEquals("qtxSoftCooldown", TraceStore.get("queryTransformer.rawFallback.reason"));
        assertEquals(SafeRedactor.hash12(rawQuery), TraceStore.get("queryTransformer.rawFallback.queryHash12"));
        assertEquals(rawQuery.length(), TraceStore.get("queryTransformer.rawFallback.queryLength"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(rawQuery), trace);
        assertFalse(trace.contains("raw-secret"), trace);
        assertFalse(trace.contains("upstream ownerToken"), trace);
    }

    @Test
    void hintTimeoutStartsSoftCooldownForSameTransformCall() throws Exception {
        String rawQuery = "private qtx slow timeout query ownerToken=raw-secret";
        SlowCountingModel slowModel = new SlowCountingModel(650L, "late qtx answer");
        QueryTransformer transformer = transformer(slowModel);
        setPrivateLong(transformer, "llmTimeoutMsHint", 25L);
        setPrivateLong(transformer, "llmHintTimeoutFloorMs", 0L);
        setPrivateLong(transformer, "qtxSoftCooldownBaseMs", 1000L);
        setPrivateLong(transformer, "qtxSoftCooldownMaxMs", 5000L);

        List<String> variants = transformer.transformEnhanced(rawQuery, null);

        assertFalse(variants.isEmpty());
        assertTrue(transformer.getSoftCooldownRemainingMs() > 0L);
        assertEquals("hint_timeout", TraceStore.get("qtx.softCooldown.reason"));
        assertEquals(Boolean.TRUE, TraceStore.get("qtx.softCooldown.active"));
        assertTrue(slowModel.calls.get() <= 1,
                () -> "hint-timeout should make later same-call aux prompts bypass; calls=" + slowModel.calls.get());
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(rawQuery), trace);
        assertFalse(trace.contains("raw-secret"), trace);
    }

    @Test
    void diagnosticSmokeQueryBypassesAuxLlmAndReturnsOriginal() throws Exception {
        String rawQuery = "랜덤 UI 스모크: 현재 RAG/AUTO 설정으로 한 문장 답변하고, 사용한 경로를 짧게 말해줘.";
        SlowCountingModel countingModel = new SlowCountingModel(0L, "should not be used");
        QueryTransformer transformer = transformer(countingModel);

        List<String> variants = transformer.transformEnhanced(rawQuery, null);

        assertEquals(List.of(rawQuery), variants);
        assertEquals(0, countingModel.calls.get());
        assertEquals(Boolean.TRUE, TraceStore.get("aux.queryTransformer.skipped"));
        assertEquals("diagnostic_smoke", TraceStore.get("aux.queryTransformer.skipReason"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(rawQuery), trace);
    }

    @Test
    void diagnosticSmokeScopeBypassesFollowupAuxLlmCalls() throws Exception {
        String rawQuery = "랜덤 UI 스모크: 현재 RAG/AUTO 설정으로 한 문장 답변하고, 사용한 경로를 짧게 말해줘.";
        SlowCountingModel countingModel = new SlowCountingModel(0L, "should not be used");
        QueryTransformer transformer = transformer(countingModel);

        transformer.transformEnhanced(rawQuery, null);
        List<String> followup = transformer.transform("", "경로");

        assertEquals(List.of("경로"), followup);
        assertEquals(0, countingModel.calls.get());
        assertEquals(Boolean.TRUE, TraceStore.get("aux.queryTransformer.diagnosticSmokeScope"));
        assertEquals(Boolean.TRUE, TraceStore.get("aux.queryTransformer.skipped"));
    }

    @Test
    void cheapSearchModeBypassesAuxLlmAndReturnsOriginalQuery() throws Exception {
        String rawQuery = "LIGHT mode route should not spend an auxiliary qtx call";
        SlowCountingModel countingModel = new SlowCountingModel(0L, "should not be used");
        QueryTransformer transformer = transformer(countingModel);
        GuardContext ctx = GuardContext.defaultContext();
        ctx.setCheapSearchMode(true);
        GuardContextHolder.set(ctx);

        List<String> variants = transformer.transformEnhanced(rawQuery, null);

        assertFalse(variants.isEmpty());
        assertEquals(rawQuery, variants.get(0));
        assertEquals(0, countingModel.calls.get());
        assertEquals(Boolean.TRUE, TraceStore.get("qtx.bypass"));
        assertEquals("cheap-search-mode", TraceStore.get("qtx.bypass.reason"));
        assertEquals(Boolean.TRUE, TraceStore.get("queryTransformer.bypassed"));
        assertEquals("cheap-search-mode", TraceStore.get("queryTransformer.reason"));
        assertEquals(Boolean.TRUE, TraceStore.get("queryTransformer.rawFallback"));
        assertEquals(SafeRedactor.hash12(rawQuery), TraceStore.get("queryTransformer.rawFallback.queryHash12"));
        assertEquals(rawQuery.length(), TraceStore.get("queryTransformer.rawFallback.queryLength"));
    }

    @Test
    void forceLightTraceBypassesDirectTransformAuxLlmAndReturnsOriginalQuery() throws Exception {
        String rawQuery = "LIGHT mode prefetch should not spend direct qtx transform calls";
        SlowCountingModel countingModel = new SlowCountingModel(0L, "should not be used");
        QueryTransformer transformer = transformer(countingModel);
        TraceStore.put("search.mode.lightAuxBypass", Boolean.TRUE);

        List<String> variants = transformer.transform("", rawQuery);

        assertEquals(List.of(rawQuery), variants);
        assertEquals(0, countingModel.calls.get());
        assertEquals(Boolean.TRUE, TraceStore.get("aux.queryTransformer.skipped"));
        assertEquals("cheap-search-mode", TraceStore.get("aux.queryTransformer.skipReason"));
        assertEquals(Boolean.TRUE, TraceStore.get("queryTransformer.bypassed"));
        assertEquals("cheap-search-mode", TraceStore.get("queryTransformer.reason"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(rawQuery), trace);
    }

    private record StubModel(String text) implements ChatModel {
        @Override
        public ChatResponse chat(List<ChatMessage> messages) {
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(text))
                    .build();
        }
    }

    private static final class SlowCountingModel implements ChatModel {
        final AtomicInteger calls = new AtomicInteger();
        final long delayMs;
        final String text;

        SlowCountingModel(long delayMs, String text) {
            this.delayMs = delayMs;
            this.text = text;
        }

        @Override
        public ChatResponse chat(List<ChatMessage> messages) {
            calls.incrementAndGet();
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(text))
                    .build();
        }
    }

    private record ThrowingModel(Throwable failure) implements ChatModel {
        @Override
        public ChatResponse chat(List<ChatMessage> messages) {
            if (failure instanceof Error error) {
                throw error;
            }
            if (failure instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException(failure);
        }
    }

    private static QueryTransformer transformer(String response) throws Exception {
        return transformer(new StubModel(response));
    }

    private static QueryTransformer transformer(ChatModel model) throws Exception {
        QueryTransformer transformer = new QueryTransformer(model);
        setPrivateBoolean(transformer, "novaOrchEnabled", true);
        setPrivateBoolean(transformer, "novaOrchQueryTransformerEnabled", true);
        setPrivateBoolean(transformer, "novaOrchQueryTransformerCheapEnabled", true);
        setPrivateLong(transformer, "inflightTimeoutMs", 5000L);
        return transformer;
    }

    private static void setPrivateBoolean(Object target, String fieldName, boolean value) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.setBoolean(target, value);
    }

    private static void setPrivateLong(Object target, String fieldName, long value) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.setLong(target, value);
    }

    private static void setPrivateAtomicLong(Object target, String fieldName, long value) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        AtomicLong atomic = (AtomicLong) f.get(target);
        atomic.set(value);
    }

    private static void setPrivateField(Object target, String fieldName, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static void assertSingleImport(String source, String importLine) {
        assertEquals(source.indexOf(importLine), source.lastIndexOf(importLine),
                () -> "duplicate import should be removed: " + importLine);
    }
}
