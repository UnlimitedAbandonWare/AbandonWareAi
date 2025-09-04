package com.example.lms.gptsearch.web.impl;

import com.example.lms.gptsearch.web.AbstractWebSearchProvider;
import com.example.lms.gptsearch.web.ProviderId;
import com.example.lms.gptsearch.web.dto.WebDocument;
import com.example.lms.gptsearch.web.dto.WebSearchQuery;
import com.example.lms.gptsearch.web.dto.WebSearchResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Concrete {@link com.example.lms.gptsearch.web.WebSearchProvider} that performs
 * searches against Azure Cognitive Search.  When enabled via the
 * {@code websearch.azure.enabled} property this provider will POST
 * search queries to the configured endpoint and index using the
 * provided query key.  Responses are parsed into {@link WebDocument}
 * instances.  Missing configuration or any I/O failure results in
 * an empty {@link WebSearchResult} being returned so that other
 * providers may be consulted.  See the companion properties
 * {@code azure.search.endpoint}, {@code azure.search.index} and
 * {@code azure.search.query-key} for required configuration values.
 */
@Component
@ConditionalOnProperty(value = "websearch.azure.enabled", havingValue = "true", matchIfMissing = false)
public class AzureProvider extends AbstractWebSearchProvider {

    /** Endpoint base URL for Azure Cognitive Search (e.g. https://demo.search.windows.net). */
    @Value("${azure.search.endpoint:}")
    private String endpoint;

    /** Name of the Azure Cognitive Search index to query. */
    @Value("${azure.search.index:}")
    private String index;

    /** Query API key for Azure Cognitive Search.  Admin keys must not be used. */
    @Value("${azure.search.query-key:}")
    private String queryKey;

    /** Constant API version used when invoking the search API. */
    private static final String API_VERSION = "2024-07-01";

    /** HTTP client reused for all Azure search requests. */
    private final HttpClient httpClient = HttpClient.newHttpClient();

    /** Object mapper for parsing JSON responses. */
    private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

    @Override
    public ProviderId id() {
        return ProviderId.AZURE;
    }

    @Override
    protected WebSearchResult doSearch(WebSearchQuery query) throws Exception {
        String text = (query == null) ? null : query.getQuery();
        int topK  = (query == null) ? 0 : query.getTopK();
        // Fail fast when configuration or query text is absent
        if (endpoint == null || endpoint.isBlank() ||
            index == null || index.isBlank() ||
            queryKey == null || queryKey.isBlank() ||
            text == null || text.isBlank()) {
            return new WebSearchResult(id().name(), java.util.Collections.emptyList());
        }
        // Determine the number of documents to request.  Azure defaults to 50 when unspecified.
        int top = topK <= 0 ? 5 : Math.min(topK, 5);
        // Build the request body according to the Azure search API.
        Map<String, Object> payload = new HashMap<>();
        payload.put("search", text);
        payload.put("queryType", "simple");
        payload.put("top", top);
        // Request only selected fields to minimise response size
        payload.put("select", "title,url,content,updatedAt");
        String url = String.format("%s/indexes/%s/docs/search?api-version=%s", endpoint, index, API_VERSION);
        String jsonBody = mapper.writeValueAsString(payload);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("api-key", queryKey)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception ex) {
            // Return an empty result on any I/O failure
            return new WebSearchResult(id().name(), java.util.Collections.emptyList());
        }
        String body = (response == null) ? null : response.body();
        if (body == null || body.isBlank()) {
            return new WebSearchResult(id().name(), java.util.Collections.emptyList());
        }
        try {
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(body);
            com.fasterxml.jackson.databind.JsonNode value = root.path("value");
            List<WebDocument> docs = new ArrayList<>();
            if (value.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode item : value) {
                    String urlField   = item.path("url").asText(null);
                    String titleField = item.path("title").asText(null);
                    String content    = item.path("content").asText(null);
                    if (content != null && content.length() > 300) {
                        content = content.substring(0, 300);
                    }
                    java.time.Instant timestamp = null;
                    if (item.hasNonNull("updatedAt")) {
                        String ts = item.get("updatedAt").asText();
                        try {
                            timestamp = java.time.Instant.parse(ts);
                        } catch (Exception ignore) {
                            // Ignore parse failures
                        }
                    }
                    // Only include documents with a non-blank URL
                    if (urlField != null && !urlField.isBlank()) {
                        docs.add(new WebDocument(urlField, titleField, content, null, timestamp));
                    }
                }
            }
            return new WebSearchResult(id().name(), docs);
        } catch (Exception ex) {
            // On parse errors return an empty result
            return new WebSearchResult(id().name(), java.util.Collections.emptyList());
        }
    }
}