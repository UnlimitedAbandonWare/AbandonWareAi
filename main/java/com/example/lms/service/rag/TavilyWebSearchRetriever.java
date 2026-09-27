package com.example.lms.service.rag;

import com.example.lms.config.ConfigValueGuards;
import com.example.lms.guard.ProviderCredentialResolver;
import com.example.lms.search.TraceStore;
import com.example.lms.search.WebProviderTraceReasons;
import com.example.lms.trace.SafeRedactor;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.scheduler.Schedulers;
import java.net.URI;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;



@Component("tavilyWebSearchRetriever")
@ConditionalOnProperty(prefix = "tavily", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class TavilyWebSearchRetriever implements ContentRetriever {
    private static final Logger log = LoggerFactory.getLogger(TavilyWebSearchRetriever.class);

    private final WebClient.Builder http;

    @Autowired(required = false)
    private ProviderCredentialResolver credentialResolver;

    @Value("${tavily.api.url:https://api.tavily.com/search}")
    private String baseUrl;

    @Value("${tavily.api.key:}")
    private String apiKey;

    @Value("${tavily.max-results:5}")
    private int maxResults;

    @Value("${tavily.timeout-ms:3000}")
    private int timeoutMs;

    @Override
    public List<Content> retrieve(Query query) {
        long startedNs = System.nanoTime();
        String q = (query != null && query.text() != null) ? query.text().strip() : "";
        int requested = Math.max(1, maxResults);
        if (q.isBlank()) {
            traceCounts(q, requested, 0, 0, "empty-query", false);
            return List.of();
        }
        String effectiveApiKey = apiKey;
        ProviderCredentialResolver resolver = credentialResolver;
        if (resolver != null) {
            ProviderCredentialResolver.Resolution resolution = resolver.resolve(
                    ProviderCredentialResolver.Provider.TAVILY);
            effectiveApiKey = resolution.valueOrNull();
            if (!resolution.enabled()) {
                String reason = "missing-credential".equals(resolution.disabledReason())
                        ? "missing_tavily_api_key"
                        : resolution.disabledReason();
                traceProviderDisabled(q, requested, reason);
                return List.of();
            }
        }
        if (ConfigValueGuards.isMissing(effectiveApiKey)) {
            traceProviderDisabled(q, requested, "missing_tavily_api_key");
            return List.of();
        }

        TavilyAttemptObservation attempt = new TavilyAttemptObservation(q);
        try {
            WebClient client = http.clone().baseUrl(baseUrl)
                    .filters(filters -> filters.add(0, (request, next) ->
                            reactor.core.publisher.Mono.defer(() -> attempt.exchange(request, next))))
                    .build();
            Map<String, Object> req = Map.of(
                    "api_key", effectiveApiKey,
                    "query", q,
                    "max_results", requested
            );

            @SuppressWarnings("unchecked")
            Map<String, Object> resp = client.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(req)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofMillis(Math.max(1000, timeoutMs)))
                    // Execute the HTTP call on an elastic scheduler to avoid
                    // blocking reactive event loop threads.  The downstream
                    // block() remains synchronous, but the network I/O
                    // happens off the event loop.
                    .subscribeOn(Schedulers.boundedElastic())
                    .block();

            if (resp == null) {
                attempt.completed(0, 0, "provider-empty");
                traceCounts(q, requested, 0, 0, "provider-empty", true);
                return List.of();
            }

            List<Content> out = new ArrayList<>();
            Object results = resp.get("results"); // each: {title,url,content/snippet,/* ... */}
            int rawCount = results instanceof List<?> list ? list.size() : 0;
            if (results instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) {
                        String title   = str(m.get("title"));
                        String snippet = str(m.get("content"));
                        String url     = str(m.get("url"));
                        String text    = (snippet == null || snippet.isBlank())
                                ? url
                                : snippet + (url == null ? "" : "\n\n[출처] " + url);
                        if (text != null && !text.isBlank()) {
                            Map<String, String> metadata = new LinkedHashMap<>();
                            if (title != null && !title.isBlank()) {
                                metadata.put("title", title);
                            }
                            if (url != null && !url.isBlank()) {
                                metadata.put("url", url);
                            }
                            out.add(metadata.isEmpty()
                                    ? Content.from(text)
                                    : Content.from(TextSegment.from(text, Metadata.from(metadata))));
                        }
                    }
                }
            }
            attempt.completed(rawCount, out.size(), rawCount == 0 ? "provider-empty"
                    : out.isEmpty() ? "after-filter-starvation" : "none");
            traceCounts(q, requested, rawCount, out.size(), null, rawCount == 0);
            return out;
        } catch (Exception e) {
            attempt.failed(e);
            traceException(q, requested, e, (System.nanoTime() - startedNs) / 1_000_000L);
            log.debug("[Tavily] retrieve failed failureReason={} errorType={} queryHash={} queryLength={}",
                    tavilyFailureReason(e),
                    tavilyErrorType(e),
                    SafeRedactor.hashValue(q),
                    q.length());
            return List.of();
        } finally {
            attempt.close();
        }
    }


    /** Carries immutable IDs into the client exchange without retaining ThreadLocal state across signals. */
    private static final class TavilyAttemptObservation {
        private final Map<String, Object> parent = TraceStore.context();
        private final String queryHash;
        private volatile Map<String, Object> attemptContext;
        private volatile Integer httpStatus;
        private long startedNs;
        private long startedAtEpochMs;
        private Object returnedCount = "unknown";
        private Object afterFilterCount = "unknown";
        private String outcome = "UNKNOWN";
        private String failureReason = "unknown";

        private TavilyAttemptObservation(String query) { queryHash = SafeRedactor.hashValue(query); }

        private reactor.core.publisher.Mono<org.springframework.web.reactive.function.client.ClientResponse> exchange(
                org.springframework.web.reactive.function.client.ClientRequest request,
                org.springframework.web.reactive.function.client.ExchangeFunction next) {
            Map<String, Object> previous = TraceStore.context();
            try {
                startedNs = System.nanoTime();
                startedAtEpochMs = System.currentTimeMillis();
                attemptContext = TraceStore.searchContext(parent, "providerAttemptId");
                TraceStore.installContext(attemptContext);
                return next.exchange(request).doOnNext(response -> httpStatus = response.statusCode().value());
            } finally {
                // Restore this same subscriber thread now, not from a later callback on another thread.
                TraceStore.installContext(previous);
            }
        }

        private void completed(int returned, int afterFilter, String reason) {
            returnedCount = returned;
            afterFilterCount = afterFilter;
            outcome = afterFilter > 0 ? "OK" : "EMPTY";
            failureReason = reason;
        }

        private void failed(Throwable error) {
            outcome = "ERROR";
            failureReason = tavilyFailureReason(error);
        }

        private void close() {
            Map<String, Object> context = attemptContext;
            if (context == null) return; // Disabled/blank/setup paths did not enter the exchange.
            Map<String, Object> previous = TraceStore.context();
            try {
                Map<String, Object> row = new LinkedHashMap<>(TraceStore.searchCorrelation(context));
                row.put("provider", "tavily");
                row.put("queryHash", queryHash);
                row.put("countScope", "raw_results_and_content_list");
                row.put("clientAttemptObserved", true);
                row.put("clientAttemptBoundary", "webclient_exchange");
                row.put("providerReceiptObserved", false);
                row.put("startedAtEpochMs", startedAtEpochMs);
                row.put("finishedAtEpochMs", System.currentTimeMillis());
                row.put("elapsedMs", Math.max(0L, (System.nanoTime() - startedNs) / 1_000_000L));
                row.put("httpStatus", httpStatus == null ? "unknown" : httpStatus);
                row.put("returnedCount", returnedCount);
                row.put("afterFilterCount", afterFilterCount);
                row.put("outcome", outcome);
                row.put("failureReason", failureReason);
                TraceStore.installContext(parent);
                TraceStore.append("web.tavily.attempt.runs", Map.copyOf(row));
            } catch (RuntimeException unavailableTraceSink) {
                // Observation availability cannot replace the existing provider outcome.
                return;
            } finally {
                TraceStore.installContext(previous);
            }
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private void traceProviderDisabled(String query, int requested, String reason) {
        traceBase(query, requested, 0, 0);
        String safeReason = SafeRedactor.traceLabelOrFallback(reason, "unknown");
        TraceStore.put("web.tavily.providerDisabled", true);
        TraceStore.put("web.tavily.disabledReason", safeReason);
        TraceStore.put("web.tavily.disabledReasonCanonical", safeReason);
        TraceStore.put("web.tavily.skipped", true);
        TraceStore.put("web.tavily.skipped.reason", safeReason);
        TraceStore.put("web.tavily.failureReason", "provider-disabled");
        TraceStore.put("web.tavily.zeroResults", true);
        TraceStore.put("web.tavily.providerEmpty", false);
        TraceStore.put("web.tavily.afterFilterStarved", false);
        TraceStore.put("web.tavily.timeout", false);
        TraceStore.put("web.tavily.cancelled", false);
        resetProviderHttpFailureFlags();
        traceCommonWebProviderCounts(query, 0, 0, true, safeReason, "provider-disabled");
    }

    private void traceCounts(String query, int requested, int returned, int afterFilter, String failureReason,
                             boolean providerEmpty) {
        traceBase(query, requested, returned, afterFilter);
        boolean zeroResults = afterFilter <= 0;
        boolean afterFilterStarved = returned > 0 && afterFilter <= 0;
        TraceStore.put("web.tavily.providerDisabled", false);
        TraceStore.put("web.tavily.zeroResults", zeroResults);
        TraceStore.put("web.tavily.providerEmpty", providerEmpty);
        TraceStore.put("web.tavily.afterFilterStarved", afterFilterStarved);
        TraceStore.put("web.tavily.timeout", false);
        TraceStore.put("web.tavily.cancelled", false);
        resetProviderHttpFailureFlags();
        String effectiveFailureReason;
        if (failureReason != null && !failureReason.isBlank()) {
            TraceStore.put("web.tavily.failureReason", SafeRedactor.traceLabelOrFallback(failureReason, "unknown"));
            effectiveFailureReason = String.valueOf(TraceStore.get("web.tavily.failureReason"));
        } else if (providerEmpty) {
            effectiveFailureReason = "provider-empty";
            TraceStore.put("web.tavily.failureReason", effectiveFailureReason);
        } else if (afterFilterStarved) {
            effectiveFailureReason = "after-filter-starvation";
            TraceStore.put("web.tavily.failureReason", effectiveFailureReason);
        } else {
            effectiveFailureReason = "";
            TraceStore.put("web.tavily.failureReason", effectiveFailureReason);
        }
        traceCommonWebProviderCounts(query, returned, afterFilter, false, null, effectiveFailureReason);
    }

    private void traceException(String query, int requested, Exception error, long tookMs) {
        traceBase(query, requested, 0, 0);
        String reason = tavilyFailureReason(error);
        String finalFailureReason = SafeRedactor.traceLabelOrFallback(reason, "unknown");
        boolean cancelled = "cancelled".equals(reason);
        TraceStore.put("web.tavily.providerDisabled", false);
        TraceStore.put("web.tavily.zeroResults", true);
        TraceStore.put("web.tavily.providerEmpty", false);
        TraceStore.put("web.tavily.afterFilterStarved", false);
        TraceStore.put("web.tavily.failureReason", finalFailureReason);
        TraceStore.put("web.tavily.exceptionType", tavilyErrorType(error));
        TraceStore.put("web.tavily.tookMs", Math.max(0L, tookMs));
        TraceStore.put("web.tavily.timeout", "timeout".equals(reason));
        TraceStore.put("web.tavily.cancelled", cancelled);
        resetProviderHttpFailureFlags();
        if (error instanceof WebClientResponseException webError) {
            int status = webError.getRawStatusCode();
            String body = webError.getResponseBodyAsString();
            TraceStore.put("web.tavily.httpStatus", status);
            TraceStore.put("web.tavily.429", status == 429);
            TraceStore.put("web.tavily.rateLimited", status == 429);
            if (status == 429) {
                TraceStore.put("web.rateLimited", true);
                TraceStore.put("web.tavily.failureReason", "rate-limit");
                finalFailureReason = "rate-limit";
                traceRemoteCooldown("rate-limit", retryAfterToMs(webError.getHeaders()));
            } else {
                TraceStore.put("web.tavily.failureReason", "http-error");
                finalFailureReason = "http-error";
            }
            TraceStore.put("web.tavily.errorBodyHash", SafeRedactor.hashValue(body));
            TraceStore.put("web.tavily.errorBodyLength", body == null ? 0 : body.length());
        }
        traceCommonWebProviderCounts(query, 0, 0, false, null, finalFailureReason);
    }

    private void traceBase(String query, int requested, int returned, int afterFilter) {
        TraceStore.put("web.tavily.requestedCount", Math.max(0, requested));
        TraceStore.put("web.tavily.returnedCount", Math.max(0, returned));
        TraceStore.put("web.tavily.afterFilterCount", Math.max(0, afterFilter));
        TraceStore.put("web.tavily.queryHash", SafeRedactor.hashValue(query));
        TraceStore.put("web.tavily.queryLength", query == null ? 0 : query.length());
        TraceStore.put("web.tavily.queryTokenBucket", queryTokenBucket(query));
        TraceStore.put("web.tavily.timeoutMs", Math.max(0, timeoutMs));
        TraceStore.put("web.tavily.endpointHost", endpointHost());
    }

    private static void traceCommonWebProviderCounts(String query,
                                                     int returned,
                                                     int after,
                                                     boolean providerDisabled,
                                                     String disabledReason,
                                                     String failureReason) {
        TraceStore.put("web.provider.name", "tavily");
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

    private static void resetProviderHttpFailureFlags() {
        TraceStore.put("web.tavily.httpStatus", null);
        TraceStore.put("web.tavily.429", false);
        TraceStore.put("web.tavily.rateLimited", false);
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

    private static void traceRemoteCooldown(String reason, long retryAfterMs) {
        long hintMs = Math.max(0L, retryAfterMs);
        TraceStore.put("web.tavily.cooldown.reason",
                SafeRedactor.traceLabelOrFallback(reason == null || reason.isBlank() ? "remote" : reason, "remote"));
        TraceStore.put("web.tavily.retryAfterMs", hintMs);
        TraceStore.put("web.tavily.cooldown.hintMs", hintMs);
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
            // Retry-After may also be an HTTP-date.
            TraceStore.put("web.tavily.suppressed.retryAfterSecondsParse", true);
            TraceStore.put("web.tavily.suppressed.retryAfterSecondsParse.errorType", "invalid_number");
            log.debug("[TavilyWebSearchRetriever] fail-soft stage={} errorType={}",
                    "retryAfter.seconds", "invalid_number");
        }
        try {
            long deltaMs = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant()
                    .toEpochMilli() - System.currentTimeMillis();
            return deltaMs <= 0L ? 0L : Math.min(60_000L, deltaMs);
        } catch (DateTimeParseException ignore) {
            TraceStore.put("web.tavily.suppressed.retryAfterDateParse", true);
            TraceStore.put("web.tavily.suppressed.retryAfterDateParse.errorType", "invalid_date");
            log.debug("[TavilyWebSearchRetriever] fail-soft stage={} errorType={}",
                    "retryAfter.httpDate", "invalid_date");
            return 0L;
        }
    }

    private String endpointHost() {
        try {
            URI uri = URI.create(baseUrl == null ? "" : baseUrl);
            return uri.getHost() == null ? "" : uri.getHost();
        } catch (Exception e) {
            log.debug("[TavilyWebSearchRetriever] fail-soft stage={}", "endpointHost");
            return "";
        }
    }

    private static boolean isTimeout(Throwable error) {
        Throwable cur = error;
        while (cur != null) {
            String name = cur.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
            String msg = cur.getMessage() == null ? "" : cur.getMessage().toLowerCase(java.util.Locale.ROOT);
            if (name.contains("timeout") || msg.contains("timeout")) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    private static boolean isCancelled(Throwable error) {
        Throwable cur = error;
        while (cur != null) {
            String name = cur.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
            String msg = cur.getMessage() == null ? "" : cur.getMessage().toLowerCase(java.util.Locale.ROOT);
            if (name.contains("cancel") || name.contains("interrupt")
                    || msg.contains("cancelled") || msg.contains("canceled") || msg.contains("interrupted")) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    private static String tavilyFailureReason(Throwable error) {
        if (isCancelled(error)) {
            return "cancelled";
        }
        if (isTimeout(error)) {
            return "timeout";
        }
        if (error instanceof WebClientResponseException webError) {
            return webError.getRawStatusCode() == 429 ? "rate-limit" : "http-error";
        }
        return "exception";
    }

    private static String tavilyErrorType(Throwable error) {
        if (isCancelled(error)) {
            return "cancelled";
        }
        return error == null ? "" : SafeRedactor.traceLabelOrFallback(error.getClass().getSimpleName(), "unknown");
    }
}
