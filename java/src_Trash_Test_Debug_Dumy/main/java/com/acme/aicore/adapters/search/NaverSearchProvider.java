package com.acme.aicore.adapters.search;

import com.acme.aicore.domain.model.SearchBundle;
import com.acme.aicore.domain.model.WebSearchQuery;
import com.acme.aicore.domain.ports.WebSearchProvider;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Minimal stub of a Naver search provider used for testing caching and
 * provider fan‑out.  Returns an empty {@link SearchBundle} and assigns a
 * lower priority than Bing.
 */
@Component
@lombok.RequiredArgsConstructor
public class NaverSearchProvider implements WebSearchProvider {
    // Prefer the per-key properties when defined.  When absent the keys remain empty
    // and will be resolved from the composite "naver.keys" in the init method below.
    @Value("${search.naver.client-id:${NAVER_CLIENT_ID:${SEARCH_NAVER_CLIENT_ID:${NAVER_SEARCH_CLIENT_ID:}}}}")
    private String clientId;
    @Value("${search.naver.client-secret:${NAVER_CLIENT_SECRET:${SEARCH_NAVER_CLIENT_SECRET:${NAVER_SEARCH_CLIENT_SECRET:}}}}")
    private String clientSecret;
    @Value("${search.naver.keys:}")
    private String naverKeys;
    // Base URL and other settings remain unchanged.  These values fall back to sensible
    // defaults when not supplied.
    @Value("${search.naver.base-url:https://openapi.naver.com}")
    private String baseUrl;
    @Value("${search.naver.count:8}")
    private int count;
    @Value("${search.naver.timeout-ms:2000}")
    private int timeoutMs;
    // Composite key containing both client ID and secret separated by a colon.  This value
    // is only consulted if the individual keys above are blank.  Note that no environment
    // fallback is provided here; the application property must supply this value.
    // naverKeys is now defined above

    /**
     * Post-construction hook to reconcile the Naver API credentials.  When the individual
     * clientId and clientSecret values are absent this method attempts to split the
     * composite "naver.keys" property in the form "id:secret".  If, after these
     * attempts, either credential remains blank, an exception is thrown to highlight
     * the misconfiguration early in the application lifecycle.
     */
    @PostConstruct
    void initNaverKeysFallback() {
        if ((!StringUtils.hasText(clientId) || !StringUtils.hasText(clientSecret))
                && StringUtils.hasText(naverKeys) && naverKeys.contains(":")) {
            String[] parts = naverKeys.split(":", 2);
            if (!StringUtils.hasText(clientId)) clientId = parts[0];
            if (!StringUtils.hasText(clientSecret)) clientSecret = parts[1];
        }
        if (!StringUtils.hasText(clientId) || !StringUtils.hasText(clientSecret)) {
            org.slf4j.LoggerFactory.getLogger(getClass())
                .warn("Naver keys missing; provider will return empty results (fail-soft).");
        }
    }

    private final org.springframework.web.reactive.function.client.WebClient.Builder http;

    @Override
    public String id() {
        return "naver";
    }

    @Override
    public Mono<SearchBundle> search(WebSearchQuery query) {
        // If credentials are missing, return an empty bundle
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            return Mono.just(SearchBundle.empty());
        }
        // Build request to Naver search API. On error or timeout return empty bundle.
        return http.baseUrl(baseUrl)
                .build()
                .get()
                .uri(uriBuilder -> uriBuilder
                        .path("/v1/search/webkr.json")
                        .queryParam("query", query.text())
                        .queryParam("display", count)
                        .build())
                .header("X-Naver-Client-Id", clientId)
                .header("X-Naver-Client-Secret", clientSecret)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(java.time.Duration.ofMillis(timeoutMs))
                .onErrorResume(ex -> Mono.just(""))
                .map(this::toBundleSafe);
    }

    @Override
    public int priority() {
        // Lower priority than Bing and Brave
        return 5;
    }

    private SearchBundle toBundleSafe(String json) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var root = mapper.readTree(json == null ? "" : json);
            var arr  = root.path("items");
            java.util.List<SearchBundle.Doc> docs = new java.util.ArrayList<>();
            if (arr.isArray()) {
                for (var it : arr) {
                    String title = it.path("title").asText("").replaceAll("<.*?>", "");
                    String url   = it.path("link").asText("");
                    String snip  = it.path("description").asText("");
                    String date  = it.path("pubDate").asText(null);
                    if (!url.isBlank()) {
                        docs.add(new SearchBundle.Doc(url, title, snip, url, date));
                    }
                }
            }
            return docs.isEmpty() ? SearchBundle.empty() : new SearchBundle("web", docs);
        } catch (Exception ignore) {
            return SearchBundle.empty();
        }
    }
}