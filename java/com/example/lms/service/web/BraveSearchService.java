package com.example.lms.service.web;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;



/**
 * Brave Search API (무료 플랜 초당 1QPS 제한) – NAVER 결과 부족 시 보강/폴백용.
 * 헤더: X-Subscription-Token: {API_KEY}
 */
@Service
@RequiredArgsConstructor
public class BraveSearchService {
    private static final Logger log = LoggerFactory.getLogger(BraveSearchService.class);

    /**
     * Spring will inject a WebClient instance.  This client is used to perform
     * HTTP requests against the Brave search API.  A dedicated WebClient
     * instance is not strictly necessary because the full URI is passed to
     * {@link WebClient#uri(String)}, overriding any configured base URL on
     * the injected client.  Should you wish to customise timeouts or other
     * connection settings for Brave specifically, define a separate
     * WebClient bean and annotate this field with @Qualifier.
     */
    private final WebClient webClient;

    // Allow both `search.brave.*` and legacy `brave.*` prefixes by falling back
    // to the latter when the former is unset.  This ensures existing
    // configuration using the shorter namespace continues to work.
    @Value("${search.brave.enabled:${brave.enabled:true}}")
    private boolean enabled;

    @Value("${search.brave.api-key:${brave.api-key:}}")
    private String apiKey;

    @Value("${search.brave.base-url:${brave.base-url:https://api.search.brave.com}}")
    private String baseUrl;

    @Value("${search.brave.qps:${brave.qps:1}}")
    private int qps;

    // Simple semaphore to enforce a 1 QPS rate limit.  If multiple calls are
    // attempted concurrently only the first will proceed; others will return
    // empty results.
    private final Semaphore permits = new Semaphore(1, true);

    /**
     * Perform a search against the Brave search API and return a list of
     * snippets (title + description) for the top results.  If the service is
     * disabled or not configured with an API key then an empty list will be
     * returned immediately.  Any exceptions during the call are logged and
     * result in an empty list.
     *
     * @param query the search query
     * @param topK  the maximum number of results to return
     * @return a list of result snippets
     */
    public List<String> searchSnippets(String query, int topK) {
        if (!enabled || apiKey == null || apiKey.isBlank()) {
            return List.of();
        }
        // Enforce QPS limit.  If acquisition fails (e.g. another call is in
        // progress) return an empty list to avoid exceeding the free tier quota.
        if (!permits.tryAcquire()) {
            return List.of();
        }
        try {
            // Construct the full request URL.  Brave expects a query string
            // parameter `q` and supports a `count` parameter to limit the
            // number of results.  URL encode the query to handle spaces and
            // other reserved characters.
            String path = "/res/v1/search";
            String url = baseUrl + path + "?q=" + urlEncode(query) + "&count=" + Math.max(1, topK);
            String body = webClient.get()
                    .uri(url)
                    .header("X-Subscription-Token", apiKey)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(8))
                    .onErrorReturn("")
                    .block();
            if (body == null || body.isBlank()) {
                return List.of();
            }
            JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
            // web.results[].{title, description, url}
            JsonNode results = root.path("web").path("results");
            if (!results.isArray()) {
                return List.of();
            }
            return toSnippets(results, topK);
        } catch (Exception e) {
            log.warn("Brave search failed", e);
            return List.of();
        } finally {
            permits.release();
        }
    }

    /**
     * Convert the raw Brave search results into display snippets.  The
     * returned list is capped at five entries and each snippet is truncated
     * to a maximum of 300 characters.  When a title and description are
     * available they are concatenated with an em dash; otherwise the URL
     * alone is returned.  Null or blank entries are removed from the
     * resulting list.
     *
     * @param arr  JSON array of result objects
     * @param topK caller supplied maximum number of results (ignored once > 5)
     * @return up to five summarised snippets
     */
    private List<String> toSnippets(JsonNode arr, int topK) {
        return com.google.common.collect.Streams.stream(arr.iterator())
                .limit(Math.min(5, Math.max(1, topK)))
                .map(n -> {
                    String title = n.path("title").asText("");
                    String desc = n.path("description").asText("");
                    String url = n.path("url").asText("");
                    String core = (title + " — " + desc).trim();
                    String snippet = core.isBlank() ? url : core;
                    // summarise to 300 characters to avoid excessively long snippets
                    return summarize(snippet, 300);
                })
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.toList());
    }

    /**
     * Summarise a string by truncating to the specified maximum length.  When
     * the string is shorter than or equal to the limit it is returned
     * unchanged.  Otherwise the substring of the first {@code max} characters
     * followed by an ellipsis is returned.
     *
     * @param s   the string to summarise
     * @param max maximum number of characters to retain
     * @return the summarised string
     */
    private static String summarize(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.replaceAll("\\s+", " ").trim();
        if (t.length() <= max) {
            return t;
        }
        return t.substring(0, max) + " /* ... *&#47;";
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }
}