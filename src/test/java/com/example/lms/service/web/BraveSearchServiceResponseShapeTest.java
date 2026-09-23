package com.example.lms.service.web;

import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class BraveSearchServiceResponseShapeTest {

    private static final String BASE_URL = "https://api.search.brave.com/res/v1/web/search";
    private static final String TEST_TOKEN = "brave-outbound-header-test-token";

    @BeforeEach
    void clearContextBeforeTest() {
        clearContext();
    }

    @AfterEach
    void clearContext() {
        TraceStore.clear();
        TimeBudgetContext.clear();
        MDC.clear();
    }

    @Test
    void malformedBodyFailsSoftWithRedactedShapeReason() {
        BraveSearchService service = enabledService();
        RestTemplate restTemplate =
                (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(request -> assertFalse(request.getURI().toString().isBlank()))
                .andRespond(withSuccess("{\"web\":\"raw-body-sentinel\"", MediaType.APPLICATION_JSON));

        BraveSearchResult result = service.searchWithMeta("bounded malformed response test", 1);

        server.verify();
        assertEquals(BraveSearchResult.Status.EXCEPTION, result.status());
        assertEquals("json-parse-error", result.message());
        assertEquals("json-parse-error", TraceStore.getString("web.brave.failureReason"));
        assertEquals("invalid_json_shape", TraceStore.getString("web.brave.responseShapeFallbackReason"));
        assertEquals(1L, TraceStore.getLong("web.brave.responseShapeFallbackCount"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("raw-body-sentinel"));
        assertFalse(Boolean.TRUE.equals(TraceStore.get("web.brave.providerSuccess")));
    }

    @Test
    void validEnvelopeWithoutWebSectionIsNotParseError() {
        // Official schema marks top-level "web" nullable: a valid search envelope
        // with no web section is a real answer without usable web evidence.
        BraveSearchService service = enabledService();
        RestTemplate restTemplate =
                (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(request -> assertFalse(request.getURI().toString().isBlank()))
                .andRespond(withSuccess(
                        "{\"type\":\"search\",\"query\":{\"original\":\"q\"},\"mixed\":{}}",
                        MediaType.APPLICATION_JSON));

        BraveSearchResult result = service.searchWithMeta("valid envelope no web", 1);

        server.verify();
        assertEquals(BraveSearchResult.Status.OK, result.status());
        assertEquals(java.util.List.of(), result.snippets());
        assertEquals(Boolean.TRUE, TraceStore.get("web.brave.webAbsent"));
        assertEquals("no_web_section", TraceStore.getString("web.brave.shapeClass"));
    }

    @Test
    void validEnvelopeWithNullWebIsNotParseError() {
        BraveSearchService service = enabledService();
        RestTemplate restTemplate =
                (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(request -> assertFalse(request.getURI().toString().isBlank()))
                .andRespond(withSuccess(
                        "{\"type\":\"search\",\"query\":{\"original\":\"q\"},\"web\":null}",
                        MediaType.APPLICATION_JSON));

        BraveSearchResult result = service.searchWithMeta("valid envelope null web", 1);

        server.verify();
        assertEquals(BraveSearchResult.Status.OK, result.status());
        assertEquals(java.util.List.of(), result.snippets());
        assertEquals(Boolean.TRUE, TraceStore.get("web.brave.webAbsent"));
    }

    @Test
    void emptyResultsArrayIsTrueZeroWithoutWebAbsentMarker() {
        BraveSearchService service = enabledService();
        RestTemplate restTemplate =
                (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(request -> assertFalse(request.getURI().toString().isBlank()))
                .andRespond(withSuccess(
                        "{\"type\":\"search\",\"query\":{\"original\":\"q\"},\"web\":{\"type\":\"search\",\"results\":[]}}",
                        MediaType.APPLICATION_JSON));

        BraveSearchResult result = service.searchWithMeta("true zero results", 1);

        server.verify();
        assertEquals(BraveSearchResult.Status.OK, result.status());
        assertEquals(java.util.List.of(), result.snippets());
        assertFalse(Boolean.TRUE.equals(TraceStore.get("web.brave.webAbsent")));
    }

    @Test
    void webObjectMissingResultsStaysFormatError() {
        BraveSearchService service = enabledService();
        RestTemplate restTemplate =
                (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(request -> assertFalse(request.getURI().toString().isBlank()))
                .andRespond(withSuccess(
                        "{\"type\":\"search\",\"query\":{\"original\":\"q\"},\"web\":{\"type\":\"search\"}}",
                        MediaType.APPLICATION_JSON));

        BraveSearchResult result = service.searchWithMeta("web without results", 1);

        server.verify();
        assertEquals(BraveSearchResult.Status.EXCEPTION, result.status());
        assertEquals("json-parse-error", result.message());
    }

    @Test
    void errorObjectStaysFormatErrorNotZeroResults() {
        BraveSearchService service = enabledService();
        RestTemplate restTemplate =
                (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(request -> assertFalse(request.getURI().toString().isBlank()))
                .andRespond(withSuccess(
                        "{\"type\":\"ErrorResponse\",\"error\":{\"detail\":\"quota\"}}",
                        MediaType.APPLICATION_JSON));

        BraveSearchResult result = service.searchWithMeta("error object body", 1);

        server.verify();
        assertEquals(BraveSearchResult.Status.EXCEPTION, result.status());
        assertEquals("json-parse-error", result.message());
        assertFalse(Boolean.TRUE.equals(TraceStore.get("web.brave.providerSuccess")));
    }

    @Test
    void emptyObjectStaysFormatError() {
        BraveSearchService service = enabledService();
        RestTemplate restTemplate =
                (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(request -> assertFalse(request.getURI().toString().isBlank()))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        BraveSearchResult result = service.searchWithMeta("empty object body", 1);

        server.verify();
        assertEquals(BraveSearchResult.Status.EXCEPTION, result.status());
        assertEquals("json-parse-error", result.message());
    }

    @Test
    void gzipBodyThroughCurrentTransportPathIsRecordedByEncoding() throws Exception {
        // The current SimpleClientHttpRequestFactory does not request or decode gzip;
        // this documents what an unsolicited compressed body becomes and proves the
        // failure-path metadata exposes Content-Encoding instead of the raw body.
        BraveSearchService service = enabledService();
        RestTemplate restTemplate =
                (RestTemplate) ReflectionTestUtils.getField(service, "restTemplate");
        byte[] gzipped;
        try (var bos = new java.io.ByteArrayOutputStream();
             var gz = new java.util.zip.GZIPOutputStream(bos)) {
            gz.write("{\"type\":\"search\",\"query\":{},\"web\":{\"type\":\"search\",\"results\":[]}}"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            gz.finish();
            gzipped = bos.toByteArray();
        }
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(request -> assertFalse(request.getURI().toString().isBlank()))
                .andRespond(withSuccess(gzipped, MediaType.APPLICATION_JSON)
                        .headers(new HttpHeaders() {{ add("Content-Encoding", "gzip"); }}));

        BraveSearchResult result = service.searchWithMeta("gzip boundary probe", 1);

        server.verify();
        assertEquals(BraveSearchResult.Status.EXCEPTION, result.status());
        assertEquals("json-parse-error", result.message());
        assertEquals("gzip", TraceStore.getString("web.brave.failure.contentEncoding"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("results"));
    }

    private static BraveSearchService enabledService() {
        BraveSearchService service = new BraveSearchService(new BraveSearchProperties(
                true,
                BASE_URL,
                TEST_TOKEN,
                0.8d,
                20,
                500L,
                200L,
                2000L));
        ReflectionTestUtils.setField(service, "configEnabled", true);
        ReflectionTestUtils.setField(service, "apiKey", TEST_TOKEN);
        ReflectionTestUtils.setField(service, "baseUrl", BASE_URL);
        ReflectionTestUtils.setField(service, "timeoutMs", 2000);
        ReflectionTestUtils.setField(service, "timeoutMarginMs", 0L);
        ReflectionTestUtils.setField(service, "adaptiveSearchEnabled", false);
        service.init();
        return service;
    }
}
