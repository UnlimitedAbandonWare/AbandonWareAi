package com.example.lms.gptapi.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;

/**
 * Simple GPT API client wrapper.
 *
 * <p>This class acts as a thin abstraction over whatever HTTP client is
 * available in the environment.  It exposes a single method for
 * performing POST requests against the configured GPT API base URL.  The
 * implementation here is deliberately minimal – calls are logged and no
 * outbound network requests are actually made.  In a full implementation
 * you would inject a WebClient or RestTemplate and perform the call
 * asynchronously with retries.</p>
 */
@Slf4j
@Component
public class GptApiClient {

    @Value("${gptapi.base-url:}")
    private String baseUrl;

    @Value("${gptapi.api-key:}")
    private String apiKey;

    /**
     * Execute a POST request against the GPT API.  In this stub
     * implementation the method simply logs the request parameters and
     * returns an empty map.
     *
     * @param path   the relative API path, e.g. "/v1/search"
     * @param params request body parameters
     * @return a map representing the JSON response (empty for now)
     */
    public Map<String, Object> post(String path, Map<String, Object> params) {
        String url = (baseUrl == null ? "" : baseUrl) + path;
        log.debug("GPT API call to {} with params {}", url, params);
        // In a production implementation, you would perform the HTTP
        // request here using a reactive or blocking HTTP client and
        // deserialize the response.  Because this environment cannot
        // perform outbound network calls, we return an empty response.
        return Collections.emptyMap();
    }
}