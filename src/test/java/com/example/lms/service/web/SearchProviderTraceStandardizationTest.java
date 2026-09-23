package com.example.lms.service.web;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.lms.gptsearch.web.AbstractWebSearchProvider;
import com.example.lms.gptsearch.web.ProviderId;
import com.example.lms.gptsearch.web.dto.WebSearchQuery;
import com.example.lms.gptsearch.web.dto.WebSearchResult;
import com.example.lms.gptsearch.web.impl.SerpApiProvider;
import com.example.lms.search.RateLimitPolicy;
import com.example.lms.search.TraceStore;
import com.example.lms.search.provider.HybridWebSearchProvider;
import com.example.lms.search.policy.AdaptiveSearchQueryVariants;
import com.example.lms.service.NaverSearchService;
import com.example.lms.service.NaverTraceSignals;
import com.example.lms.service.rag.TavilyWebSearchRetriever;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;
import reactor.core.publisher.Mono;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class SearchProviderTraceStandardizationTest {

    @AfterEach
    void clearTraceStore() {
        TraceStore.clear();
    }

    @Test
    void braveAndSerpApiProviderLogsDoNotUseRawThrowableMessages() throws Exception {
        assertProviderLogDoesNotUseRawThrowableMessages(
                "main/java/com/example/lms/service/web/BraveSearchService.java");
        assertProviderLogDoesNotUseRawThrowableMessages(
                "main/java/com/example/lms/gptsearch/web/impl/SerpApiProvider.java");
        String brave = Files.readString(Path.of("main/java/com/example/lms/service/web/BraveSearchService.java"));
        assertFalse(brave.contains("Failed to configure RestTemplate timeouts: {}"));
        assertFalse(brave.contains("SafeRedactor.safeMessage(e.getMessage(), 180)"));
        assertTrue(brave.contains("[AWX][search][brave] timeout configuration failed failureReason={} errorType={} baseUrlHost={} timeoutMs={} timeoutMarginMs={}"));
        assertFalse(brave.contains("JSON parse failed, trying regex fallback: {}"));
        assertFalse(brave.contains("Regex fallback failed: {}"));
        assertTrue(brave.contains("[AWX][search][brave] parse failed failureReason={} errorType={} bodyHash={} bodyLength={}"));
        assertTrue(brave.contains("[AWX][search][brave] regex fallback failed failureReason={} errorType={} bodyHash={} bodyLength={}"));
        assertTrue(brave.contains("\"json-parse-error\""));
        assertTrue(brave.contains("\"regex-fallback-error\""));
        assertTrue(brave.contains("SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), \"unknown\")"));
        String serp = Files.readString(Path.of("main/java/com/example/lms/gptsearch/web/impl/SerpApiProvider.java"));
        assertFalse(serp.contains("SafeRedactor.safeMessage(e.getMessage()"));
        assertTrue(serp.contains("parse failed failureReason={} errorType={} bodyHash={} bodyLength={}"));
    }

    @Test
    void braveSearchServiceDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/web/BraveSearchService.java"));

        assertEquals(0, Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}").matcher(source).results().count(),
                "BraveSearchService fail-soft paths need trace breadcrumbs instead of exact empty catch bodies");
    }


    @Test
    void braveExceptionBreadcrumbsFollowTheThrownFailureType() {
        for (boolean resourceAccess : List.of(true, false)) {
            TraceStore.clear();
            BraveSearchService service = enabledBraveService("fixture-brave-breadcrumb-key", 2000, 0.0d);
            RestTemplate template = (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
            var attempts = new java.util.concurrent.atomic.AtomicInteger();
            template.setRequestFactory((uri, method) -> {
                attempts.incrementAndGet();
                if (resourceAccess) throw new org.springframework.web.client.ResourceAccessException("synthetic transport failure");
                throw new IllegalStateException("synthetic generic failure");
            });
            BraveSearchResult result = service.searchWithMeta("synthetic breadcrumb query", 3);
            assertEquals(BraveSearchResult.Status.EXCEPTION, result.status());
            assertTrue(result.snippets().isEmpty());
            assertEquals(1, attempts.get());
            assertEquals(true, TraceStore.get(resourceAccess ? "web.brave.suppressed.resourceAccess" : "web.brave.suppressed.exception"));
            assertNull(TraceStore.get(resourceAccess ? "web.brave.suppressed.exception" : "web.brave.suppressed.resourceAccess"));
            assertEquals(resourceAccess ? "transport-error" : "exception", TraceStore.get("web.brave.failureReason"));
            assertProviderTraceDoesNotContain("synthetic breadcrumb query", "fixture-brave-breadcrumb-key");
        }
    }

    @Test
    void braveUtilityFallbacksExposeDirectTraceBreadcrumbs() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/web/BraveSearchService.java"));

        assertTrue(source.contains("TraceStore.put(\"web.brave.suppressed.safeHost\", true)"));
        assertTrue(source.contains("TraceStore.put(\"web.brave.suppressed.safeHost.errorType\""));
        assertTrue(source.contains("TraceStore.put(\"web.brave.suppressed.cacheableDepth\", true)"));
        assertTrue(source.contains("TraceStore.put(\"web.brave.suppressed.cacheableDepth.errorType\""));
        assertTrue(source.contains("TraceStore.put(\"web.brave.suppressed.cacheOnly.missTrace\", true)"));
        assertTrue(source.contains("TraceStore.put(\"web.brave.suppressed.cacheOnly.hitTrace\", true)"));
        assertTrue(source.contains("TraceStore.put(\"web.brave.suppressed.cacheOnly.errorTrace\", true)"));
        assertTrue(source.contains("TraceStore.put(\"web.brave.suppressed.cacheOnly.getCached\", true)"));
        assertTrue(source.contains("TraceStore.put(\"web.brave.suppressed.cacheSeed.suppressedTrace\", true)"));
        assertTrue(source.contains("TraceStore.put(\"web.brave.suppressed.cacheSeed.cacheRead\", true)"));
        assertTrue(source.contains("TraceStore.put(\"web.brave.suppressed.cacheSeed.cacheWrite\", true)"));
        assertTrue(source.contains("TraceStore.put(\"web.brave.suppressed.cacheSeed.seededTrace\", true)"));
        assertTrue(source.contains("TraceStore.put(\"web.brave.suppressed.cacheSeed.outer\", true)"));
        assertTrue(source.contains("TraceStore.put(\"web.brave.suppressed.quotaExhaustedTrace\", true)"));
        assertTrue(source.contains("TraceStore.put(\"web.brave.suppressed.rateLimitLocalTrace\", true)"));
        assertTrue(source.contains("TraceStore.put(\"web.brave.suppressed.localStreakReset\", true)"));
        assertTrue(source.contains("TraceStore.put(\"web.brave.suppressed.http429\", true)"));
        assertTrue(source.contains("TraceStore.put(\"web.brave.suppressed.httpStatus\", true)"));
        assertTrue(source.contains("TraceStore.put(\"web.brave.suppressed.adaptiveInterrupt\", true)"));
        assertTrue(source.contains("TraceStore.put(\"web.brave.suppressed.adaptiveTrace\", true)"));
        assertTrue(source.contains("TraceStore.put(\"web.brave.suppressed.adaptiveSkippedTrace\", true)"));
        assertTrue(source.contains("TraceStore.put(\"web.brave.suppressed.countsTrace\", true)"));
        assertTrue(source.contains("TraceStore.put(\"web.brave.suppressed.failureTrace\", true)"));
        assertTrue(source.contains("TraceStore.put(\"web.brave.suppressed.logSkipOnce\", true)"));
        assertTrue(source.contains("TraceStore.put(\"web.brave.suppressed.jsonParse\", true)"));
        assertTrue(source.contains("TraceStore.put(\"web.brave.suppressed.regexFallback\", true)"));
        assertTrue(source.contains("TraceStore.put(\"web.brave.suppressed.localCooldownProps\", true)"));
        assertTrue(source.contains("TraceStore.put(\"web.brave.suppressed.localCooldownStreak\", true)"));
        assertTrue(source.contains("TraceStore.put(\"web.brave.suppressed.localCooldownJitter\", true)"));
        assertTrue(source.contains("TraceStore.put(\"web.brave.suppressed.localCooldownTrace\", true)"));
        assertTrue(source.contains("TraceStore.put(\"web.brave.suppressed.markQuotaTrace\", true)"));
        assertTrue(source.contains("TraceStore.put(\"web.brave.suppressed.freeTierQuotaTrace\", true)"));
        assertTrue(source.contains("TraceStore.put(\"web.brave.suppressed.parseMonthlyRemaining\", true)"));
        assertTrue(source.contains("TraceStore.put(\"web.brave.suppressed.startCooldownTrace\", true)"));
        assertTrue(source.contains("TraceStore.put(\"web.brave.suppressed.remoteCooldownTrace\", true)"));
        assertTrue(source.contains("TraceStore.put(\"web.brave.suppressed.retryAfterSecondsParse\", true)"));
        assertTrue(source.contains("TraceStore.put(\"web.brave.suppressed.retryAfterDateParse\", true)"));
    }

    @Test
    void braveSafeHostSuppressionStoresErrorType() {
        String host = ReflectionTestUtils.invokeMethod(BraveSearchService.class, "safeHost", "http://[");

        assertEquals("invalid", host);
        assertEquals(Boolean.TRUE, TraceStore.get("web.brave.suppressed.safeHost"));
        assertEquals("IllegalArgumentException", TraceStore.get("web.brave.suppressed.safeHost.errorType"));
    }

    @Test
    void braveRetryAfterParserLeavesStableErrorTypeBreadcrumbs() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Retry-After", "private-brave-date");

        Long parsed = ReflectionTestUtils.invokeMethod(BraveSearchService.class, "retryAfterToMs", headers);

        assertTrue(parsed != null && parsed > 0L);
        assertEquals(Boolean.TRUE, TraceStore.get("web.brave.suppressed.retryAfterSecondsParse"));
        assertEquals("invalid_number",
                TraceStore.get("web.brave.suppressed.retryAfterSecondsParse.errorType"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.brave.suppressed.retryAfterDateParse"));
        assertEquals("invalid_date",
                TraceStore.get("web.brave.suppressed.retryAfterDateParse.errorType"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("private-brave-date"), trace);
        assertFalse(trace.contains("NumberFormatException"), trace);
        assertFalse(trace.contains("DateTimeParseException"), trace);
    }

    @Test
    void tavilyRetryAfterParserLeavesStableErrorTypeBreadcrumbs() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Retry-After", "private-tavily-date");

        Long parsed = ReflectionTestUtils.invokeMethod(TavilyWebSearchRetriever.class, "retryAfterToMs", headers);

        assertEquals(0L, parsed);
        assertEquals(Boolean.TRUE, TraceStore.get("web.tavily.suppressed.retryAfterSecondsParse"));
        assertEquals("invalid_number",
                TraceStore.get("web.tavily.suppressed.retryAfterSecondsParse.errorType"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.tavily.suppressed.retryAfterDateParse"));
        assertEquals("invalid_date",
                TraceStore.get("web.tavily.suppressed.retryAfterDateParse.errorType"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("private-tavily-date"), trace);
        assertFalse(trace.contains("NumberFormatException"), trace);
        assertFalse(trace.contains("DateTimeParseException"), trace);
    }

    @Test
    void braveMonthlyRemainingParserLeavesStableErrorTypeWithoutRawHeader() throws Exception {
        TraceStore.clear();
        BraveSearchService service = enabledBraveService("sk-bravequota000003", 10);
        HttpHeaders headers = new HttpHeaders();
        String raw = "1, ownerToken=raw-secret";
        headers.add("X-RateLimit-Remaining", raw);

        Integer parsed = ReflectionTestUtils.invokeMethod(service,
                "parseMonthlyRemainingHeader", headers, "test-context");

        assertNull(parsed);
        assertEquals(Boolean.TRUE, TraceStore.get("web.brave.suppressed.parseMonthlyRemaining"));
        assertEquals("invalid_number", TraceStore.get("web.brave.suppressed.parseMonthlyRemaining.errorType"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(raw), trace);
        assertFalse(trace.contains("NumberFormatException"), trace);
        String source = Files.readString(Path.of("main/java/com/example/lms/service/web/BraveSearchService.java"));
        assertFalse(source.contains(
                "log.warn(\"[Brave] Failed to parse X-RateLimit-Remaining on {}: {}\", context, remainingHeader)"));
        assertTrue(source.contains("headerHash"));
        assertTrue(source.contains("headerLength"));
    }

    @Test
    void braveTraceSuppressionsNormalizeNumericErrorType() {
        TraceStore.clear();

        BraveSearchTraceSuppressions.trace("retryAfterSecondsParse", new NumberFormatException("ownerToken=secret"));

        assertEquals("retryAfterSecondsParse", TraceStore.get("web.brave.suppressed.stage"));
        assertEquals("invalid_number", TraceStore.get("web.brave.suppressed.errorType"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.brave.suppressed.retryAfterSecondsParse"));
        assertEquals("invalid_number", TraceStore.get("web.brave.suppressed.retryAfterSecondsParse.errorType"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("NumberFormatException"));
        assertFalse(trace.contains("ownerToken=secret"));
    }

    @Test
    void adaptiveWebSearchHandlerDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/integration/handlers/AdaptiveWebSearchHandler.java"));

        assertEquals(0, Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}").matcher(source).results().count(),
                "AdaptiveWebSearchHandler fail-soft paths need trace breadcrumbs instead of exact empty catch bodies");
    }

    @Test
    void adaptiveWebMetadataIntegerParserOnlyCatchesNumberFormatException() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/integration/handlers/AdaptiveWebSearchHandler.java"),
                StandardCharsets.UTF_8).replace("\r\n", "\n");
        String parserCall = "return v == null ? d : Integer.parseInt(String.valueOf(v));";
        int parser = source.indexOf(parserCall);
        assertTrue(parser >= 0, "AdaptiveWeb metaInt parser should be locatable");
        String window = source.substring(parser, Math.min(source.length(), parser + 220));

        assertFalse(window.contains("catch (Exception"),
                "AdaptiveWeb metadata parser fallback must not hide non-parse failures");
        assertTrue(window.contains("catch (NumberFormatException"),
                "AdaptiveWeb metadata parser fallback should catch only NumberFormatException");
    }

    @Test
    void adaptiveWebInvalidMetadataIntegerUsesStableReasonCodeWithoutRawValue() {
        Integer out = ReflectionTestUtils.invokeMethod(
                com.example.lms.integration.handlers.AdaptiveWebSearchHandler.class,
                "metaInt",
                java.util.Map.of("webTopK", "private adaptive topK"),
                "webTopK",
                5);

        assertEquals(5, out);
        assertEquals("metaInt", TraceStore.get("web.adaptive.suppressed.stage"));
        assertEquals("invalid_number", TraceStore.get("web.adaptive.suppressed.errorType"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.adaptive.suppressed.metaInt"));
        assertEquals("invalid_number", TraceStore.get("web.adaptive.suppressed.metaInt.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("private adaptive topK"));
    }

    @Test
    void analyzeWebSearchRetrieverDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/rag/AnalyzeWebSearchRetriever.java"));

        assertEquals(0, Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}").matcher(source).results().count(),
                "AnalyzeWebSearchRetriever fail-soft paths need trace breadcrumbs instead of exact empty catch bodies");
    }

    @Test
    void providerFailSoftLogsDoNotUseRawThrowableMessages() throws Exception {
        assertProviderLogDoesNotUseRawThrowableMessages(
                "main/java/com/example/lms/gptsearch/web/AbstractWebSearchProvider.java");
        assertProviderLogDoesNotUseRawThrowableMessages(
                "main/java/com/example/lms/integration/handlers/AdaptiveWebSearchHandler.java");
        assertProviderLogDoesNotUseRawThrowableMessages(
                "main/java/com/example/lms/service/rag/TavilyWebSearchRetriever.java");
        assertProviderLogDoesNotUseRawThrowableMessages(
                "main/java/com/example/lms/service/rag/WebSearchRetriever.java");
        assertProviderLogDoesNotUseRawThrowableMessages(
                "main/java/ai/abandonware/nova/orch/adapters/NovaAnalyzeWebSearchRetriever.java");
        assertProviderLogDoesNotUseRawThrowableMessages(
                "main/java/com/example/lms/service/rag/AnalyzeWebSearchRetriever.java");
        String abstractProvider = Files.readString(Path.of("main/java/com/example/lms/gptsearch/web/AbstractWebSearchProvider.java"));
        assertFalse(abstractProvider.contains("web search failed: {}"));
        assertFalse(abstractProvider.contains("SafeRedactor.safeMessage(String.valueOf(t), 180)"));
        assertTrue(abstractProvider.contains("[AWX][search][provider] web search failed provider={} failureReason={} errorType={} queryHash12={} queryLength={}"));
        assertTrue(abstractProvider.contains("\"provider-search-error\""));
        assertTrue(abstractProvider.contains("SafeRedactor.traceLabelOrFallback(t.getClass().getSimpleName(), \"unknown\")"));
        assertTrue(abstractProvider.contains("SafeRedactor.hash12(query)"));
        String tavily = Files.readString(Path.of("main/java/com/example/lms/service/rag/TavilyWebSearchRetriever.java"));
        assertFalse(tavily.contains("SafeRedactor.safeMessage(String.valueOf(e)"));
        assertTrue(tavily.contains("log.debug(\"[TavilyWebSearchRetriever] fail-soft stage={} errorType={}\",")
                        && tavily.contains("\"retryAfter.seconds\", \"invalid_number\""),
                "Tavily numeric Retry-After parse fallback should use stable invalid_number error label");
        assertTrue(tavily.contains("\"retryAfter.httpDate\", \"invalid_date\""),
                "Tavily HTTP-date Retry-After parse fallback should use stable invalid_date error label");
        String webSearch = Files.readString(Path.of("main/java/com/example/lms/service/rag/WebSearchRetriever.java"));
        assertFalse(webSearch.contains("SafeRedactor.safeMessage(e.getMessage()"));
        assertTrue(webSearch.contains("[AWX][search][web] attempt failed failureReason={} errorType={} attempt={}/{} queryHash12={} queryLength={}"));
        String adaptive = Files.readString(Path.of("main/java/com/example/lms/integration/handlers/AdaptiveWebSearchHandler.java"));
        assertFalse(adaptive.contains("Web provider {} failed: {}"));
        assertFalse(adaptive.contains("[AdaptiveWeb] search failed: {}"));
        assertFalse(adaptive.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertFalse(adaptive.contains("SafeRedactor.safeMessage(String.valueOf(ex), 180)"));
        assertFalse(adaptive.contains("/* ?덉쟾 臾댁떆 */"));
        assertTrue(adaptive.contains("[AdaptiveWeb] provider failed provider={} errorHash={} errorLength={}"));
        assertTrue(adaptive.contains("[AdaptiveWeb] search failed. errorHash={} errorLength={}"));
        assertTrue(adaptive.contains("traceSuppressed(\"metadataGate\", ignore)"));
        assertTrue(adaptive.contains("traceSuppressed(\"searchModeParse\", ignore)"));
        assertTrue(adaptive.contains("traceSuppressed(\"providerProbe\", ignore)"));
        assertTrue(adaptive.contains("TraceStore.put(\"web.adaptive.suppressed.\" + safeStage, true)"));
        assertTrue(adaptive.contains("safeProviderId(p), SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));
        assertTrue(adaptive.contains("SafeRedactor.hashValue(messageOf(ex)), messageLength(ex)"));
        String novaAnalyze = Files.readString(Path.of("main/java/ai/abandonware/nova/orch/adapters/NovaAnalyzeWebSearchRetriever.java"));
        assertFalse(novaAnalyze.contains("SafeRedactor.safeMessage(String.valueOf(e)"));
        assertFalse(novaAnalyze.contains("SafeRedactor.safeMessage(e.getMessage()"));
        assertFalse(novaAnalyze.contains("catch (Exception ignore) {\n                    // ignore\n                }"));
        assertTrue(novaAnalyze.contains("traceCancelFailure(\"\", cancelFailure)"),
                "cancel failure tracing must not retain the raw query");
        assertTrue(novaAnalyze.contains("traceInterruptedPoll(originalQuery, interrupted)"));
        assertTrue(novaAnalyze.contains("web.await.analyze.cancelFailure.reason"));
        assertTrue(novaAnalyze.contains("web.await.analyze.interrupted.reason"));
        String analyze = Files.readString(Path.of("main/java/com/example/lms/service/rag/AnalyzeWebSearchRetriever.java"));
        assertFalse(analyze.contains("SafeRedactor.safeMessage(String.valueOf(e)"));
        assertFalse(analyze.contains("SafeRedactor.safeMessage(e.getMessage()"));
    }

    @Test
    void novaAnalyzeMetadataIntegerParserOnlyCatchesNumberFormatException() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/adapters/NovaAnalyzeWebSearchRetriever.java"),
                StandardCharsets.UTF_8).replace("\r\n", "\n");
        String parserCall = "return Integer.parseInt(s);";
        int parser = source.indexOf(parserCall);
        assertTrue(parser >= 0, "NovaAnalyze metaInt parser should be locatable");
        String window = source.substring(parser, Math.min(source.length(), parser + 220));

        assertFalse(window.contains("catch (Exception"),
                "NovaAnalyze metadata parser fallback must not hide non-parse failures");
        assertTrue(window.contains("catch (NumberFormatException"),
                "NovaAnalyze metadata parser fallback should catch only NumberFormatException");
        assertTrue(window.contains("traceMetaIntParseFallback(key, parseError)"),
                "NovaAnalyze metadata parser fallback should leave a redacted breadcrumb");
    }

    @Test
    void analyzeMetadataIntegerParserOnlyCatchesNumberFormatException() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/rag/AnalyzeWebSearchRetriever.java"),
                StandardCharsets.UTF_8).replace("\r\n", "\n");
        String parserCall = "return Integer.parseInt(s);";
        int parser = source.indexOf(parserCall);
        assertTrue(parser >= 0, "Analyze metaInt parser should be locatable");
        String window = source.substring(parser, Math.min(source.length(), parser + 220));

        assertFalse(window.contains("catch (Exception"),
                "Analyze metadata parser fallback must not hide non-parse failures");
        assertTrue(window.contains("catch (NumberFormatException"),
                "Analyze metadata parser fallback should catch only NumberFormatException");
        assertTrue(window.contains("fail-soft stage={} errorType={}")
                        && window.contains("\"metaInt.parse\"")
                        && window.contains("\"invalid_number\""),
                "Analyze metadata parser fallback should use stable invalid_number error label");
    }

    @Test
    void webProviderStructuredLogDoesNotRenderThrowableMessages() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/aop/WebProviderStructuredLogAspect.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(t), 180)"));
        assertFalse(source.contains("{} error: {}{}"));
        assertTrue(source.contains("failureReason={} errorType={} httpStatus={}"));
        assertTrue(source.contains("SafeRedactor.traceLabelOrFallback(t.getClass().getSimpleName(), \"unknown\")"));
    }

    @Test
    void abstractProviderBlankQueryEmitsGenericSkipDiagnosticsWithoutCallingProvider() {
        TraceStore.clear();
        AbstractWebSearchProvider provider = new AbstractWebSearchProvider() {
            @Override
            public ProviderId id() {
                return ProviderId.MOCK;
            }

            @Override
            protected WebSearchResult doSearch(WebSearchQuery q) {
                throw new AssertionError("blank query should not call provider");
            }
        };

        WebSearchResult result = provider.search(new WebSearchQuery("   ", 2, null, null));

        assertTrue(result.getDocuments().isEmpty());
        assertEquals(2, TraceStore.get("web.mock.requestedCount"));
        assertEquals(0, TraceStore.get("web.mock.returnedCount"));
        assertEquals(0, TraceStore.get("web.mock.afterFilterCount"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.mock.providerDisabled"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.mock.providerEmpty"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.mock.afterFilterStarved"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.mock.zeroResults"));
        assertEquals("blank_query", TraceStore.get("web.mock.skipped.reason"));
        assertEquals("blank_query", TraceStore.get("web.mock.failureReason"));
        assertNull(TraceStore.get("web.mock.queryHash"));
        assertEquals(3, TraceStore.get("web.mock.queryLength"));
    }

    @Test
    void abstractProviderExceptionEmitsGenericFailureDiagnosticsWithoutRawQueryOrMessage() {
        TraceStore.clear();
        String rawQuery = "private generic provider failure query";
        String secret = "provider-token-placeholder";
        AbstractWebSearchProvider provider = new AbstractWebSearchProvider() {
            @Override
            public ProviderId id() {
                return ProviderId.MOCK;
            }

            @Override
            protected WebSearchResult doSearch(WebSearchQuery q) {
                throw new IllegalStateException("failed for " + rawQuery + " ownerToken=" + secret);
            }
        };

        WebSearchResult result = provider.search(new WebSearchQuery(rawQuery, 3, null, null));

        assertTrue(result.getDocuments().isEmpty());
        assertEquals(3, TraceStore.get("web.mock.requestedCount"));
        assertEquals(0, TraceStore.get("web.mock.returnedCount"));
        assertEquals(0, TraceStore.get("web.mock.afterFilterCount"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.mock.providerDisabled"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.mock.providerEmpty"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.mock.afterFilterStarved"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.mock.zeroResults"));
        assertEquals("provider-search-error", TraceStore.get("web.mock.failureReason"));
        assertEquals("IllegalStateException", TraceStore.get("web.mock.errorType"));
        assertTrue(String.valueOf(TraceStore.get("web.mock.queryHash")).startsWith("hash:"));
        assertEquals(rawQuery.length(), TraceStore.get("web.mock.queryLength"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(rawQuery), trace);
        assertFalse(trace.contains(secret), trace);
        assertFalse(trace.contains("ownerToken"), trace);
    }

    @Test
    void providerDisabledAndFailureReasonsUseSafeMessages() throws Exception {
        String naver = Files.readString(Path.of("main/java/com/example/lms/service/NaverSearchService.java"));
        String brave = Files.readString(Path.of("main/java/com/example/lms/service/web/BraveSearchService.java"));
        String serp = Files.readString(Path.of("main/java/com/example/lms/gptsearch/web/impl/SerpApiProvider.java"));
        String tavily = Files.readString(Path.of("main/java/com/example/lms/service/rag/TavilyWebSearchRetriever.java"));

        assertFalse(naver.contains("TraceStore.put(\"web.naver.disabledReason\", reason);"));
        assertFalse(naver.contains("TraceStore.put(\"web.naver.disabledReasonCanonical\", reason);"));
        assertFalse(naver.contains("TraceStore.put(\"web.naver.failureReason\", reason);"));
        assertFalse(naver.contains("TraceStore.put(\"web.naver.disabledReason\", SafeRedactor.safeMessage(reason, 120));"));
        assertFalse(naver.contains("TraceStore.put(\"web.naver.disabledReasonCanonical\", SafeRedactor.safeMessage(reason, 120));"));
        assertFalse(naver.contains("TraceStore.put(\"web.naver.failureReason\", SafeRedactor.safeMessage(reason, 120));"));
        assertTrue(naver.contains("String safeReason = SafeRedactor.traceLabelOrFallback(reason, \"unknown\");"));
        assertTrue(naver.contains("TraceStore.put(\"web.naver.disabledReason\", safeReason);"));
        assertTrue(naver.contains("TraceStore.put(\"web.naver.disabledReasonCanonical\", safeReason);"));
        assertTrue(naver.contains("TraceStore.put(\"web.naver.skipped.reason\", safeReason);"));
        assertTrue(naver.contains("TraceStore.put(\"web.naver.failureReason\", safeReason);"));

        assertFalse(brave.contains("TraceStore.put(\"web.brave.disabledReason\", reason);"));
        assertFalse(brave.contains("TraceStore.put(\"web.brave.disabledReasonCanonical\", reason);"));
        assertFalse(brave.contains("TraceStore.put(\"web.brave.quota.disabledReason\", reason);"));
        assertFalse(brave.contains("TraceStore.put(\"web.brave.failureReason\", failureReason == null ? \"unknown\" : failureReason);"));
        assertFalse(brave.contains("log.info(\"[{}] Brave call skipped (reason={}){}\", tag, reason, LogCorrelation.suffix());"));
        assertFalse(brave.contains("TraceStore.put(\"web.brave.disabledReason\", disabledReason == null || disabledReason.isBlank()"));
        assertFalse(brave.contains("TraceStore.put(\"web.brave.disabledReasonCanonical\", disabledReason == null || disabledReason.isBlank()"));
        assertFalse(brave.contains("TraceStore.put(\"web.brave.disabledReason\", SafeRedactor.safeMessage(reason, 120));"));
        assertFalse(brave.contains("TraceStore.put(\"web.brave.disabledReasonCanonical\", SafeRedactor.safeMessage(reason, 120));"));
        assertFalse(brave.contains("TraceStore.put(\"web.brave.quota.disabledReason\", SafeRedactor.safeMessage(reason, 120));"));
        assertFalse(brave.contains("String safeFailureReason = SafeRedactor.safeMessage(failureReason == null ? \"unknown\" : failureReason, 120);"));
        assertTrue(brave.contains("TraceStore.put(\"web.brave.disabledReason\", SafeRedactor.traceLabelOrFallback(reason, \"unknown\"));"));
        assertTrue(brave.contains("TraceStore.put(\"web.brave.disabledReasonCanonical\", SafeRedactor.traceLabelOrFallback(reason, \"unknown\"));"));
        assertTrue(brave.contains("TraceStore.put(\"web.brave.quota.disabledReason\", SafeRedactor.traceLabelOrFallback(reason, \"unknown\"));"));
        assertTrue(brave.contains("String safeFailureReason = SafeRedactor.traceLabelOrFallback(failureReason == null ? \"unknown\" : failureReason, \"unknown\");"));
        assertTrue(brave.contains("TraceStore.put(\"web.brave.failureReason\", safeFailureReason);"));
        assertTrue(brave.contains("SafeRedactor.traceLabelOrFallback(reason, \"unknown\"), LogCorrelation.suffix()"));
        assertTrue(brave.contains("safeDisabledReason(disabledReason, \"disabled\")"));
        assertTrue(brave.contains("safeDisabledReason(disabledReason, \"quota_exhausted\")"));

        assertFalse(serp.contains("TraceStore.put(\"web.serpapi.disabledReason\", reason);"));
        assertFalse(serp.contains("TraceStore.put(\"web.serpapi.disabledReasonCanonical\", reason);"));
        assertFalse(serp.contains("TraceStore.put(\"web.serpapi.failureReason\", failureReason == null ? \"unknown\" : failureReason);"));
        assertFalse(serp.contains("TraceStore.put(\"web.serpapi.disabledReason\", SafeRedactor.safeMessage(reason, 120));"));
        assertFalse(serp.contains("TraceStore.put(\"web.serpapi.disabledReasonCanonical\", SafeRedactor.safeMessage(reason, 120));"));
        assertFalse(serp.contains("String safeFailureReason = SafeRedactor.safeMessage(failureReason == null ? \"unknown\" : failureReason, 120);"));
        assertTrue(serp.contains("String safeReason = SafeRedactor.traceLabelOrFallback(reason, \"unknown\");"));
        assertTrue(serp.contains("TraceStore.put(\"web.serpapi.disabledReason\", safeReason);"));
        assertTrue(serp.contains("TraceStore.put(\"web.serpapi.disabledReasonCanonical\", safeReason);"));
        assertTrue(serp.contains("TraceStore.put(\"web.serpapi.skipped.reason\", safeReason);"));
        assertTrue(serp.contains("String safeFailureReason = SafeRedactor.traceLabelOrFallback(failureReason == null ? \"unknown\" : failureReason, \"unknown\");"));
        assertTrue(serp.contains("TraceStore.put(\"web.serpapi.failureReason\", safeFailureReason);"));

        assertFalse(tavily.contains("TraceStore.put(\"web.tavily.disabledReason\", reason);"));
        assertFalse(tavily.contains("TraceStore.put(\"web.tavily.disabledReasonCanonical\", reason);"));
        assertFalse(tavily.contains("TraceStore.put(\"web.tavily.failureReason\", reason);"));
        assertFalse(tavily.contains("TraceStore.put(\"web.tavily.disabledReason\", SafeRedactor.safeMessage(reason, 120));"));
        assertFalse(tavily.contains("TraceStore.put(\"web.tavily.disabledReasonCanonical\", SafeRedactor.safeMessage(reason, 120));"));
        assertFalse(tavily.contains("TraceStore.put(\"web.tavily.failureReason\", SafeRedactor.safeMessage(reason, 120));"));
        assertTrue(tavily.contains("String safeReason = SafeRedactor.traceLabelOrFallback(reason, \"unknown\");"));
        assertTrue(tavily.contains("TraceStore.put(\"web.tavily.disabledReason\", safeReason);"));
        assertTrue(tavily.contains("TraceStore.put(\"web.tavily.disabledReasonCanonical\", safeReason);"));
        assertTrue(tavily.contains("TraceStore.put(\"web.tavily.skipped.reason\", safeReason);"));
        assertTrue(tavily.contains("TraceStore.put(\"web.tavily.failureReason\", SafeRedactor.traceLabelOrFallback(failureReason, \"unknown\"));"));
    }

    @Test
    void providerAdaptiveTriggerReasonsUseSafeMessages() throws Exception {
        String naver = Files.readString(Path.of("main/java/com/example/lms/service/NaverSearchService.java"));
        String brave = Files.readString(Path.of("main/java/com/example/lms/service/web/BraveSearchService.java"));

        assertFalse(naver.contains("TraceStore.put(\"web.naver.adaptive.triggerReason\", plan.triggerReason());"));
        assertFalse(naver.contains(
                "TraceStore.put(\"web.naver.adaptive.triggerReason\", SafeRedactor.safeMessage(plan.triggerReason(), 120));"));
        assertTrue(naver.contains(
                "TraceStore.put(\"web.naver.adaptive.triggerReason\", SafeRedactor.traceLabelOrFallback(plan.triggerReason(), \"unknown\"));"));
        assertFalse(brave.contains("TraceStore.put(\"web.brave.adaptive.triggerReason\", plan.triggerReason());"));
        assertFalse(brave.contains(
                "TraceStore.put(\"web.brave.adaptive.triggerReason\", reason == null || reason.isBlank() ? \"skipped\" : reason);"));
        assertFalse(brave.contains(
                "TraceStore.put(\"web.brave.adaptive.triggerReason\", SafeRedactor.safeMessage(plan.triggerReason(), 120));"));
        assertFalse(brave.contains(
                "TraceStore.put(\"web.brave.adaptive.triggerReason\", SafeRedactor.safeMessage(reason == null || reason.isBlank() ? \"skipped\" : reason, 120));"));
        assertTrue(brave.contains(
                "TraceStore.put(\"web.brave.adaptive.triggerReason\", SafeRedactor.traceLabelOrFallback(plan.triggerReason(), \"unknown\"));"));
        assertTrue(brave.contains(
                "TraceStore.put(\"web.brave.adaptive.triggerReason\", SafeRedactor.traceLabelOrFallback(reason == null || reason.isBlank() ? \"skipped\" : reason, \"skipped\"));"));
    }

    @Test
    void braveConfigLogDoesNotWriteRawBaseUrl() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/web/BraveSearchService.java"));

        assertFalse(source.contains("[Brave] Config: baseUrl={}, timeout={}ms"));
        assertFalse(source.contains("baseUrl, timeoutMs"));
        assertTrue(source.contains("[Brave] Config: baseUrlHost={} baseUrlHash={} timeout={}ms"));
        assertTrue(source.contains("safeHost(baseUrl)"));
        assertTrue(source.contains("SafeRedactor.hashValue(baseUrl)"));
    }

    @Test
    void serpApiConfigLogDoesNotWriteRawBaseUrl() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/gptsearch/web/impl/SerpApiProvider.java"));

        assertFalse(source.contains("[SerpApi] Config: baseUrl={}, timeout={}ms"));
        assertFalse(source.contains("baseUrl, timeoutMs"));
        assertTrue(source.contains("[SerpApi] Config: baseUrlHost={} baseUrlHash={} timeout={}ms"));
        assertTrue(source.contains("safeHost(baseUrl)"));
        assertTrue(source.contains("SafeRedactor.hashValue(baseUrl)"));
    }

    @Test
    void serpApiSuppressedPathsLeaveFixedStageBreadcrumbs() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/gptsearch/web/impl/SerpApiProvider.java"));

        assertTrue(source.contains("traceSuppressed(\"serpapi.safeHost\", ignored);"));
        assertTrue(source.contains("traceSuppressed(\"serpapi.rateLimit\", e);"));
        assertTrue(source.contains("traceSuppressed(\"serpapi.httpStatus\", e);"));
        assertTrue(source.contains("traceSuppressed(\"serpapi.resourceAccess\", e);"));
        assertTrue(source.contains("traceSuppressed(\"serpapi.search\", e);"));
        assertTrue(source.contains("traceSuppressed(\"serpapi.providerDisabledTrace\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"serpapi.failureTrace\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"serpapi.cooldownTrace\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"serpapi.retryAfterSeconds\", ignore);"));
        assertTrue(source.contains("traceSuppressed(\"serpapi.retryAfterDate\", ignore);"));
        assertTrue(source.contains("TraceStore.put(\"web.serpapi.suppressed.stage\", safeStage);"));
        assertTrue(source.contains("TraceStore.put(\"web.serpapi.suppressed.errorType\", safeErrorType);"));
        assertTrue(source.contains("TraceStore.put(\"web.serpapi.suppressed.\" + safeStage, true);"));
        assertTrue(source.contains("TraceStore.put(\"web.serpapi.suppressed.\" + safeStage + \".errorType\", safeErrorType);"));
    }

    @Test
    void serpApiRetryAfterParserLeavesStableErrorTypeBreadcrumbs() {
        TraceStore.clear();
        HttpHeaders headers = new HttpHeaders();
        headers.set("Retry-After", "private-not-a-date");

        Long parsed = ReflectionTestUtils.invokeMethod(SerpApiProvider.class, "retryAfterToMs", headers);

        assertEquals(0L, parsed);
        assertEquals("serpapi.retryAfterDate", TraceStore.get("web.serpapi.suppressed.stage"));
        assertEquals("invalid_date", TraceStore.get("web.serpapi.suppressed.errorType"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.serpapi.suppressed.serpapi.retryAfterSeconds"));
        assertEquals("invalid_number",
                TraceStore.get("web.serpapi.suppressed.serpapi.retryAfterSeconds.errorType"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.serpapi.suppressed.serpapi.retryAfterDate"));
        assertEquals("invalid_date", TraceStore.get("web.serpapi.suppressed.serpapi.retryAfterDate.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("private-not-a-date"));
    }

    @Test
    void providerErrorBodyDiagnosticsDoNotKeepPreviewHelpers() throws Exception {
        String brave = Files.readString(Path.of("main/java/com/example/lms/service/web/BraveSearchService.java"));
        String serp = Files.readString(Path.of("main/java/com/example/lms/gptsearch/web/impl/SerpApiProvider.java"));

        for (String source : List.of(brave, serp)) {
            assertFalse(source.contains("redactedBodyPreview("));
            assertFalse(source.contains("SafeRedactor.safeMessage(body, 240)"));
            assertTrue(source.contains(".errorBodyHash"));
            assertTrue(source.contains(".errorBodyLength"));
        }
    }

    @Test
    void braveDisabledProviderEmitsCanonicalCountsWithoutExternalCall() {
        TraceStore.clear();
        BraveSearchService service = new BraveSearchService(new BraveSearchProperties(
                true,
                "https://api.search.brave.com/res/v1/web/search",
                "",
                1.0d,
                2000,
                500L,
                200L,
                2000L));
        ReflectionTestUtils.setField(service, "configEnabled", true);
        ReflectionTestUtils.setField(service, "apiKey", "");
        service.init();

        BraveSearchResult result = service.searchWithMeta("disabled brave query", 7);

        assertTrue(result.snippets().isEmpty());
        assertEquals(Boolean.TRUE, TraceStore.get("web.brave.providerDisabled"));
        assertEquals(7, TraceStore.get("web.brave.requestedCount"));
        assertEquals(0, TraceStore.get("web.brave.returnedCount"));
        assertEquals(0, TraceStore.get("web.brave.afterFilterCount"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.brave.zeroResults"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.brave.afterFilterStarved"));
        assertEquals("missing_brave_api_key", TraceStore.get("web.brave.disabledReason"));
        assertEquals("missing_brave_api_key", TraceStore.get("web.brave.disabledReasonCanonical"));
        assertEquals("missing_brave_api_key", TraceStore.get("web.brave.skipped.reason"));
        assertTrue(String.valueOf(TraceStore.get("web.brave.queryHash")).startsWith("hash:"));
    }

    @Test
    void braveAndNaverProviderCountsEmitCommonRedactedWebTraceKeys() {
        String rawQuery = "private common trace query ownerToken=raw-secret";
        String rawReason = "missing_brave_api_key";

        TraceStore.clear();
        BraveSearchService brave = enabledBraveService("sk-bravecommon0001");
        ReflectionTestUtils.invokeMethod(brave, "traceBraveCounts",
                rawQuery, 4, 0, 0, true, rawReason);

        assertEquals("brave", TraceStore.get("web.provider.name"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.provider.enabled"));
        assertEquals("missing-key", TraceStore.get("web.provider.disabledReason"));
        assertEquals(0, TraceStore.get("web.provider.resultCount"));
        assertEquals(0, TraceStore.get("web.query.variantCount"));
        assertTrue(String.valueOf(TraceStore.get("web.query.hash")).startsWith("hash:"));
        assertEquals("provider-disabled", TraceStore.get("web.failsoft.reason"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(rawQuery));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("raw-secret"));

        TraceStore.clear();
        NaverSearchService naver = naverService("naver-id:naver-secret", "", "");
        ReflectionTestUtils.invokeMethod(naver, "traceNaverCounts",
                rawQuery, 5, 3, 0, false, null);

        assertEquals("naver", TraceStore.get("web.provider.name"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.provider.enabled"));
        assertNull(TraceStore.get("web.provider.disabledReason"));
        assertEquals(3, TraceStore.get("web.provider.resultCount"));
        assertEquals(0, TraceStore.get("web.query.variantCount"));
        assertTrue(String.valueOf(TraceStore.get("web.query.hash")).startsWith("hash:"));
        assertEquals("after-filter-starvation", TraceStore.get("web.failsoft.reason"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains(rawQuery));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("raw-secret"));
    }

    @Test
    void hybridMergeBoundaryEmitsCommonFusionAndStarvationTraceKeys() {
        TraceStore.clear();
        HybridWebSearchProvider provider = new HybridWebSearchProvider(
                mock(NaverSearchService.class),
                mock(BraveSearchService.class));

        ReflectionTestUtils.invokeMethod(provider,
                "emitMergeBoundaryEvent",
                "unit",
                "private merge query api_key=raw-secret",
                3,
                List.of("brave-one"),
                List.of(),
                List.of(),
                Map.of(),
                null);

        assertEquals(0, TraceStore.get("web.fusion.selectedCount"));
        assertEquals("after-filter-starvation", TraceStore.get("web.filter.starvationReason"));
        assertEquals("after-filter-starvation", TraceStore.get("web.failsoft.reason"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("private merge query"), trace);
        assertFalse(trace.contains("raw-secret"), trace);
    }

    @Test
    void braveBlankQueryEmitsSkippedReasonWithoutExternalCall() {
        TraceStore.clear();
        BraveSearchService service = enabledBraveService("sk-braveblank000000");
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        restTemplate.setRequestFactory((uri, httpMethod) -> {
            throw new AssertionError("Brave RestTemplate must not run for blank query");
        });

        BraveSearchResult result = service.searchWithMeta("   ", 4);

        assertTrue(result.snippets().isEmpty());
        assertEquals(4, TraceStore.get("web.brave.requestedCount"));
        assertEquals(0, TraceStore.get("web.brave.returnedCount"));
        assertEquals(0, TraceStore.get("web.brave.afterFilterCount"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.brave.providerDisabled"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.brave.providerEmpty"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.brave.zeroResults"));
        assertEquals("blank_query", TraceStore.get("web.brave.skipped.reason"));
        assertEquals("blank_query", TraceStore.get("web.brave.failureReason"));
        assertNull(TraceStore.get("web.brave.queryHash"));
        assertEquals(0, TraceStore.get("web.brave.queryLength"));
    }

    @Test
    void bravePublicReasonsUseTraceLabelsForFreeTextAndCanonicalCodes() {
        TraceStore.clear();
        BraveSearchService service = enabledBraveService("sk-bravesecret000003");
        String rawQuery = "private brave reason query";
        String rawReason = "private brave disabled reason for student query api_key=<test-api-key>";

        ReflectionTestUtils.invokeMethod(service, "traceBraveCounts",
                rawQuery, 4, 0, 0, true, "missing_brave_api_key");
        assertEquals("missing_brave_api_key", TraceStore.get("web.brave.disabledReason"));
        assertEquals("missing_brave_api_key", TraceStore.get("web.brave.disabledReasonCanonical"));

        TraceStore.clear();
        ReflectionTestUtils.invokeMethod(service, "traceBraveCounts",
                rawQuery, 4, 0, 0, true, rawReason);
        assertTrue(String.valueOf(TraceStore.get("web.brave.disabledReason")).startsWith("hash:"));
        assertTrue(String.valueOf(TraceStore.get("web.brave.disabledReasonCanonical")).startsWith("hash:"));

        TraceStore.clear();
        ReflectionTestUtils.invokeMethod(service, "traceBraveFailure",
                rawQuery, 4, 500, rawReason, false, false, "error body", 9L);
        assertTrue(String.valueOf(TraceStore.get("web.brave.failureReason")).startsWith("hash:"));

        TraceStore.clear();
        ReflectionTestUtils.invokeMethod(service, "traceFreeTierQuota", 0, true, rawReason);
        assertTrue(String.valueOf(TraceStore.get("web.brave.quota.disabledReason")).startsWith("hash:"));

        TraceStore.clear();
        ReflectionTestUtils.invokeMethod(service, "traceBraveAdaptiveSkipped", rawReason, 0, 0);
        assertTrue(String.valueOf(TraceStore.get("web.brave.adaptive.triggerReason")).startsWith("hash:"));

        TraceStore.clear();
        ReflectionTestUtils.invokeMethod(BraveSearchService.class, "traceRemoteCooldown", rawReason, 1000L, 1000L);
        assertTrue(String.valueOf(TraceStore.get("web.brave.cooldown.reason")).startsWith("hash:"));

        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(rawReason), trace);
        assertFalse(trace.contains("student query"), trace);
        assertFalse(trace.contains("<test-api-key>"), trace);
    }

    @Test
    void serpApiDisabledProviderEmitsCanonicalCountsWithoutExternalCall() {
        TraceStore.clear();
        SerpApiProvider provider = new SerpApiProvider();
        ReflectionTestUtils.setField(provider, "configEnabled", true);
        ReflectionTestUtils.setField(provider, "apiKey", "");
        ReflectionTestUtils.invokeMethod(provider, "init");

        var result = provider.search(new WebSearchQuery("disabled serp query", 4, null, null));

        assertTrue(result.getDocuments().isEmpty());
        assertEquals(Boolean.TRUE, TraceStore.get("web.serpapi.providerDisabled"));
        assertEquals(4, TraceStore.get("web.serpapi.requestedCount"));
        assertEquals(0, TraceStore.get("web.serpapi.returnedCount"));
        assertEquals(0, TraceStore.get("web.serpapi.afterFilterCount"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.serpapi.zeroResults"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.serpapi.afterFilterStarved"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.serpapi.providerEmpty"));
        assertEquals("provider-disabled", TraceStore.get("web.serpapi.failureReason"));
        assertEquals("missing_serpapi_api_key", TraceStore.get("web.serpapi.disabledReason"));
        assertEquals("missing_serpapi_api_key", TraceStore.get("web.serpapi.disabledReasonCanonical"));
        assertEquals("missing_serpapi_api_key", TraceStore.get("web.serpapi.skipped.reason"));
        assertTrue(String.valueOf(TraceStore.get("web.serpapi.queryHash")).startsWith("hash:"));
        assertEquals("serpapi", TraceStore.get("web.provider.name"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.provider.enabled"));
        assertEquals("missing-key", TraceStore.get("web.provider.disabledReason"));
        assertEquals(0, TraceStore.get("web.provider.resultCount"));
        assertTrue(String.valueOf(TraceStore.get("web.query.hash")).startsWith("hash:"));
        assertEquals("provider-disabled", TraceStore.get("web.failsoft.reason"));
    }

    @Test
    void serpApiBlankQueryEmitsSkippedReasonWithoutExternalCall() {
        TraceStore.clear();
        SerpApiProvider provider = enabledSerpApiProvider("sk-serpblank000000");

        var result = provider.search(new WebSearchQuery("   ", 4, null, null));

        assertTrue(result.getDocuments().isEmpty());
        assertEquals(4, TraceStore.get("web.serpapi.requestedCount"));
        assertEquals(0, TraceStore.get("web.serpapi.returnedCount"));
        assertEquals(0, TraceStore.get("web.serpapi.afterFilterCount"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.serpapi.providerDisabled"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.serpapi.providerEmpty"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.serpapi.zeroResults"));
        assertEquals("blank_query", TraceStore.get("web.serpapi.skipped.reason"));
        assertEquals("blank_query", TraceStore.get("web.serpapi.failureReason"));
        assertNull(TraceStore.get("web.serpapi.queryHash"));
        assertEquals(3, TraceStore.get("web.serpapi.queryLength"));
    }

    @Test
    void serpApiCountPathClassifiesProviderEmptyAndAfterFilterStarvation() {
        TraceStore.clear();
        SerpApiProvider provider = new SerpApiProvider();
        ReflectionTestUtils.setField(provider, "timeoutMs", 2000);

        ReflectionTestUtils.invokeMethod(provider, "traceSerpApiCounts",
                "private serp empty query", 4, 0, 0, false, null);

        assertEquals(Boolean.TRUE, TraceStore.get("web.serpapi.providerEmpty"));
        assertEquals("provider-empty", TraceStore.get("web.serpapi.failureReason"));
        assertEquals("serpapi", TraceStore.get("web.provider.name"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.provider.enabled"));
        assertNull(TraceStore.get("web.provider.disabledReason"));
        assertEquals(0, TraceStore.get("web.provider.resultCount"));
        assertTrue(String.valueOf(TraceStore.get("web.query.hash")).startsWith("hash:"));
        assertEquals("empty-provider-output", TraceStore.get("web.failsoft.reason"));

        ReflectionTestUtils.invokeMethod(provider, "traceSerpApiCounts",
                "private serp filtered query", 4, 3, 0, false, null);

        assertEquals(Boolean.FALSE, TraceStore.get("web.serpapi.providerEmpty"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.serpapi.afterFilterStarved"));
        assertEquals("after-filter-starvation", TraceStore.get("web.serpapi.failureReason"));
        assertEquals(3, TraceStore.get("web.provider.resultCount"));
        assertEquals("after-filter-starvation", TraceStore.get("web.filter.starvationReason"));
        assertEquals("after-filter-starvation", TraceStore.get("web.failsoft.reason"));
    }

    @Test
    void serpApiPublicReasonsUseTraceLabelsForFreeTextAndCanonicalCodes() {
        TraceStore.clear();
        SerpApiProvider provider = new SerpApiProvider();
        ReflectionTestUtils.setField(provider, "timeoutMs", 2000);
        String rawQuery = "private serpapi reason query";
        String rawReason = "private serpapi disabled reason for student query api_key=<test-api-key>";

        ReflectionTestUtils.invokeMethod(provider, "traceSerpApiCounts",
                rawQuery, 4, 0, 0, true, "missing_serpapi_api_key");
        assertEquals("missing_serpapi_api_key", TraceStore.get("web.serpapi.disabledReason"));
        assertEquals("missing_serpapi_api_key", TraceStore.get("web.serpapi.disabledReasonCanonical"));

        TraceStore.clear();
        ReflectionTestUtils.invokeMethod(provider, "traceSerpApiCounts",
                rawQuery, 4, 0, 0, true, rawReason);
        assertTrue(String.valueOf(TraceStore.get("web.serpapi.disabledReason")).startsWith("hash:"));
        assertTrue(String.valueOf(TraceStore.get("web.serpapi.disabledReasonCanonical")).startsWith("hash:"));
        assertTrue(String.valueOf(TraceStore.get("web.serpapi.skipped.reason")).startsWith("hash:"));

        TraceStore.clear();
        ReflectionTestUtils.invokeMethod(provider, "traceSerpApiFailure",
                rawQuery, 4, 500, rawReason, false, false, "error body", 9L);
        assertTrue(String.valueOf(TraceStore.get("web.serpapi.failureReason")).startsWith("hash:"));

        TraceStore.clear();
        ReflectionTestUtils.invokeMethod(SerpApiProvider.class, "traceSerpApiRemoteCooldown", rawReason, 1000L);
        assertTrue(String.valueOf(TraceStore.get("web.serpapi.cooldown.reason")).startsWith("hash:"));

        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(rawReason), trace);
        assertFalse(trace.contains("student query"), trace);
        assertFalse(trace.contains("<test-api-key>"), trace);
    }

    @Test
    void tavilyDisabledProviderEmitsCanonicalCountsWithoutExternalCall() {
        TraceStore.clear();
        String rawQuery = "disabled tavily private query";
        TavilyWebSearchRetriever retriever = new TavilyWebSearchRetriever(WebClient.builder()
                .exchangeFunction(request -> {
                    throw new AssertionError("Tavily must not call the network without an api key");
                }));
        ReflectionTestUtils.setField(retriever, "apiKey", "");
        ReflectionTestUtils.setField(retriever, "baseUrl", "https://api.tavily.com/search");
        ReflectionTestUtils.setField(retriever, "maxResults", 5);
        ReflectionTestUtils.setField(retriever, "timeoutMs", 2200);

        var result = retriever.retrieve(new Query(rawQuery));

        assertTrue(result.isEmpty());
        assertEquals(Boolean.TRUE, TraceStore.get("web.tavily.providerDisabled"));
        assertEquals(5, TraceStore.get("web.tavily.requestedCount"));
        assertEquals(0, TraceStore.get("web.tavily.returnedCount"));
        assertEquals(0, TraceStore.get("web.tavily.afterFilterCount"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.tavily.zeroResults"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.tavily.providerEmpty"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.tavily.afterFilterStarved"));
        assertEquals("provider-disabled", TraceStore.get("web.tavily.failureReason"));
        assertEquals("missing_tavily_api_key", TraceStore.get("web.tavily.disabledReason"));
        assertEquals("missing_tavily_api_key", TraceStore.get("web.tavily.disabledReasonCanonical"));
        assertEquals("missing_tavily_api_key", TraceStore.get("web.tavily.skipped.reason"));
        assertTrue(String.valueOf(TraceStore.get("web.tavily.queryHash")).startsWith("hash:"));
        assertEquals(rawQuery.length(), TraceStore.get("web.tavily.queryLength"));
        assertEquals("1-4", TraceStore.get("web.tavily.queryTokenBucket"));
        assertEquals(2200, TraceStore.get("web.tavily.timeoutMs"));
        assertEquals("tavily", TraceStore.get("web.provider.name"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.provider.enabled"));
        assertEquals("missing-key", TraceStore.get("web.provider.disabledReason"));
        assertEquals(0, TraceStore.get("web.provider.resultCount"));
        assertTrue(String.valueOf(TraceStore.get("web.query.hash")).startsWith("hash:"));
        assertEquals("provider-disabled", TraceStore.get("web.failsoft.reason"));
        assertProviderTraceDoesNotContain(rawQuery, "tavily-secret");
    }

    @Test
    void tavilyCountPathEmitsCommonProviderAliases() {
        TraceStore.clear();
        TavilyWebSearchRetriever retriever = new TavilyWebSearchRetriever(WebClient.builder());
        ReflectionTestUtils.setField(retriever, "timeoutMs", 2000);

        ReflectionTestUtils.invokeMethod(retriever, "traceCounts",
                "private tavily empty query", 4, 0, 0, "provider-empty", true);

        assertEquals("provider-empty", TraceStore.get("web.tavily.failureReason"));
        assertEquals("tavily", TraceStore.get("web.provider.name"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.provider.enabled"));
        assertNull(TraceStore.get("web.provider.disabledReason"));
        assertEquals(0, TraceStore.get("web.provider.resultCount"));
        assertTrue(String.valueOf(TraceStore.get("web.query.hash")).startsWith("hash:"));
        assertEquals("empty-provider-output", TraceStore.get("web.failsoft.reason"));

        ReflectionTestUtils.invokeMethod(retriever, "traceCounts",
                "private tavily filtered query", 4, 3, 0, null, false);

        assertEquals("after-filter-starvation", TraceStore.get("web.tavily.failureReason"));
        assertEquals(3, TraceStore.get("web.provider.resultCount"));
        assertEquals("after-filter-starvation", TraceStore.get("web.filter.starvationReason"));
        assertEquals("after-filter-starvation", TraceStore.get("web.failsoft.reason"));
    }

    @Test
    void tavilyPublicReasonsUseTraceLabelsForFreeTextAndCanonicalCodes() {
        TraceStore.clear();
        TavilyWebSearchRetriever retriever = new TavilyWebSearchRetriever(WebClient.builder());
        ReflectionTestUtils.setField(retriever, "baseUrl", "https://api.tavily.com/search");
        ReflectionTestUtils.setField(retriever, "timeoutMs", 2000);
        String rawQuery = "private tavily reason query";
        String rawReason = "private tavily disabled reason for student query api_key=<test-api-key>";

        ReflectionTestUtils.invokeMethod(retriever, "traceProviderDisabled",
                rawQuery, 4, "missing_tavily_api_key");
        assertEquals("missing_tavily_api_key", TraceStore.get("web.tavily.disabledReason"));
        assertEquals("missing_tavily_api_key", TraceStore.get("web.tavily.disabledReasonCanonical"));

        TraceStore.clear();
        ReflectionTestUtils.invokeMethod(retriever, "traceProviderDisabled",
                rawQuery, 4, rawReason);
        assertTrue(String.valueOf(TraceStore.get("web.tavily.disabledReason")).startsWith("hash:"));
        assertTrue(String.valueOf(TraceStore.get("web.tavily.disabledReasonCanonical")).startsWith("hash:"));
        assertTrue(String.valueOf(TraceStore.get("web.tavily.skipped.reason")).startsWith("hash:"));

        TraceStore.clear();
        ReflectionTestUtils.invokeMethod(retriever, "traceCounts",
                rawQuery, 4, 0, 0, rawReason, false);
        assertTrue(String.valueOf(TraceStore.get("web.tavily.failureReason")).startsWith("hash:"));

        TraceStore.clear();
        ReflectionTestUtils.invokeMethod(TavilyWebSearchRetriever.class, "traceRemoteCooldown", rawReason, 1000L);
        assertTrue(String.valueOf(TraceStore.get("web.tavily.cooldown.reason")).startsWith("hash:"));

        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(rawReason), trace);
        assertFalse(trace.contains("student query"), trace);
        assertFalse(trace.contains("<test-api-key>"), trace);
    }

    @Test
    void brave429EmitsRateLimitTraceWithoutRawQueryOrToken() {
        TraceStore.clear();
        String apiKey = "sk-bravesecret000000";
        String rawQuery = "private brave raw query";
        BraveSearchService service = enabledBraveService(apiKey);
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo(containsString("api.search.brave.com")))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .header("Retry-After", "7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"rate limit for " + rawQuery + " Bearer " + apiKey + "\"}"));

        BraveSearchResult result = service.searchWithMeta(rawQuery, 3);

        server.verify();
        assertTrue(result.snippets().isEmpty());
        assertEquals(BraveSearchResult.Status.HTTP_429, result.status());
        assertEquals(429, result.httpStatus());
        assertEquals(7000L, result.cooldownMs());
        assertEquals(429, TraceStore.get("web.brave.httpStatus"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.brave.429"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.brave.rateLimited"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.rateLimited"));
        assertEquals("rate-limit", TraceStore.get("web.brave.failureReason"));
        assertEquals("rate-limit", TraceStore.get("web.brave.cooldown.reason"));
        assertEquals(7000L, TraceStore.get("web.brave.retryAfterMs"));
        assertTrue(String.valueOf(TraceStore.get("web.brave.errorBodyHash")).startsWith("hash:"));
        assertNull(TraceStore.get("web.brave.errorBodyPreview"));
        assertProviderTraceDoesNotContain(rawQuery, apiKey);
    }

    @Test
    void brave503RetryAfterEmitsCooldownTraceWithoutRawQueryOrToken() {
        TraceStore.clear();
        String apiKey = "brave-unavailable-token";
        String rawQuery = "private brave unavailable query";
        BraveSearchService service = enabledBraveService(apiKey);
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo(containsString("api.search.brave.com")))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                        .header("Retry-After", "11")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"temporarily unavailable for " + rawQuery + " token=" + apiKey + "\"}"));

        BraveSearchResult result = service.searchWithMeta(rawQuery, 3);

        server.verify();
        assertTrue(result.snippets().isEmpty());
        assertEquals(BraveSearchResult.Status.HTTP_503, result.status());
        assertEquals(503, result.httpStatus());
        assertEquals(11000L, result.cooldownMs());
        assertEquals(503, TraceStore.get("web.brave.httpStatus"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.brave.429"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.brave.rateLimited"));
        assertEquals("http-503", TraceStore.get("web.brave.failureReason"));
        assertEquals("http-503", TraceStore.get("web.brave.cooldown.reason"));
        assertEquals(11000L, TraceStore.get("web.brave.retryAfterMs"));
        assertTrue(String.valueOf(TraceStore.get("web.brave.errorBodyHash")).startsWith("hash:"));
        assertNull(TraceStore.get("web.brave.errorBodyPreview"));
        assertProviderTraceDoesNotContain(rawQuery, apiKey);
    }

    @Test
    void braveFreeOnlyQuotaCapAllowsOneCallThenDisablesWithoutSecondExternalCall() {
        TraceStore.clear();
        BraveSearchService service = enabledBraveService("sk-bravequota000000", 1);
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo(containsString("api.search.brave.com")))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(braveBody("quota-one")));

        BraveSearchResult first = service.searchWithMeta("free only quota query", 3);

        server.verify();
        assertFalse(first.snippets().isEmpty());
        assertEquals(0, service.monthlyRemaining().get());
        assertTrue(service.isQuotaExhausted());
        assertFalse(service.isEnabled());

        TraceStore.clear();
        BraveSearchResult second = service.searchWithMeta("free only quota query again", 3);

        assertTrue(second.snippets().isEmpty());
        assertEquals(BraveSearchResult.Status.DISABLED, second.status());
        assertEquals(Boolean.TRUE, TraceStore.get("web.brave.providerDisabled"));
        assertEquals("quota_exhausted", TraceStore.get("web.brave.disabledReasonCanonical"));
        assertEquals("quota_exhausted", TraceStore.get("web.brave.skipped.reason"));
    }

    @Test
    void braveServerMonthlyRemainingZeroDisablesImmediately() {
        TraceStore.clear();
        BraveSearchService service = enabledBraveService("sk-bravequota000001", 10);
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo(containsString("api.search.brave.com")))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-RateLimit-Remaining", "1, 0")
                        .body(braveBody("quota-zero")));

        BraveSearchResult result = service.searchWithMeta("provider quota zero query", 3);

        server.verify();
        assertFalse(result.snippets().isEmpty());
        assertEquals(0, service.monthlyRemaining().get());
        assertTrue(service.isQuotaExhausted());
        assertFalse(service.isEnabled());
        assertEquals(Boolean.TRUE, TraceStore.get("web.brave.quota.exhausted"));
        assertEquals("quota_exhausted", TraceStore.get("web.brave.disabledReasonCanonical"));
    }

    @Test
    void braveProviderRemainingAboveLocalCapDoesNotRaiseLocalRemaining() {
        TraceStore.clear();
        BraveSearchService service = enabledBraveService("sk-bravequota000002", 2);
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo(containsString("api.search.brave.com")))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-RateLimit-Remaining", "1, 9999")
                        .body(braveBody("quota-high")));

        BraveSearchResult result = service.searchWithMeta("provider quota high query", 3);

        server.verify();
        assertFalse(result.snippets().isEmpty());
        assertEquals(1, service.monthlyRemaining().get());
        assertFalse(service.isQuotaExhausted());
        assertEquals(1, TraceStore.get("web.brave.quota.remaining"));
    }

    @Test
    void braveTimeoutEmitsTimeoutTraceWithoutRawQuery() {
        TraceStore.clear();
        String rawQuery = "private brave timeout query";
        BraveSearchService service = enabledBraveService("sk-bravesecret000001");
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        restTemplate.setRequestFactory(timeoutRequestFactory("timed out while calling " + rawQuery));

        BraveSearchResult result = service.searchWithMeta(rawQuery, 3);

        assertTrue(result.snippets().isEmpty());
        assertEquals(BraveSearchResult.Status.EXCEPTION, result.status());
        assertEquals(Boolean.TRUE, TraceStore.get("web.brave.timeout"));
        assertEquals("timeout", TraceStore.get("web.brave.failureReason"));
        assertProviderTraceDoesNotContain(rawQuery, "sk-bravesecret000001");
    }

    @Test
    void braveCancellationEmitsCancelledTraceWithoutRawQueryOrApiKey() {
        TraceStore.clear();
        String rawQuery = "private brave cancelled query";
        String apiKey = "brave-cancelled-test-key";
        BraveSearchService service = enabledBraveService(apiKey);
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        restTemplate.setRequestFactory(cancellationRequestFactory(
                "cancelled ownerToken=fake-token query=" + rawQuery + " api_key=" + apiKey));

        BraveSearchResult result = service.searchWithMeta(rawQuery, 3);

        assertTrue(result.snippets().isEmpty());
        assertEquals(BraveSearchResult.Status.EXCEPTION, result.status());
        assertEquals(Boolean.FALSE, TraceStore.get("web.brave.timeout"));
        assertEquals("cancelled", TraceStore.get("web.brave.failureReason"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.brave.cancelled"));
        assertEquals("cancelled", TraceStore.get("web.brave.exceptionType"));
        assertProviderTraceDoesNotContain(rawQuery, apiKey);
        assertProviderTraceDoesNotContain("CancellationException", "ownerToken");
    }

    @Test
    void braveCancellationClearsProviderRateLimitResidue() {
        TraceStore.clear();
        TraceStore.put("web.brave.httpStatus", 429);
        TraceStore.put("web.brave.429", true);
        TraceStore.put("web.brave.rateLimited", true);
        String rawQuery = "private brave cancelled after rate limit query";
        String apiKey = "brave-cancelled-stale-key";
        BraveSearchService service = enabledBraveService(apiKey);
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        restTemplate.setRequestFactory(cancellationRequestFactory("cancelled for " + rawQuery));

        BraveSearchResult result = service.searchWithMeta(rawQuery, 3);

        assertTrue(result.snippets().isEmpty());
        assertEquals(Boolean.TRUE, TraceStore.get("web.brave.cancelled"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.brave.rateLimited"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.brave.429"));
        assertNull(TraceStore.get("web.brave.httpStatus"));
        assertEquals("cancelled", TraceStore.get("web.brave.failureReason"));
        assertProviderTraceDoesNotContain(rawQuery, apiKey);
    }

    @Test
    void providerCountTracesClearStaleTimeoutResidue() {
        String rawQuery = "private provider timeout residue query";

        TraceStore.clear();
        TraceStore.put("web.brave.timeout", true);
        ReflectionTestUtils.invokeMethod(enabledBraveService("sk-bravetimeout0001"), "traceBraveCounts",
                rawQuery, 3, 2, 2, false, null);
        assertEquals(Boolean.FALSE, TraceStore.get("web.brave.timeout"));

        TraceStore.clear();
        TraceStore.put("web.serpapi.timeout", true);
        ReflectionTestUtils.invokeMethod(enabledSerpApiProvider("sk-serptimeout0001"), "traceSerpApiCounts",
                rawQuery, 3, 2, 2, false, null);
        assertEquals(Boolean.FALSE, TraceStore.get("web.serpapi.timeout"));

        TraceStore.clear();
        TraceStore.put("web.tavily.timeout", true);
        TavilyWebSearchRetriever retriever = new TavilyWebSearchRetriever(WebClient.builder());
        ReflectionTestUtils.setField(retriever, "timeoutMs", 2000);
        ReflectionTestUtils.invokeMethod(retriever, "traceCounts",
                rawQuery, 3, 2, 2, null, false);
        assertEquals(Boolean.FALSE, TraceStore.get("web.tavily.timeout"));

        TraceStore.clear();
        TraceStore.put("web.naver.timeout", true);
        ReflectionTestUtils.invokeMethod(naverServiceWithoutKeys(), "traceNaverCounts",
                rawQuery, 3, 2, 2, false, null);
        assertEquals(Boolean.FALSE, TraceStore.get("web.naver.timeout"));
    }

    @Test
    void tavilyProviderDisabledClearsStaleTimeoutResidue() {
        TraceStore.clear();
        TraceStore.put("web.tavily.timeout", true);
        String rawQuery = "private tavily disabled timeout residue query";
        TavilyWebSearchRetriever retriever = new TavilyWebSearchRetriever(WebClient.builder());
        ReflectionTestUtils.setField(retriever, "apiKey", "");
        ReflectionTestUtils.setField(retriever, "maxResults", 4);

        var result = retriever.retrieve(new Query(rawQuery));

        assertTrue(result.isEmpty());
        assertEquals(Boolean.TRUE, TraceStore.get("web.tavily.providerDisabled"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.tavily.timeout"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.tavily.cancelled"));
        assertEquals("provider-disabled", TraceStore.get("web.tavily.failureReason"));
        assertProviderTraceDoesNotContain(rawQuery, "tvly-disabled-secret");
    }

    @Test
    void braveAdaptiveVariantRunsAfterProviderEmptyWithoutRawQueryTrace() {
        TraceStore.clear();
        String apiKey = "sk-braveadaptive000000";
        String rawQuery = "private brave adaptive query";
        BraveSearchService service = enabledBraveService(apiKey, 2000, 100.0d);
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo(containsString("api.search.brave.com")))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"web\":{\"results\":[]}}"));
        server.expect(requestTo(containsString("api.search.brave.com")))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(braveBody("adaptive-hit")));

        BraveSearchResult result = service.searchWithMeta(rawQuery, 1);

        server.verify();
        assertFalse(result.snippets().isEmpty());
        assertEquals("provider-empty", TraceStore.get("web.brave.adaptive.triggerReason"));
        assertTrue(((Number) TraceStore.get("web.brave.adaptive.variantCount")).intValue() > 0);
        assertEquals("exploratory", TraceStore.get("web.brave.adaptive.temperatureProfile"));
        assertTrue(((Number) TraceStore.get("web.brave.adaptive.validationTemperature")).doubleValue() <= 0.25d);
        assertTrue(((Number) TraceStore.get("web.brave.adaptive.explorationTemperature")).doubleValue()
                > ((Number) TraceStore.get("web.brave.adaptive.validationTemperature")).doubleValue());
        assertEquals("exploratory", TraceStore.get("web.query.rewrite.temperatureProfile"));
        assertProviderTraceDoesNotContain(rawQuery, apiKey);
    }

    @Test
    void brave429ClampsReportedCooldownAndRedactsRemoteBodyAcrossLogsAndTrace() {
        TraceStore.clear();
        String apiKey = "brave-private-cooldown-token";
        String rawQuery = "private brave cooldown contract query";
        String remoteBodySentinel = "REMOTE_PRIVATE_STATUS_43";
        BraveSearchService service = enabledBraveService(apiKey, 2000, 1.0d, 120_000L);
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo(containsString("api.search.brave.com")))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .header("Retry-After", "999999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"" + remoteBodySentinel + " " + rawQuery + " token=" + apiKey + "\"}"));
        Logger logger = (Logger) LoggerFactory.getLogger(BraveSearchService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        BraveSearchResult result;
        try {
            result = service.searchWithMeta(rawQuery, 3);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        server.verify();
        assertEquals(BraveSearchResult.Status.HTTP_429, result.status());
        assertEquals(30_000L, result.cooldownMs());
        assertEquals(result.cooldownMs(), TraceStore.get("web.brave.cooldownMs"));
        assertEquals(result.cooldownMs(), TraceStore.get("web.brave.cooldown.hintMs"));
        long remainingMs = service.cooldownRemainingMs();
        assertTrue(result.cooldownMs() >= remainingMs);
        assertTrue(result.cooldownMs() - remainingMs <= 250L);
        assertEquals(30_000L, TraceStore.get("web.brave.retryAfterMs"));
        assertTrue(String.valueOf(TraceStore.get("web.brave.errorBodyHash")).startsWith("hash:"));
        assertTrue(((Number) TraceStore.get("web.brave.errorBodyLength")).intValue() > remoteBodySentinel.length());
        String logs = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (left, right) -> left + "\n" + right);
        assertFalse(logs.contains(remoteBodySentinel), logs);
        assertFalse(logs.contains(rawQuery), logs);
        assertFalse(logs.contains(apiKey), logs);
        assertProviderTraceDoesNotContain(remoteBodySentinel, apiKey);
        assertProviderTraceDoesNotContain(rawQuery, apiKey);
    }

    @Test
    void braveGenericHttpStatusTextCannotEscapeThroughResultOrTrace() {
        TraceStore.clear();
        String statusTextSentinel = "REMOTE_PRIVATE_STATUS_43";
        String rawQuery = "private brave generic status query";
        BraveSearchService service = enabledBraveService("brave-generic-status-token");
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        restTemplate.setRequestFactory((uri, method) -> {
            throw HttpServerErrorException.create(
                    HttpStatus.BAD_GATEWAY,
                    statusTextSentinel,
                    HttpHeaders.EMPTY,
                    new byte[0],
                    StandardCharsets.UTF_8);
        });

        BraveSearchResult result = service.searchWithMeta(rawQuery, 1);

        assertEquals(BraveSearchResult.Status.HTTP_ERROR, result.status());
        assertEquals("http-error", result.message());
        assertFalse(String.valueOf(TraceStore.getAll()).contains(statusTextSentinel));
        assertProviderTraceDoesNotContain(rawQuery, "brave-generic-status-token");
    }

    @Test
    void boundedHybridRouteSuppressesBraveAdaptiveWireFanout() {
        TraceStore.clear();
        TraceStore.put("web.boundedRoute", true);
        BraveSearchService service = enabledBraveService("brave-bounded-test-value", 2000, 100.0d);
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo(containsString("api.search.brave.com")))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"web\":{\"results\":[]}}"));

        BraveSearchResult result = service.searchWithMeta("bounded brave empty query", 1);

        server.verify();
        assertTrue(result.snippets().isEmpty());
        assertEquals("bounded-route", TraceStore.get("web.brave.adaptive.triggerReason"));
        assertEquals(0, TraceStore.get("web.brave.adaptive.variantCount"));
    }

    @Test
    void providerAdaptivePlansHonorDeepSearchModeTraceHint() {
        TraceStore.clear();
        TraceStore.put("chatApi.web.recallModeRequested", true);

        BraveSearchService brave = enabledBraveService("sk-braverecall000001", 2000, 100.0d);
        AdaptiveSearchQueryVariants.Plan bravePlan = ReflectionTestUtils.invokeMethod(brave,
                "planBraveVariants",
                "spring boot virtual threads",
                List.of("spring boot virtual threads"),
                false,
                false);

        assertEquals("exploratory", bravePlan.temperatureProfile());
        assertTrue(bravePlan.queries().contains("spring boot virtual threads official source"));
        assertEquals(1, bravePlan.diagnostics().verificationLaneCount());
        assertEquals(1, bravePlan.diagnostics().explorationLaneCount());

        NaverSearchService naver = naverServiceWithoutKeys();
        AdaptiveSearchQueryVariants.Plan naverPlan = ReflectionTestUtils.invokeMethod(naver,
                "planNaverVariants",
                "spring boot virtual threads",
                List.of("spring boot virtual threads"),
                false,
                false);

        assertEquals("exploratory", naverPlan.temperatureProfile());
        assertTrue(naverPlan.queries().contains("spring boot virtual threads implementation examples"));
    }

    @Test
    void providerAdaptivePlansHonorWorkflowSearchPolicyRecallHint() {
        TraceStore.clear();
        TraceStore.put("search.policy.mode", "RECALL");

        BraveSearchService brave = enabledBraveService("brave-workflow-recall-key", 2000, 100.0d);
        AdaptiveSearchQueryVariants.Plan bravePlan = ReflectionTestUtils.invokeMethod(brave,
                "planBraveVariants",
                "spring boot virtual threads",
                List.of("spring boot virtual threads"),
                false,
                false);

        assertEquals("exploratory", bravePlan.temperatureProfile());
        assertTrue(bravePlan.queries().contains("spring boot virtual threads official source"));
        assertEquals(1, bravePlan.diagnostics().verificationLaneCount());
        assertEquals(1, bravePlan.diagnostics().explorationLaneCount());

        NaverSearchService naver = naverServiceWithoutKeys();
        AdaptiveSearchQueryVariants.Plan naverPlan = ReflectionTestUtils.invokeMethod(naver,
                "planNaverVariants",
                "spring boot virtual threads",
                List.of("spring boot virtual threads"),
                false,
                false);

        assertEquals("exploratory", naverPlan.temperatureProfile());
        assertTrue(naverPlan.queries().contains("spring boot virtual threads implementation examples"));
    }

    @Test
    void providerAdaptiveTraceIncludesRedactedLaneDiagnostics() {
        TraceStore.clear();
        String rawQuery = "private spring boot virtual threads ownerToken=secret";
        AdaptiveSearchQueryVariants.Plan plan = AdaptiveSearchQueryVariants.plan(
                rawQuery,
                List.of(rawQuery),
                new AdaptiveSearchQueryVariants.Options(
                        AdaptiveSearchQueryVariants.Provider.NAVER,
                        true,
                        7,
                        4500,
                        4500,
                        600,
                        true,
                        false,
                        false));

        NaverSearchService naver = naverServiceWithoutKeys();
        ReflectionTestUtils.invokeMethod(naver, "traceNaverAdaptive", plan, 0, 0);

        assertTrue(String.valueOf(TraceStore.get("web.naver.adaptive.querySeedHash12")).matches("[0-9a-f]{12}"));
        assertTrue(String.valueOf(TraceStore.get("web.naver.adaptive.variantSetHash12")).matches("[0-9a-f]{12}"));
        assertEquals(2, TraceStore.get("web.naver.adaptive.verificationLaneCount"));
        assertTrue(((Number) TraceStore.get("web.naver.adaptive.explorationLaneCount")).intValue() >= 3);
        assertTrue(String.valueOf(TraceStore.get("web.naver.adaptive.laneLabels"))
                .contains("verification:official_source"));
        assertTrue(String.valueOf(TraceStore.get("web.query.rewrite.laneSummary"))
                .contains("verification:official_source"));
        assertTrue(String.valueOf(TraceStore.get("web.rewritePlan.seedHash12")).matches("[0-9a-f]{12}"));
        assertTrue(String.valueOf(TraceStore.get("web.rewritePlan.variantHash12")).matches("[0-9a-f]{12}"));
        assertEquals(2, TraceStore.get("web.rewritePlan.verificationCount"));
        assertTrue(((Number) TraceStore.get("web.rewritePlan.explorationCount")).intValue() >= 3);
        assertTrue(String.valueOf(TraceStore.get("web.rewritePlan.laneSummary"))
                .contains("verification:official_source"));
        assertProviderTraceDoesNotContain(rawQuery, "ownerToken=secret");

        TraceStore.clear();
        BraveSearchService brave = enabledBraveService("sk-bravediag000005");
        ReflectionTestUtils.invokeMethod(brave, "traceBraveAdaptive", plan, 0, 0);

        assertTrue(String.valueOf(TraceStore.get("web.brave.adaptive.querySeedHash12")).matches("[0-9a-f]{12}"));
        assertTrue(String.valueOf(TraceStore.get("web.brave.adaptive.variantSetHash12")).matches("[0-9a-f]{12}"));
        assertEquals(2, TraceStore.get("web.brave.adaptive.verificationLaneCount"));
        assertTrue(((Number) TraceStore.get("web.brave.adaptive.explorationLaneCount")).intValue() >= 3);
        assertTrue(String.valueOf(TraceStore.get("web.brave.adaptive.laneLabels"))
                .contains("verification:official_source"));
        assertTrue(String.valueOf(TraceStore.get("web.query.rewrite.laneSummary"))
                .contains("verification:official_source"));
        assertTrue(String.valueOf(TraceStore.get("web.rewritePlan.seedHash12")).matches("[0-9a-f]{12}"));
        assertTrue(String.valueOf(TraceStore.get("web.rewritePlan.variantHash12")).matches("[0-9a-f]{12}"));
        assertEquals(2, TraceStore.get("web.rewritePlan.verificationCount"));
        assertTrue(((Number) TraceStore.get("web.rewritePlan.explorationCount")).intValue() >= 3);
        assertTrue(String.valueOf(TraceStore.get("web.rewritePlan.laneSummary"))
                .contains("verification:official_source"));
        assertProviderTraceDoesNotContain(rawQuery, "ownerToken=secret");
    }

    @Test
    void adaptiveProviderTracePersistsVariantLaneTemperatureHintsWithoutRawQueries() throws Exception {
        String naver = Files.readString(Path.of("main/java/com/example/lms/service/NaverSearchService.java"));
        String brave = Files.readString(Path.of("main/java/com/example/lms/service/web/BraveSearchService.java"));

        for (String source : List.of(naver, brave)) {
            assertTrue(source.contains("plan.variantLaneTemperatureHints()"));
            assertTrue(source.contains("variantLaneTemperatureHints"));
            assertTrue(source.contains("web.query.rewrite.variantLaneTemperatureHints"));
            assertTrue(source.contains("web.rewritePlan.variantLaneTemperatureHints"));
            assertFalse(source.contains("web.query.rewrite.rawQuery"));
            assertFalse(source.contains("web.rewritePlan.rawQuery"));
        }
    }

    @Test
    void serpApi429EmitsRateLimitTraceWithoutRawQueryOrApiKey() {
        TraceStore.clear();
        String apiKey = "sk-serpsecret000000";
        String rawQuery = "private serp raw query";
        SerpApiProvider provider = enabledSerpApiProvider(apiKey);
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(provider, "restTemplate");
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo(containsString("serpapi.com/search.json")))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .header("Retry-After", "13")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"rate limit for " + rawQuery + " api_key=" + apiKey + "\"}"));

        var result = provider.search(new WebSearchQuery(rawQuery, 4, null, null));

        server.verify();
        assertTrue(result.getDocuments().isEmpty());
        assertEquals(429, TraceStore.get("web.serpapi.httpStatus"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.serpapi.429"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.serpapi.rateLimited"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.rateLimited"));
        assertEquals("rate-limit", TraceStore.get("web.serpapi.failureReason"));
        assertEquals("rate-limit", TraceStore.get("web.serpapi.cooldown.reason"));
        assertEquals(13000L, TraceStore.get("web.serpapi.retryAfterMs"));
        assertEquals(13000L, TraceStore.get("web.serpapi.cooldown.hintMs"));
        assertTrue(String.valueOf(TraceStore.get("web.serpapi.errorBodyHash")).startsWith("hash:"));
        assertNull(TraceStore.get("web.serpapi.errorBodyPreview"));
        assertProviderTraceDoesNotContain(rawQuery, apiKey);
    }

    @Test
    void tavily429EmitsRateLimitTraceWithoutRawQueryOrApiKey() {
        TraceStore.clear();
        String apiKey = "tvly-secret000000000";
        String rawQuery = "private tavily raw query";
        TavilyWebSearchRetriever retriever = new TavilyWebSearchRetriever(WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.TOO_MANY_REQUESTS)
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .header("Retry-After", "5")
                        .body("{\"error\":\"rate limit for " + rawQuery + " api_key=" + apiKey + "\"}")
                        .build())));
        ReflectionTestUtils.setField(retriever, "apiKey", apiKey);
        ReflectionTestUtils.setField(retriever, "baseUrl", "https://api.tavily.com/search");
        ReflectionTestUtils.setField(retriever, "maxResults", 4);
        ReflectionTestUtils.setField(retriever, "timeoutMs", 2000);

        var result = retriever.retrieve(new Query(rawQuery));

        assertTrue(result.isEmpty());
        assertEquals(429, TraceStore.get("web.tavily.httpStatus"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.tavily.429"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.tavily.rateLimited"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.tavily.timeout"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.rateLimited"));
        assertEquals("rate-limit", TraceStore.get("web.tavily.failureReason"));
        assertEquals("rate-limit", TraceStore.get("web.tavily.cooldown.reason"));
        assertEquals(5000L, TraceStore.get("web.tavily.retryAfterMs"));
        assertEquals(5000L, TraceStore.get("web.tavily.cooldown.hintMs"));
        assertTrue(((Number) TraceStore.get("web.tavily.tookMs")).longValue() >= 0L);
        assertTrue(String.valueOf(TraceStore.get("web.tavily.errorBodyHash")).startsWith("hash:"));
        assertProviderTraceDoesNotContain(rawQuery, apiKey);
    }

    @Test
    void tavilyCancellationEmitsCancelledTraceWithoutRawQueryOrApiKey() {
        TraceStore.clear();
        String apiKey = "tvly-cancel-secret";
        String rawQuery = "private tavily cancelled query";
        TavilyWebSearchRetriever retriever = new TavilyWebSearchRetriever(WebClient.builder()
                .exchangeFunction(request -> Mono.error(new CancellationException(
                        "cancelled ownerToken=fake-token query=" + rawQuery + " api_key=" + apiKey))));
        ReflectionTestUtils.setField(retriever, "apiKey", apiKey);
        ReflectionTestUtils.setField(retriever, "baseUrl", "https://api.tavily.com/search");
        ReflectionTestUtils.setField(retriever, "maxResults", 4);
        ReflectionTestUtils.setField(retriever, "timeoutMs", 2000);

        var result = retriever.retrieve(new Query(rawQuery));

        assertTrue(result.isEmpty());
        assertEquals(Boolean.TRUE, TraceStore.get("web.tavily.zeroResults"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.tavily.providerEmpty"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.tavily.afterFilterStarved"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.tavily.timeout"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.tavily.cancelled"));
        assertEquals("cancelled", TraceStore.get("web.tavily.failureReason"));
        assertEquals("cancelled", TraceStore.get("web.tavily.exceptionType"));
        assertProviderTraceDoesNotContain(rawQuery, apiKey);
        assertProviderTraceDoesNotContain("CancellationException", "ownerToken");
    }

    @Test
    void tavilyCancellationClearsProviderRateLimitResidue() {
        TraceStore.clear();
        TraceStore.put("web.tavily.httpStatus", 429);
        TraceStore.put("web.tavily.429", true);
        TraceStore.put("web.tavily.rateLimited", true);
        String rawQuery = "private tavily cancelled after rate limit query";
        TavilyWebSearchRetriever retriever = new TavilyWebSearchRetriever(WebClient.builder()
                .exchangeFunction(request -> Mono.error(new CancellationException("cancelled for " + rawQuery))));
        ReflectionTestUtils.setField(retriever, "apiKey", "tvly-cancel-secret");
        ReflectionTestUtils.setField(retriever, "baseUrl", "https://api.tavily.com/search");
        ReflectionTestUtils.setField(retriever, "maxResults", 4);
        ReflectionTestUtils.setField(retriever, "timeoutMs", 2000);

        var result = retriever.retrieve(new Query(rawQuery));

        assertTrue(result.isEmpty());
        assertEquals(Boolean.TRUE, TraceStore.get("web.tavily.cancelled"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.tavily.rateLimited"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.tavily.429"));
        assertNull(TraceStore.get("web.tavily.httpStatus"));
        assertEquals("cancelled", TraceStore.get("web.tavily.failureReason"));
        assertProviderTraceDoesNotContain(rawQuery, "tvly-cancel-secret");
    }

    @Test
    void serpApiHttpErrorEmitsHttpErrorTraceWithoutRawQueryOrApiKey() {
        TraceStore.clear();
        String apiKey = "sk-serpsecret000001";
        String rawQuery = "private serp http query";
        SerpApiProvider provider = enabledSerpApiProvider(apiKey);
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(provider, "restTemplate");
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo(containsString("serpapi.com/search.json")))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"failed for " + rawQuery + " api_key=" + apiKey + "\"}"));

        var result = provider.search(new WebSearchQuery(rawQuery, 4, null, null));

        server.verify();
        assertTrue(result.getDocuments().isEmpty());
        assertEquals(500, TraceStore.get("web.serpapi.httpStatus"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.serpapi.429"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.serpapi.rateLimited"));
        assertEquals("http-error", TraceStore.get("web.serpapi.failureReason"));
        assertProviderTraceDoesNotContain(rawQuery, apiKey);
    }

    @Test
    void serpApiTimeoutEmitsTimeoutTraceWithoutRawQueryOrApiKey() {
        TraceStore.clear();
        String apiKey = "sk-serpsecret000002";
        String rawQuery = "private serp timeout query";
        SerpApiProvider provider = enabledSerpApiProvider(apiKey);
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(provider, "restTemplate");
        restTemplate.setRequestFactory(timeoutRequestFactory("timed out while calling " + rawQuery));

        var result = provider.search(new WebSearchQuery(rawQuery, 4, null, null));

        assertTrue(result.getDocuments().isEmpty());
        assertEquals(Boolean.TRUE, TraceStore.get("web.serpapi.timeout"));
        assertEquals("timeout", TraceStore.get("web.serpapi.failureReason"));
        assertProviderTraceDoesNotContain(rawQuery, apiKey);
    }

    @Test
    void serpApiCancellationEmitsCancelledTraceWithoutRawQueryOrApiKey() {
        TraceStore.clear();
        String apiKey = "sk-serpsecret000003";
        String rawQuery = "private serp cancelled query";
        SerpApiProvider provider = enabledSerpApiProvider(apiKey);
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(provider, "restTemplate");
        restTemplate.setRequestFactory(cancellationRequestFactory(
                "cancelled ownerToken=fake-token query=" + rawQuery + " api_key=" + apiKey));

        var result = provider.search(new WebSearchQuery(rawQuery, 4, null, null));

        assertTrue(result.getDocuments().isEmpty());
        assertEquals(Boolean.FALSE, TraceStore.get("web.serpapi.timeout"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.serpapi.cancelled"));
        assertEquals("cancelled", TraceStore.get("web.serpapi.failureReason"));
        assertEquals("cancelled", TraceStore.get("web.serpapi.exceptionType"));
        assertProviderTraceDoesNotContain(rawQuery, apiKey);
        assertProviderTraceDoesNotContain("CancellationException", "ownerToken");
    }

    @Test
    void serpApiCancellationClearsProviderRateLimitResidue() {
        TraceStore.clear();
        TraceStore.put("web.serpapi.httpStatus", 429);
        TraceStore.put("web.serpapi.429", true);
        TraceStore.put("web.serpapi.rateLimited", true);
        String apiKey = "sk-serpsecret000004";
        String rawQuery = "private serp cancelled after rate limit query";
        SerpApiProvider provider = enabledSerpApiProvider(apiKey);
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(provider, "restTemplate");
        restTemplate.setRequestFactory(cancellationRequestFactory("cancelled for " + rawQuery));

        var result = provider.search(new WebSearchQuery(rawQuery, 4, null, null));

        assertTrue(result.getDocuments().isEmpty());
        assertEquals(Boolean.TRUE, TraceStore.get("web.serpapi.cancelled"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.serpapi.rateLimited"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.serpapi.429"));
        assertNull(TraceStore.get("web.serpapi.httpStatus"));
        assertEquals("cancelled", TraceStore.get("web.serpapi.failureReason"));
        assertProviderTraceDoesNotContain(rawQuery, apiKey);
    }

    @Test
    void naverDisabledProviderEmitsCanonicalCountsWithoutExternalCall() {
        TraceStore.clear();
        NaverSearchService service = naverServiceWithoutKeys();

        NaverSearchService.SearchResult result = service.searchWithTraceSync(
                "disabled naver query",
                6,
                Duration.ofMillis(500));

        assertTrue(result.snippets().isEmpty());
        assertEquals(Boolean.TRUE, TraceStore.get("web.naver.providerDisabled"));
        assertEquals(6, TraceStore.get("web.naver.requestedCount"));
        assertEquals(0, TraceStore.get("web.naver.returnedCount"));
        assertEquals(0, TraceStore.get("web.naver.afterFilterCount"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.naver.zeroResults"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.naver.afterFilterStarved"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.naver.providerEmpty"));
        assertEquals("provider-disabled", TraceStore.get("web.naver.failureReason"));
        assertEquals("missing_naver_client_credentials", TraceStore.get("web.naver.disabledReason"));
        assertEquals("missing_naver_client_credentials", TraceStore.get("web.naver.disabledReasonCanonical"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.naver.skipped"));
        assertEquals("missing_naver_client_credentials", TraceStore.get("web.naver.skipped.reason"));
        assertEquals("missing-key", TraceStore.get("web.provider.disabledReason"));
        assertTrue(String.valueOf(TraceStore.get("web.naver.queryHash")).startsWith("hash:"));
    }

    @Test
    void naverPublicReasonsUseTraceLabelsForFreeTextAndCanonicalCodes() {
        TraceStore.clear();
        NaverSearchService service = naverServiceWithoutKeys();
        String rawQuery = "private naver reason query";
        String rawReason = "private naver disabled reason for student query client_secret=<test-secret>";

        ReflectionTestUtils.invokeMethod(service, "traceNaverCounts",
                rawQuery, 4, 0, 0, true, "missing_naver_client_credentials");
        assertEquals("missing_naver_client_credentials", TraceStore.get("web.naver.disabledReason"));
        assertEquals("missing_naver_client_credentials", TraceStore.get("web.naver.disabledReasonCanonical"));
        assertEquals("missing_naver_client_credentials", TraceStore.get("web.naver.skipped.reason"));

        TraceStore.clear();
        ReflectionTestUtils.invokeMethod(service, "traceNaverCounts",
                rawQuery, 4, 0, 0, true, rawReason);
        assertTrue(String.valueOf(TraceStore.get("web.naver.disabledReason")).startsWith("hash:"));
        assertTrue(String.valueOf(TraceStore.get("web.naver.disabledReasonCanonical")).startsWith("hash:"));
        assertTrue(String.valueOf(TraceStore.get("web.naver.skipped.reason")).startsWith("hash:"));

        TraceStore.clear();
        ReflectionTestUtils.invokeMethod(service, "traceNaverFailure",
                rawQuery, 4, 500, rawReason, false, false, "error body", 9L);
        assertTrue(String.valueOf(TraceStore.get("web.naver.failureReason")).startsWith("hash:"));

        TraceStore.clear();
        ReflectionTestUtils.invokeMethod(NaverSearchService.class, "traceNaverRemoteCooldown", rawReason, 1000L);
        assertTrue(String.valueOf(TraceStore.get("web.naver.cooldown.reason")).startsWith("hash:"));

        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(rawReason), trace);
        assertFalse(trace.contains("student query"), trace);
        assertFalse(trace.contains("<test-secret>"), trace);
    }

    @Test
    void naverBreakerOpenEmitsCanonicalSkippedReasonAndTiming() throws Exception {
        TraceStore.clear();

        NaverTraceSignals.traceBreakerOpen(1234L);

        assertEquals(Boolean.TRUE, TraceStore.get("web.naver.skippedByBreaker"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.naver.skipped"));
        assertEquals("breaker_open_or_half_open", TraceStore.get("web.naver.skipped.reason"));
        assertEquals("breaker_open_or_half_open", TraceStore.get("web.naver.failureReason"));
        assertEquals(1234L, TraceStore.get("web.naver.breaker.remainingMs"));

        String naver = Files.readString(Path.of("main/java/com/example/lms/service/NaverSearchService.java"));
        long callCount = Pattern.compile("NaverTraceSignals\\.traceBreakerOpen\\(").matcher(naver).results().count();
        assertEquals(2, callCount,
                "the reactive wire owner and synchronous compatibility owner should use the canonical trace helper");
    }

    @Test
    void naverEmptyAndAfterFilterStarvationEmitCanonicalFailureReason() {
        TraceStore.clear();
        NaverSearchService service = naverServiceWithoutKeys();

        ReflectionTestUtils.invokeMethod(service, "traceNaverCounts",
                "private naver empty query", 5, 0, 0, false, null);

        assertEquals(Boolean.TRUE, TraceStore.get("web.naver.zeroResults"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.naver.providerEmpty"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.naver.afterFilterStarved"));
        assertEquals("provider-empty", TraceStore.get("web.naver.failureReason"));
        assertProviderTraceDoesNotContain("private naver empty query", "unused-secret");

        TraceStore.clear();
        ReflectionTestUtils.invokeMethod(service, "traceNaverCounts",
                "private naver filtered query", 5, 3, 0, false, null);

        assertEquals(Boolean.FALSE, TraceStore.get("web.naver.zeroResults"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.naver.providerEmpty"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.naver.afterFilterStarved"));
        assertEquals("after-filter-starvation", TraceStore.get("web.naver.failureReason"));
        assertProviderTraceDoesNotContain("private naver filtered query", "unused-secret");
    }

    @Test
    void naver429EmitsRateLimitTraceWithoutRawQueryOrSecret() {
        TraceStore.clear();
        String clientId = "naver-client-id";
        String clientSecret = "naver-secret-000000";
        String rawQuery = "private naver raw query";
        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.TOO_MANY_REQUESTS)
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .header("Retry-After", "9")
                        .body("{\"error\":\"rate limit for " + rawQuery + " secret=" + clientSecret + "\"}")
                        .build()))
                .build();
        NaverSearchService service = naverService("", clientId, clientSecret, webClient);

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> invokeNaverApiMono(service, rawQuery).block(Duration.ofSeconds(1)));

        assertEquals("RATE_LIMIT", failure.getMessage());
        assertEquals("RATE_LIMIT", TraceStore.get("web.naver.failureClass"));
        assertEquals(429, TraceStore.get("web.naver.httpStatus"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.naver.429"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.naver.rateLimited"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.rateLimited"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.naver.providerEmpty"));
        assertEquals("rate-limit", TraceStore.get("web.naver.failureReason"));
        assertEquals("rate-limit", TraceStore.get("web.naver.cooldown.reason"));
        assertEquals(9000L, TraceStore.get("web.naver.retryAfterMs"));
        assertEquals(9000L, TraceStore.get("web.naver.cooldown.hintMs"));
        assertTrue(String.valueOf(TraceStore.get("web.naver.errorBodyHash")).startsWith("hash:"));
        assertProviderTraceDoesNotContain(rawQuery, clientSecret);
    }

    @Test
    void naverTimeoutEmitsTimeoutTraceWithoutRawQueryOrSecret() {
        TraceStore.clear();
        String clientId = "naver-client-id";
        String clientSecret = "naver-secret-000001";
        String rawQuery = "private naver timeout query";
        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> Mono.error(new TimeoutException(
                        "timed out while calling " + rawQuery + " secret=" + clientSecret)))
                .build();
        NaverSearchService service = naverService("", clientId, clientSecret, webClient);

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> invokeNaverApiMono(service, rawQuery).block(Duration.ofSeconds(1)));

        assertEquals("TIMEOUT_OR_BUDGET", failure.getMessage());
        assertEquals("TIMEOUT_OR_BUDGET", TraceStore.get("web.naver.failureClass"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.naver.timeout"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.naver.providerEmpty"));
        assertEquals("timeout", TraceStore.get("web.naver.failureReason"));
        assertProviderTraceDoesNotContain(rawQuery, clientSecret);
    }

    @Test
    void naverTimeoutClearsProviderRateLimitResidue() {
        TraceStore.clear();
        TraceStore.put("web.naver.httpStatus", 429);
        TraceStore.put("web.naver.429", true);
        TraceStore.put("web.naver.rateLimited", true);
        String clientId = "naver-client-id";
        String clientSecret = "naver-secret-000002";
        String rawQuery = "private naver timeout after rate limit query";
        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> Mono.error(new TimeoutException("timed out for " + rawQuery)))
                .build();
        NaverSearchService service = naverService("", clientId, clientSecret, webClient);

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> invokeNaverApiMono(service, rawQuery).block(Duration.ofSeconds(1)));

        assertEquals("TIMEOUT_OR_BUDGET", failure.getMessage());
        assertEquals("TIMEOUT_OR_BUDGET", TraceStore.get("web.naver.failureClass"));
        assertEquals(Boolean.TRUE, TraceStore.get("web.naver.timeout"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.naver.rateLimited"));
        assertEquals(Boolean.FALSE, TraceStore.get("web.naver.429"));
        assertNull(TraceStore.get("web.naver.httpStatus"));
        assertEquals("timeout", TraceStore.get("web.naver.failureReason"));
        assertProviderTraceDoesNotContain(rawQuery, clientSecret);
    }

    @Test
    void naverTraceHtmlHidesRawSnippetsByDefault() {
        NaverSearchService service = naverServiceWithoutKeys();
        ReflectionTestUtils.setField(service, "traceSnippetsEnabled", false);
        NaverSearchService.SearchTrace trace = new NaverSearchService.SearchTrace();
        trace.query = "hash:already-redacted";
        trace.provider = "Naver";

        String html = service.buildTraceHtml(trace, List.of("raw snippet with ownerToken and api-key"));

        assertFalse(html.contains("raw snippet"));
        assertFalse(html.contains("ownerToken"));
        assertFalse(html.contains("api-key"));
        assertTrue(html.contains("snippetCount=1"));
        assertTrue(html.contains("snippetHash12="));
    }

    @Test
    void naverExplicitMalformedKeysFallBackToClientPair() {
        TraceStore.clear();
        NaverSearchService service = naverService("only-client-id", "fallback-id", "fallback-secret");

        Collection<?> parsedKeys = (Collection<?>) ReflectionTestUtils.getField(service, "naverKeys");
        assertEquals(1, parsedKeys == null ? 0 : parsedKeys.size());
        assertEquals(1, ReflectionTestUtils.getField(service, "parsedNaverKeyCount"));
        assertFalse("invalid_naver_keys".equals(TraceStore.get("web.naver.disabledReason")));
    }

    @Test
    void naverClientPairBridgeIsUsedOnlyWhenNaverKeysIsBlank() {
        NaverSearchService service = naverService("", "fallback-id", "fallback-secret");

        Collection<?> parsedKeys = (Collection<?>) ReflectionTestUtils.getField(service, "naverKeys");

        assertEquals(1, parsedKeys == null ? 0 : parsedKeys.size());
        assertEquals(1, ReflectionTestUtils.getField(service, "parsedNaverKeyCount"));
    }

    @Test
    void braveOperationalControlsDoNotNeedPrivateFieldReflection() {
        BraveSearchService service = new BraveSearchService(new BraveSearchProperties(
                true,
                "https://api.search.brave.com/res/v1/web/search",
                "token",
                1.0d,
                2000,
                500L,
                200L,
                2000L));

        service.markQuotaExhausted();
        assertTrue(service.isQuotaExhausted());

        service.setOperationallyDisabled("quota_exhausted untilEpochMs=123");
        assertFalse(service.isEnabled());
        assertEquals("quota_exhausted untilEpochMs=123", service.disabledReason());

        service.clearQuotaExhausted();
        service.clearOperationalDisableIfQuota();
        assertFalse(service.isQuotaExhausted());
        assertTrue(service.isEnabled());
        assertEquals("", service.disabledReason());
    }

    private static BraveSearchService enabledBraveService(String apiKey) {
        return enabledBraveService(apiKey, 2000);
    }

    private static BraveSearchService enabledBraveService(String apiKey, int monthlyQuota) {
        return enabledBraveService(apiKey, monthlyQuota, 1.0d);
    }

    private static BraveSearchService enabledBraveService(String apiKey, int monthlyQuota, double qpsLimit) {
        return enabledBraveService(apiKey, monthlyQuota, qpsLimit, 2000L);
    }

    private static BraveSearchService enabledBraveService(
            String apiKey,
            int monthlyQuota,
            double qpsLimit,
            long cooldownMs) {
        BraveSearchService service = new BraveSearchService(new BraveSearchProperties(
                true,
                "https://api.search.brave.com/res/v1/web/search",
                apiKey,
                qpsLimit,
                monthlyQuota,
                500L,
                200L,
                cooldownMs));
        ReflectionTestUtils.setField(service, "configEnabled", true);
        ReflectionTestUtils.setField(service, "apiKey", apiKey);
        ReflectionTestUtils.setField(service, "baseUrl", "https://api.search.brave.com/res/v1/web/search");
        ReflectionTestUtils.setField(service, "timeoutMs", 2000);
        ReflectionTestUtils.setField(service, "timeoutMarginMs", 0L);
        service.init();
        return service;
    }

    private static String braveBody(String suffix) {
        return """
                {"web":{"results":[{"title":"title-%s","description":"desc-%s","url":"https://example.com/%s"}]}}
                """.formatted(suffix, suffix, suffix);
    }

    private static SerpApiProvider enabledSerpApiProvider(String apiKey) {
        SerpApiProvider provider = new SerpApiProvider();
        ReflectionTestUtils.setField(provider, "configEnabled", true);
        ReflectionTestUtils.setField(provider, "apiKey", apiKey);
        ReflectionTestUtils.setField(provider, "baseUrl", "https://serpapi.com/search.json");
        ReflectionTestUtils.setField(provider, "timeoutMs", 2000);
        ReflectionTestUtils.invokeMethod(provider, "init");
        return provider;
    }

    private static ClientHttpRequestFactory timeoutRequestFactory(String message) {
        return (uri, httpMethod) -> {
            throw new SocketTimeoutException(message);
        };
    }

    private static ClientHttpRequestFactory cancellationRequestFactory(String message) {
        return (uri, httpMethod) -> {
            java.io.IOException io = new java.io.IOException(message);
            io.initCause(new CancellationException(message));
            throw new org.springframework.web.client.ResourceAccessException(
                    message,
                    io);
        };
    }

    private static void assertProviderTraceDoesNotContain(String rawQuery, String secret) {
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains(rawQuery), trace);
        assertFalse(trace.contains(secret), trace);
    }

    private static void assertProviderLogDoesNotUseRawThrowableMessages(String sourcePath) throws Exception {
        String source = Files.readString(Path.of(sourcePath), StandardCharsets.UTF_8);
        List<String> rawThrowableLogLines = source.lines()
                .filter(line -> line.contains("log."))
                .filter(line -> line.contains(".getMessage()")
                        || line.contains(".toString()")
                        || line.contains("String.valueOf(e)")
                        || line.trim().matches(".*,[\\s]*(e|ex|throwable|exception)\\);"))
                .filter(line -> !line.contains("SafeRedactor.safeMessage("))
                .toList();

        assertEquals(List.of(), rawThrowableLogLines, sourcePath);
    }

    @SuppressWarnings("unchecked")
    private static Mono<List<String>> invokeNaverApiMono(NaverSearchService service, String rawQuery) {
        return (Mono<List<String>>) ReflectionTestUtils.invokeMethod(
                service,
                "callNaverApiMono",
                rawQuery,
                NaverSearchService.SearchPolicy.freeMode());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static NaverSearchService naverServiceWithoutKeys() {
        return naverService("", "", "");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static NaverSearchService naverService(String naverKeys, String clientId, String clientSecret) {
        return naverService(
                naverKeys,
                clientId,
                clientSecret,
                WebClient.builder().baseUrl("https://openapi.naver.com").build());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static NaverSearchService naverService(String naverKeys,
                                                   String clientId,
                                                   String clientSecret,
                                                   WebClient webClient) {
        NaverSearchService service = new NaverSearchService(
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
                webClient,
                null,
                null);
        ReflectionTestUtils.setField(service, "apiTimeoutMs", 1000L);
        ReflectionTestUtils.setField(service, "syncBlockTimeoutMs", 1000L);
        ReflectionTestUtils.setField(service, "display", 10);
        ReflectionTestUtils.setField(service, "webTopK", 3);
        return service;
    }
}
