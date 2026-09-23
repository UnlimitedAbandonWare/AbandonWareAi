package com.example.lms.gptsearch.web.impl;

import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.config.ConfigValueGuards;
import com.example.lms.guard.ProviderCredentialResolver;
import com.example.lms.gptsearch.web.AbstractWebSearchProvider;
import com.example.lms.gptsearch.web.ProviderId;
import com.example.lms.gptsearch.web.dto.WebDocument;
import com.example.lms.gptsearch.web.dto.WebSearchQuery;
import com.example.lms.gptsearch.web.dto.WebSearchResult;
import com.example.lms.search.TraceStore;
import com.example.lms.search.WebProviderTraceReasons;
import com.example.lms.trace.SafeRedactor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * WebSearchProvider implementation backed by SerpApi.
 * <p>
 * Configuration resolution order for the API key:
 * <ol>
 *   <li>gpt-search.serpapi.api-key</li>
 *   <li>search.serpapi.api-key (legacy)</li>
 *   <li>GPT_SEARCH_SERPAPI_API_KEY environment variable</li>
 *   <li>SERPAPI_API_KEY environment variable</li>
 * </ol>
 * When no API key can be resolved the provider is disabled and will
 * simply return empty results.
 */
@Component
public class SerpApiProvider extends AbstractWebSearchProvider {

    private static final Logger log = LoggerFactory.getLogger(SerpApiProvider.class);

    @Autowired(required = false)
    private ProviderCredentialResolver credentialResolver;

    @Value("${gpt-search.serpapi.api-key:${search.serpapi.api-key:${GPT_SEARCH_SERPAPI_API_KEY:${SERPAPI_API_KEY:}}}}")
    private String apiKey;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.lms.debug.ApiFailureRecorder apiFailureRecorder;

    @Value("${gpt-search.serpapi.base-url:${search.serpapi.base-url:https://serpapi.com/search.json}}")
    private String baseUrl;

    @Value("${gpt-search.serpapi.timeout-ms:${search.serpapi.timeout-ms:3000}}")
    private int timeoutMs;

    @Value("${gpt-search.serpapi.enabled:${search.serpapi.enabled:true}}")
    private boolean configEnabled;

    @Value("${gpt-search.serpapi.max-results:${search.serpapi.max-results:20}}")
    private int maxResults = 20;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private boolean enabled;
    private String disabledReason = "";

    @PostConstruct
    void init() {
        if (!configEnabled) {
            enabled = false;
            disabledReason = "disabled_by_config";
            log.info("[AWX2AF2][search][serpapi] provider disabled enabled=false hasKey=false sourceName=gpt-search.serpapi.enabled disabledReason={}",
                    disabledReason);
            return;
        }

        ProviderCredentialResolver resolver = credentialResolver;
        if (resolver != null) {
            ProviderCredentialResolver.Resolution resolution = resolver.resolve(
                    ProviderCredentialResolver.Provider.SERPAPI);
            apiKey = resolution.valueOrNull();
            if (!resolution.enabled()) {
                enabled = false;
                disabledReason = "missing-credential".equals(resolution.disabledReason())
                        ? "missing_serpapi_api_key"
                        : resolution.disabledReason();
                log.warn("[AWX2AF2][search][serpapi] provider disabled enabled=false hasKey={} sourceName={} aliasCount={} disabledReason={}",
                        resolution.credentialPresent(),
                        resolution.sourceName(),
                        resolution.configuredAliasCount(),
                        disabledReason);
                return;
            }
        }

        if (ConfigValueGuards.isMissing(apiKey)) {
            enabled = false;
            disabledReason = "missing_serpapi_api_key";
            log.warn("[AWX2AF2][search][serpapi] provider disabled enabled=false hasKey=false sourceName=gpt-search.serpapi.api-key/search.serpapi.api-key disabledReason={}",
                    disabledReason);
        } else {
            enabled = true;
            disabledReason = "";
            log.info("[SerpApi] API key loaded successfully");
            log.debug("[SerpApi] Config: baseUrlHost={} baseUrlHash={} timeout={}ms",
                    safeHost(baseUrl), SafeRedactor.hashValue(baseUrl), timeoutMs);

            // Ensure RestTemplate has connect/read timeouts. Without this, cancel(false)
            // cannot stop a blocking HTTP call and threads may linger far beyond the caller deadline.
            try {
                int t = Math.max(timeoutMs, 2000);
                SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
                rf.setConnectTimeout(t);
                rf.setReadTimeout(t);
                this.restTemplate.setRequestFactory(rf);
                log.info("[SerpApi] RestTemplate timeouts set: {}ms (connect/read)", t);
            } catch (Exception e) {
                log.warn("[SerpApi] Failed to configure RestTemplate timeouts failureReason={} errorType={} timeoutMs={}",
                        "exception",
                        safeErrorType(e),
                        Math.max(timeoutMs, 2000));
            }
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String disabledReason() {
        return disabledReason;
    }

    private int configuredMaximum() {
        return Math.max(1, Math.min(100, maxResults));
    }

    private RestTemplate requestScopedTemplateForCurrentBudget() {
        TimeBudget budget = TimeBudgetContext.get();
        if (budget == null) {
            return restTemplate;
        }
        long remainingMs = budget.remainingMillis();
        if (remainingMs <= 0L) {
            return null;
        }
        // Socket phase limits; zero would mean unlimited and shared timeouts must stay unchanged.
        int effectiveTimeoutMs = (int) Math.max(1L, Math.min(Math.max(timeoutMs, 2000), remainingMs));
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(effectiveTimeoutMs);
        factory.setReadTimeout(effectiveTimeoutMs);
        RestTemplate scoped = new RestTemplate(factory);
        scoped.setInterceptors(new ArrayList<>(restTemplate.getInterceptors()));
        scoped.setMessageConverters(new ArrayList<>(restTemplate.getMessageConverters()));
        scoped.setErrorHandler(restTemplate.getErrorHandler());
        return scoped;
    }

    private int admittedTopK(WebSearchQuery query) {
        if (query == null || query.getTopK() <= 0) {
            return 0;
        }
        return Math.min(query.getTopK(), configuredMaximum());
    }

    private static String safeHost(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        try {
            String host = URI.create(value.trim()).getHost();
            return host == null || host.isBlank() ? "unknown" : host;
        } catch (Exception ignored) {
            traceSuppressed("serpapi.safeHost", ignored);
            return "invalid";
        }
    }

    @Override
    public ProviderId id() {
        return ProviderId.SERPAPI;
    }

    @Override
    protected WebSearchResult blankQueryResult(WebSearchQuery query) {
        String queryText = query == null ? "" : query.getQuery();
        int topK = admittedTopK(query);
        traceSerpApiCounts(queryText, topK, 0, 0, false, "blank_query");
        return new WebSearchResult(id().name(), Collections.emptyList());
    }

    @Override
    protected WebSearchResult doSearch(WebSearchQuery query) throws Exception {
        long t0Ns = System.nanoTime();
        String queryText = query == null ? "" : query.getQuery();
        int topK = admittedTopK(query);
        if (topK <= 0) {
            traceSerpApiCounts(queryText, 0, 0, 0, false, "non_positive_top_k");
            return new WebSearchResult(id().name(), Collections.emptyList());
        }
        if (!enabled) {
            log.debug("[SerpApi] Skipping search - provider disabled");
            traceSerpApiCounts(queryText, topK, 0, 0, true, disabledReason);
            return new WebSearchResult(id().name(), Collections.emptyList());
        }

        if (queryText == null || queryText.isBlank()) {
            traceSerpApiCounts(queryText, topK, 0, 0, false, "blank_query");
            return new WebSearchResult(id().name(), Collections.emptyList());
        }

        RestTemplate requestTemplate = requestScopedTemplateForCurrentBudget();
        if (requestTemplate == null) {
            traceSerpApiFailure(queryText, topK, -1, "request_budget_exhausted", false, false,
                    null, (System.nanoTime() - t0Ns) / 1_000_000L);
            return new WebSearchResult(id().name(), Collections.emptyList());
        }

        // Includes api_key as a query parameter for the vendor call; do not log
        // this URI. Diagnostics should use queryHash/queryLength/counts/reasons.
        URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .queryParam("engine", "google")
                .queryParam("api_key", apiKey)
                .queryParam("q", queryText)
                .queryParam("num", topK)
                .build()
                .encode(StandardCharsets.UTF_8)
                .toUri();

        try (SerpApiAttemptObservation attempt = SerpApiAttemptObservation.start(queryText)) {
            String body;
            try {
                var response = requestTemplate.getForEntity(uri, String.class);
                if (attempt != null) attempt.received(response.getStatusCode().value());
                body = response.getBody();
            } catch (HttpClientErrorException.TooManyRequests e) {
                if (apiFailureRecorder != null) apiFailureRecorder.recordException(baseUrl, "google", e);
                traceSuppressed("serpapi.rateLimit", e);
                if (attempt != null) attempt.failed(429, "rate-limit");
                long elapsedMs = (System.nanoTime() - t0Ns) / 1_000_000L;
                traceSerpApiRemoteCooldown("rate-limit", retryAfterToMs(e.getResponseHeaders()));
                traceSerpApiFailure(queryText, topK, 429, "rate-limit", true, false,
                        e.getResponseBodyAsString(), elapsedMs);
                return new WebSearchResult(id().name(), Collections.emptyList());
            } catch (HttpStatusCodeException e) {
                if (apiFailureRecorder != null) apiFailureRecorder.recordException(baseUrl, "google", e);
                traceSuppressed("serpapi.httpStatus", e);
                int code = e.getStatusCode() == null ? -1 : e.getStatusCode().value();
                boolean rateLimited = code == 429;
                if (attempt != null) attempt.failed(code, rateLimited ? "rate-limit" : "http-error");
                long elapsedMs = (System.nanoTime() - t0Ns) / 1_000_000L;
                if (rateLimited) {
                    traceSerpApiRemoteCooldown("rate-limit", retryAfterToMs(e.getResponseHeaders()));
                }
                traceSerpApiFailure(queryText, topK, code, rateLimited ? "rate-limit" : "http-error", rateLimited,
                        false, e.getResponseBodyAsString(), elapsedMs);
                return new WebSearchResult(id().name(), Collections.emptyList());
            } catch (ResourceAccessException e) {
                if (apiFailureRecorder != null && !isCancellationFailure(e)) apiFailureRecorder.recordException(baseUrl, "google", e);
                traceSuppressed("serpapi.resourceAccess", e);
                long elapsedMs = (System.nanoTime() - t0Ns) / 1_000_000L;
                boolean cancelled = isCancellationFailure(e);
                if (attempt != null) attempt.failed(-1, cancelled ? "cancelled" : "timeout");
                traceSerpApiFailure(queryText, topK, -1, cancelled ? "cancelled" : "timeout", false, !cancelled, null,
                        elapsedMs);
                if (cancelled) {
                    traceSerpApiCancellation();
                }
                return new WebSearchResult(id().name(), Collections.emptyList());
            } catch (Exception e) {
                traceSuppressed("serpapi.search", e);
                if (apiFailureRecorder != null && !isCancellationFailure(e)) apiFailureRecorder.recordException(baseUrl, "google", e);
                long elapsedMs = (System.nanoTime() - t0Ns) / 1_000_000L;
                boolean cancelled = isCancellationFailure(e);
                boolean timeout = !cancelled && isTimeoutFailure(e);
                if (attempt != null) attempt.failed(-1, cancelled ? "cancelled" : (timeout ? "timeout" : "exception"));
                traceSerpApiFailure(queryText, topK, -1, cancelled ? "cancelled" : (timeout ? "timeout" : "exception"),
                        false, timeout, null,
                        elapsedMs);
                if (cancelled) {
                    traceSerpApiCancellation();
                }
                return new WebSearchResult(id().name(), Collections.emptyList());
            }
            List<WebDocument> docs = new ArrayList<>();

            if (body != null && !body.isBlank()) {
                try {
                    JsonNode root = objectMapper.readTree(body);
                    JsonNode organic = root.path("organic_results");
                    if (organic.isArray()) {
                        for (JsonNode item : organic) {
                            String title = safeText(item, "title");
                            String link = safeText(item, "link");
                            String snippet = safeText(item, "snippet");
                            if (!title.isBlank() && !link.isBlank()) {
                                WebDocument d = new WebDocument();
                                d.setTitle(title);
                                d.setUrl(link);
                            d.setSnippet(snippet);
                            docs.add(d);
                            if (docs.size() >= topK) {
                                break;
                            }
                        }
                    }
                    }
                } catch (Exception e) {
                    if (attempt != null) attempt.failed(-1, "parse-error");
                    log.warn("[SerpApi] parse failed failureReason={} errorType={} bodyHash={} bodyLength={}",
                            "parse-error",
                            safeErrorType(e),
                            SafeRedactor.hashValue(body),
                            body.length());
                }
            }

            traceSerpApiCounts(queryText, topK, docs.size(), docs.size(), false, null);
            if (docs.isEmpty()) {
                log.debug("[AWX2AF2][search][serpapi] zero results returnedCount=0 requestedCount={} timeoutMs={}",
                        topK, timeoutMs);
            }

            if (attempt != null) attempt.completed(docs.size());
            return new WebSearchResult(id().name(), docs);
        }
    }

    /** One synchronous client invocation owns its outcome; legacy shared scalars are not read back. */
    private static final class SerpApiAttemptObservation implements AutoCloseable {
        private final java.util.Map<String, Object> parent;
        private final java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
        private final long startedNs = System.nanoTime();

        private SerpApiAttemptObservation(String query) {
            parent = TraceStore.context();
            var attemptContext = TraceStore.searchContext(parent, "providerAttemptId");
            row.putAll(TraceStore.searchCorrelation(attemptContext));
            row.put("provider", "serpapi");
            row.put("queryHash", SafeRedactor.hashValue(query));
            row.put("countScope", "admitted_document_list");
            row.put("clientAttemptObserved", true);
            row.put("clientAttemptBoundary", "resttemplate_get");
            row.put("providerReceiptObserved", false);
            row.put("startedAtEpochMs", System.currentTimeMillis());
            for (String key : java.util.List.of("httpStatus", "returnedCount", "afterFilterCount", "failureReason")) row.put(key, "unknown");
            row.put("outcome", "UNKNOWN");
            TraceStore.installContext(attemptContext);
        }

        private static SerpApiAttemptObservation start(String query) {
            try { return new SerpApiAttemptObservation(query); }
            catch (RuntimeException unavailableContext) { return null; }
        }

        private void received(int status) { row.put("httpStatus", status); }

        private void failed(int status, String reason) {
            if (status > 0) received(status);
            row.put("outcome", "ERROR");
            row.put("failureReason", reason);
        }

        private void completed(int count) {
            if (!"UNKNOWN".equals(row.get("outcome"))) return;
            row.put("outcome", count == 0 ? "EMPTY" : "OK");
            row.put("failureReason", count == 0 ? "provider_empty" : "none");
            row.put("returnedCount", count);
            row.put("afterFilterCount", count);
        }

        @Override public void close() {
            try {
                row.put("finishedAtEpochMs", System.currentTimeMillis());
                row.put("elapsedMs", Math.max(0L, (System.nanoTime() - startedNs) / 1_000_000L));
                TraceStore.append("web.serpapi.attempt.runs", java.util.Map.copyOf(row));
            } catch (RuntimeException unavailableTraceSink) {
                // A closed observation sink must never replace the provider outcome.
            } finally {
                TraceStore.installContext(parent);
            }
        }
    }

    private void traceSerpApiCounts(String query,
                                    int requestedCount,
                                    int returnedCount,
                                    int afterFilterCount,
                                    boolean providerDisabled,
                                    String disabledReason) {
        try {
            int requested = Math.max(0, requestedCount);
            int returned = Math.max(0, returnedCount);
            int after = Math.max(0, afterFilterCount);
            String skipReason = !providerDisabled && disabledReason != null && !disabledReason.isBlank()
                    ? SafeRedactor.traceLabelOrFallback(disabledReason, "unknown")
                    : "";
            TraceStore.put("web.serpapi.requestedCount", requested);
            TraceStore.put("web.serpapi.timeoutMs", Math.max(0, timeoutMs));
            TraceStore.put("web.serpapi.returnedCount", returned);
            TraceStore.put("web.serpapi.afterFilterCount", after);
            TraceStore.put("web.serpapi.zeroResults", returned == 0);
            TraceStore.put("web.serpapi.afterFilterStarved", returned > 0 && after == 0);
            TraceStore.put("web.serpapi.providerDisabled", providerDisabled);
            TraceStore.put("web.serpapi.providerEmpty", !providerDisabled && skipReason.isBlank() && returned == 0);
            TraceStore.put("web.serpapi.queryHash", query == null ? "" : SafeRedactor.hashValue(query));
            TraceStore.put("web.serpapi.queryLength", query == null ? 0 : query.length());
            TraceStore.put("web.serpapi.queryTokenBucket", queryTokenBucket(query));
            resetProviderHttpFailureFlags();
            TraceStore.put("web.serpapi.timeout", false);
            TraceStore.put("web.serpapi.cancelled", false);
            if (!skipReason.isBlank()) {
                TraceStore.put("web.serpapi.skipped.reason", skipReason);
            }
            String failureReason = !skipReason.isBlank()
                    ? skipReason
                    : classifySerpApiCountFailure(returned, after, providerDisabled);
            TraceStore.put("web.serpapi.failureReason", failureReason);
            traceCommonWebProviderCounts(query, returned, after, providerDisabled, disabledReason, failureReason);
            if (providerDisabled) {
                String reason = disabledReason == null || disabledReason.isBlank()
                        ? "disabled"
                        : disabledReason;
                String safeReason = SafeRedactor.traceLabelOrFallback(reason, "unknown");
                TraceStore.put("web.serpapi.providerDisabled", true);
                TraceStore.put("web.serpapi.disabledReason", safeReason);
                TraceStore.put("web.serpapi.disabledReasonCanonical", safeReason);
                TraceStore.put("web.serpapi.skipped", true);
                TraceStore.put("web.serpapi.skipped.reason", safeReason);
            }
        } catch (RuntimeException ignore) {
            traceSuppressed("serpapi.providerDisabledTrace", ignore);
            // fail-soft telemetry only
        }
    }

    private void traceSerpApiFailure(String query,
                                     int requestedCount,
                                     int httpStatus,
                                     String failureReason,
                                     boolean rateLimited,
                                     boolean timeout,
                                     String errorBody,
                                     long tookMs) {
        try {
            String safeFailureReason = SafeRedactor.traceLabelOrFallback(failureReason == null ? "unknown" : failureReason, "unknown");
            traceSerpApiCounts(query, requestedCount, 0, 0, false, null);
            TraceStore.put("web.serpapi.zeroResults", false);
            TraceStore.put("web.serpapi.providerEmpty", false);
            if (httpStatus > 0) {
                TraceStore.put("web.serpapi.httpStatus", httpStatus);
            }
            TraceStore.put("web.serpapi.429", httpStatus == 429);
            TraceStore.put("web.serpapi.rateLimited", rateLimited);
            TraceStore.put("web.serpapi.timeout", timeout);
            TraceStore.put("web.serpapi.failureReason", safeFailureReason);
            TraceStore.put("web.serpapi.tookMs", Math.max(0L, tookMs));
            if (rateLimited) {
                TraceStore.put("web.rateLimited", true);
            }
            traceCommonWebProviderCounts(query, 0, 0, false, null, safeFailureReason);
            traceErrorBody("web.serpapi", errorBody, query);
            log.warn("[AWX][search][serpapi] provider failure failureReason={} httpStatus={} queryHash={} queryLength={} bodyHash={} bodyLength={} tookMs={}",
                    safeFailureReason,
                    httpStatus > 0 ? httpStatus : "none",
                    query == null ? "" : SafeRedactor.hashValue(query),
                    query == null ? 0 : query.length(),
                    errorBody == null || errorBody.isBlank() ? "" : SafeRedactor.hashValue(errorBody),
                    errorBody == null ? 0 : errorBody.length(),
                    Math.max(0L, tookMs));
        } catch (Throwable ignore) {
            traceSuppressed("serpapi.failureTrace", ignore);
            // fail-soft telemetry only
        }
    }

    private static void traceErrorBody(String prefix, String body, String query) {
        TraceStore.put(prefix + ".errorBodyLength", body == null ? 0 : body.length());
        if (body == null || body.isBlank()) {
            return;
        }
        TraceStore.put(prefix + ".errorBodyHash", SafeRedactor.hashValue(body));
    }

    private static void resetProviderHttpFailureFlags() {
        TraceStore.put("web.serpapi.httpStatus", null);
        TraceStore.put("web.serpapi.429", false);
        TraceStore.put("web.serpapi.rateLimited", false);
    }

    private static void traceSerpApiRemoteCooldown(String reason, long retryAfterMs) {
        try {
            long hintMs = Math.max(0L, retryAfterMs);
            TraceStore.put("web.serpapi.cooldown.reason",
                    SafeRedactor.traceLabelOrFallback(reason == null || reason.isBlank() ? "remote" : reason, "remote"));
            TraceStore.put("web.serpapi.retryAfterMs", hintMs);
            TraceStore.put("web.serpapi.cooldown.hintMs", hintMs);
        } catch (Throwable ignore) {
            traceSuppressed("serpapi.cooldownTrace", ignore);
            // fail-soft telemetry only
        }
    }

    private static long retryAfterToMs(HttpHeaders headers) {
        if (headers == null) {
            return 0L;
        }
        String raw = headers.getFirst("Retry-After");
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        String value = raw.trim();
        try {
            long seconds = Long.parseLong(value);
            if (seconds <= 0L) {
                return 0L;
            }
            return seconds >= 60L ? 60_000L : seconds * 1000L;
        } catch (NumberFormatException ignore) {
            traceSuppressed("serpapi.retryAfterSeconds", ignore);
            // Retry-After may also be an HTTP-date.
        }
        try {
            long deltaMs = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant()
                    .toEpochMilli() - System.currentTimeMillis();
            return deltaMs <= 0L ? 0L : Math.min(60_000L, deltaMs);
        } catch (DateTimeParseException ignore) {
            traceSuppressed("serpapi.retryAfterDate", ignore);
            return 0L;
        }
    }

    private static boolean isTimeoutFailure(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof ResourceAccessException) {
                return true;
            }
            String type = cur.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
            String msg = cur.getMessage() == null ? "" : cur.getMessage().toLowerCase(java.util.Locale.ROOT);
            if (type.contains("timeout") || msg.contains("timeout") || msg.contains("timed out")) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    private static boolean isCancellationFailure(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof java.util.concurrent.CancellationException
                    || cur instanceof InterruptedException) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    private static void traceSerpApiCancellation() {
        try {
            TraceStore.put("web.serpapi.cancelled", true);
            TraceStore.put("web.serpapi.exceptionType", "cancelled");
        } catch (Throwable ignore) {
            traceSuppressed("serpapi.cancelledTrace", ignore);
        }
    }

    private static String queryTokenBucket(String query) {
        if (query == null || query.isBlank()) {
            return "0";
        }
        int tokens = query.trim().split("\\s+").length;
        if (tokens <= 4) {
            return "1-4";
        }
        if (tokens <= 12) {
            return "5-12";
        }
        if (tokens <= 32) {
            return "13-32";
        }
        return "33+";
    }

    private static String classifySerpApiCountFailure(int returned, int after, boolean providerDisabled) {
        if (providerDisabled) {
            return "provider-disabled";
        }
        if (returned <= 0) {
            return "provider-empty";
        }
        if (after <= 0) {
            return "after-filter-starvation";
        }
        return "none";
    }

    private static void traceCommonWebProviderCounts(String query,
                                                     int returned,
                                                     int after,
                                                     boolean providerDisabled,
                                                     String disabledReason,
                                                     String failureReason) {
        TraceStore.put("web.provider.name", "serpapi");
        TraceStore.put("web.provider.enabled", !providerDisabled);
        TraceStore.put("web.provider.resultCount", Math.max(0, returned));
        TraceStore.put("web.provider.disabledReason", providerDisabled
                ? WebProviderTraceReasons.disabledReason(disabledReason)
                : null);
        TraceStore.put("web.query.hash", query == null || query.isBlank()
                ? null
                : SafeRedactor.hashValue(query));
        TraceStore.put("web.query.length", query == null ? 0 : query.length());
        TraceStore.putIfAbsent("web.query.variantCount", 0);
        if (returned > 0 && after <= 0) {
            TraceStore.put("web.filter.starvationReason", "after-filter-starvation");
        }
        String commonReason = commonFailSoftReason(failureReason);
        if (commonReason != null) {
            TraceStore.put("web.failsoft.reason", commonReason);
        }
    }

    private static String commonFailSoftReason(String reason) {
        String safe = SafeRedactor.traceLabelOrFallback(reason, "none");
        return switch (safe) {
            case "none" -> null;
            case "provider-empty" -> "empty-provider-output";
            default -> safe;
        };
    }

    private static String safeErrorType(Throwable error) {
        Throwable cur = error;
        for (int i = 0; i < 8 && cur != null && cur.getCause() != null; i++) {
            cur = cur.getCause();
        }
        String type = cur == null ? "unknown" : cur.getClass().getSimpleName();
        return SafeRedactor.traceLabelOrFallback(type, "unknown");
    }

    private static void traceSuppressed(String stage, Throwable ignored) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String safeErrorType = errorType(ignored);
        TraceStore.put("web.serpapi.suppressed.stage", safeStage);
        TraceStore.put("web.serpapi.suppressed.errorType", safeErrorType);
        TraceStore.put("web.serpapi.suppressed." + safeStage, true);
        TraceStore.put("web.serpapi.suppressed." + safeStage + ".errorType", safeErrorType);
    }

    private static String errorType(Throwable ignored) {
        if (ignored == null) {
            return "unknown";
        }
        if (isCancellationFailure(ignored)) {
            return "cancelled";
        }
        if (ignored instanceof NumberFormatException) {
            return "invalid_number";
        }
        if (ignored instanceof DateTimeParseException) {
            return "invalid_date";
        }
        return safeErrorType(ignored);
    }

    private static String safeText(JsonNode node, String field) {
        if (node == null) {
            return "";
        }
        JsonNode child = node.get(field);
        return child != null && !child.isNull() ? child.asText("") : "";
    }
}
