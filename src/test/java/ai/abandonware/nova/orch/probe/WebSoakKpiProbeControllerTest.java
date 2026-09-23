package ai.abandonware.nova.orch.probe;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WebSoakKpiProbeControllerTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void queryKeyRejectedByDefault() {
        WebSoakKpiProbeService service = mock(WebSoakKpiProbeService.class);
        MockEnvironment env = new MockEnvironment()
                .withProperty("probe.websoak-kpi.key", "secret");
        WebSoakKpiProbeController controller = new WebSoakKpiProbeController(service, env);

        ResponseEntity<?> response = controller.run(new WebSoakKpiProbeController.Request(), null, null, "secret");

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals("PROBE_UNAUTHORIZED", body.get("code"));
        assertEquals(Boolean.FALSE, body.get("allowQueryParamKey"));
        verifyNoInteractions(service);
    }

    @Test
    void placeholderProbeKeyIsTreatedAsNotConfigured() {
        WebSoakKpiProbeService service = mock(WebSoakKpiProbeService.class);
        MockEnvironment env = new MockEnvironment()
                .withProperty("probe.websoak-kpi.key", "changeme");
        WebSoakKpiProbeController controller = new WebSoakKpiProbeController(service, env);

        ResponseEntity<?> response = controller.run(new WebSoakKpiProbeController.Request(), null, "changeme", null);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals("PROBE_UNAUTHORIZED", body.get("code"));
        assertEquals(Boolean.FALSE, body.get("keyConfigured"));
        assertTrue(String.valueOf(body.get("message")).contains("not configured"));
        verifyNoInteractions(service);
    }

    @Test
    void runEndpointRedactsProbeFailureMessages() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/probe/WebSoakKpiProbeController.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("log.error(\"[WebSoakKPI] probe failed\", e);"));
        assertFalse(source.contains("\"message\", bad.getMessage()"));
        assertFalse(source.contains("\"message\", e.getMessage()"));
        assertTrue(source.contains("\"message\", safeFailureMessage(bad, \"bad_request\")"));
        assertTrue(source.contains("\"message\", safeFailureMessage(e, \"internal_error\")"));
        assertTrue(source.contains("SafeRedactor.traceLabelOrFallback(t.getMessage(), \"\")"));
        assertTrue(source.contains("SafeRedactor.traceLabelOrFallback(String.valueOf(e), \"\")"));
        assertFalse(source.matches("(?s).*catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}.*"),
                "WebSoakKpiProbeController Java/embedded UI fail-soft paths need fixed-stage breadcrumbs");
    }

    @Test
    void numericKpiControllerParserOnlyCatchesNumberFormatException() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/probe/WebSoakKpiProbeController.java"),
                StandardCharsets.UTF_8).replace("\r\n", "\n");
        String parserCall = "return Long.parseLong(String.valueOf(v).trim());";
        int parser = source.indexOf(parserCall);
        assertTrue(parser >= 0, "WebSoak KPI controller long parser should be locatable");
        String window = source.substring(parser, Math.min(source.length(), parser + 320));

        assertFalse(window.contains("catch (Exception"),
                "WebSoak KPI controller numeric parser fallback must not hide non-parse failures");
        assertTrue(window.contains("catch (NumberFormatException"),
                "WebSoak KPI controller numeric parser fallback should catch only NumberFormatException");
        assertTrue(window.contains("numeric parse fallback stage={} errorType={}")
                        && window.contains("\"controller.toLong\"")
                        && window.contains("\"invalid_number\""),
                "WebSoak KPI controller numeric parser fallback should leave a stable invalid-number breadcrumb");
        assertFalse(window.contains("log.debug(\"[WebSoakKPI] numeric parse fallback stage={} value={}"),
                "WebSoak KPI controller numeric parser fallback must not log raw values");
    }

    @Test
    void uiTableIncludesAllProviderStatesAndSkipReasons() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/probe/WebSoakKpiProbeController.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("add('provider.naver', kpi['provider.naver'] || '');"));
        assertTrue(source.contains("add('provider.brave', kpi['provider.brave'] || '');"));
        assertTrue(source.contains("add('provider.serpapi', kpi['provider.serpapi'] || '');"));
        assertTrue(source.contains("add('provider.tavily', kpi['provider.tavily'] || '');"));
        assertTrue(source.contains("add('web.naver.skipped.reason', kpi['web.naver.skipped.reason'] || '');"));
        assertTrue(source.contains("add('web.brave.skipped.reason', kpi['web.brave.skipped.reason'] || '');"));
        assertTrue(source.contains("add('web.serpapi.skipped.reason', kpi['web.serpapi.skipped.reason'] || '');"));
        assertTrue(source.contains("add('web.tavily.skipped.reason', kpi['web.tavily.skipped.reason'] || '');"));
        assertTrue(source.contains("['naver','brave','serpapi','tavily'].forEach(function(p){"));
        assertTrue(source.contains(
                "['failureReason','requestedCount','returnedCount','afterFilterCount','providerEmpty',"));
        assertTrue(source.contains(
                "'afterFilterStarved','timeout','timeoutMs','rateLimited','retryAfterMs','cancelled','exceptionType']"));
        assertTrue(source.contains(
                "add(prefix + '.traceObserved', truthy(kpi[prefix + '.traceObserved']));"));
        assertTrue(source.contains(
                ".forEach(function(metric){ add(prefix + '.' + metric, providerMetric(p, metric)); });"));
        assertTrue(source.contains("add('vectorFallback.used', kpi['vectorFallback.used'] || false);"));
        assertTrue(source.contains("add('vectorFallback.reason', kpi['vectorFallback.reason'] || '');"));
        assertTrue(source.contains("add('vectorFallback.effectiveTopK', kpi['vectorFallback.effectiveTopK'] || 0);"));
        assertTrue(source.contains("'web.failsoft.rateLimitBackoff.' + p + '.last.kind'"));
        assertTrue(source.contains("'web.failsoft.rateLimitBackoff.' + p + '.last.delayMs'"));
        assertTrue(source.contains("['naver','brave','serpapi','tavily'].forEach"));
    }

    @Test
    void uiTableDistinguishesUnobservedProviderTaxonomyFromObservedZeros() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/probe/WebSoakKpiProbeController.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("function providerMetric(p, metric)"));
        assertTrue(source.contains("if (!truthy(kpi[prefix + '.traceObserved'])) return 'unknown';"));
        assertTrue(source.contains(
                "return value === undefined || value === null || value === '' ? 'unknown' : value;"));
        assertTrue(source.contains(
                "add(prefix + '.traceObserved', truthy(kpi[prefix + '.traceObserved']));"));
        assertFalse(source.contains("evidenceObserved"));
        assertTrue(source.contains(
                ".forEach(function(metric){ add(prefix + '.' + metric, providerMetric(p, metric)); });"));
        assertFalse(source.contains("add('web.naver.returnedCount', kpi['web.naver.returnedCount'] || 0);"));
        assertFalse(source.contains("add('web.tavily.timeout', kpi['web.tavily.timeout'] || false);"));
    }

    @Test
    void prettyTextIncludesSerpApiAndTavilyBackoffRows() {
        Map<String, Object> kpi = new LinkedHashMap<>();
        kpi.put("web.failsoft.rateLimitBackoff.serpapi.last.kind", "AWAIT_TIMEOUT");
        kpi.put("web.failsoft.rateLimitBackoff.serpapi.last.delayMs", 123L);
        kpi.put("web.failsoft.rateLimitBackoff.tavily.last.kind", "TIMEOUT");
        kpi.put("web.failsoft.rateLimitBackoff.tavily.last.delayMs", 456L);
        WebSoakKpiLastStore.Snapshot snapshot = new WebSoakKpiLastStore.Snapshot(1L, kpi, "{}");

        String pretty = ReflectionTestUtils.invokeMethod(
                WebSoakKpiProbeController.class,
                "buildPrettyText",
                snapshot,
                0L,
                "test");

        assertTrue(pretty.contains("serpapi last.kind=AWAIT_TIMEOUT"), pretty);
        assertTrue(pretty.contains("serpapi last.kind=AWAIT_TIMEOUT delayMs=123"), pretty);
        assertTrue(pretty.contains("tavily last.kind=TIMEOUT"), pretty);
        assertTrue(pretty.contains("tavily last.kind=TIMEOUT delayMs=456"), pretty);
    }

    @Test
    void embeddedUiParseAndStorageFallbacksDoNotRetainRawResponseText() throws Exception {
        String source = Files.readString(
                Path.of("main/java/ai/abandonware/nova/orch/probe/WebSoakKpiProbeController.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("console.debug('websoak ui storage skipped stage=get_key');"));
        assertTrue(source.contains("console.debug('websoak ui storage skipped stage=set_key');"));
        assertTrue(source.contains("console.debug('websoak ui json parse skipped stage=fetch_json');"));
        assertTrue(source.contains("obj = { error: 'bad_json', rawPresent: !!txt, rawLength: txt ? txt.length : 0 };"));
        assertFalse(source.contains("raw: txt"));
    }

    @Test
    void runEndpointKeepsBadRequestResponseWhenFailureMessageIsMissing() {
        WebSoakKpiProbeService service = mock(WebSoakKpiProbeService.class);
        when(service.run(any())).thenThrow(new IllegalArgumentException((String) null));
        MockEnvironment env = new MockEnvironment()
                .withProperty("probe.websoak-kpi.require-key", "false");
        WebSoakKpiProbeController controller = new WebSoakKpiProbeController(service, env);

        ResponseEntity<?> response = assertDoesNotThrow(
                () -> controller.run(new WebSoakKpiProbeController.Request(), null, null, null));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals("bad_request", body.get("error"));
        assertEquals("bad_request", body.get("message"));
        assertFalse(String.valueOf(body).contains("IllegalArgumentException"));
    }

    @Test
    void runEndpointWritesFixedStageBreadcrumbsForBadRequestAndInternalFailures() {
        String badRequestMessage = "private bad request ownerToken=secret";
        WebSoakKpiProbeService badRequestService = mock(WebSoakKpiProbeService.class);
        when(badRequestService.run(any())).thenThrow(new IllegalArgumentException(badRequestMessage));
        MockEnvironment env = new MockEnvironment()
                .withProperty("probe.websoak-kpi.require-key", "false");
        WebSoakKpiProbeController badRequestController = new WebSoakKpiProbeController(badRequestService, env);

        ResponseEntity<?> badRequest = badRequestController.run(
                new WebSoakKpiProbeController.Request(), null, null, null);

        assertEquals(HttpStatus.BAD_REQUEST, badRequest.getStatusCode());
        assertEquals(Boolean.TRUE, TraceStore.get("probe.websoakKpi.run.failed"));
        assertEquals("bad_request", TraceStore.get("probe.websoakKpi.run.failureStage"));
        assertEquals("bad_request", TraceStore.get("probe.websoakKpi.run.failureType"));
        assertEquals(SafeRedactor.hashValue(badRequestMessage),
                TraceStore.get("probe.websoakKpi.run.messageHash"));
        assertEquals(badRequestMessage.length(), TraceStore.get("probe.websoakKpi.run.messageLength"));
        assertFalse(TraceStore.getAll().containsValue(badRequestMessage));

        TraceStore.clear();

        String internalMessage = "private internal failure ownerToken=secret";
        WebSoakKpiProbeService internalService = mock(WebSoakKpiProbeService.class);
        when(internalService.run(any())).thenThrow(new RuntimeException(internalMessage));
        WebSoakKpiProbeController internalController = new WebSoakKpiProbeController(internalService, env);

        ResponseEntity<?> internal = internalController.run(
                new WebSoakKpiProbeController.Request(), null, null, null);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, internal.getStatusCode());
        assertEquals(Boolean.TRUE, TraceStore.get("probe.websoakKpi.run.failed"));
        assertEquals("internal_error", TraceStore.get("probe.websoakKpi.run.failureStage"));
        assertEquals("internal_error", TraceStore.get("probe.websoakKpi.run.failureType"));
        assertEquals(SafeRedactor.hashValue(internalMessage),
                TraceStore.get("probe.websoakKpi.run.messageHash"));
        assertEquals(internalMessage.length(), TraceStore.get("probe.websoakKpi.run.messageLength"));
        assertFalse(TraceStore.getAll().containsValue(internalMessage));
    }

    @Test
    void runEndpointHashesFreeTextFailureMessages() {
        String rawMessage = "private probe failure for user query";
        WebSoakKpiProbeService service = mock(WebSoakKpiProbeService.class);
        when(service.run(any())).thenThrow(new IllegalArgumentException(rawMessage));
        MockEnvironment env = new MockEnvironment()
                .withProperty("probe.websoak-kpi.require-key", "false");
        WebSoakKpiProbeController controller = new WebSoakKpiProbeController(service, env);

        ResponseEntity<?> response = assertDoesNotThrow(
                () -> controller.run(new WebSoakKpiProbeController.Request(), null, null, null));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        String message = String.valueOf(body.get("message"));
        assertFalse(message.contains(rawMessage));
        assertFalse(message.contains("private probe failure"));
        assertTrue(message.startsWith("hash:"));
    }

    @Test
    void runEndpointKeepsInternalErrorResponseWhenFailureMessageIsMissing() {
        WebSoakKpiProbeService service = mock(WebSoakKpiProbeService.class);
        when(service.run(any())).thenThrow(new RuntimeException((String) null));
        MockEnvironment env = new MockEnvironment()
                .withProperty("probe.websoak-kpi.require-key", "false");
        WebSoakKpiProbeController controller = new WebSoakKpiProbeController(service, env);

        ResponseEntity<?> response = assertDoesNotThrow(
                () -> controller.run(new WebSoakKpiProbeController.Request(), null, null, null));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals("internal_error", body.get("error"));
        assertEquals("internal_error", body.get("message"));
        assertFalse(String.valueOf(body).contains("RuntimeException"));
    }

    @Test
    void runEndpointClassifiesCancellationFailureWithoutRawExceptionOrToken() {
        String tokenMarker = "owner" + "Token";
        WebSoakKpiProbeService service = mock(WebSoakKpiProbeService.class);
        when(service.run(any()))
                .thenThrow(new CancellationException("cancelled " + tokenMarker + "=secret query=private soak"));
        MockEnvironment env = new MockEnvironment()
                .withProperty("probe.websoak-kpi.require-key", "false");
        WebSoakKpiProbeController controller = new WebSoakKpiProbeController(service, env);

        ResponseEntity<?> response = assertDoesNotThrow(
                () -> controller.run(new WebSoakKpiProbeController.Request(), null, null, null));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals("internal_error", body.get("error"));
        assertEquals("cancelled", body.get("message"));
        String serialized = String.valueOf(body);
        assertFalse(serialized.contains("CancellationException"), serialized);
        assertFalse(serialized.contains(tokenMarker), serialized);
        assertFalse(serialized.contains("private soak"), serialized);
    }

    @Test
    void lastPrettyShowsAllProviderStatesAndRedactedSkipReasons() {
        WebSoakKpiProbeService service = mock(WebSoakKpiProbeService.class);
        MockEnvironment env = new MockEnvironment()
                .withProperty("probe.websoak-kpi.require-key", "false");
        WebSoakKpiProbeController controller = new WebSoakKpiProbeController(service, env);
        WebSoakKpiLastStore lastStore = new WebSoakKpiLastStore();
        Map<String, Object> kpi = new LinkedHashMap<>();
        kpi.put("outCount", 0);
        kpi.put("cacheOnly.merged.count", 1);
        kpi.put("tracePool.size", 4);
        kpi.put("rescueMerge.used", true);
        kpi.put("vectorFallback.used", true);
        kpi.put("vectorFallback.reason", "web_empty");
        kpi.put("vectorFallback.effectiveTopK", 10L);
        kpi.put("starvationFallback.used", true);
        kpi.put("starvationFallback.poolUsed", "NOFILTER_SAFE");
        kpi.put("starvationFallback.count", 2L);
        kpi.put("starvationFallback.added", 2L);
        kpi.put("starvationFallback.pool.safe.size", 3L);
        kpi.put("starvationFallback.pool.dev.size", 1L);
        kpi.put("provider.naver", "cache_only");
        kpi.put("provider.brave", "skipped");
        kpi.put("provider.serpapi", "skipped");
        kpi.put("provider.tavily", "skipped");
        kpi.put("web.naver.skipped.reason", "breaker_open_or_half_open");
        kpi.put("web.brave.skipped.reason", "disabled ownerToken=secret api_key=test-fixture");
        kpi.put("web.serpapi.skipped.reason", "missing_key");
        kpi.put("web.tavily.skipped.reason", "missing_tavily_api_key");
        kpi.put("web.naver.providerDisabled", false);
        kpi.put("web.naver.disabledReason", "");
        kpi.put("web.naver.failureReason", "cache-only-rescue");
        kpi.put("web.naver.rateLimited", false);
        kpi.put("web.naver.retryAfterMs", 0L);
        kpi.put("web.brave.providerDisabled", true);
        kpi.put("web.brave.disabledReason", "missing_brave_api_key");
        kpi.put("web.brave.failureReason", "rate-limit");
        kpi.put("web.brave.rateLimited", true);
        kpi.put("web.brave.retryAfterMs", 2500L);
        kpi.put("web.brave.cancelled", true);
        kpi.put("web.brave.exceptionType", "cancelled");
        kpi.put("web.brave.timeout", false);
        kpi.put("web.brave.providerEmpty", true);
        kpi.put("web.brave.afterFilterStarved", false);
        kpi.put("web.serpapi.providerDisabled", true);
        kpi.put("web.serpapi.disabledReason", "missing_serpapi_api_key");
        kpi.put("web.serpapi.failureReason", "provider-disabled");
        kpi.put("web.serpapi.rateLimited", true);
        kpi.put("web.serpapi.retryAfterMs", 13000L);
        kpi.put("web.tavily.providerDisabled", true);
        kpi.put("web.tavily.disabledReason", "missing_tavily_api_key");
        kpi.put("web.tavily.failureReason", "provider-disabled");
        kpi.put("web.tavily.rateLimited", false);
        kpi.put("web.tavily.retryAfterMs", 0L);
        lastStore.update(kpi, "{}");
        ReflectionTestUtils.setField(controller, "lastStore", lastStore);

        ResponseEntity<?> response = controller.last(null, null, null, "pretty");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        String body = String.valueOf(response.getBody());
        assertTrue(body.contains("naver=cache_only"), body);
        assertTrue(body.contains("brave=skipped"), body);
        assertTrue(body.contains("serpapi=skipped"), body);
        assertTrue(body.contains("tavily=skipped"), body);
        assertTrue(body.contains("rescueMerge.used=true"), body);
        assertTrue(body.contains("vectorFallback.used=true"), body);
        assertTrue(body.contains("vectorFallback.reason=web_empty"), body);
        assertTrue(body.contains("vectorFallback.effectiveTopK=10"), body);
        assertTrue(body.contains("starvationFallback.used=true"), body);
        assertTrue(body.contains("starvationFallback.poolUsed=NOFILTER_SAFE"), body);
        assertTrue(body.contains("starvationFallback.count=2"), body);
        assertTrue(body.contains("starvationFallback.added=2"), body);
        assertTrue(body.contains("starvationFallback.pool.safe.size=3"), body);
        assertTrue(body.contains("starvationFallback.pool.dev.size=1"), body);
        assertTrue(body.contains("naver.reason=breaker_open_or_half_open"), body);
        assertTrue(body.contains("brave.reason=hash:"), body);
        assertTrue(body.contains("serpapi.reason=missing_key"), body);
        assertTrue(body.contains("tavily.reason=missing_tavily_api_key"), body);
        assertTrue(body.contains("naver.taxonomy disabled=false/"), body);
        assertTrue(body.contains("failure=cache-only-rescue"), body);
        assertTrue(body.contains("brave.taxonomy disabled=true/missing_brave_api_key"), body);
        assertTrue(body.contains("failure=rate-limit"), body);
        assertTrue(body.contains("cancelled=true"), body);
        assertTrue(body.contains("exceptionType=cancelled"), body);
        assertTrue(body.contains("timeout=false"), body);
        assertTrue(body.contains("providerEmpty=true"), body);
        assertTrue(body.contains("retryAfterMs=2500"), body);
        assertTrue(body.contains("serpapi.taxonomy disabled=true/missing_serpapi_api_key"), body);
        assertTrue(body.contains("failure=provider-disabled"), body);
        assertTrue(body.contains("rateLimited=true"), body);
        assertTrue(body.contains("retryAfterMs=13000"), body);
        assertTrue(body.contains("tavily.taxonomy disabled=true/missing_tavily_api_key"), body);
        assertFalse(body.contains("ownerToken=secret"), body);
        assertFalse(body.contains("api_key=test-fixture"), body);
    }

    @Test
    void lastPrettyShowsEcosystemRecirculationFields() {
        WebSoakKpiProbeService service = mock(WebSoakKpiProbeService.class);
        MockEnvironment env = new MockEnvironment()
                .withProperty("probe.websoak-kpi.require-key", "false");
        WebSoakKpiProbeController controller = new WebSoakKpiProbeController(service, env);
        WebSoakKpiLastStore lastStore = new WebSoakKpiLastStore();
        Map<String, Object> kpi = new LinkedHashMap<>();
        kpi.put("outCount", 0);
        kpi.put("ecosystem.recirculate.used", true);
        kpi.put("ecosystem.recirculate.count", 3);
        kpi.put("ecosystem.recirculate.safe", 2);
        kpi.put("ecosystem.recirculate.allUnverified", false);
        kpi.put("ecosystem.pool.size", 4);
        kpi.put("ecosystem.recycled.total", 5L);
        kpi.put("ecosystem.ammonia.score", "0.33");
        kpi.put("ecosystem.ammonia.quarantined", 1);
        kpi.put("ecosystem.ammonia.safe", 2);
        kpi.put("ecosystem.ammonia.threshold", "0.50");
        kpi.put("ecosystem.ammonia.surgeBlocked", false);
        kpi.put("starvationFallback.trigger", "ecosystem->NOFILTER_SAFE");
        kpi.put("poolSafeEmpty", false);
        lastStore.update(kpi, "{}");
        ReflectionTestUtils.setField(controller, "lastStore", lastStore);

        ResponseEntity<?> response = controller.last(null, null, null, "pretty");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        String body = String.valueOf(response.getBody());
        assertTrue(body.contains("ecosystem.recirculate.used=true"), body);
        assertTrue(body.contains("ecosystem.recirculate.count=3"), body);
        assertTrue(body.contains("ecosystem.recirculate.safe=2"), body);
        assertTrue(body.contains("ecosystem.recirculate.allUnverified=false"), body);
        assertTrue(body.contains("ecosystem.pool.size=4"), body);
        assertTrue(body.contains("ecosystem.recycled.total=5"), body);
        assertTrue(body.contains("ecosystem.ammonia.score=0.33"), body);
        assertTrue(body.contains("ecosystem.ammonia.quarantined=1"), body);
        assertTrue(body.contains("ecosystem.ammonia.safe=2"), body);
        assertTrue(body.contains("ecosystem.ammonia.threshold=0.50"), body);
        assertTrue(body.contains("ecosystem.ammonia.surgeBlocked=false"), body);
        assertTrue(body.contains("starvationFallback.trigger=ecosystem->NOFILTER_SAFE"), body);
        assertTrue(body.contains("poolSafeEmpty=false"), body);
    }

    @Test
    void lastPrettyDropsNonFiniteNumericKpiValues() {
        WebSoakKpiProbeService service = mock(WebSoakKpiProbeService.class);
        MockEnvironment env = new MockEnvironment()
                .withProperty("probe.websoak-kpi.require-key", "false");
        WebSoakKpiProbeController controller = new WebSoakKpiProbeController(service, env);
        WebSoakKpiLastStore lastStore = new WebSoakKpiLastStore();
        Map<String, Object> kpi = new LinkedHashMap<>();
        kpi.put("outCount", Double.POSITIVE_INFINITY);
        kpi.put("tracePool.size", Double.NEGATIVE_INFINITY);
        kpi.put("web.failsoft.rateLimitBackoff.max.delayMs", Double.POSITIVE_INFINITY);
        lastStore.update(kpi, "{}");
        ReflectionTestUtils.setField(controller, "lastStore", lastStore);

        ResponseEntity<?> response = controller.last(null, null, null, "pretty");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        String body = String.valueOf(response.getBody());
        assertTrue(body.contains("outCount=0  cacheOnly.merged.count=0  tracePool.size=0"), body);
        assertTrue(body.contains("max.delayMs=0"), body);
        assertFalse(body.contains(String.valueOf(Long.MAX_VALUE)), body);
        assertFalse(body.contains(String.valueOf(Long.MIN_VALUE)), body);
    }
}
