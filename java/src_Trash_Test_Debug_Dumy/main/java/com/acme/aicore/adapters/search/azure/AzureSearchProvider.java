package com.acme.aicore.adapters.search.azure;

import com.acme.aicore.domain.model.SearchBundle;
import com.acme.aicore.domain.model.WebSearchQuery;
import com.acme.aicore.domain.ports.WebSearchProvider;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Web search provider backed by Azure Cognitive Search.  This provider is
 * conditionally enabled when valid credentials are present in the
 * {@link SecretKeyRegistry}.  It performs a simple heuristic to decide
 * whether to invoke Azure based on the query text: only queries that
 * mention Azure‑related terms (e.g. "azure", "애저", "무료", "12개월",
 * "B1s", "Blob", "LRS", "S0") will be sent to the service.  All other
 * queries short‑circuit by returning an empty bundle.  When invoked the
 * provider issues a POST to the search endpoint using the query key and
 * converts the response into a {@link SearchBundle}.  Failures are
 * swallowed, returning an empty bundle in lieu of error propagation.
 */
@Slf4j
// Register this provider only when Azure search is enabled via
// feature.search.azure.enabled=true.  Prevents fallback noise when no keys
// are provided and ensures explicit opt-in for this provider.
@ConditionalOnProperty(value = "feature.search.azure.enabled", havingValue = "true", matchIfMissing = false)
@Component
@RequiredArgsConstructor
public class AzureSearchProvider implements WebSearchProvider {

    private final AzureSearchClient client;
    private static final String API_VERSION = "2024-07-01";

    @Override
    public String id() {
        return "azure";
    }

    @Override
    public Mono<SearchBundle> search(WebSearchQuery query) {
        // If secrets are not loaded or query is null, short circuit
        if (!SecretKeyRegistry.isLoaded() || query == null || query.text() == null) {
            return Mono.just(SearchBundle.empty());
        }
        String text = query.text().trim();
        if (!shouldSearch(text)) {
            return Mono.just(SearchBundle.empty());
        }
        return Mono.fromCallable(() -> {
            Map<String, Object> body = new HashMap<>();
            body.put("search", text);
            body.put("queryType", "simple");
            body.put("top", 10);
            body.put("includeTotalResultCount", false);
            JsonNode resp = client.search(
                    SecretKeyRegistry.getEndpoint(),
                    SecretKeyRegistry.getIndex(),
                    SecretKeyRegistry.getQueryKey(),
                    API_VERSION,
                    body
            );
            List<SearchBundle.Doc> docs = AzureSearchMapper.toDocuments(resp);
            if (docs == null || docs.isEmpty()) {
                return SearchBundle.empty();
            }
            return new SearchBundle("web", docs);
        }).onErrorResume(ex -> {
            log.warn("[AzureSearchProvider] 검색 실패 – 패스: {}", ex.toString());
            return Mono.just(SearchBundle.empty());
        });
    }

    /**
     * Determine whether the given query text warrants invoking Azure Search.
     * Simple substring checks are used; this method may be adjusted to use
     * more sophisticated heuristics if necessary.
     *
     * @param q query text
     * @return true when the text contains Azure‑related triggers
     */
    private boolean shouldSearch(String q) {
        if (q == null) return false;
        String lower = q.toLowerCase();
        return lower.contains("azure") || lower.contains("애저") || lower.contains("free") || lower.contains("무료")
               || lower.contains("12개월") || lower.contains("12 month") || lower.contains("b1s")
               || lower.contains("blob") || lower.contains("lrs") || lower.contains("s0");
    }

    @Override
    public int priority() {
        // Slightly lower number than Bing to be considered earlier in fan‑out.
        return 7;
    }
}