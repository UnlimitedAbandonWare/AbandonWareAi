package com.example.lms.service.web;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;

import com.example.lms.search.provider.WebSearchProvider;
import com.example.lms.service.NaverSearchService.SearchResult;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import com.google.common.util.concurrent.RateLimiter;
import org.springframework.web.client.HttpClientErrorException;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * Thin synchronous Brave search client kept for backwards compatibility with
 * existing LMS controllers. For new code prefer the reactive
 * {@code com.acme.aicore.adapters.search.BraveSearchProvider}.
 *
 * <p>Configuration resolution order for the API key:</p>
 * <ol>
 * <li>gpt-search.brave.api-key</li>
 * <li>search.brave.api-key (legacy)</li>
 * <li>GPT_SEARCH_BRAVE_API_KEY environment variable</li>
 * <li>BRAVE_API_KEY environment variable</li>
 * </ol>
 */
@Service
public class BraveSearchService implements WebSearchProvider {

    private static final Logger log = LoggerFactory.getLogger(BraveSearchService.class);

    // [Patch] Brave API 문서 기준: count 파라미터 최대값 20 (Stuff 0, 1, 2, 4 통합 의견 반영)
    private static final int BRAVE_MAX_TOPK = 20;

    @Value("${gpt-search.brave.api-key:${search.brave.api-key:${GPT_SEARCH_BRAVE_API_KEY:${BRAVE_API_KEY:}}}}")
    private String apiKey;

    @Value("${gpt-search.brave.base-url:${search.brave.base-url:https://api.search.brave.com/res/v1/web/search}}")
    private String baseUrl;

    @Value("${gpt-search.brave.enabled:${search.brave.enabled:true}}")
    private boolean configEnabled;

    @Value("${gpt-search.brave.timeout-ms:${search.brave.timeout-ms:3000}}")
    private int timeoutMs;

    private final RestTemplate restTemplate;
    private final BraveSearchProperties props;
    private final RateLimiter rateLimiter;
    private final AtomicInteger monthlyRemaining;
    private volatile LocalDate lastResetDate = LocalDate.now();
    private volatile boolean quotaExhausted = false;

    private boolean enabled;


        public BraveSearchService(BraveSearchProperties props) {
        this.props = props;
        this.restTemplate = new RestTemplate();
        this.rateLimiter = RateLimiter.create(Math.max(0.1, props.qpsLimit()));
        this.monthlyRemaining = new AtomicInteger(props.monthlyQuota());
    }

@PostConstruct
    void init() {
        if (!configEnabled) {
            enabled = false;
            log.info("[Brave] Disabled via configuration (gpt-search.brave.enabled=false)");
            return;
        }

        if (apiKey == null || apiKey.isBlank()) {
            enabled = false;
            log.warn("[Brave] API key missing - checked paths:");
            log.warn("[Brave]   1) gpt-search.brave.api-key");
            log.warn("[Brave]   2) search.brave.api-key (legacy)");
            log.warn("[Brave]   3) GPT_SEARCH_BRAVE_API_KEY env");
            log.warn("[Brave]   4) BRAVE_API_KEY env");
            log.warn("[Brave] Provider will be disabled until a key is configured.");
        } else {
            enabled = true;
            log.info("[Brave] API key loaded successfully");
            log.debug("[Brave] Config: baseUrl={}, timeout={}ms", baseUrl, timeoutMs);
            this.lastResetDate = LocalDate.now();
            int quota = props.monthlyQuota();
            if (quota > 0) {
                this.monthlyRemaining.set(quota);
                log.info("[Brave] Local monthly quota initialized to {}", quota);
            } else {
                log.info("[Brave] Local monthly quota disabled (monthly-quota <= 0)");
            }
        }
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Execute a Brave web search and return raw JSON snippets. The higher level
     * GPT search layer will take care of parsing and ranking.
     */
    @Override
    public List<String> search(String query, int topK) {
        return searchSnippets(query, topK);
    }

    public List<String> searchSnippets(String query, int limit) {
        if (query == null || query.isBlank()) {
            return Collections.emptyList();
        }

        // Gate 0: Brave 비활성 / 로컬 월 쿼터 소진
        if (!enabled || quotaExhausted) {
            log.debug("[Brave] Skipped: disabled or quota exhausted. query={}", query);
            return Collections.emptyList();
        }

        // Gate 1: 달 바뀌면 월 카운터 리셋
        LocalDate today = LocalDate.now();
        if (today.getYear() != lastResetDate.getYear()
                || !today.getMonth().equals(lastResetDate.getMonth())) {
            monthlyRemaining.set(props.monthlyQuota());
            quotaExhausted = false;
            lastResetDate = today;
        }

        // Gate 2: 로컬 월 쿼터
        if (props.monthlyQuota() > 0 && monthlyRemaining.get() <= 0) {
            quotaExhausted = true;
            log.warn("[Brave] Local monthly quota reached 0. Skip Brave.");
            return Collections.emptyList();
        }

        // Gate 3: QPS 레이트리미트
        if (!rateLimiter.tryAcquire(props.acquireTimeoutMs(), TimeUnit.MILLISECONDS)) {
            log.warn("[Brave] Rate limit hit ({} QPS). Fallback to other providers.", props.qpsLimit());
            return Collections.emptyList();
        }

// [FIX] Brave API: count 파라미터는 최대 20까지만 허용.
        // webTopK가 24처럼 들어와도 Brave에는 20으로 제한해서 422 오류를 방지한다.
        // {스터프4} 의견 반영: Math.min으로 강제 클램핑
        int requestedTopK = (limit > 0 ? limit : 5);
        int topK = Math.min(requestedTopK, BRAVE_MAX_TOPK);

        if (requestedTopK > BRAVE_MAX_TOPK) {
            log.debug("[Brave] Requested topK={} exceeds max {}; clamping to Brave limit.",
                    requestedTopK, BRAVE_MAX_TOPK);
        }

        try {
            // [PATCH] Brave 한글 쿼리 UTF-8 인코딩
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
            URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl)
                    .queryParam("q", encodedQuery)
                    .queryParam("count", topK)
                    .build(true)
                    .toUri();

            log.debug("[Brave] query='{}', encoded='{}', uri='{}'",
                    query, encodedQuery, uri);

            HttpHeaders headers = new HttpHeaders();
            headers.add("Accept", "application/json");
            headers.add("X-Subscription-Token", apiKey);

            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    uri,
                    HttpMethod.GET,
                    entity,
                    String.class
            );

            if (response != null) {
                updateQuotaFromHeaders(response.getHeaders());
            }

            if (response != null && response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                String body = response.getBody();
                try {
                    java.util.List<String> parsed = extractSnippetsFromJson(body, topK);
                    if (!parsed.isEmpty()) {
                        log.debug("[Brave] Parsed {} snippets from JSON", parsed.size());
                        return parsed;
                    }
                    log.warn("[Brave] JSON parsed but no snippets extracted. query={}", query);
                    return java.util.Collections.emptyList();
                } catch (Exception e) {
                    log.warn("[Brave] JSON parsing failed for query='{}': {}", query, e.getMessage());
                    return java.util.Collections.emptyList();
                }
            }
        } catch (HttpClientErrorException.TooManyRequests e) {
            log.error("[Brave] 429 Too Many Requests: {}", e.getMessage());
            int after = monthlyRemaining.decrementAndGet();
            if (after <= 0) {
                quotaExhausted = true;
            }
            return Collections.emptyList();
        } catch (Exception e) {
            log.warn("[Brave] Search failed: {}", e.getMessage());
            return Collections.emptyList();
        }

        return Collections.emptyList();
    }


    /**
     * Parse Brave Web Search JSON and extract human-readable snippets.
     * This keeps raw JSON out of the LLM context.
     */
        /**
     * Parse Brave Web Search JSON and extract human-readable snippets.
     * This keeps raw JSON out of the LLM context and is resilient to minor schema changes.
     */
    private java.util.List<String> extractSnippetsFromJson(String json, int topK) {
        java.util.List<String> snippets = new java.util.ArrayList<>();

        // Primary: Jackson parsing
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(json);
            com.fasterxml.jackson.databind.JsonNode results = root.path("web").path("results");

            if (results.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode node : results) {
                    if (snippets.size() >= topK) break;

                    String title = node.path("title").asText("");
                    String desc = node.path("description").asText("");
                    String url = node.path("url").asText("");

                    StringBuilder sb = new StringBuilder();
                    if (!title.isBlank()) {
                        sb.append(title.trim()).append("\n");
                    }
                    if (!desc.isBlank()) {
                        sb.append(desc.trim()).append("\n");
                    }
                    if (!url.isBlank()) {
                        sb.append("URL: ").append(url.trim());
                    }

                    String snippet = sb.toString().trim();
                    if (!snippet.isBlank()) {
                        snippets.add(snippet);
                    }
                }
            }

            log.debug("[Brave] Parsed {} snippets from JSON", snippets.size());
            return snippets;

        } catch (Exception e) {
            log.warn("[Brave] JSON parse failed, trying regex fallback: {}", e.getMessage());
        }

        // Fallback: Regex extraction on raw JSON
        try {
            Pattern pattern = Pattern.compile("\"description\"\\s*:\\s*\"(.*?)\"", Pattern.DOTALL);
            Matcher matcher = pattern.matcher(json);

            while (matcher.find() && snippets.size() < topK) {
                String desc = matcher.group(1)
                        .replace("\\n", " ")
                        .replace("\\\"", "\"");
                if (!desc.isBlank()) {
                    snippets.add(desc);
                }
            }

            if (!snippets.isEmpty()) {
                log.debug("[Brave] Regex fallback extracted {} snippets", snippets.size());
                return snippets;
            }
        } catch (Exception e) {
            log.error("[Brave] Regex fallback failed", e);
        }

        // Ultimate fallback: empty list (never return raw JSON as snippet)
        return java.util.Collections.emptyList();
    }


    private void updateQuotaFromHeaders(HttpHeaders headers) {
    if (props.monthlyQuota() <= 0) {
        return;
    }
    if (headers == null) {
        int after = monthlyRemaining.decrementAndGet();
        if (after <= 0) {
            quotaExhausted = true;
        }
        return;
    }

    String remainingHeader = headers.getFirst("X-RateLimit-Remaining");
    if (remainingHeader != null) {
        try {
            String[] parts = remainingHeader.split(",");
            String candidate = parts[parts.length - 1].trim();
            int remaining = Integer.parseInt(candidate);
            monthlyRemaining.set(remaining);
            if (remaining <= 0) {
                quotaExhausted = true;
            }
            if (remaining >= 0 && remaining < 100) {
                log.warn("[Brave] Rate limit low ({}), consider routing to Naver/Tavily", remaining);
            }
        } catch (NumberFormatException e) {
            log.warn("[Brave] Failed to parse X-RateLimit-Remaining: {}", remainingHeader);
        }
        return;
    }

    // 헤더에 남은 쿼터 정보가 없으면 보수적으로 1 감소
    int after = monthlyRemaining.decrementAndGet();
    if (after <= 0) {
        quotaExhausted = true;
    }
}


    @Override
    public SearchResult searchWithTrace(String query, int topK) {
        List<String> snippets = search(query, topK);
        return new SearchResult(snippets, null);
    }

    @Override
    public String getName() {
        return "Brave";
    }

}