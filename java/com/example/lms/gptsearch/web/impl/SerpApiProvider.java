package com.example.lms.gptsearch.web.impl;

import com.example.lms.gptsearch.web.AbstractWebSearchProvider;
import com.example.lms.gptsearch.web.ProviderId;
import com.example.lms.gptsearch.web.dto.WebDocument;
import com.example.lms.gptsearch.web.dto.WebSearchQuery;
import com.example.lms.gptsearch.web.dto.WebSearchResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
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

    @Value("${gpt-search.serpapi.api-key:${search.serpapi.api-key:${GPT_SEARCH_SERPAPI_API_KEY:${SERPAPI_API_KEY:}}}}")
    private String apiKey;

    @Value("${gpt-search.serpapi.base-url:${search.serpapi.base-url:https://serpapi.com/search.json}}")
    private String baseUrl;

    @Value("${gpt-search.serpapi.timeout-ms:${search.serpapi.timeout-ms:3000}}")
    private int timeoutMs;

    @Value("${gpt-search.serpapi.enabled:${search.serpapi.enabled:true}}")
    private boolean configEnabled;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private boolean enabled;

    @PostConstruct
    void init() {
        if (!configEnabled) {
            enabled = false;
            log.info("[SerpApi] Disabled via configuration (gpt-search.serpapi.enabled=false)");
            return;
        }

        if (apiKey == null || apiKey.isBlank()) {
            enabled = false;
            log.warn("[SerpApi] API key missing - checked paths:");
            log.warn("[SerpApi]   1) gpt-search.serpapi.api-key");
            log.warn("[SerpApi]   2) search.serpapi.api-key (legacy)");
            log.warn("[SerpApi]   3) GPT_SEARCH_SERPAPI_API_KEY env");
            log.warn("[SerpApi]   4) SERPAPI_API_KEY env");
            log.warn("[SerpApi] Provider will be disabled until a key is configured.");
        } else {
            enabled = true;
            log.info("[SerpApi] API key loaded successfully");
            log.debug("[SerpApi] Config: baseUrl={}, timeout={}ms", baseUrl, timeoutMs);

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
                log.warn("[SerpApi] Failed to configure RestTemplate timeouts: {}", e.getMessage());
            }
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public ProviderId id() {
        return ProviderId.SERPAPI;
    }

    @Override
    protected WebSearchResult doSearch(WebSearchQuery query) throws Exception {
        if (!enabled) {
            log.debug("[SerpApi] Skipping search - provider disabled");
            return new WebSearchResult(id().name(), Collections.emptyList());
        }

        if (query == null || query.getQuery() == null || query.getQuery().isBlank()) {
            return new WebSearchResult(id().name(), Collections.emptyList());
        }

        int topK = query.getTopK() > 0 ? query.getTopK() : 5;

        URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .queryParam("engine", "google")
                .queryParam("api_key", apiKey)
                .queryParam("q", query.getQuery())
                .queryParam("num", topK)
                .build(true)
                .toUri();

        String body = restTemplate.getForObject(uri, String.class);
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
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("[SerpApi] Parse error {}", e.getMessage());
            }
        }

        return new WebSearchResult(id().name(), docs);
    }

    private static String safeText(JsonNode node, String field) {
        if (node == null) {
            return "";
        }
        JsonNode child = node.get(field);
        return child != null && !child.isNull() ? child.asText("") : "";
    }
}
