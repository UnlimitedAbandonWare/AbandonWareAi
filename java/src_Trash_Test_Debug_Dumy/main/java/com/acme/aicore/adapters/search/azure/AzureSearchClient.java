package com.acme.aicore.adapters.search.azure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

/**
 * Thin wrapper over {@link java.net.http.HttpClient} for invoking the Azure
 * Cognitive Search REST API.  This client is intentionally minimal and
 * introduces no additional dependencies.  It exposes convenience methods
 * for executing search queries and indexing operations.  The caller is
 * responsible for providing valid endpoints, index names, API keys and
 * request payloads.  Errors are propagated as exceptions to allow
 * fail‑soft handling by higher layers.
 */
import org.springframework.stereotype.Component;

@Component
public class AzureSearchClient {

    /** Constant API version used when calling the search and index APIs. */
    private static final String DEFAULT_API_VERSION = "2024-07-01";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public AzureSearchClient() {
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Execute a search against an Azure Cognitive Search index.
     *
     * @param endpoint   full base URL of the search service (e.g. https://demo.search.windows.net)
     * @param index      name of the index to query
     * @param queryKey   query API key; must not be the admin key
     * @param apiVersion API version; when null or blank defaults to {@link #DEFAULT_API_VERSION}
     * @param request    request body encoded as a map; must contain at least a "search" field
     * @return parsed JSON response
     * @throws IOException if the request fails or cannot be parsed
     * @throws InterruptedException if the thread is interrupted during I/O
     */
    public JsonNode search(String endpoint,
                           String index,
                           String queryKey,
                           String apiVersion,
                           Map<String, Object> request)
            throws IOException, InterruptedException {
        if (endpoint == null || index == null || queryKey == null || request == null) {
            throw new IllegalArgumentException("Endpoint, index, queryKey and request must be non-null");
        }
        String version = (apiVersion == null || apiVersion.isBlank()) ? DEFAULT_API_VERSION : apiVersion;
        String uri = String.format("%s/indexes/%s/docs/search?api-version=%s", endpoint, index, version);
        String body = objectMapper.writeValueAsString(request);
        HttpRequest httpReq = HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .header("Content-Type", "application/json")
                .header("api-key", queryKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = httpClient.send(httpReq, HttpResponse.BodyHandlers.ofString());
        return objectMapper.readTree(resp.body());
    }

    /**
     * Upload (merge or upload) documents into an Azure Cognitive Search index.  This
     * method should be used for idempotent indexing operations.  When the
     * Desktop Gate is not enabled this method will still construct the
     * request but callers should guard invocation externally.
     *
     * @param endpoint   full base URL of the search service
     * @param index      target index name
     * @param adminKey   admin API key granting write access
     * @param apiVersion API version; when null or blank defaults to {@link #DEFAULT_API_VERSION}
     * @param documents  list of documents; each must include an "@search.action" field
     * @throws IOException if the request fails
     * @throws InterruptedException if the thread is interrupted during I/O
     */
    public void uploadDocuments(String endpoint,
                                String index,
                                String adminKey,
                                String apiVersion,
                                List<Map<String, Object>> documents)
            throws IOException, InterruptedException {
        if (endpoint == null || index == null || adminKey == null || documents == null) {
            throw new IllegalArgumentException("Endpoint, index, adminKey and documents must be non-null");
        }
        String version = (apiVersion == null || apiVersion.isBlank()) ? DEFAULT_API_VERSION : apiVersion;
        String uri = String.format("%s/indexes/%s/docs/index?api-version=%s", endpoint, index, version);
        Map<String, Object> payload = Map.of("value", documents);
        String body = objectMapper.writeValueAsString(payload);
        HttpRequest httpReq = HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .header("Content-Type", "application/json")
                .header("api-key", adminKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        httpClient.send(httpReq, HttpResponse.BodyHandlers.discarding());
    }
}