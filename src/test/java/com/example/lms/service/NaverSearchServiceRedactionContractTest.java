package com.example.lms.service;

import com.example.lms.guard.GuardProfile;
import com.example.lms.guard.GuardProfileProps;
import com.example.lms.search.RateLimitPolicy;
import com.example.lms.search.TraceStore;
import com.example.lms.transform.QueryTransformer;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.AfterEach;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class NaverSearchServiceRedactionContractTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void outboundHeadersDoNotCarryInternalKeyLabels() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/NaverSearchService.java"));

        assertFalse(source.contains("\"X-Key-Label\""));
        assertTrue(source.contains("\"X-Naver-Client-Id\""));
        assertTrue(source.contains("\"X-Naver-Client-Secret\""));
    }

    @Test
    void webkrRequestsDoNotSendUnsupportedSortParam() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/NaverSearchService.java"));

        assertFalse(source.contains(".queryParam(\"sort\""));
        assertFalse(source.contains("sort=date"));
    }

    @Test
    void naverSearchServiceDoesNotUseExactEmptyCatchBlocks() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/NaverSearchService.java"));

        long exactEmptyCatchBlocks = Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")
                .matcher(source)
                .results()
                .count();

        assertEquals(0L, exactEmptyCatchBlocks);
    }

    @Test
    void naverDisplayClampStaysInsideVendorBounds() {
        assertEquals(10, NaverSearchService.clampNaverDisplay(0));
        assertEquals(10, NaverSearchService.clampNaverDisplay(9));
        assertEquals(20, NaverSearchService.clampNaverDisplay(20));
        assertEquals(100, NaverSearchService.clampNaverDisplay(101));
        assertEquals(100, NaverSearchService.clampNaverDisplay(500));
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "category={0} blocked={1}")
    @org.junit.jupiter.params.provider.CsvSource({"email,false", "phone,false", "email,true", "phone,true"})
    void characterizesIdentityCategoryAtActualHybridNaverFallbackBoundary(String category, boolean blocked) {
        com.abandonware.ai.addons.budget.TimeBudgetContext.clear();
        com.example.lms.service.guard.GuardContextHolder.clear();
        TraceStore.clear();
        org.slf4j.MDC.clear();
        try {
            String value = category.equals("email") ? "identity.fixture@example.test" : "010-2345-6789";
            String query = "synthetic contact " + value;
            AtomicInteger attempts = new AtomicInteger();
            AtomicReference<String> capturedQuery = new AtomicReference<>();
            WebClient webClient = WebClient.builder().exchangeFunction(request -> {
                attempts.incrementAndGet();
                String encoded = org.springframework.web.util.UriComponentsBuilder.fromUri(request.url())
                        .build().getQueryParams().getFirst("query");
                capturedQuery.set(java.net.URLDecoder.decode(encoded, java.nio.charset.StandardCharsets.UTF_8));
                return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", "application/json")
                        .body(naverItemsJson(1)).build());
            }).build();
            NaverSearchService naver = outboundService(webClient);
            // Direct construction bypasses the configured synchronous wait; the facade otherwise clamps its zero field to 250ms.
            ReflectionTestUtils.setField(naver, "syncBlockTimeoutMs", 5_000L);
            com.example.lms.service.web.BraveSearchService brave = mock(com.example.lms.service.web.BraveSearchService.class);
            org.mockito.Mockito.when(brave.isEnabled()).thenReturn(false);
            com.example.lms.search.provider.HybridWebSearchProvider provider =
                    new com.example.lms.search.provider.HybridWebSearchProvider(naver, brave);
            // Bounded mode starts Brave-first; a disabled Brave selects the real Naver fallback.
            ReflectionTestUtils.setField(provider, "boundedFallbackEnabled", true);
            ReflectionTestUtils.setField(provider, "timeoutSec", 5);
            ReflectionTestUtils.setField(provider, "blockWebSearch", blocked);

            List<String> result = provider.search(query, 1);
            String identityCharacters = value.replaceAll("[^A-Za-z0-9]", "");
            boolean identityCharactersPresent = capturedQuery.get() != null
                    && capturedQuery.get().replaceAll("[^A-Za-z0-9]", "").contains(identityCharacters);
            System.out.println("SEC01_IDENTITY category=" + category + " blocked=" + blocked
                    + " attempts=" + attempts.get() + " results=" + result.size()
                    + " exact=" + query.equals(capturedQuery.get())
                    + " literalIdentityPresent=" + (capturedQuery.get() != null && capturedQuery.get().contains(value))
                    + " identityCharactersPresent=" + identityCharactersPresent
                    + " rawCount=" + TraceStore.get("web.naver.filter.rawCount")
                    + " afterBlockedCount=" + TraceStore.get("web.naver.filter.afterBlockedCount")
                    + " afterStrictCount=" + TraceStore.get("web.naver.filter.afterStrictCount"));

            if (blocked) {
                assertTrue(result.isEmpty());
                assertEquals(0, attempts.get());
                org.junit.jupiter.api.Assertions.assertNull(capturedQuery.get());
                assertEquals(Boolean.TRUE, TraceStore.get("privacy.web.blocked"));
                org.mockito.Mockito.verifyNoInteractions(brave);
            } else {
                assertEquals(1, attempts.get());
                // Returned snippets are a separate parse/filter/timing outcome; this contract measures admission.
                assertTrue(result.size() <= 1, "requested-result-cap-preserved");
                // The cache loader sends Q.canonical(query): separators change but identifier content remains.
                assertFalse(query.equals(capturedQuery.get()), "naver-query-normalization-observed");
                assertFalse(capturedQuery.get().contains(value), "literal-separators-normalized");
                assertTrue(identityCharactersPresent, "identifier-content-remains-at-request-boundary");
                org.mockito.Mockito.verify(brave, org.mockito.Mockito.atLeastOnce()).isEnabled();
                org.mockito.Mockito.verifyNoMoreInteractions(brave);
            }
            String publicTrace = String.valueOf(TraceStore.getAll());
            assertFalse(publicTrace.contains(value), "trace-excludes-identity-fixture");
            assertFalse(publicTrace.replaceAll("[^A-Za-z0-9]", "").contains(identityCharacters),
                    "trace-excludes-normalized-identifier-content");
        } finally {
            com.abandonware.ai.addons.budget.TimeBudgetContext.clear();
            com.example.lms.service.guard.GuardContextHolder.clear();
            TraceStore.clear();
            org.slf4j.MDC.clear();
        }
    }

    @Test
    void requestTopKIsClampedBeforeOutboundAndAfterParsing() {
        TraceStore.put("web.boundedRoute", true);
        AtomicReference<java.net.URI> capturedUri = new AtomicReference<>();
        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> {
                    capturedUri.set(request.url());
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header("Content-Type", "application/json")
                            .body(naverItemsJson(100))
                            .build());
                })
                .build();
        NaverSearchService service = outboundService(webClient);
        ReflectionTestUtils.setField(service, "display", 20);
        ReflectionTestUtils.setField(service, "webTopK", 80);

        List<String> out = service.searchSnippetsMono("bounded request top k", 10_000)
                .block(java.time.Duration.ofSeconds(3));

        assertEquals("20", org.springframework.web.util.UriComponentsBuilder.fromUri(capturedUri.get())
                .build()
                .getQueryParams()
                .getFirst("display"));
        assertEquals(20, out.size());
    }

    @Test
    void requestSpecificOutboundFetchParticipatesInCacheIdentity() {
        TraceStore.put("web.boundedRoute", true);
        AtomicInteger attempts = new AtomicInteger();
        List<Integer> displays = new java.util.concurrent.CopyOnWriteArrayList<>();
        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> {
                    attempts.incrementAndGet();
                    int outboundDisplay = Integer.parseInt(
                            org.springframework.web.util.UriComponentsBuilder.fromUri(request.url())
                                    .build()
                                    .getQueryParams()
                                    .getFirst("display"));
                    displays.add(outboundDisplay);
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header("Content-Type", "application/json")
                            .body(naverItemsJson(outboundDisplay))
                            .build());
                })
                .build();
        NaverSearchService service = outboundService(webClient);
        ReflectionTestUtils.setField(service, "display", 20);
        ReflectionTestUtils.setField(service, "webTopK", 8);

        List<String> first = service.searchSnippetsMono("same fetch-aware cache query", 3)
                .block(java.time.Duration.ofSeconds(3));
        List<String> second = service.searchSnippetsMono("same fetch-aware cache query", 20)
                .block(java.time.Duration.ofSeconds(3));

        assertEquals(3, first.size());
        assertEquals(20, second.size());
        assertEquals(List.of(10, 20), displays);
        assertEquals(2, attempts.get());
    }

    @Test
    void zeroAndNegativeTopKAreExplicitEmptyAdmissionsWithoutOutboundCall() {
        AtomicInteger attempts = new AtomicInteger();
        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> {
                    attempts.incrementAndGet();
                    throw new AssertionError("non-positive topK must not call Naver");
                })
                .build();
        NaverSearchService service = outboundService(webClient);
        ReflectionTestUtils.setField(service, "display", 20);

        assertTrue(service.searchSnippetsMono("zero request top k", 0)
                .block(java.time.Duration.ofSeconds(1)).isEmpty());
        assertTrue(service.searchSnippetsMono("negative request top k", -1)
                .block(java.time.Duration.ofSeconds(1)).isEmpty());
        assertEquals(0, attempts.get());
    }

    @Test
    void errorBodyDiagnosticsAreRedacted() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/NaverSearchService.java"));

        assertTrue(source.contains("\"bodyHash\""));
        assertTrue(source.contains("\"bodyLength\""));
        assertFalse(source.contains("bodyPreview"));
        assertFalse(source.contains("SafeRedactor.safeMessage(body == null ? \"\" : body, 512)"));
        assertFalse(source.contains("String cut = (body != null && body.length() > 512)"));
        assertFalse(source.contains("body={}\""));
    }

    @Test
    void sensitiveHeaderDiagnosticsDoNotExposeMaskedTails() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/NaverSearchService.java"));

        assertTrue(source.contains("present=true"));
        assertFalse(source.contains("private static String mask("));
        assertFalse(source.contains("substring(s.length() - 4)"));
    }

    @Test
    void providerFailureLogsDoNotRenderThrowableToString() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/NaverSearchService.java"));

        assertFalse(source.contains("e.toString()"));
        assertFalse(source.contains("t.toString()"));
        assertFalse(source.contains("ex.toString()"));
        assertFalse(source.contains("searchWithTraceSync failed: {}"));
        assertFalse(source.contains("Naver API {} failed: {}"));
        assertFalse(source.contains("log.error(\"Naver API call failed\", ex)"));
        assertTrue(source.contains("failureReason={} errorType={} queryHash={} queryLength={}"));
    }

    @Test
    void naverTraceSuppressionsNormalizeNumericErrorType() {
        TraceStore.clear();

        NaverTraceSuppressions.trace("retryAfter.parse", new NumberFormatException("ownerToken=secret"));

        assertEquals(Boolean.TRUE, TraceStore.get("web.naver.suppressed.retryAfter.parse"));
        assertEquals("invalid_number", TraceStore.get("web.naver.suppressed.retryAfter.parse.errorType"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("NumberFormatException"));
        assertFalse(trace.contains("ownerToken=secret"));
    }

    @Test
    void queryTransformerFailureLogsUseHashAndLengthOnly() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/NaverSearchService.java"));

        assertFalse(source.contains("queryTransformer.transformEnhanced failed: {}\", e.getMessage())"));
        assertFalse(source.contains("queryTransformer.transform failed: {}\", e.getMessage())"));
        assertFalse(source.contains("SafeRedactor.safeMessage(e.getMessage(), 180)"));
        assertTrue(source.contains("queryTransformer.transformEnhanced failed. errorHash={} errorLength={}"));
        assertTrue(source.contains("queryTransformer.transform failed. errorHash={} errorLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(e.getMessage()), e.getMessage() == null ? 0 : e.getMessage().length()"));
    }

    @Test
    void traceStoreCorrelationIdsAreHashOnly() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/NaverSearchService.java"));

        assertFalse(source.contains("TraceStore.put(\"sid\", sessionId);"));
        assertFalse(source.contains("TraceStore.put(\"trace.id\", requestId);"));
        assertTrue(source.contains("TraceStore.put(\"sid\", SafeRedactor.hashValue(sessionId));"));
        assertTrue(source.contains("TraceStore.put(\"trace.id\", SafeRedactor.hashValue(requestId));"));
    }

    @Test
    void preprocessorAndCacheOnlyFailuresLeaveRedactedTraceTypes() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/NaverSearchService.java"));

        assertTrue(source.contains("TraceStore.put(\"web.naver.preprocessor.failed\", true);"));
        assertTrue(source.contains("TraceStore.put(\"web.naver.preprocessor.failureReason\""));
        assertTrue(source.contains("TraceStore.put(\"web.naver.cacheOnly.errorType\""));
        assertFalse(source.contains("TraceStore.put(\"web.naver.preprocessor.query\", original);"));
        assertFalse(source.contains("TraceStore.put(\"web.naver.cacheOnly.errorType\", t.toString());"));
    }

    @Test
    void webSearchProviderOverrideUsesGuardedSyncFacade() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/NaverSearchService.java"));
        String body = methodBody(source,
                "public SearchResult searchWithTrace(String query, int topK)",
                "    /**");

        assertFalse(body.contains(".block(Duration.ofSeconds(5))"));
        assertTrue(body.contains("return searchWithTraceSync(query, topK, Duration.ofSeconds(5));"));
    }

    @Test
    void assistantAnswerSnippetFacadeUsesFailSoftBlockGuard() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/NaverSearchService.java"));
        String body = methodBody(source,
                "public List<String> searchSnippets(String userPrompt,",
                "    /** Trace");

        assertTrue(body.contains("try {"));
        assertTrue(body.contains("traceNaverFailure(userPrompt, topK"));
        assertTrue(body.contains("failureReason={} errorType={} queryHash={} queryLength={} timeoutMs={}"));
        assertTrue(body.contains("return List.of();"));
        assertTrue(body.contains(".blockOptional(timeout)"));
        assertFalse(body.contains(".blockOptional(Duration.ofSeconds(5))"));
    }

    @Test
    void snippetReinforcementFailureLogUsesHashAndLengthOnly() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/NaverSearchService.java"));

        assertFalse(source.contains("Failed to reinforce snippet: {}\", e.getMessage())"));
        assertFalse(source.contains("SafeRedactor.safeMessage(e.getMessage(), 180)"));
        assertTrue(source.contains("Failed to reinforce snippet. errorHash={} errorLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(e.getMessage()), e.getMessage() == null ? 0 : e.getMessage().length()"));
    }

    @Test
    void localRagFailureLogUsesHashAndLengthOnly() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/NaverSearchService.java"));

        assertFalse(source.contains("Local RAG retrieval failed: {}\", SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(source.contains("Local RAG retrieval failed. errorHash={} errorLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length()"));
    }

    @Test
    void sensitiveHeaderLogValueOnlyReportsPresence() throws Exception {
        String rendered = headerLogValue("X-Naver-Client-Secret", List.of("secret-value-1234567890"));

        assertEquals("present=true", rendered);
        assertFalse(rendered.contains("secret-value"));
        assertFalse(rendered.contains("7890"));
        assertFalse(rendered.contains("20"));
    }

    @Test
    void sensitiveHeaderLogValuePreservesMissingSignalWithoutValueShape() throws Exception {
        String rendered = headerLogValue("Authorization", List.of("", "Bearer " + "secret-value-1234567890"));

        assertEquals("present=false, present=true", rendered);
        assertFalse(rendered.contains("Bearer"));
        assertFalse(rendered.contains("secret-value"));
    }

    @Test
    void explicitMalformedOrPartialNaverKeysFallBackToClientPair() {
        NaverSearchService malformed = naverService("only-client-id", "fallback-id", "fallback-secret");
        NaverSearchService partial = naverService("client-id:", "fallback-id", "fallback-secret");

        assertEquals(1, parsedNaverKeyCount(malformed));
        assertEquals(1, parsedNaverKeyCount(partial));
    }

    @Test
    void explicitNaverKeysParseFailureHasConstructorBridgeFallback() throws IOException {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/NaverSearchService.java"));

        assertTrue(source.contains("using client-id/secret bridge after naver.keys parse failure"));
        assertTrue(source.contains("fallbackUsed=true"));
    }

    @Test
    void blankNaverKeysUsesClientPairBridgeOnlyWhenBothPartsExist() {
        NaverSearchService bridged = naverService("", "fallback-id", "fallback-secret");
        NaverSearchService missingSecret = naverService("", "fallback-id", "");

        assertEquals(1, parsedNaverKeyCount(bridged));
        assertNoParsedNaverKeys(missingSecret);
    }

    @Test
    void quotedCommaNaverKeysParsesAsSingleExplicitPair() {
        NaverSearchService service = naverService("\"primary-id,primary-secret\"", "fallback-id", "fallback-secret");

        assertEquals(1, parsedNaverKeyCount(service));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static NaverSearchService naverService(String naverKeys, String clientId, String clientSecret) {
        return new NaverSearchService(
                null,
                null,
                null,
                mock(dev.langchain4j.store.embedding.EmbeddingStore.class),
                mock(dev.langchain4j.model.embedding.EmbeddingModel.class),
                () -> 1L,
                null,
                naverKeys,
                clientId,
                clientSecret,
                10L,
                1L,
                mock(PlatformTransactionManager.class),
                new RateLimitPolicy(),
                WebClient.builder().baseUrl("https://openapi.naver.com").build(),
                null,
                null);
    }

    private static String headerLogValue(String name, List<String> values) throws Exception {
        Method method = NaverSearchService.class.getDeclaredMethod("safeHeaderValueForLog", String.class, List.class);
        method.setAccessible(true);
        return (String) method.invoke(null, name, values);
    }

    private static String methodBody(String source, String startMarker, String nextMarker) {
        int start = source.indexOf(startMarker);
        assertTrue(start >= 0, "missing source marker: " + startMarker);
        int end = source.indexOf(nextMarker, start + startMarker.length());
        assertTrue(end > start, "missing next marker: " + nextMarker);
        return source.substring(start, end);
    }

    private static void assertNoParsedNaverKeys(NaverSearchService service) {
        Collection<?> parsedKeys = (Collection<?>) ReflectionTestUtils.getField(service, "naverKeys");
        assertTrue(parsedKeys == null || parsedKeys.isEmpty());
        assertEquals(0, parsedNaverKeyCount(service));
    }

    private static int parsedNaverKeyCount(NaverSearchService service) {
        Object count = ReflectionTestUtils.getField(service, "parsedNaverKeyCount");
        return count instanceof Number number ? number.intValue() : -1;
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> shippedWhitelistProfileControls() throws Exception {
        var applier = new com.example.lms.plan.PlanHintApplier(new org.springframework.core.io.DefaultResourceLoader());
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
        var seen = new java.util.HashSet<String>();
        var selected = new java.util.TreeSet<String>();
        var cases = new java.util.ArrayList<org.junit.jupiter.params.provider.Arguments>();
        try (var paths = Files.list(Path.of("main/resources/plans"))) {
            for (Path path : paths.filter(p -> p.getFileName().toString().endsWith(".yaml")).sorted().toList()) {
                String file = path.getFileName().toString();
                var plan = applier.load(file.substring(0, file.length() - 5));
                if (!seen.add(plan.planId()) || plan.whitelistProfile() == null) continue;
                var tree = mapper.readTree(Files.readString(Path.of("main/resources/plans", plan.planId() + ".yaml")));
                assertEquals(plan.whitelistProfile(), tree.path("guards").path("whitelist_profile").textValue());
                selected.add(plan.planId());
                for (String control : List.of("authored", "swap", "removed", "caller", "precedence",
                        "partial_down", "blank_allowlist", "thin", "starved")) {
                    cases.add(org.junit.jupiter.params.provider.Arguments.of(plan.planId(), plan.whitelistProfile(), control));
                }
            }
        }
        assertEquals(java.util.Set.of("document_evidence.v1", "kg_first.v1", "safe.v1", "zero100.v1"), selected);
        assertEquals(36, cases.size());
        return cases.stream();
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0} profile={1} control={2}")
    @org.junit.jupiter.params.provider.MethodSource("shippedWhitelistProfileControls")
    void shippedWhitelistProfileChangesActualNaverFilterAndRecordsRescue(String planId, String authored,
            String control) throws Exception {
        com.example.lms.service.guard.GuardContextHolder.clear();
        TraceStore.clear();
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory());
            var original = mapper.readTree(Files.readString(Path.of("main/resources/plans", planId + ".yaml")));
            var modified = original.deepCopy();
            var guards = (com.fasterxml.jackson.databind.node.ObjectNode) modified.path("guards");
            String alternative = "jul14".equals(authored) ? "official" : "docs";
            boolean removed = "removed".equals(control) || "caller".equals(control);
            if ("swap".equals(control)) guards.put("whitelist_profile", alternative);
            if (removed) guards.remove("whitelist_profile");
            if ("precedence".equals(control)) {
                assertFalse(original.has("whitelistProfile"));
                ((com.fasterxml.jackson.databind.node.ObjectNode) modified).put("whitelistProfile", alternative);
            }
            var restored = modified.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) restored.path("guards")).put("whitelist_profile", authored);
            if ("precedence".equals(control)) ((com.fasterxml.jackson.databind.node.ObjectNode) restored).remove("whitelistProfile");
            assertEquals(original, restored, "all other authored plan fields stay intact");
            byte[] bytes = mapper.writeValueAsBytes(modified);
            var resources = new org.springframework.core.io.DefaultResourceLoader() {
                @Override public org.springframework.core.io.Resource getResource(String location) {
                    if (("classpath:plans/" + planId + ".yaml").equals(location)) {
                        return new org.springframework.core.io.ByteArrayResource(bytes) {
                            @Override public String getFilename() { return planId + ".yaml"; }
                        };
                    }
                    return super.getResource(location);
                }
            };
            var applier = new com.example.lms.plan.PlanHintApplier("authored".equals(control)
                    ? new org.springframework.core.io.DefaultResourceLoader() : resources);
            var hints = applier.load(planId);
            String selectedProfile = removed ? null : "swap".equals(control) ? alternative : authored;
            assertEquals(selectedProfile, hints.whitelistProfile());
            var guard = new com.example.lms.service.guard.GuardContext();
            if ("caller".equals(control)) guard.setDomainProfile("community_blog");
            applier.applyToGuardContext(hints, guard);
            String contextProfile = "caller".equals(control) ? "community_blog" : selectedProfile;
            assertEquals(contextProfile, guard.getDomainProfile());
            assertEquals("safe.v1".equals(planId), guard.isOfficialOnly());
            assertEquals(hints.minCitations(), guard.getMinCitations());
            com.example.lms.service.guard.GuardContextHolder.set(guard);
            TraceStore.put("orch.webPartialDown", "partial_down".equals(control));
            var loader = org.mockito.Mockito.spy(new com.example.lms.service.rag.auth.DomainProfileLoader(
                    new com.example.lms.service.rag.auth.DomainWhitelist()));
            loader.load();
            if (authored.startsWith("trusted+")) {
                assertFalse(((java.util.Map<?, ?>) ReflectionTestUtils.getField(loader, "profiles")).containsKey(authored),
                        "unconfigured authored profile names use the existing official fallback");
            }
            AtomicInteger transportCalls = new AtomicInteger();
            WebClient client = WebClient.builder().exchangeFunction(request -> {
                transportCalls.incrementAndGet();
                return Mono.error(new AssertionError("parsed-item filter must never call transport"));
            }).build();
            NaverSearchService service = outboundService(client);
            ReflectionTestUtils.setField(service, "domainProfileLoader", loader);
            ReflectionTestUtils.setField(service, "allowlist", "blank_allowlist".equals(control) ? "" : "fallback-only.example.test");
            ReflectionTestUtils.setField(service, "blockedDomainsCsv", "");
            var categories = new java.util.LinkedHashMap<String, String>();
            categories.put("O", "openai.com"); categories.put("D", "readthedocs.io");
            categories.put("C", "stackoverflow.com"); categories.put("B", "medium.com");
            categories.put("X", "unlisted.example.test");
            var offered = new java.util.LinkedHashMap<String, String>();
            for (var category : categories.entrySet()) {
                for (int i = 0; i < 3; i++) offered.put(category.getKey() + i,
                        "https://" + category.getValue() + "/synthetic-" + i);
            }
            if ("thin".equals(control)) offered.keySet().retainAll(java.util.Set.of("O0", "X0", "X1", "X2"));
            if ("starved".equals(control)) offered.keySet().retainAll(java.util.Set.of("X0", "X1", "X2"));
            Class<?> itemType = Class.forName("com.example.lms.service.NaverSearchService$NaverItem");
            var constructor = itemType.getDeclaredConstructor(String.class, String.class, String.class);
            constructor.setAccessible(true);
            var items = new java.util.ArrayList<Object>();
            for (var entry : offered.entrySet()) items.add(constructor.newInstance(entry.getKey(), entry.getValue(), "synthetic member"));
            var method = NaverSearchService.class.getDeclaredMethod("filterAndFormatItems", List.class, String.class,
                    NaverSearchService.SearchPolicy.class);
            method.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<String> lines = (List<String>) method.invoke(service, items, "synthetic profile fixture",
                    new NaverSearchService.SearchPolicy(false, false, "filter", 0));
            String effectiveProfile = "partial_down".equals(control) ? null : contextProfile;
            if (effectiveProfile == null && guard.isOfficialOnly()) effectiveProfile = "official";
            boolean strict = effectiveProfile != null;
            java.util.Set<String> groups = effectiveProfile == null ? java.util.Set.of("O", "D", "C", "B", "X")
                    : switch (effectiveProfile) {
                        case "jul14" -> java.util.Set.of("O", "D", "C", "B");
                        case "docs" -> java.util.Set.of("O", "D");
                        case "community_blog" -> java.util.Set.of("B");
                        default -> java.util.Set.of("O");
                    };
            var expectedIds = new java.util.LinkedHashSet<String>();
            for (String id : offered.keySet()) if ("blank_allowlist".equals(control) || groups.contains(id.substring(0, 1))) expectedIds.add(id);
            int afterStrict = expectedIds.size();
            if ("thin".equals(control)) {
                int required = guard.getMinCitations() == null ? 2 : guard.getMinCitations();
                expectedIds.clear(); expectedIds.add("O0");
                for (int i = 0; i < required - 1; i++) expectedIds.add("X" + i);
            }
            if ("starved".equals(control)) expectedIds.addAll(offered.keySet());
            var expectedLines = expectedIds.stream().map(id -> "- <a href=\"" + offered.get(id)
                    + "\" target=\"_blank\" rel=\"noopener\">" + id + "</a>: synthetic member").toList();
            assertEquals(expectedLines, lines, "assert final membership after any rescue, not only strict-stage counters");
            assertEquals(offered.size(), TraceStore.get("web.naver.filter.rawCount"));
            assertEquals(afterStrict, TraceStore.get("web.naver.filter.afterStrictCount"));
            assertEquals(strict, TraceStore.get("web.naver.filter.strictByPlan"));
            assertEquals(strict, TraceStore.get("web.naver.filter.profileUsable"));
            assertEquals("partial_down".equals(control), Boolean.TRUE.equals(TraceStore.get("web.naver.domainProfileDemoted.filterStage")));
            boolean rescued = "thin".equals(control) || "starved".equals(control);
            assertEquals(rescued, Boolean.TRUE.equals(TraceStore.get("web.naver.filter.thinRescue.used")));
            if (rescued) assertEquals(lines.size(), TraceStore.get("web.naver.filter.thinRescue.after"));
            if (strict) org.mockito.Mockito.verify(loader, org.mockito.Mockito.times(offered.size()))
                    .isAllowedByProfile(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(effectiveProfile));
            else org.mockito.Mockito.verify(loader, org.mockito.Mockito.never())
                    .isAllowedByProfile(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
            assertEquals(0, transportCalls.get());
            System.out.printf("TBL07_PROFILE plan=%s control=%s profile=%s official=%s strict=%s raw=%d afterStrict=%d final=%d rescued=%s transport=%d%n",
                    planId, control, effectiveProfile, guard.isOfficialOnly(), strict, offered.size(), afterStrict,
                    lines.size(), rescued, transportCalls.get());
        } finally {
            com.example.lms.service.guard.GuardContextHolder.clear();
            TraceStore.clear();
        }
    }

    @SuppressWarnings("unchecked")
    private static NaverSearchService outboundService(WebClient webClient) {
        RateLimitPolicy ratePolicy = mock(RateLimitPolicy.class);
        org.mockito.Mockito.when(ratePolicy.allowedExpansions()).thenReturn(1);
        org.mockito.Mockito.when(ratePolicy.currentDelayMs()).thenReturn(0L);

        GuardProfileProps guardProfileProps = mock(GuardProfileProps.class);
        org.mockito.Mockito.when(guardProfileProps.currentProfile()).thenReturn(GuardProfile.PROFILE_FREE);

        NaverSearchService service = new NaverSearchService(
                mock(QueryTransformer.class),
                mock(MemoryReinforcementService.class),
                mock(ObjectProvider.class),
                mock(EmbeddingStore.class),
                mock(EmbeddingModel.class),
                (Supplier<Long>) () -> null,
                null,
                "test-client:test-secret",
                "",
                "",
                16L,
                30L,
                mock(PlatformTransactionManager.class),
                ratePolicy,
                webClient,
                mock(ObjectProvider.class),
                null);
        ReflectionTestUtils.setField(service, "guardProfileProps", guardProfileProps);
        ReflectionTestUtils.setField(service, "apiTimeoutMs", 1_000L);
        ReflectionTestUtils.setField(service, "queryTransformTimeoutMs", 500L);
        ReflectionTestUtils.setField(service, "similarThreshold", 0.99d);
        ReflectionTestUtils.setField(service, "adaptiveSearchEnabled", false);
        ReflectionTestUtils.setField(service, "fusionPolicy", "none");
        return service;
    }

    private static String naverItemsJson(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> "{\"title\":\"result-" + i
                        + "\",\"link\":\"https://example.com/" + i
                        + "\",\"description\":\"usable result " + i + "\"}")
                .collect(Collectors.joining(",", "{\"items\":[", "]}"));
    }
}
