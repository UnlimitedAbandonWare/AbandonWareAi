package com.example.lms.service.web;

import com.example.lms.config.ConfigValueGuards;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.LogCorrelation;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
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
import org.springframework.cache.annotation.Cacheable;

/**
 * Thin synchronous Brave search client kept for backwards compatibility with
 * existing LMS controllers. For new code prefer the reactive
 * {@code com.acme.aicore.adapters.search.BraveSearchProvider}.
 *
 * <p>
 * Configuration resolution order for the API key:
 * </p>
 * <ol>
 * <li>gpt-search.brave.api-key</li>
 * <li>search.brave.api-key (legacy)</li>
 * <li>GPT_SEARCH_BRAVE_API_KEY environment variable</li>
 * <li>BRAVE_API_KEY environment variable</li>
 * </ol>
 */
@Service
public class BraveSearchService implements WebSearchProvider {

    // Normalize search query to avoid URI encoding issues (control chars, extra spaces, overly long queries)
    private static String sanitizeQuery(String q) {
        if (q == null) return "";
        String t = q.replaceAll("\\p{Cntrl}", " ").trim();
        t = t.replaceAll("\\s{2,}", " ");
        if (t.length() > 200) t = t.substring(0, 200);
        return t;
    }

    private static final Logger log = LoggerFactory.getLogger(BraveSearchService.class);
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

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
    private final double effectiveQpsLimit;
    private final AtomicInteger monthlyRemaining;
    private volatile LocalDate lastResetDate = LocalDate.now();
    private volatile boolean quotaExhausted = false;

    // [Fix] 429 fail-soft + cool-down to prevent request storms.
    private static final long DEFAULT_429_COOLDOWN_MS = 2000L;
    private static final long MAX_429_COOLDOWN_MS = 30_000L;
    private final java.util.concurrent.atomic.AtomicLong cooldownUntilEpochMs = new java.util.concurrent.atomic.AtomicLong(
            0L);

    private boolean enabled;
    private volatile String disabledReason = "";

    public BraveSearchService(BraveSearchProperties props) {
        this.props = props;
        this.restTemplate = new RestTemplate();
        // Brave free-tier commonly enforces 1 QPS. Clamp configured QPS to a safer
        // ceiling.
        double configuredQps = props.qpsLimit();
        double effectiveQps = Math.min(Math.max(0.1, configuredQps), 0.8d);
        if (configuredQps > effectiveQps) {
            log.info("[Brave] qpsLimit clamped from {} to {} to respect provider rate limits", configuredQps,
                    effectiveQps);
        }
        this.rateLimiter = RateLimiter.create(effectiveQps);
        this.effectiveQpsLimit = effectiveQps;
        this.monthlyRemaining = new AtomicInteger(props.monthlyQuota());
    }

    @PostConstruct
    void init() {
        if (!configEnabled) {
            enabled = false;
            disabledReason = "disabled_by_config";
            log.info("[ProviderGuard] Brave disabled (config flag off){}", LogCorrelation.suffix());
            return;
        }

        if (ConfigValueGuards.isMissing(apiKey)) {
            enabled = false;
            disabledReason = "missing_api_key";
            // One-line, grep-friendly message (do NOT proceed with blank token).
            log.warn("[ProviderGuard] Brave: 키 없음으로 disable (missing api key){}", LogCorrelation.suffix());
        } else {
            enabled = true;
            disabledReason = "";
            log.info("[Brave] API key loaded successfully");
            log.debug("[Brave] Config: baseUrl={}, timeout={}ms", baseUrl, timeoutMs);

            // [FIX-E1] Timeout (connect/read) + fail-soft 안정화
            // - RestTemplate 기본 설정은 무기한 대기 가능 → cancel/connection leak 패턴 유발
            // - 최소 4초는 보장(짧은 timeout으로 인한 빈 결과 폭발 방지)
            try {
                int t = (int) Math.max(timeoutMs, 4000L);
                SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
                rf.setConnectTimeout(t);
                rf.setReadTimeout(t);
                this.restTemplate.setRequestFactory(rf);
                log.info("[Brave] RestTemplate timeouts set: {}ms (connect/read)", t);
            } catch (Exception e) {
                log.warn("[Brave] Failed to configure RestTemplate timeouts: {}", e.getMessage());
            }

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
     * Returns the last known disable reason (best-effort).
     */
    public String disabledReason() {
        return disabledReason;
    }

    /**
     * Expose Brave cooldown state for upstream orchestration.
     * <p>
     * When Brave is rate-limited (HTTP 429), this service enters a cooldown window
     * and will short-circuit searches to empty. Upstream components may prefer to
     * skip scheduling Brave calls entirely to reduce thread & connection churn.
     */
    public boolean isCoolingDown() {
        long now = System.currentTimeMillis();
        return now < cooldownUntilEpochMs.get();
    }

    /** Remaining cooldown time (ms). Returns 0 if not cooling down. */
    public long cooldownRemainingMs() {
        long now = System.currentTimeMillis();
        long until = cooldownUntilEpochMs.get();
        return Math.max(0L, until - now);
    }

    /**
     * Execute a Brave web search and return raw JSON snippets. The higher level
     * GPT search layer will take care of parsing and ranking.
     */
    @Override
    @Cacheable(value = "webSearchCache", key = "#query + '-' + #topK", sync = true)
    public List<String> search(String query, int topK) {
        return searchSnippets(query, topK);
    }

    /**
     * Brave 검색을 메타 정보와 함께 수행한다.
     * - 기존 search()는 하위 호환을 위해 snippets만 반환 (cacheable)
     * - 이 메서드는 "0건"이 "실패"인지 "정상 0건"인지 구분 가능하도록 status를 포함한다.
     */
    public BraveSearchResult searchWithMeta(String query, int limit) {
        long t0 = System.nanoTime();
        return searchWithMetaInternal(query, limit, t0);
    }

    private BraveSearchResult searchWithMetaInternal(String query, int limit, long t0Ns) {
        String safeQuery = sanitizeQuery(query);
        if (safeQuery.isBlank()) {
            // Downstream safety pin: explicit skip tag (do NOT call providers).
            logSkipOnce("SKIP_EMPTY_QUERY", "blank_query");
            long elapsedMs = (System.nanoTime() - t0Ns) / 1_000_000L;
            return new BraveSearchResult(java.util.List.of(), BraveSearchResult.Status.OK, 200, 0L, "blank query",
                    elapsedMs);
        }

        // Gate -1: cool-down after 429 to avoid retry storms.
        long now = System.currentTimeMillis();
        long cooldownUntil = cooldownUntilEpochMs.get();
        if (now < cooldownUntil) {
            long elapsedMs = (System.nanoTime() - t0Ns) / 1_000_000L;
            return BraveSearchResult.cooldown(cooldownUntil - now, elapsedMs);
        }

        // Gate 0: Brave 비활성 / 로컬 월 쿼터 소진
        if (!enabled || quotaExhausted) {
            if (!enabled) {
                logSkipOnce("PROVIDER_DISABLED", disabledReason == null ? "disabled" : disabledReason);
            }
            long elapsedMs = (System.nanoTime() - t0Ns) / 1_000_000L;
            return BraveSearchResult.disabled(elapsedMs);
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
            long elapsedMs = (System.nanoTime() - t0Ns) / 1_000_000L;
            return new BraveSearchResult(java.util.List.of(), BraveSearchResult.Status.RATE_LIMIT_LOCAL, null, 0L,
                    "local monthly quota exhausted", elapsedMs);
        }

        // Gate 3: QPS 레이트리미트
        if (!rateLimiter.tryAcquire(props.acquireTimeoutMs(), TimeUnit.MILLISECONDS)) {
            long elapsedMs = (System.nanoTime() - t0Ns) / 1_000_000L;
            return new BraveSearchResult(java.util.List.of(), BraveSearchResult.Status.RATE_LIMIT_LOCAL, null, 0L,
                    "local rate limit hit", elapsedMs);
        }

        // Brave API: count 파라미터는 최대 20까지만 허용.
        int requestedTopK = (limit > 0 ? limit : 5);
        int topK = Math.min(requestedTopK, BRAVE_MAX_TOPK);

        try {
            URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl)
                    .queryParam("q", safeQuery)
                    .queryParam("count", topK)
                    .build()
                    .encode(StandardCharsets.UTF_8)
                    .toUri();

            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Subscription-Token", apiKey);
            headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<String> res = restTemplate.exchange(uri, HttpMethod.GET, entity, String.class);
            String body = res.getBody();
            if (body == null || body.isBlank()) {
                long elapsedMs = (System.nanoTime() - t0Ns) / 1_000_000L;
                return BraveSearchResult.ok(java.util.List.of(), elapsedMs);
            }

            List<String> snippets = extractSnippetsFromJson(body, topK);
            if (snippets == null) {
                snippets = java.util.List.of();
            }

            // Consume local quota on success
            if (!snippets.isEmpty() && props.monthlyQuota() > 0) {
                int left = monthlyRemaining.decrementAndGet();
                if (left <= 0) {
                    quotaExhausted = true;
                }
            }
            long elapsedMs = (System.nanoTime() - t0Ns) / 1_000_000L;
            return BraveSearchResult.ok(snippets, elapsedMs);

        } catch (HttpClientErrorException.TooManyRequests e) {
            long cooldownMs = Math.max(0L, props.cooldownMs());
            long until = System.currentTimeMillis() + cooldownMs;
            cooldownUntilEpochMs.set(until);
            long elapsedMs = (System.nanoTime() - t0Ns) / 1_000_000L;
            return new BraveSearchResult(java.util.List.of(), BraveSearchResult.Status.HTTP_429, 429, cooldownMs,
                    "remote 429", elapsedMs);
        } catch (HttpClientErrorException e) {
            long elapsedMs = (System.nanoTime() - t0Ns) / 1_000_000L;
            return new BraveSearchResult(java.util.List.of(), BraveSearchResult.Status.HTTP_ERROR,
                    e.getStatusCode().value(), 0L, e.getStatusText(), elapsedMs);
        } catch (Exception e) {
            long elapsedMs = (System.nanoTime() - t0Ns) / 1_000_000L;
            return new BraveSearchResult(java.util.List.of(), BraveSearchResult.Status.EXCEPTION,
                    null, 0L, e.getClass().getSimpleName() + ": " + e.getMessage(), elapsedMs);
        }
    }

    private void logSkipOnce(String tag, String reason) {
        try {
            String k = "brave." + tag;
            if (TraceStore.get(k) != null) return;
            TraceStore.put(k, "1");
        } catch (Throwable ignore) {
            // ignore
        }
        log.info("[{}] Brave call skipped (reason={}){}", tag, reason, LogCorrelation.suffix());
    }

    public List<String> searchSnippets(String query, int limit) {
        BraveSearchResult r = searchWithMeta(query, limit);
        if (r == null || r.snippets() == null) {
            return Collections.emptyList();
        }
        return r.snippets();
    }

    /**
     * Parse Brave Web Search JSON and extract human-readable snippets.
     * This keeps raw JSON out of the LLM context.
     */
    /**
     * Parse Brave Web Search JSON and extract human-readable snippets.
     * This keeps raw JSON out of the LLM context and is resilient to minor schema
     * changes.
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
                    if (snippets.size() >= topK)
                        break;

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

    // ---------------------------------------------------------------------
    // [Fix] Rate-limit / 429 handling helpers
    // ---------------------------------------------------------------------
    private void startCooldown(long waitMs) {
        long now = System.currentTimeMillis();
        long ms = waitMs <= 0 ? DEFAULT_429_COOLDOWN_MS : waitMs;
        if (ms > MAX_429_COOLDOWN_MS) {
            ms = MAX_429_COOLDOWN_MS;
        }
        long until = now + ms;
        cooldownUntilEpochMs.updateAndGet(prev -> Math.max(prev, until));
    }

    private static long retryAfterToMs(HttpHeaders headers) {
        if (headers == null) {
            return DEFAULT_429_COOLDOWN_MS;
        }
        String retryAfter = headers.getFirst("Retry-After");
        if (retryAfter == null || retryAfter.isBlank()) {
            return DEFAULT_429_COOLDOWN_MS;
        }
        String v = retryAfter.trim();

        // Retry-After can be either seconds or an HTTP-date. We handle seconds.
        try {
            long sec = Long.parseLong(v);
            if (sec <= 0) {
                return DEFAULT_429_COOLDOWN_MS;
            }
            long ms = sec * 1000L;
            return Math.min(ms, MAX_429_COOLDOWN_MS);
        } catch (NumberFormatException ignore) {
            return DEFAULT_429_COOLDOWN_MS;
        }
    }

    /**
     * On error responses (e.g. 429) Brave might still include rate-limit headers.
     * We update the local quota tracker only when the header is present.
     * (Unlike the success-path, we do NOT decrement when missing.)
     */
    private void updateQuotaFromHeadersOnError(HttpHeaders headers) {
        if (props.monthlyQuota() <= 0) {
            return;
        }
        if (headers == null) {
            return;
        }
        String remainingHeader = headers.getFirst("X-RateLimit-Remaining");
        if (remainingHeader == null) {
            return;
        }
        try {
            String[] parts = remainingHeader.split(",");
            String candidate = parts[parts.length - 1].trim();
            int remaining = Integer.parseInt(candidate);
            monthlyRemaining.set(remaining);
            if (remaining <= 0) {
                quotaExhausted = true;
            }
        } catch (NumberFormatException e) {
            log.warn("[Brave] Failed to parse X-RateLimit-Remaining on error: {}", remainingHeader);
        }
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

    @Override
    public boolean supportsSiteOrSyntax() {
        // Brave Search typically supports boolean OR and site: filters.
        return true;
    }

    @Override
    public boolean isAvailable() {
        return isEnabled();
    }

    @Override
    public int getPriority() {
        return 20;
    }
}