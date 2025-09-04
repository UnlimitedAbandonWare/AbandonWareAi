package com.acme.aicore.adapters.search;

import com.acme.aicore.domain.model.SearchBundle;
import com.acme.aicore.domain.model.WebSearchQuery;
import com.acme.aicore.domain.ports.WebSearchProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Minimal stub of a Bing search provider.  In a real implementation this
 * provider would invoke the Bing API via WebClient and convert the response
 * into a {@link SearchBundle}.  The priority is set high so that this
 * provider is invoked first when fanning out.
 */
@Component
@lombok.RequiredArgsConstructor
public class BingSearchProvider implements WebSearchProvider {
    // Prefer the property key when defined; otherwise fall back to the environment variables
    // SEARCH_BING_API_KEY or BING_API_KEY.  Using SpEL avoids blank property values suppressing the fallback.
    @org.springframework.beans.factory.annotation.Value("#{ T(org.springframework.util.StringUtils).hasText('${search.bing.api-key:}') ? '${search.bing.api-key}' : ( T(org.springframework.util.StringUtils).hasText('${SEARCH_BING_API_KEY:}') ? '${SEARCH_BING_API_KEY}' : '${BING_API_KEY:}' ) }")
    private String apiKey;
    @org.springframework.beans.factory.annotation.Value("${search.bing.base-url:https://api.bing.microsoft.com/v7.0}")
    private String baseUrl;
    @org.springframework.beans.factory.annotation.Value("${search.bing.count:8}")
    private int count;
    @org.springframework.beans.factory.annotation.Value("${search.bing.timeout-ms:2000}")
    private int timeoutMs;

    private final org.springframework.web.reactive.function.client.WebClient.Builder http;

    @Override
    public String id() {
        return "bing";
    }

    @Override
    public Mono<SearchBundle> search(WebSearchQuery query) {
        // If no API key, return empty bundle.
        if (apiKey == null || apiKey.isBlank()) {
            return Mono.just(SearchBundle.empty());
        }
        String text = query.text();
        return http.baseUrl(baseUrl)
                .build()
                .get()
                .uri(uriBuilder -> uriBuilder
                        .path("/search")
                        .queryParam("q", text)
                        .queryParam("count", count)
                        .build())
                .header("Ocp-Apim-Subscription-Key", apiKey)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(java.time.Duration.ofMillis(timeoutMs))
                .onErrorResume(ex -> Mono.just(""))
                                .map(this::toBundleSafe);
    }

    @Override
    public int priority() {
        // Higher priority than Naver but lower than Brave
        return 10;
    }

    private SearchBundle toBundleSafe(String json) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var root = mapper.readTree(json == null ? "" : json);
            var arr  = root.path("webPages").path("value");
            java.util.List<SearchBundle.Doc> docs = new java.util.ArrayList<>();
            if (arr.isArray()) {
                for (var it : arr) {
                    String title = it.path("name").asText("");
                    String url   = it.path("url").asText("");
                    String snip  = it.path("snippet").asText("");
                    String date  = it.path("datePublished").asText(null);
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