package com.example.lms.api;

import com.acme.aicore.domain.ports.WebSearchProvider;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Diagnostic controller exposing the current web search configuration.
 * Returns a JSON structure indicating whether Brave and Naver search are
 * configured with API keys and which provider is currently active.
 */
@RestController
@RequestMapping("/api/diag")
public class DiagController {
    /**
     * All registered web search providers.  Injecting the interface rather
     * than specific implementations allows this controller to operate
     * even when individual providers are disabled or missing.  The list
     * may be empty when no providers are configured.
     */
    private final java.util.List<WebSearchProvider> providers;
    // Fanout search service for quorum-based web search; used here to expose circuit state and other metrics
    private final com.example.lms.gptsearch.web.FanoutQuorumSearchService fanout;
    private final Environment env;

    public DiagController(java.util.List<WebSearchProvider> providers,
                          Environment env,
                          com.example.lms.gptsearch.web.FanoutQuorumSearchService fanout) {
        this.providers = (providers == null) ? java.util.Collections.emptyList() : providers;
        this.env = env;
        this.fanout = fanout;
    }

    @GetMapping("/websearch")
    public Map<String, Object> websearch() {
        String provider = env.getProperty("llm.provider", "openai");
        java.util.Map<String, Object> info = new java.util.LinkedHashMap<>();
        for (WebSearchProvider p : providers) {
            if (p == null) continue;
            String id = p.id();
            boolean configured;
            if (id == null) {
                configured = false;
            } else if ("brave".equalsIgnoreCase(id)) {
                configured = hasAny(
                        "search.brave.api-key", "brave.api.key",
                        "BRAVE_API_KEY", "SEARCH_BRAVE_API_KEY", "BRAVE_SEARCH_API_KEY"
                );
            } else if ("naver".equalsIgnoreCase(id)) {
                configured = hasAny(
                        "search.naver.client-id", "NAVER_CLIENT_ID", "SEARCH_NAVER_CLIENT_ID", "NAVER_SEARCH_CLIENT_ID"
                ) && hasAny(
                        "search.naver.client-secret", "NAVER_CLIENT_SECRET", "SEARCH_NAVER_CLIENT_SECRET", "NAVER_SEARCH_CLIENT_SECRET"
                );
            } else if ("azure".equalsIgnoreCase(id)) {
                configured = hasAny(
                        "azure.search.endpoint", "AZURE_SEARCH_ENDPOINT"
                ) && hasAny(
                        "azure.search.index", "AZURE_SEARCH_INDEX"
                ) && hasAny(
                        "azure.search.query-key", "AZURE_SEARCH_QUERY_KEY"
                );
            } else {
                // Unknown providers are assumed configured by default
                configured = true;
            }
            info.put(id, java.util.Map.of("configured", configured));
        }
        // Include additional configuration settings such as Azure enablement and fanout/circuit metrics
        java.util.Map<String,Object> fanoutSnapshot = null;
        try {
            fanoutSnapshot = (fanout == null) ? null : fanout.snapshot();
        } catch (Exception ignore) {
            fanoutSnapshot = null;
        }
        return java.util.Map.of(
                "provider", provider,
                "azure.enabled", env.getProperty("websearch.azure.enabled", "false"),
                "providers", info,
                "fanout", fanoutSnapshot
        );
    }

    private boolean hasAny(String... keys) {
        for (String k : keys) {
            String v = env.getProperty(k);
            if (v != null && !v.isBlank()) {
                return true;
            }
        }
        return false;
    }
}