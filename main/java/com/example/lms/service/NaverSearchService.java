package com.example.lms.service;

import com.example.lms.search.policy.GrokPromotionDiscovery;

import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
import org.springframework.lang.Nullable;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeoutException;
import reactor.core.publisher.Flux;
import com.fasterxml.jackson.databind.JsonNode;
import reactor.core.publisher.Mono;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.http.ResponseEntity;
import com.example.lms.search.RateLimitPolicy;
import com.example.lms.search.TraceStore;
import com.example.lms.search.WebProviderTraceReasons;
import com.example.lms.search.policy.AdaptiveSearchQueryVariants;
import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.infra.resilience.NightmareKeys;
import org.springframework.dao.DataIntegrityViolationException;
import com.example.lms.config.ConfigValueGuards;
import com.example.lms.guard.KeyResolver;
import com.example.lms.transform.QueryTransformer;
import com.example.lms.util.RelevanceScorer;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import com.example.lms.service.rag.LangChainRAGService;
import com.example.lms.service.rag.auth.DomainProfileLoader;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.Query;
import com.example.lms.config.NaverFilterProperties;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.infra.exec.ContextPropagation;
import com.example.lms.trace.AblationContributionTracker;
import com.example.lms.trace.SafeRedactor;
import dev.langchain4j.store.embedding.EmbeddingStore;
import java.util.regex.Matcher;
import java.io.IOException;
import java.net.URI;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.Arrays;
import java.util.stream.Stream;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.util.StringUtils;
import jakarta.annotation.PreDestroy;
import reactor.util.retry.Retry;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import org.slf4j.MDC;
import com.example.lms.guard.rulebreak.RuleBreakContext;
import com.example.lms.guard.rulebreak.RuleBreakPolicy;
import com.example.lms.search.provider.WebSearchProvider;

import com.example.lms.service.search.NaverCredentialBridge;
import com.example.lms.service.search.SearchDisambiguation; // 중의성(자동차 등) 필터
import java.util.concurrent.Semaphore; // 세마포어 클래스 

import java.util.Locale; // ★ Locale 누락

import org.springframework.transaction.PlatformTransactionManager; // ─ 트랜잭션 템플릿 추가
import com.example.lms.service.rag.pre.QueryContextPreprocessor; // ⭐ NEW
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers; // B. Schedulers 임포트 추가
import java.time.Duration; // ▲ Sync Facade에서 block 타임아웃에 사용
import java.util.Objects; // NEW - distinct/limit 필터
import com.github.benmanes.caffeine.cache.LoadingCache; // recentSnippetCache 용
import dev.langchain4j.data.embedding.Embedding; // NEW - batch embedAll
// - Lombok RequiredArgsConstructor는 명시 생성자와 충돌
import com.example.lms.guard.GuardProfile;
import com.example.lms.guard.GuardProfileProps;

/**
 * Simplified NaverSearchService that does not automatically append
 * marketplace keywords (e.g., 번개장터, 중고나라) or site restrictions.
 * It processes queries, applies an optional location suffix,
 * filters by allow/deny lists and stores snippets into memory with
 * cosine-similarity scores.
 *
 * =====================================================================================
 * [KNOWN ISSUE / TROUBLESHOOTING HISTORY]
 *
 * Symptom:
 * - "Web search 0 results" or "Brave search 0 results" followed by
 * slow RAG fallback and potential SSE timeout/IOException on the client.
 *
 * Root cause:
 * - Strict domain filtering (domain-policy=filter + narrow allowlist)
 * can remove all otherwise valid Naver results, forcing the slower
 * Brave/RAG path.
 *
 * How to fix:
 * 1) Check application.properties / application.yml:
 * naver.filters.enable-domain-filter=false
 * naver.filters.domain-policy=none (or "boost")
 * 2) Do NOT assume a code bug before verifying the configuration.
 * =====================================================================================
 */
@Service
public class NaverSearchService implements WebSearchProvider {
    @Autowired
    private GuardProfileProps guardProfileProps;

    @Autowired(required = false)
    private NightmareBreaker nightmareBreaker;

    @Autowired(required = false)
    private DebugEventStore debugEventStore;

    private static final Logger log = LoggerFactory.getLogger(NaverSearchService.class);

    // ---------------------------------------------------------------------
    // [SECURITY] Never log secrets / tokens in plaintext.
    // ---------------------------------------------------------------------
    private static boolean isSensitiveHeader(String name) {
        if (name == null)
            return false;
        String n = name.toLowerCase(Locale.ROOT);
        return n.contains("secret")
                || n.contains("token")
                || n.contains("authorization")
                || n.contains("api-key")
                || n.contains("apikey")
                || n.contains("client-id")
                || n.contains("clientid")
                || n.contains("key-label")
                || n.contains("keylabel")
                || n.contains("cookie")
                || n.contains("set-cookie");
    }

        // 더 강하게 마스킹: 앞부분 노출 금지(운영 로그 안전)
    private static String safeHeaderValueForLog(String name, java.util.List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        if (isSensitiveHeader(name)) {
            return values.stream()
                    .map(value -> value == null || value.isBlank() ? "present=false" : "present=true")
                    .collect(Collectors.joining(", "));
        }
        return String.valueOf(values);
    }

    // [PATCH] User-Agent Rotation Pool (to reduce bot-block)
    private static final String[] USER_AGENTS = {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    };

    /*
     * ────────────────────────────────────────────────────────────────
     * [TRENDY & ALL-ROUNDER] 커뮤니티/여론/공략 인텐트 감지 패턴
     * 특정 게임명에 의존하지 않고, 유저가 '집단지성'을 찾을 때 쓰는 패턴을 공략
     * ────────────────────────────────────────────────────────────────
     */
    private static final Pattern COMMUNITY_INTENT_PATTERN = Pattern.compile(
            "(?i)(" +
            // 1. 비교/선호도 (Opinion & Comparison)
                    "vs|대|차이|장단점|좋아|나아|추천|비추|어때|평가|후기|리뷰|" +
                    // 2. 공략/정보 (Strategy & Meta)
                    "공략|빌드|세팅|조합|파티|덱|스킬|트리|찍는법|국룰|종결|졸업|" +
                    // 3. 랭킹/트렌드 (Rank & Tier)
                    "티어|등급|순위|랭킹|meta|tier|top|best|0티어|1티어|" +
                    // 4. 서브컬처/게이밍 공통 용어 (Subculture Jargon)
                    "리세|가챠|천장|픽업|복각|전무|성유물|돌파|재련|명함" +
                    ")");

    /**
     * LLM 답변을 활용한 검색 (딥 리서치 모드)
     */

    // Naver API key CSV and single client-id/secret bridge. Never log values.
    private String naverKeysCsv; // final 제거 (생성자 내 재할당 허용)
    private final boolean naverKeysPresent;
    private final boolean naverClientPairPresent;
    private int parsedNaverKeyCount;
    private List<NaverCredentialBridge.Credential> naverKeys = List.of(); // 초기값은 빈 리스트
    private final AtomicLong keyCursor = new AtomicLong(); // 라운드-로빈 인덱스

    /**
     * 검색 단계(시도) 로그
     */
    public static final class SearchStep {
        public final String query;
        public final int returned;
        public final int afterFilter;
        public final long tookMs;

        public SearchStep(String query, int returned, int afterFilter, long tookMs) {
            this.query = query;
            this.returned = returned;
            this.afterFilter = afterFilter;
            this.tookMs = tookMs;
        }
    }

    /**
     * 한 번의 사용자 질의에 대한 전체 검색 추적
     */
    public static final class SearchTrace {
        /** Request-owned terminal category. UNKNOWN never authorizes a zero-result retry. */
        public String outcomeClass = "UNKNOWN";
        public final List<SearchStep> steps = new ArrayList<>();
        public boolean domainFilterEnabled;
        public boolean keywordFilterEnabled;
        public String suffixApplied;
        public long totalMs;
        public String query; // TraceHtmlBuilder compatibility; normally a redacted label, not raw query text.
        public String queryHash;
        public int queryLength;
        public String queryTokenBucket;
        public String provider; // 제공자 이름 저장용 (TraceHtmlBuilder 호환)

        // TraceHtmlBuilder 호환성을 위한 Record 스타일 접근자 메서드
        public String query() {
            return query;
        }

        public String provider() {
            return provider;
        }

        public long elapsedMs() {
            return totalMs;
        }

        /** Reason why the domain filter was disabled, or null when enabled. */
        public String reasonDomainFilterDisabled;
        /** Reason why the keyword filter was disabled, or null when enabled. */
        public String reasonKeywordFilterDisabled;

        /** Whether an organisation was resolved for this query. */
        public boolean orgResolved;
        /** The canonical name of the resolved organisation, if any. */
        public String orgCanonical;
        /** List of site filters applied during org-aware search. */
        public java.util.List<String> siteFiltersApplied = new java.util.ArrayList<>();
    }

    /**
     * 스니펫 + 추적 묶음
     */
    public record SearchResult(List<String> snippets, SearchTrace trace) {
    }

    /** One subscribed HTTP attempt owns these counters; shared scalar trace fields are not read back. */
    private static final class NaverObservation extends java.util.concurrent.ConcurrentHashMap<String, Object> {
        private final Map<String, Object> context;
        private final java.util.concurrent.atomic.AtomicBoolean published = new java.util.concurrent.atomic.AtomicBoolean();

        private NaverObservation(String query, Map<String, Object> context, boolean clientAttempt) {
            this.context = context;
            putAll(TraceStore.searchCorrelation(context));
            put("provider", "naver");
            put("queryHash", SafeRedactor.hashValue(query));
            put("countScope", "single_response_items_array");
            put("clientAttemptObserved", clientAttempt);
            put("clientAttemptBoundary", "webclient_subscription");
            put("providerReceiptObserved", false);
            put("startedAtEpochMs", System.currentTimeMillis());
            for (String key : List.of("rawSize", "parsedCount", "rawCount", "afterBlockedCount",
                    "afterStrictCount", "formattedBeforeDedupCount", "afterDedupCount", "afterFilterCount", "httpStatus")) {
                put(key, "unknown");
            }
            put("failureClass", "unknown");
        }

        private void finish() {
            if (!published.compareAndSet(false, true)) return;
            put("finishedAtEpochMs", System.currentTimeMillis());
            Map<String, Object> immutable = Map.copyOf(this);
            try {
                withTraceContext(context, () -> {
                    TraceStore.append("web.naver.filter.runs", immutable);
                    return null;
                });
            } catch (RuntimeException unavailableTraceSink) {
                // An immutable/closed observation sink must never replace the provider outcome.
            }
        }
    }

    private record ObservedNaverResponse(ResponseEntity<String> entity, NaverObservation observation) { }

    private record NaverAttemptOutcome(List<String> snippets, String failureClass) {
        private static NaverAttemptOutcome fromSnippets(List<String> snippets) {
            List<String> safe = snippets == null ? List.of() : snippets;
            return new NaverAttemptOutcome(safe, safe.isEmpty() ? "TRUE_ZERO" : null);
        }

        private static NaverAttemptOutcome failure(String failureClass) {
            return new NaverAttemptOutcome(List.of(), failureClass);
        }

        private boolean allowsBoundedVariant() {
            return "TRUE_ZERO".equals(failureClass);
        }
    }

    private static final class NaverClassifiedFailure extends RuntimeException {
        private final String failureClass;

        private NaverClassifiedFailure(String failureClass) {
            super(failureClass, null, false, false);
            this.failureClass = failureClass;
        }
    }

    /**
     * 불변 검색 정책 객체 - 요청 시점에 생성되어 파이프라인 전체에서 사용
     * 전역 필드를 변경하지 않고 정책을 전파
     */
    public record SearchPolicy(
            boolean domainFilterEnabled,
            boolean keywordFilterEnabled,
            String domainPolicy, // "filter" | "boost"
            int keywordMinHits) {
        /** 엄격 모드: 의료/공식 질의용 */
        public static SearchPolicy defaultStrict() {
            return new SearchPolicy(true, true, "filter", 2);
        }

        /** Free 모드: 브레이브/제로브레이크 등 */
        public static SearchPolicy freeMode() {
            return new SearchPolicy(false, false, "boost", 0);
        }

        /** 설정값 기반 기본 정책 */
        public static SearchPolicy fromConfig(boolean domainFilter, boolean keywordFilter,
                String policy, int minHits) {
            String p = "filter".equalsIgnoreCase(policy) ? "filter" : "boost";
            return new SearchPolicy(domainFilter, keywordFilter, p, minHits);
        }

        /** 부분 override 헬퍼 */
        public SearchPolicy withDomainFilterEnabled(boolean v) {
            return new SearchPolicy(v, this.keywordFilterEnabled, this.domainPolicy, this.keywordMinHits);
        }
    }

    public static final class MetadataKeys {
        /**
         * Unified metadata key used across services. To ensure that RAG, web, and
         * memory
         * retrievals all reference the same session metadata field, reuse the
         * constant defined in {@link LangChainRAGService}. The original value
         * "sessionId" is replaced with {@link LangChainRAGService#META_SID}, which
         * resolves to "sid". This eliminates mismatches where one component
         * writes "sid" metadata but another reads "sessionId", resulting in
         * cross-session bleed.
         */
        public static final String SESSION_ID = LangChainRAGService.META_SID;

        private MetadataKeys() {
        }
    }

    /* === Dependencies === */
    private final MemoryReinforcementService memorySvc;
    private final ObjectProvider<ContentRetriever> retrieverProvider;
    private final EmbeddingStore<TextSegment> embeddingStore;
    @Qualifier("guardrailQueryPreprocessor") // ◀ 정확한 bean 이름
    private final QueryContextPreprocessor preprocessor; // ⭐ NEW
    private final EmbeddingModel embeddingModel;
    private final WebClient web;
    private final ObjectMapper om;
    /** Cache for normalized queries to web snippet lists. */
    /** 비동기 캐시 (block 금지) */
    private final AsyncLoadingCache<String, List<String>> cache;

    // [HARDENING] optional detector for location intent; auto-wired when present
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.lms.location.intent.LocationIntentDetector locationIntentDetector;

    // [PATCH] PlanHint(officialOnly/domainProfile) 적용을 위한 프로필 로더 (옵션)
    @Autowired(required = false)
    private DomainProfileLoader domainProfileLoader;
    /** Cache to prevent reinforcing duplicate snippets. */
    private final LoadingCache<String, Boolean> recentSnippetCache;
    /** Cache for location token embeddings (memoization) */
    private final LoadingCache<String, float[]> locationEmbedCache;
    /** Scorer for cosine similarity. */
    private final RelevanceScorer relevanceScorer;
    /** 레이트리밋 정책(헤더 기반 동적 제어) */
    private final RateLimitPolicy ratePolicy;
    /** NEW - 별도 트랜잭션 컨텍스트용 */
    private final TransactionTemplate txTemplate;
    /** Supplier of the current session id. */
    private final Supplier<Long> sessionIdProvider;

    /**
     * Optional Redis-based cooldown service. When provided this service
     * guards external API calls with a short-lived lock to prevent
     * thundering herd behaviour and excessive concurrent requests. When
     * null no cooldown is applied and calls proceed immediately. The
     * implementation is provided via Spring and may be absent when Redis
     * is unavailable or not configured.
     */
    private final com.example.lms.service.redis.RedisCooldownService cooldownService;

    /* === Configuration properties === */
    // (client-id / client-secret 개별 프로퍼티는 더 이상 사용하지 않는다)
    /** 단순화된 호출 타임아웃(ms) */
    private static final long API_TIMEOUT_MS = 3000;
    @Value("${naver.search.web-top-k:8}")
    private int webTopK; // LLM에 넘길 개수
    @Value("${naver.search.rag-top-k:5}")
    private int ragTopK; // 벡터 RAG top-k
    /** (NEW) 네이버 API에서 한 번에 받아올 검색 결과 수(1-100) */

    @Value("${naver.search.display:20}")
    private int display;
    @Value("${naver.search.query-suffix:}")
    private String querySuffix;
    @Value("${naver.search.query-sim-threshold:0.3}")
    private double querySimThreshold;
    // Domain filtering enabled flag. This value is initialised from {@link
    // NaverFilterProperties}.
    private volatile boolean enableDomainFilter; // [HARDENING] default true

    /* ---------- 2. ApiKey 헬퍼 타입 ---------- */
    // 기본 허용 목록에 서브도메인 포함 도메인 추가(부재 시 0개 스니펫 방지)
    // Comma separated allowlist of domain suffixes. Populated from {@link
    // NaverFilterProperties}.
    private volatile String allowlist;
    // Keyword filtering enabled flag (initialised from NaverFilterProperties).
    private boolean enableKeywordFilter;
    // 키워드 필터는 OR(하나 이상 매칭)로 완화
    // Minimum number of keyword hits required; populated from {@link
    // NaverFilterProperties}.
    private int keywordMinHits;
    /* === Configuration properties === */
    @Value("${naver.search.debug:false}") // ⬅ 추가
    private boolean debugSearchApi; // ⬅ 추가
    @Value("${web.trace.raw-query-enabled:false}")
    private boolean rawQueryTraceEnabled;
    @Value("${web.trace.expose-snippets:false}")
    private boolean traceSnippetsEnabled;

    /** Comma-separated blacklist of domains to exclude entirely. */
    @Value("${naver.search.blocked-domains:}")
    private String blockedDomainsCsv;

    /** (선택) 대화 문맥에 따라 쿼리를 재작성하는 Transformer - 존재하지 않으면 주입 안 됨 */
    /** 오타·맞춤법 교정을 담당하는 Transformer */
    private final QueryTransformer queryTransformer;

    @Autowired
    @Qualifier("llmFastExecutor")
    private java.util.concurrent.ExecutorService llmFastExecutor;

    private volatile Scheduler naverIoScheduler;

    @PreDestroy
    private void shutdownScheduler() {
        Scheduler s = naverIoScheduler;
        naverIoScheduler = null;
        if (s != null) {
            try {
                s.dispose();
            } catch (Exception e) {
                log.debug("[Naver] scheduler dispose failed err={}", e.getClass().getSimpleName());
                TraceStore.inc("web.naver.scheduler.dispose.failed");
            }
        }
        REQUEST_SEMAPHORE.drainPermits();
    }

    private Scheduler ioScheduler() {
        Scheduler s = naverIoScheduler;
        if (s != null) {
            return s;
        }
        synchronized (this) {
            if (naverIoScheduler == null) {
                // Capture the same request budget before crossing the scheduler boundary.
                // This scheduler owns its wrapper, never the injected or shared executor.
                java.util.concurrent.Executor executor = llmFastExecutor != null
                        ? llmFastExecutor
                        : command -> Schedulers.boundedElastic().schedule(command);
                naverIoScheduler = Schedulers.fromExecutor(
                        command -> executor.execute(ContextPropagation.wrap(command)));
            }
            return naverIoScheduler;
        }
    }

    /* 최대 동시 네이버 API 호출량 (429 방지) */
    private static final int MAX_CONCURRENT_API = 10; // 2 -> 10, 병목 완화
    /** 네이버 API 429 방지를 위한 전역 세마포어 */
    private final Semaphore REQUEST_SEMAPHORE = new Semaphore(MAX_CONCURRENT_API, true);
    /*
     * ★ NEW: 한 검색당 최대 변형 쿼리 수
     * assistantAnswer 기반 딥-서치에서 QueryTransformer가 생성하는
     * 변형 쿼리 폭주를 안전하게 제한한다.
     */
    private static final int MAX_QUERIES_PER_SEARCH = 9;

    /*
     * ────────────────────────────────────────────────
     * “site eulji ac kr /* ... *&#47;” 류 도메인-스코프 변형 차단용 패턴
     * - ‘site ’ 로 시작하거나
     * - ac kr 등 TLD 조각이 앞머리에 노출되는 경우
     * ────────────────────────────────────────────────
     */
    private static final Pattern DOMAIN_SCOPE_PREFIX = Pattern.compile("(?i)^\\s*(site\\s+)?\\S+\\s+ac\\s+kr\\b");

    static int clampNaverDisplay(int requested) {
        return Math.max(10, Math.min(100, requested));
    }

    private int configuredNaverMaximum() {
        return clampNaverDisplay(display);
    }

    private int admitNaverTopK(int requested) {
        if (requested <= 0) {
            return 0;
        }
        return Math.min(requested, configuredNaverMaximum());
    }

    private int outboundDisplayFor(int admittedTopK) {
        return admittedTopK <= 0 ? 0 : clampNaverDisplay(admittedTopK);
    }

    // [REMOVED] 비공식 HTML 크롤링 폴백/헤징은 운영 리스크(봇 차단/캡차)로 인해 제거.
    @Value("${naver.search.timeout-ms:3000}") /* [ECO-FIX v3.0] 40000 -> 3000 (3초 컷) */
    private long apiTimeoutMs;

    /**
     * (SYNC FACADE) blockOptional() timeout for searchSnippetsSync/searchWithTraceSync.
     *
     * <p>
     * Default ties to naver.search.timeout-ms to keep budgets consistent, but can be
     * overridden when the caller has a tighter global deadline (e.g. HybridWebSearchProvider).
     * </p>
     */
    @Value("${naver.search.sync-block-timeout-ms:${naver.search.timeout-ms:3000}}")
    private long syncBlockTimeoutMs;


    // Retry knobs (fail-soft). Default 0 = disabled (enable via application.yml)
    @Value("${naver.search.retry.max-attempts:0}")
    private int retryMaxAttempts;

    @Value("${naver.search.retry.initial-backoff-ms:200}")
    private long retryInitialBackoffMs;

    @Value("${naver.search.retry.max-backoff-ms:800}")
    private long retryMaxBackoffMs;

    @Value("${naver.search.retry.jitter:0.2}")
    private double retryJitter;

    @Value("${naver.search.query-transform-timeout-ms:${search.query-transform-timeout-ms:500}}")
    private long queryTransformTimeoutMs;

    @Value("${naver.search.adaptive.enabled:true}")
    private boolean adaptiveSearchEnabled = true;

    @Value("${naver.search.adaptive.max-queries:4}")
    private int adaptiveMaxQueries = 4;

    @Value("${naver.search.adaptive.max-overall-timeout-ms:4500}")
    private long adaptiveMaxOverallTimeoutMs = 4500L;

    @Value("${naver.search.adaptive.per-call-floor-ms:600}")
    private long adaptivePerCallFloorMs = 600L;

    @Value("${naver.search.debug-json:false}")
    private boolean debugJson;
    @Value("${naver.search.expansion-policy:conservative}")
    private String expansionPolicy; // 동의어 확장 정책 (conservative|none)

    @Value("${naver.search.product-keywords:k8plus,k8 plus,k8+,케이8 플러스,케이8플러스}")
    private String productKeywordsCsv;

    @Value("${naver.search.fold-keywords:폴드,fold,갤럭시폴드}")
    private String foldKeywordsCsv;

    @Value("${naver.search.flip-keywords:플립,flip,갤럭시플립}")
    private String flipKeywordsCsv;

    /* ───── {스터프2} 에서 가져온 ‘메모리 오염 방지’ 옵션 ───── */
    /** assistant 답변을 장기 메모리에 reinforcement 할지 여부 (기본 OFF) */
    @Value("${naver.reinforce-assistant:false}")
    private boolean enableAssistantReinforcement;

    /** reinforcement 시 적용할 감쇠 가중치 (0.0 ~ 1.0) - 높을수록 더 많이 반영 */
    @Value("${naver.reinforce-assistant.weight:0.4}")
    private double assistantReinforceWeight;

    // Domain policy controlling filter/boost behaviour. Initialised from {@link
    // NaverFilterProperties}.
    private String domainPolicy;

    @Value("${naver.search.fusion:none}") // none|rrf
    private String fusionPolicy;

    /** 딥-리서치 시 확장 쿼리 후보 최대 개수 */
    @Value("${naver.search.expand-query-candidates:3}")
    private int expandQueryCandidates;

    /** Centralised filter properties bean */
    @org.springframework.beans.factory.annotation.Autowired
    private NaverFilterProperties naverFilterProperties;

    /**
     * Initialise filter flags from {@link NaverFilterProperties}. This method runs
     * after
     * dependency injection and assigns the internal filtering fields to the
     * values supplied by the centralised configuration bean. When no allowlist
     * is configured the field defaults to an empty string.
     */
    @jakarta.annotation.PostConstruct
    private void initFilterProperties() {
        if (this.naverFilterProperties != null) {
            this.enableDomainFilter = naverFilterProperties.isEnableDomainFilter();
            java.util.List<String> list = naverFilterProperties.getDomainAllowlist();
            this.allowlist = (list == null || list.isEmpty()) ? "" : String.join(",", list);
            this.enableKeywordFilter = naverFilterProperties.isEnableKeywordFilter();
            this.keywordMinHits = naverFilterProperties.getKeywordMinHits();
            String rawPolicy = naverFilterProperties.getDomainPolicy();
            this.domainPolicy = ("filter".equalsIgnoreCase(rawPolicy)) ? "filter" : "boost";

            // Fail-safe: 도메인 필터는 켜져 있으나 허용 목록이 비어 있으면,
            // 모든 검색 결과가 전부 차단되는 자폭 구성을 막기 위해 필터를 자동으로 끕니다.
            if (this.enableDomainFilter && (this.allowlist == null || this.allowlist.isBlank())) {
                log.warn("🚨 [NaverSearchConfig] Domain filter is ENABLED but allowlist is EMPTY. " +
                        "Auto-disabling filter to prevent zero-result searches.");
                this.enableDomainFilter = false;
            }

            // [NaverSearchConfig] 현재 필터 설정을 부팅 시점에 로그로 남겨
            // "검색 0건" 이슈가 발생했을 때 설정 문제를 즉시 진단할 수 있도록 한다.
            if (log.isInfoEnabled()) {
                log.info(
                        "[NaverSearchConfig] policy='{}', enableDomainFilter={}, enableKeywordFilter={}, keywordMinHits={}, allowlistSample={}",
                        domainPolicy,
                        enableDomainFilter,
                        enableKeywordFilter,
                        keywordMinHits,
                        (allowlist == null || allowlist.isBlank() ? "<empty>" : safeTrunc(allowlist, 200)));
            }
        } else {
            log.warn("[NaverSearchConfig] NaverFilterProperties not injected; using default filter flags");
        }
    }

    /**
     * 요청 시점에 정책을 계산 - 전역 필드 변경 없이 불변 객체 반환
     *
     * @param query                검색어(현재는 로깅/확장 용도)
     * @param isFreeMode           Free/Brave/ZeroBreak 모드 여부
     * @param strictDomainRequired 의료/공공/위치 등 엄격 필터 필요 여부
     * @return 해당 요청에 적용할 SearchPolicy
     */
    private SearchPolicy computePolicy(String query, boolean isFreeMode, boolean strictDomainRequired) {
        SearchPolicy base = SearchPolicy.fromConfig(
                this.enableDomainFilter,
                this.enableKeywordFilter,
                this.domainPolicy,
                this.keywordMinHits);

        // strictDomainRequired이면 Free 모드 무시 (의료/위치 등 안전 우선)
        if (strictDomainRequired) {
            log.info("[NaverSearch] strictDomainRequired=true → enforce strict policy (ignore free)");
            return SearchPolicy.defaultStrict();
        }

        if (isFreeMode) {
            return SearchPolicy.freeMode();
        }

        return base;
    }

    private Set<String> productKeywords;
    private Set<String> foldKeywords;
    private Set<String> flipKeywords;
    // ❌ 별칭/규칙 기반 전처리 제거: 의도/재작성은 ChatService 상단의 LLM 단계에서 끝낸다.

    /* === Patterns and stop words === */
    // ❌ 불용어/접두사/필러 제거 로직 삭제 (단순 검색 전용으로 축소)
    // [HARDENING] Require both intent keywords and geographic suffix tokens
    private static final Pattern LOCATION_PATTERN = Pattern.compile(
            "(?=.*(근처|가까운|주변|길찾기|경로|지도|주소|위치|시간|얼마나\\s*걸려|가는\\s*법))" +
                    "(?=.*(시|구|동|읍|면|군|로|길|거리|역|정류장))",
            Pattern.UNICODE_CHARACTER_CLASS | Pattern.CASE_INSENSITIVE);
    private static final Pattern NON_ALNUM = Pattern.compile("[^\\p{IsHangul}\\p{L}\\p{Nd}]");
    // () 캐시키/유사도 정규화에 사용할 패턴 (한글/영문/숫자만 유지)
    private static final Pattern NON_ALNUM_KO = Pattern.compile("[^\\p{IsHangul}\\p{L}\\p{Nd}]+");
    private static final Pattern MEDICAL_PATTERN = Pattern.compile(
            "(?i)(병원|의료진|교수|진료과|의사|전문의|센터|클리닉)");
    private static final Pattern OFFICIAL_INFO_PATTERN = Pattern.compile(
            "(?i)(병원|의료|의사|전문의|교수|대학교|대학|학과|연구실|연구소|센터|학교|공공기관|정부기관|학회|세미나|논문)");

    /** 학술·논문 검색어 감지용 */
    private static final Pattern ACADEMIC_PATTERN = Pattern.compile(
            "(?i)(논문|학술|저널|학회|conference|publication|research)");

    /**
     * Low-trust URL markers used only for 'thinRescue' top-up in official-only
     * mode.
     * <p>
     * We still keep blocked-domains enforcement; this list only helps avoid topping
     * up with
     * obvious community/social sources when the plan explicitly requested official
     * sources.
     */
    private static final List<String> THIN_RESCUE_LOW_TRUST_URL_MARKERS = List.of(
            "namu.wiki",
            "tistory.com",
            "blog.naver.com",
            "cafe.naver.com",
            "dcinside.com",
            "ruliweb.com",
            "fmkorea.com",
            "theqoo.net",
            "ppomppu.co.kr",
            "mlbpark.donga.com",
            "clien.net",
            "inven.co.kr",
            "arca.live",
            "youtube.com",
            "x.com",
            "twitter.com",
            "instagram.com");

    private static boolean isLowTrustUrlForThinRescue(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String u = url.toLowerCase(Locale.ROOT);
        for (String marker : THIN_RESCUE_LOW_TRUST_URL_MARKERS) {
            if (u.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /** Source tag for assistant-generated responses stored into memory. */
    private static final String ASSISTANT_SOURCE = "ASSISTANT";
    // Match whole version tokens such as 5.8, v5.8, 5·8, 5-8, 5 8, and 5.8.1.
    private static final Pattern VERSION_PATTERN = Pattern.compile(
            "(?i)(?<![\\p{L}\\p{Nd}])v?\\d+(?:[\\.·\\-\\s]+\\d+)+(?![\\p{L}\\p{Nd}])");

    /**
     * Determine whether the supplied query appears to reference a game
     * patch version. A game patch query should contain the game name
     * (either "원신" or "genshin"), a patch related keyword ("패치",
     * "업데이트", or "버전") and a version token matching
     * {@link #VERSION_PATTERN}. When any of these are absent the method
     * returns false.
     *
     * @param q the user query (may be null)
     * @return true if the query looks like a Genshin Impact patch query
     */
    private boolean isGamePatchQuery(String query) {
        if (query == null || query.isBlank())
            return false;
        String q = query.toLowerCase(Locale.ROOT);

        boolean hasPatchKw = q.contains("패치")
                || q.contains("업데이트")
                || q.contains("버전")
                || q.contains("patch")
                || q.contains("update")
                || q.contains("notes")
                || q.contains("release");
        boolean hasVersion = VERSION_PATTERN.matcher(q).find();

        GuardProfile profile = guardProfileProps.currentProfile();
        // PROFILE_FREE: 버전 패턴만 있어도 패치 취급 (커버리지를 넓게 인정)
        if (profile == GuardProfile.PROFILE_FREE) {
            return hasVersion || hasPatchKw;
        }
        // PROFILE_MEMORY 및 기타 프로파일: 키워드 + 버전 둘 다 필요
        return hasPatchKw && hasVersion;
    }

    /**
     * [Hardening] 사용자 질의가 '정형화된 공식 정보'보다
     * '커뮤니티의 의견/공략'을 필요로 하는지 판단합니다. (All-rounder Logic)
     *
     * @param q 사용자 질의 (null 가능)
     * @return 커뮤니티 인텐트가 감지되면 true
     */
    private boolean isCommunityPreferredQuery(String q) {
        if (q == null || q.isBlank()) {
            return false;
        }
        // 1. 명시적 게임 패치/버전 쿼리는 커뮤니티 + 공식 혼합 필요
        if (isGamePatchQuery(q)) {
            return true;
        }
        // 2. 트렌디/커뮤니티 인텐트 패턴 매칭
        return COMMUNITY_INTENT_PATTERN.matcher(q).find();
    }

    // (+) 유사 쿼리로 판정할 Jaccard 임계값 (운영에서 조정 가능)
    @Value("${naver.search.similar-threshold:0.86}")
    private double similarThreshold;

    /* ───── E. 외부 클래스에서 여전히 참조하는 상수/유틸 복구 ───── */
    /** 의료 OR 공공 패턴(간단합치기) */
    public static final Pattern MEDICAL_OR_OFFICIAL_PATTERN = Pattern.compile(MEDICAL_PATTERN.pattern() + "|" +
            OFFICIAL_INFO_PATTERN.pattern(),
            Pattern.CASE_INSENSITIVE);

    /** “중고나라” 키워드 포함 여부 */
    public static boolean containsJoongna(String t) {
        return t != null && t.toLowerCase().contains("중고나라");
    }

    /** “번개장터” 키워드 포함 여부 */
    public static boolean containsBunjang(String t) {
        return t != null && t.toLowerCase().contains("번개장터");
    }

    private List<String> expandQueries(String query) {
        // When the query is blank simply return a single empty string so that
        // downstream
        // logic can handle it gracefully.
        if (!org.springframework.util.StringUtils.hasText(query)) {
            return java.util.List.of("");
        }
        java.util.regex.Matcher m = VERSION_PATTERN.matcher(query);
        if (!m.find()) {
            // No version token present; return the query unchanged.
            return java.util.List.of(query);
        }
        String rawVersion = m.group();
        String version = normalizeVersionToken(rawVersion);
        if (version == null) {
            return java.util.List.of(query);
        }
        String quoted = "\"" + version + "\"";
        // Base queries include quoting and various patch related phrases in both
        // Korean and English. The quoted version helps search engines match
        // the exact version token.
        java.util.List<String> base = new java.util.ArrayList<>(java.util.List.of(
                query,
                query.replace(rawVersion, quoted),
                "원신 " + quoted + " 패치 노트",
                "원신 " + quoted + " 업데이트",
                "Genshin " + quoted + " patch notes",
                "Genshin " + quoted + " version update",
                // ★ 공식 발표/방송 용어
                "Genshin " + quoted + " Special Program",
                quoted + " Genshin special program",
                "Genshin " + quoted + " livestream",
                "원신 " + quoted + " 스페셜 프로그램",
                "원신 " + quoted + " 라이브 방송",
                "원신 " + quoted + " 공지",
                "원신 " + quoted + " 업데이트 안내"));
        // When the query is clearly a game patch request, append domain scoped
        // variants to prioritise official sources. These additional queries
        // restrict results to the genshin.hoyoverse.com and hoyolab.com domains.
        if (isGamePatchQuery(query)) {
            java.util.List<String> more = new java.util.ArrayList<>();
            for (String b : base) {
                more.add(b + " site:genshin.hoyoverse.com");
                more.add(b + " site:hoyolab.com");
            }
            base = java.util.stream.Stream.concat(base.stream(), more.stream())
                    .distinct()
                    .toList();
        }
        return base;
    }

    /** Utility methods for query classification. */
    private static boolean isMedicalQuery(String q) {
        return q != null && !q.isBlank() && MEDICAL_PATTERN.matcher(q).find();
    }

    private static boolean isOfficialInfoQuery(String q) {
        return q != null && !q.isBlank() && OFFICIAL_INFO_PATTERN.matcher(q).find();
    }

    private static boolean isLocationQuery(String q) {
        return q != null && !q.isBlank() && LOCATION_PATTERN.matcher(q).find();
    }

    private static boolean isAcademicQuery(String q) {
        return q != null && !q.isBlank() && ACADEMIC_PATTERN.matcher(q).find();
    }

    /** 의료/공식정보 질의 통합 판정(기존 유틸 OR) */
    private static boolean isMedicalOfficialInfoQuery(String q) {
        return isMedicalQuery(q) || isOfficialInfoQuery(q);
    }

    /**
     * Constructor with dependency injection.
     */
    @Autowired
    public NaverSearchService(
            QueryTransformer queryTransformer,
            MemoryReinforcementService memorySvc,
            ObjectProvider<ContentRetriever> retrieverProvider,
            EmbeddingStore<TextSegment> embeddingStore,
            EmbeddingModel embeddingModel,
            @Lazy Supplier<Long> sessionIdProvider,
            @Qualifier("compositeQueryContextPreprocessor") QueryContextPreprocessor preprocessor, // ⭐ NEW
            /* 🔴 키 CSV를 생성자 파라미터로 주입받는다 */
            @Value("${naver.keys:${NAVER_KEYS:}}") String naverKeysCsv,
            @Value("${naver.client-id:${NAVER_CLIENT_ID:}}") String naverClientId,
            @Value("${naver.client-secret:${NAVER_CLIENT_SECRET:}}") String naverClientSecret,
            @Value("${naver.web.cache.max-size:2000}") long maxSize,
            @Value("${naver.web.cache.ttl-sec:300}") long ttlSec,
            PlatformTransactionManager txManager,
            RateLimitPolicy ratePolicy,
            // Inject the shared Naver WebClient bean. Qualifier ensures
            // that the correct bean is selected when multiple WebClients
            // are available.
            @Qualifier("naverWebClient") WebClient web,
            ObjectProvider<KeyResolver> keyResolverProvider,
            @Autowired(required = false) com.example.lms.service.redis.RedisCooldownService cooldownService) {
        // cooldownService assignment deferred below
        this.queryTransformer = queryTransformer;
        this.memorySvc = memorySvc;
        this.retrieverProvider = retrieverProvider;
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
        this.sessionIdProvider = sessionIdProvider;
        this.preprocessor = preprocessor; // ⭐ NEW

        this.naverKeysPresent = !ConfigValueGuards.isMissing(naverKeysCsv);
        this.naverClientPairPresent = !ConfigValueGuards.isMissing(naverClientId)
                && !ConfigValueGuards.isMissing(naverClientSecret);
        KeyResolver injectedKeyResolver = null;
        try {
            injectedKeyResolver = keyResolverProvider == null ? null : keyResolverProvider.getIfAvailable();
        } catch (BeansException unavailable) {
            TraceStore.put("naver.cred.keyResolver.failureClass", "key_resolver_unavailable");
            TraceStore.put("naver.credential.keyResolver.failureClass", "key_resolver_unavailable");
        }
        String resolved;
        if (injectedKeyResolver != null) {
            // A present resolver is authoritative, including its conflict-disabled result.
            // Never rebuild conflicting aliases through the legacy constructor bridge.
            resolved = injectedKeyResolver.resolveNaverKeysCsvSafe();
        } else {
            resolved = NaverCredentialBridge.resolveKeysCsv(naverKeysCsv, naverClientId, naverClientSecret);
        }
        this.naverKeysCsv = resolved;
        // 🔴 저장
        this.relevanceScorer = new RelevanceScorer(embeddingModel);
        this.ratePolicy = ratePolicy;

        // Assign the optional cooldown service. When null no Redis-backed
        // cooldown is applied and requests will proceed without gating.
        this.cooldownService = cooldownService;

        /*
         * ───────────────────────────────
         * ① 공통 HTTP 요청-응답 로그 필터
         * debugSearchApi=true 일 때만 TRACE/DEBUG 레벨로 출력
         * ───────────────────────────────
         */
        ExchangeFilterFunction logFilter = (req, next) -> {
            final long startNs = System.nanoTime();
            final String method = String.valueOf(req.method());
            final URI url = req.url();

            // Try to recover correlation from outbound headers (reactive threads may lose
            // MDC).
            final String _ridH = firstNonBlank(
                    req.headers().getFirst("x-request-id"),
                    req.headers().getFirst("X-Request-Id"));
            final String _sidH = firstNonBlank(
                    req.headers().getFirst("x-session-id"),
                    req.headers().getFirst("X-Session-Id"));

            DebugEventStore.ProbeScope tempProbe = DebugEventStore.ProbeScope.noop();
            if (debugEventStore != null) {
                // [FIX] 람다에서 사용 가능하도록 임시 변수 → final 대입
                String h = null;
                try {
                    h = url.getHost();
                } catch (Exception suppressed) { NaverTraceSuppressions.traceSuppressed("url.hostParse", suppressed); }
                final String host = h;
                String trace = firstNonBlank(TraceStore.getString("trace.id"), MDC.get("traceId"), MDC.get("trace"));
                boolean missingCorrelation = isBlank(req.headers().getFirst("x-request-id"))
                        && isBlank(req.headers().getFirst("x-session-id"));
                java.util.Map<String, Object> traceMap = new java.util.HashMap<>();
                traceMap.put("host", host);
                traceMap.put("method", req.method().name());
                traceMap.put("path", url.getPath());
                traceMap.put("hasQuery", url.getQuery() != null);
                traceMap.put("trace", trace);
                traceMap.put("missingCorrelation", missingCorrelation);
                tempProbe = withTempMdc(_ridH, _sidH, () -> debugEventStore.probe(
                        DebugProbeType.WEB_SEARCH,
                        "naver-search",
                        "Naver Search API call",
                        traceMap));

                if (missingCorrelation) {
                    // Keep this as a single, structured signal so it doesn't get lost in log noise.
                    withTempMdc(_ridH, _sidH, () -> debugEventStore.emit(
                            DebugProbeType.WEB_SEARCH,
                            DebugEventLevel.WARN,
                            "websearch.correlation.missing",
                            "WebSearch request missing correlation ids (x-request-id / x-session-id)",
                            "NaverSearchService.logFilter",
                            java.util.Map.of(
                                    "host", host,
                                    "method", req.method().name(),
                                    "path", url.getPath(),
                                    "trace", trace),
                            null));
                }
            }
            // [FIX] 람다에서 사용할 수 있도록 final 변수에 대입
            final DebugEventStore.ProbeScope probe = tempProbe;

            if (debugSearchApi && log.isDebugEnabled()) {
                withTempMdc(_ridH, _sidH, () -> {
                    log.debug("[HTTP] → {} {}", req.method(), safeUriSummary(req.url()));
                    req.headers().forEach((k, v) -> log.debug("[HTTP] → {}: {}", k, safeHeaderValueForLog(k, v)));
                });
            }

            return next.exchange(req)
                    .doOnNext(res -> {
                        long tookMs = Math.max(0, (System.nanoTime() - startNs) / 1_000_000);
                        if (debugSearchApi && log.isDebugEnabled()) {
                            withTempMdc(_ridH, _sidH, () -> {
                                log.debug("[HTTP] ← {}", res.statusCode()); // 200 OK·404 NOT_FOUND 형태로 출력
                                res.headers().asHttpHeaders()
                                        .forEach(
                                                (k, v) -> log.debug("[HTTP] ← {}: {}", k, safeHeaderValueForLog(k, v)));
                            });
                        }
                        withTempMdc(_ridH, _sidH, () -> probe.success(java.util.Map.of(
                                "status", res.statusCode().value(),
                                "tookMs", tookMs)));

                    })
                    .doOnError(ex -> {
                        long tookMs = Math.max(0, (System.nanoTime() - startNs) / 1_000_000);
                        withTempMdc(_ridH, _sidH, () -> probe.failure(ex, java.util.Map.of(
                                "tookMs", tookMs)));

                    });
        };

        /* ② NAVER Open API 클라이언트 */
        // Use the provided WebClient and attach our logging filter. We
        // mutate the builder to avoid modifying the original shared instance.
        this.web = web.mutate()
                .filter(injectCorrelationHeaders())
                .filter(logFilter)
                .filter(logOnError())
                .build();

        this.om = new ObjectMapper();
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(Duration.ofSeconds(ttlSec))
                .recordStats()
                // [CTX] Preserve MDC/GuardContext/TraceStore across Caffeine async boundaries.
                // Without this, reactive callbacks may lose correlation ids and logs can show
                // rid-missing/sid-missing placeholders.
                .executor(cmd -> java.util.concurrent.ForkJoinPool.commonPool().execute(ContextPropagation.wrap(cmd)))
                // Cache key payload is "boundedDisplay:canonical(query)" so one
                // request's smaller fetch cannot starve a later larger request.
                .buildAsync((key, executor) -> {
                    String q = queryFromCacheKey(key);
                    int outboundDisplay = outboundDisplayFromCacheKey(key);
                    SearchPolicy policy = policyFromCacheKey(key);
                    return callNaverApiMono(q, policy, outboundDisplay).toFuture();
                });

        this.recentSnippetCache = Caffeine.newBuilder()
                .maximumSize(4_096)
                .expireAfterWrite(Duration.ofMinutes(10))
                .recordStats()
                .build(k -> Boolean.TRUE);

        // Cache for (text → vector) to eliminate repeated remote embedding calls
        this.locationEmbedCache = Caffeine.newBuilder()
                .maximumSize(4_096)
                .expireAfterAccess(Duration.ofMinutes(30))
                .recordStats()
                .build(key -> embeddingModel.embed(key) // Response<Embedding>
                        .content() // → Embedding
                        .vector()); // → float[]

        // Snippet 저장 시 독립 트랜잭션 사용
        this.txTemplate = new TransactionTemplate(txManager);

        // 🔴 생성자에서 바로 CSV → ApiKey 리스트 초기화
        // - 쉼표 분리(split(","))는 따옴표로 감싼 값("a,b")을 깨뜨릴 수 있으므로
        // 간단한 CSV 파서를 사용한다.
        // - ':' / ';' 모두 허용 (id:secret 또는 id;secret)
        // - [FIX] "id,secret" 형태도 지원한다. (일부 환경에서 ':' 대신 ','로 주입되는 케이스)
        if (!isBlank(this.naverKeysCsv)) {
            List<String> tokens = splitCsv(this.naverKeysCsv).stream()
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(NaverSearchService::stripQuotes)
                    .map(s -> s.replace(";", ":"))
                    .toList();

            List<NaverCredentialBridge.Credential> parsedKeys = new ArrayList<>();
            List<String> bare = new ArrayList<>();

            for (String t : tokens) {
                if (t == null || t.isBlank()) {
                    continue;
                }
                if (t.contains(":")) {
                    String[] p = t.split(":", 2); // secret 쪽 ':' 허용
                    String id = p.length > 0 ? p[0].trim() : "";
                    String sec = p.length > 1 ? p[1].trim() : "";
                    if (!ConfigValueGuards.isMissing(id) && !ConfigValueGuards.isMissing(sec)) {
                        parsedKeys.add(new NaverCredentialBridge.Credential(id, sec));
                    }
                } else if (t.contains(",")) {
                    String[] p = t.split(",", 2);
                    String id = p.length > 0 ? p[0].trim() : "";
                    String sec = p.length > 1 ? p[1].trim() : "";
                    if (!ConfigValueGuards.isMissing(id) && !ConfigValueGuards.isMissing(sec)) {
                        parsedKeys.add(new NaverCredentialBridge.Credential(id, sec));
                    }
                } else {
                    // e.g. "id,secret" 케이스는 splitCsv() 결과로 [id, secret]로 들어온다.
                    bare.add(t);
                }
            }

            // bare 토큰은 (id, secret) 쌍으로 소비
            for (int i = 0; i + 1 < bare.size(); i += 2) {
                String id = bare.get(i) != null ? bare.get(i).trim() : "";
                String sec = bare.get(i + 1) != null ? bare.get(i + 1).trim() : "";
                if (!ConfigValueGuards.isMissing(id) && !ConfigValueGuards.isMissing(sec)) {
                    parsedKeys.add(new NaverCredentialBridge.Credential(id, sec));
                }
            }
            if (bare.size() % 2 == 1) {
                log.warn("[NaverSearch] naver.keys has an odd number of comma-delimited tokens; last token ignored");
            }

            naverKeys = parsedKeys;
            parsedNaverKeyCount = parsedKeys.size();
        }

        // CSV 파싱 결과가 비어 있고, client-id/secret이 있으면 브리지
        if (naverKeysPresent && naverClientPairPresent && !String.valueOf(naverKeysCsv).trim().equals(String.valueOf(this.naverKeysCsv).trim()) && parsedNaverKeyCount > 0) log.warn("[AWX][search][naver] using client-id/secret bridge after naver.keys parse failure sourceName=naver.client-id/NAVER_CLIENT_ID keysPresent={} clientPairPresent={} parsedCount={} fallbackUsed=true", naverKeysPresent, naverClientPairPresent, parsedNaverKeyCount);
        else if (naverKeysPresent && (naverKeys == null || naverKeys.isEmpty())) log.warn("[AWX][search][naver] naver.keys parsing produced no valid keys sourceName=naver.keys present=true parsedCount=0 fallbackUsed=false");

    }

    private static double domainWeight(String url) {
        if (url == null)
            return 0.0;
        String h = url.toLowerCase(Locale.ROOT);
        double w = 0.0;
        // 공식/권위 도메인
        if (h.contains("genshin.hoyoverse.com") || h.contains("hoyolab.com"))
            w += 2.0;
        if (h.contains("hoyoverse.com"))
            w += 1.5;
        if (h.contains("wikipedia.org") || h.endsWith(".go.kr") || h.endsWith(".ac.kr"))
            w += 1.0;
        // 서브컬처/커뮤니티 도메인 (항상 + 가중치)
        if (h.contains("namu.wiki") || h.contains("tistory.com")
                || h.contains("fandom.com") || h.contains("inven.co.kr")
                || h.contains("ruliweb.com") || h.contains("arca.live")) {
            w += 1.0;
        }
        return w;
    }

    private static @Nullable String extractHref(String line) {
        int i = (line == null) ? -1 : line.indexOf("href=\"");
        if (i < 0)
            return null;
        int s = i + 6, e = line.indexOf('"', s);
        return (e > s) ? line.substring(s, e) : null;
    }

    /* ---------- 4. 키 순환 유틸 ---------- */
    private @Nullable NaverCredentialBridge.Credential nextKey() {
        if (naverKeys.isEmpty())
            return null;
        long idx = keyCursor.getAndUpdate(i -> (i + 1) % naverKeys.size());
        return naverKeys.get((int) idx);
    }

    /* === Public API === */

    /** Search using the default topK (LLM 힌트 미사용). */
    /*
     * ───────────────────────────────────────────────
     * 1) ── Reactive(Mono) 이름 → *Mono 로 변경 ──
     * ──────────────────────────────────────────────
     */

    /** Mono 버전(기존 구현) - 새 코드에서만 호출 */
    public Mono<List<String>> searchSnippetsMono(String query) {
        return searchSnippetsInternal(query, webTopK, null, null);
    }

    // ──────────────────────────────────────────────
    // Sync Facade (기존 호출부 호환용 · 임시 block)
    // ──────────────────────────────────────────────

    private Duration resolveSyncBlockTimeout(@Nullable Duration override) {
        long allowedMs = Math.max(250L, syncBlockTimeoutMs);
        if (override != null) {
            try {
                long ms = override.toMillis();
                if (ms > 0L) allowedMs = ms;
            } catch (Throwable ignore) {
                NaverTraceSuppressions.traceSuppressed("syncBlockTimeout.override", ignore);
            }
        }
        TimeBudget budget = TimeBudgetContext.get();
        if (budget != null) allowedMs = budget.capWaitMillis(allowedMs);
        allowedMs = Math.min(allowedMs, com.example.lms.trace.TraceContext.current().remainingMillis());
        return Duration.ofMillis(allowedMs);
    }

    /** (임시) 동기 호출을 원하는 곳에서 사용 - block 시간은 설정/오버라이드 기반 */
    public List<String> searchSnippetsSync(String query, int topK) {
        return searchSnippetsSync(query, topK, null);
    }

    /**
     * 동기 호출(오버로드): 호출자가 더 타이트한 global deadline을 갖는 경우 timeout을 주입할 수 있다.
     */
    public List<String> searchSnippetsSync(String query, int topK, @Nullable Duration blockTimeout) {
        Duration t = resolveSyncBlockTimeout(blockTimeout);
        if (Thread.currentThread().isInterrupted() || t.isZero()) {
            return stoppedSyncSearch(query, topK).snippets();
        }
        try {
            return searchSnippetsMono(query, topK)
                    .blockOptional(t)
                    .orElseGet(List::of);
        } catch (Exception e) {
            NaverTraceSuppressions.traceSuppressed("searchSnippetsSync", e);
            if (e instanceof InterruptedException) {
                restoreInterruptFlag();
            }
            traceNaverFailure(query, topK, naverHttpStatus(e), naverFailureReason(e),
                    isNaverRateLimited(e), isNaverTimeoutFailure(e), naverErrorBody(e), 0L);
            log.debug("[NaverSearchService] searchSnippetsSync failed failureReason={} errorType={} queryHash={} queryLength={} timeoutMs={}",
                    naverFailureReason(e),
                    naverErrorType(e),
                    query == null ? "" : SafeRedactor.hashValue(query),
                    query == null ? 0 : query.length(),
                    t.toMillis());
            return List.of();
        }
    }

    /** 기본 top-K(webTopK) 동기 검색 */
    public List<String> searchSnippetsSync(String query) {
        return searchSnippetsSync(query, webTopK);
    }

    /**
     * Cache-only escape hatch.
     *
     * <p>Returns cached snippets (if present) without triggering any network calls and without
     * consulting breakers. This is intended for last-resort fail-soft paths when web providers are
     * rate-limited / timed out and the caller wants "something" rather than an empty merge.</p>
     *
     * <p>This method never blocks. If the cached entry is currently loading (in-flight) it is treated
     * as a cache miss.</p>
     *
     * <p>Demotion ladder (first hit wins):
     * <ol>
     *   <li>STRICT: {@link SearchPolicy#defaultStrict()}</li>
     *   <li>RELAXED: current config policy (and a boost-variant)</li>
     *   <li>NOFILTER_SAFE: {@link SearchPolicy#freeMode()}</li>
     * </ol>
     * </p>
     */
    public List<String> searchSnippetsCacheOnly(String query, int topK) {
        return searchSnippetsCacheOnly(query, topK, null);
    }

    /**
     * Cache-only escape hatch with an explicit policy ladder.
     *
     * @param ladder optional ordered list of policies to probe; if null, a default ladder is used
     */
    public List<String> searchSnippetsCacheOnly(String query, int topK, @Nullable java.util.List<SearchPolicy> ladder) {
        try {
            int resultLimit = admitNaverTopK(topK);
            if (!StringUtils.hasText(query) || resultLimit <= 0) {
                return List.of();
            }
            if (this.cache == null) {
                return List.of();
            }

            String qTrim = query.trim();
            String cleaned = normalizeQuery(qTrim);
            String normalized = normalizeDeclaratives(cleaned);

            java.util.LinkedHashSet<String> qCandidates = new java.util.LinkedHashSet<>();
            if (StringUtils.hasText(normalized)) qCandidates.add(normalized);
            if (StringUtils.hasText(cleaned)) qCandidates.add(cleaned);
            if (StringUtils.hasText(qTrim)) qCandidates.add(qTrim);

            // Include a few "expanded" variants, but keep it very small for hot-path safety.
            try {
                java.util.List<String> exp = expandQueries(normalized);
                if (exp != null) {
                    for (String e : exp) {
                        if (StringUtils.hasText(e)) {
                            qCandidates.add(e);
                        }
                        if (qCandidates.size() >= 6) break;
                    }
                }
            } catch (Throwable ignore) {
                NaverTraceSuppressions.traceSuppressed("cacheOnly.expandQueries", ignore);
            }

            java.util.LinkedHashSet<SearchPolicy> policies = new java.util.LinkedHashSet<>();
            if (ladder != null && !ladder.isEmpty()) {
                policies.addAll(ladder);
            } else {
                // strict -> config -> config(boost) -> free
                try {
                    policies.add(SearchPolicy.defaultStrict());
                } catch (Throwable suppressed) { NaverTraceSuppressions.traceSuppressed("cacheOnly.policy.defaultStrict", suppressed); }
                try {
                    SearchPolicy cfg = defaultPolicy();
                    if (cfg != null) {
                        policies.add(cfg);
                        if (!"boost".equalsIgnoreCase(cfg.domainPolicy())) {
                            policies.add(new SearchPolicy(cfg.domainFilterEnabled(), cfg.keywordFilterEnabled(), "boost", cfg.keywordMinHits()));
                        }
                    }
                } catch (Throwable suppressed) { NaverTraceSuppressions.traceSuppressed("cacheOnly.policy.config", suppressed); }
                try {
                    policies.add(SearchPolicy.freeMode());
                } catch (Throwable suppressed) { NaverTraceSuppressions.traceSuppressed("cacheOnly.policy.freeMode", suppressed); }
            }

            for (SearchPolicy pol : policies) {
                if (pol == null) continue;

                for (String qv : qCandidates) {
                    if (!StringUtils.hasText(qv)) continue;

                    String key = cacheKeyFor(qv, pol, outboundDisplayFor(resultLimit));
                    java.util.concurrent.CompletableFuture<List<String>> fut = null;
                    try {
                        fut = this.cache.getIfPresent(key);
                    } catch (Throwable ignore) {
                        NaverTraceSuppressions.traceSuppressed("cacheOnly.getIfPresent", ignore); fut = null;
                    }
                    if (fut == null) {
                        continue;
                    }
                    if (!fut.isDone() || fut.isCompletedExceptionally()) {
                        continue; // never block; treat in-flight as miss
                    }

                    List<String> hit;
                    try {
                        hit = fut.getNow(null);
                    } catch (Throwable ignore) {
                        NaverTraceSuppressions.traceSuppressed("cacheOnly.getNow", ignore); hit = null;
                    }
                    if (hit == null || hit.isEmpty()) {
                        continue;
                    }

                    java.util.LinkedHashSet<String> dedup = new java.util.LinkedHashSet<>();
                    for (String s : hit) {
                        if (!StringUtils.hasText(s)) continue;
                        dedup.add(s);
                        if (dedup.size() >= resultLimit) break;
                    }

                    List<String> out = new java.util.ArrayList<>(dedup);
                    try {
                        TraceStore.put("web.naver.cacheOnly.hit", true);
                        TraceStore.put("web.naver.cacheOnly.hit.policy", pol.domainPolicy() + "|" + pol.domainFilterEnabled() + "|" + pol.keywordFilterEnabled());
                        TraceStore.put("web.naver.cacheOnly.hit.queryVariantHash12", SafeRedactor.hash12(qv));
                        TraceStore.put("web.naver.cacheOnly.hit.count", out.size());
                    } catch (Throwable suppressed) { NaverTraceSuppressions.traceSuppressed("cacheOnly.hitTrace", suppressed); }
                    return out;
                }
            }

            try {
                TraceStore.putIfAbsent("web.naver.cacheOnly.miss", true);
            } catch (Throwable suppressed) { NaverTraceSuppressions.traceSuppressed("cacheOnly.missTrace", suppressed); }
            return List.of();
        } catch (Throwable t) {
            // fail-soft: never break request path
            try {
                TraceStore.putIfAbsent("web.naver.cacheOnly.error", "cache_only_failed"); TraceStore.put("web.naver.cacheOnly.errorType", naverErrorType(t));
            } catch (Throwable suppressed) { NaverTraceSuppressions.traceSuppressed("cacheOnly.errorTrace", suppressed); }
            return List.of();
        }
    }

    /** Trace 결과를 동기로 돌려주는 Facade */
    public SearchResult searchWithTraceSync(String query, int topK) {
        return searchWithTraceSync(query, topK, null);
    }

    /** Trace Facade (오버로드): block timeout 주입 */
    public SearchResult searchWithTraceSync(String query, int topK, @Nullable Duration blockTimeout) {
        Duration t = resolveSyncBlockTimeout(blockTimeout);
        if (Thread.currentThread().isInterrupted() || t.isZero()) {
            return stoppedSyncSearch(query, topK);
        }
        long startedNs = System.nanoTime();
        try {
            return searchWithTraceMono(query, topK).block(t);
        } catch (Exception e) {
            NaverTraceSuppressions.traceSuppressed("searchWithTraceSync", e); if (e instanceof InterruptedException) {
                restoreInterruptFlag();
            }
            long tookMs = elapsedMs(startedNs);
            String failureReason = naverFailureReason(e);
            traceNaverFailure(query, topK, naverHttpStatus(e), failureReason,
                    isNaverRateLimited(e), isNaverTimeoutFailure(e), naverErrorBody(e), tookMs);
            log.debug("[NaverSearchService] searchWithTraceSync failed failureReason={} errorType={} queryHash={} queryLength={} timeoutMs={}",
                    failureReason,
                    naverErrorType(e),
                    query == null ? "" : SafeRedactor.hashValue(query),
                    query == null ? 0 : query.length(),
                    t.toMillis());
            // Preserve legacy behaviour as much as possible: return an empty result shell.
            return emptySyncFailure(query, failureReason, tookMs);
        }
    }

    private SearchResult stoppedSyncSearch(String query, int topK) {
        String reason = Thread.currentThread().isInterrupted() ? "cancelled" : "request_budget_exhausted";
        traceNaverFailure(query, topK, -1, reason, false, !"cancelled".equals(reason), null, 0L);
        return emptySyncFailure(query, reason, 0L);
    }

    private SearchResult emptySyncFailure(String query, String reason, long tookMs) {
        SearchTrace trace = new SearchTrace();
        trace.outcomeClass = "cancelled".equals(reason) ? "CLIENT_CANCELLED"
                : reason != null && (reason.contains("timeout") || reason.contains("budget"))
                        ? "TIMEOUT_OR_BUDGET" : "PROVIDER_ERROR";
        trace.query = traceQueryLabel(query);
        trace.queryHash = SafeRedactor.hashValue(query);
        trace.queryLength = query == null ? 0 : query.length();
        trace.queryTokenBucket = queryTokenBucket(query);
        trace.provider = getName();
        trace.totalMs = tookMs;
        trace.steps.add(new SearchStep(reason, 0, 0, tookMs));
        return new SearchResult(List.of(), trace);
    }

    /** LLM 답변까지 받아서 ‘딥 리서치’ 검색을 수행하는 Mono 버전 */
    public Mono<List<String>> searchSnippetsMono(String userPrompt,
            String assistantAnswer,
            int topK) {
        return searchSnippetsInternal(userPrompt, topK, null, assistantAnswer);
    }

    /**
     * 사용자의 쿼리를 검색하면서 동시에 어시스턴트가 생성한 최종 답변을
     * 메모리 서비스에 강화(Reinforce)합니다.
     */
    public Mono<List<String>> searchAndReinforce(String query, String answer) {
        return searchAndReinforce(query, webTopK, answer);
    }

    /** topK 지정 검색 후 답변을 메모리에 강화 */
    public Mono<List<String>> searchAndReinforce(String query, int topK, String answer) {
        return searchSnippetsInternal(query, topK, null, answer)
                .doOnNext(list -> {
                    if (enableAssistantReinforcement && !list.isEmpty()) {
                        reinforceAssistantResponse(query, answer);
                    }
                });
    }

    /** UI(검색 과정 패널) 없이 일반 검색 */
    public Mono<List<String>> searchSnippetsMono(String query, int topK) {
        return searchSnippetsInternal(query, topK, null, null);
    }

    /** UI(검색 과정 패널) 노출을 위해 추적 포함 검색 */
    public Mono<SearchResult> searchWithTraceMono(String query, int topK) {
        SearchTrace trace = new SearchTrace();
        trace.query = traceQueryLabel(query);
        trace.queryHash = SafeRedactor.hashValue(query);
        trace.queryLength = query == null ? 0 : query.length();
        trace.queryTokenBucket = queryTokenBucket(query);
        trace.provider = getName(); // "Naver" 반환
        long t0 = System.nanoTime();
        return searchSnippetsInternal(query, topK, trace, null)
                .map(snippets -> {
                    trace.totalMs = (System.nanoTime() - t0) / 1_000_000L;
                    if (!snippets.isEmpty()) trace.outcomeClass = "NONE";
                    else if ("NONE".equals(trace.outcomeClass)) trace.outcomeClass = "FILTER_ZERO";
                    if ("AUTH_OR_CONFIG".equals(trace.outcomeClass)) {
                        trace.steps.add(new SearchStep("키 미설정으로 호출 생략", 0, 0, 0));
                    }
                    return new SearchResult(snippets, trace);
                });
    }

    /*
     * ───────────────────────────────────────────────
     * 2) ── Sync Facade - “옛 API” 유지 ────────────
     * ──────────────────────────────────────────────
     */

    /** default top-K 동기 검색(List) */
    public List<String> searchSnippets(String query) {
        return searchSnippetsSync(query, webTopK);
    }

    public List<String> searchSnippets(String query, int topK) {
        return searchSnippetsSync(query, topK);
    }

    public List<String> searchSnippets(String userPrompt, String assistantAnswer, int topK) {
        Duration timeout = Duration.ofSeconds(5);
        try {
            return searchSnippetsMono(userPrompt, assistantAnswer, topK).blockOptional(timeout).orElseGet(List::of);
        } catch (Exception e) {
            if (e instanceof InterruptedException) restoreInterruptFlag();
            String reason = naverFailureReason(e);
            traceNaverFailure(userPrompt, topK, naverHttpStatus(e), reason, isNaverRateLimited(e), isNaverTimeoutFailure(e), naverErrorBody(e), 0L);
            log.debug("[NaverSearchService] searchSnippets(answer) failed failureReason={} errorType={} queryHash={} queryLength={} timeoutMs={}", reason, naverErrorType(e), userPrompt == null ? "" : SafeRedactor.hashValue(userPrompt), userPrompt == null ? 0 : userPrompt.length(), timeout.toMillis());
            return List.of();
        }
    }

    /** Trace 포함 동기 버전 */
    @Override
    public SearchResult searchWithTrace(String query, int topK) {
        return searchWithTraceSync(query, topK, Duration.ofSeconds(5));
    }

    /**
     * 실제 검색 본체(일반/추적 공용)
     * - 두 번째 소스의 normalizeQuery / extractTopKeywords를 통합
     * - assistantAnswer 브랜치에 힌트 기반 보강 쿼리 추가
     */
    private Mono<List<String>> searchSnippetsInternal(String query,
            int topK,
            SearchTrace trace,
            @Nullable String assistantAnswer) {
        final int resultLimit = admitNaverTopK(topK);
        if (resultLimit <= 0) {
            return Mono.just(Collections.emptyList());
        }
        final int outboundDisplay = outboundDisplayFor(resultLimit);
        GuardContext ctx = GuardContextHolder.get();
        final Map<String, Object> callerTraceContext = TraceStore.context();
        final boolean boundedRoute = traceBool("web.boundedRoute");
        // Privacy boundary: allow orchestration to block outbound web search entirely.
        // (Higher-level providers already enforce this, but keep a final fail-safe here
        // in case a call path reaches NaverSearchService directly.)
        if (ctx != null) {
            boolean blockAll = ctx.planBool("privacy.boundary.block-web-search", false);
            boolean blockOnSensitive = ctx.planBool("privacy.boundary.block-web-search-on-sensitive", false);
            if (blockAll || (ctx.isSensitiveTopic() && blockOnSensitive)) {
                try {
                    TraceStore.put("privacy.web.blocked", true);
                } catch (Throwable suppressed) { NaverTraceSuppressions.traceSuppressed("privacy.webBlocked", suppressed); }
                log.info(
                        "[NaverSearch] Privacy boundary blocked outbound web search (blockAll={}, sensitive={}, blockOnSensitive={})",
                        blockAll, ctx.isSensitiveTopic(), blockOnSensitive);
                return Mono.just(Collections.emptyList());
            }
        }
        boolean isFreeMode = ctx != null && ("brave".equalsIgnoreCase(ctx.getHeaderMode()) ||
                "zero_break".equalsIgnoreCase(ctx.getHeaderMode()) ||
                "free".equalsIgnoreCase(ctx.getHeaderMode()) ||
                "NONE".equalsIgnoreCase(ctx.getMemoryProfile()));
        boolean isEntitySearch = GuardContext.detectEntityQuery(query);
        if (ctx != null && isEntitySearch) {
            ctx.setEntityQuery(true);
        }

        // ① Guardrail 전처리 적용 ------------------------------------------------
        if (preprocessor != null) {
            String original = query;
            try {
                java.util.Map<String, Object> meta = new java.util.HashMap<>();
                meta.put("purpose", "WEB_SEARCH");
                query = preprocessor.enrich(query, meta);
            } catch (Exception e) {
                try { TraceStore.put("web.naver.preprocessor.failed", true); TraceStore.put("web.naver.preprocessor.failureReason", naverFailureReason(e)); } catch (Throwable suppressed) { NaverTraceSuppressions.traceSuppressed("preprocessor.failureTrace", suppressed); }
                log.warn("[NaverSearch] Preprocessor failed/timeout, fallback to raw query. failureReason={} errorType={} queryHash={} queryLength={}",
                        naverFailureReason(e),
                        naverErrorType(e),
                        original == null ? "" : SafeRedactor.hashValue(original),
                        original == null ? 0 : original.length());
                query = original;
            }
        }

        if (isBlank(query)) {
            return Mono.just(Collections.emptyList());
        }
        if (!hasCreds()) {
            if (trace != null) trace.outcomeClass = "AUTH_OR_CONFIG";
            traceNaverCounts(query, resultLimit, 0, 0, true, naverDisabledReason());
            traceNaverClassifiedOutcome("AUTH_OR_CONFIG", false, true);
            return Mono.just(Collections.emptyList());
        }

        // [FIX] 람다 캡처를 위해 effectively final 변수 생성
        final String effectiveQuery = query;

        // 두 번째 소스의 normalizeQuery + 기존 선언형 정리(normalizeDeclaratives) 결합
        String cleaned = normalizeQuery(query == null ? "" : query.trim());
        String normalized = normalizeDeclaratives(cleaned);

        // 의료/공공/위치 등 엄격한 출처가 요구되는 질의인지 판별
        boolean isMedicalOfficialInfoQuery = isMedicalOfficialInfoQuery(normalized);
        boolean isLocationQuery = isLocationQuery(normalized);
        boolean strictDomainRequired = isMedicalOfficialInfoQuery || isLocationQuery;

        // [PATCH] PlanHint(officialOnly/domainProfile)를 검색 정책에 반영
        GuardContext gctx = GuardContextHolder.get();
        boolean planOfficialOnly = (gctx != null && gctx.isOfficialOnly());
        String domainProfile = (gctx != null ? gctx.getDomainProfile() : null);
        boolean hasDomainProfile = (domainProfile != null && !domainProfile.isBlank());

        // [UAW] If orchestration indicates the web layer is partially degraded
        // (e.g., one engine is down), loosen domain strictness by one step so Naver
        // does not get starved by an overly tight profile while still honoring
        // officialOnly when it is set.
        boolean orchWebPartialDown = traceBool("orch.webPartialDown");
        boolean eligibleForPartialDemotion = orchWebPartialDown && !strictDomainRequired;

        String domainProfileOriginal = domainProfile;
        if (eligibleForPartialDemotion && hasDomainProfile) {
            // Demote: drop domainProfile but keep officialOnly as-is.
            domainProfile = null;
            hasDomainProfile = false;
            try {
                TraceStore.put("web.naver.domainProfileDemoted", true);
                TraceStore.put("web.naver.domainProfileDemoted.reason", "orch.webPartialDown");
                TraceStore.put("web.naver.domainProfileDemoted.original", SafeRedactor.hashValue(domainProfileOriginal));
            } catch (Throwable ignore) {
                NaverTraceSuppressions.traceSuppressed("domainProfile.demotionTrace", ignore);
            }
        }

        boolean planHintStrict = planOfficialOnly || hasDomainProfile;

        boolean strictForcedByPlan = false;
        if (planHintStrict) {
            // When web is partially degraded, do not escalate to the strict policy
            // unless the query itself is high-risk (medical/location) where strictness
            // is non-negotiable.
            if (!eligibleForPartialDemotion) {
                strictDomainRequired = true;
                strictForcedByPlan = true;
            } else {
                try {
                    TraceStore.put("web.naver.strictDomainRequiredDemoted", true);
                    TraceStore.put("web.naver.strictDomainRequiredDemoted.reason", "orch.webPartialDown");
                } catch (Throwable ignore) {
                    NaverTraceSuppressions.traceSuppressed("strictDomain.demotionTrace", ignore);
                }
            }
        }

        // Trace rationale (debug-only)
        try {
            TraceStore.put("web.naver.planHintStrict", planHintStrict);
            TraceStore.put("web.naver.planOfficialOnly", planOfficialOnly);
            TraceStore.put("web.naver.domainProfile", hasDomainProfile ? SafeRedactor.hashValue(domainProfile) : null);
            TraceStore.put("web.naver.strictDomainRequired", strictDomainRequired);
            TraceStore.put("web.naver.strictForcedByPlan", strictForcedByPlan);
            TraceStore.put("web.naver.orchWebPartialDown", orchWebPartialDown);
            TraceStore.put("web.naver.partialDemotionEligible", eligibleForPartialDemotion);
            TraceStore.put("web.naver.isMedicalOfficialInfoQuery", isMedicalOfficialInfoQuery);
            TraceStore.put("web.naver.isLocationQuery", isLocationQuery);
        } catch (Throwable ignore) {
            NaverTraceSuppressions.traceSuppressed("policy.rationaleTrace", ignore);
        }

        boolean academic = isAcademicQuery(normalized);
        // ✨ 요청 시점 정책 계산 (전역 필드 변경 없음)
        SearchPolicy computedPolicy = computePolicy(normalized, isFreeMode, strictDomainRequired);

        // 학술/엔티티 검색은 도메인 필터 완화 (단, planHint로 strict가 강제된 경우는 예외)
        final SearchPolicy policy = (academic || isEntitySearch) && !planHintStrict
                ? computedPolicy.withDomainFilterEnabled(false)
                : computedPolicy;

        // trace 기록 (policy 기반)
        if (trace != null) {
            trace.suffixApplied = deriveLocationSuffix(query);
            trace.domainFilterEnabled = policy.domainFilterEnabled();
            trace.keywordFilterEnabled = policy.keywordFilterEnabled();

            if (!policy.domainFilterEnabled()) {
                if (isFreeMode) {
                    trace.reasonDomainFilterDisabled = "FREE_MODE";
                } else if (academic || isEntitySearch) {
                    trace.reasonDomainFilterDisabled = "ACADEMIC_OR_ENTITY";
                } else if (!this.enableDomainFilter) {
                    trace.reasonDomainFilterDisabled = "PROPERTY_FALSE";
                } else {
                    trace.reasonDomainFilterDisabled = "POLICY_OVERRIDE";
                }
            } else {
                trace.reasonDomainFilterDisabled = null;
            }

            if (!policy.keywordFilterEnabled()) {
                if (isFreeMode) {
                    trace.reasonKeywordFilterDisabled = "FREE_MODE";
                } else if (!this.enableKeywordFilter) {
                    trace.reasonKeywordFilterDisabled = "PROPERTY_FALSE";
                } else {
                    trace.reasonKeywordFilterDisabled = "POLICY_OVERRIDE";
                }
            } else {
                trace.reasonKeywordFilterDisabled = null;
            }
        }

        // assistantAnswer(딥-리서치) 브랜치 - QueryTransformer + 키워드 힌트 통합

        if (!boundedRoute && assistantAnswer != null && !assistantAnswer.isBlank()) {

            Mono<List<String>> qsMono = Mono.fromCallable(() -> {
                // assistantAnswer 기반 확장: 실패/지연 시 즉시 폴백
                List<String> qs;
                try {
                    if (queryTransformer == null) {
                        qs = expandQueries(normalized);
                    } else {
                        // userPrompt=normalized, assistantAnswer=assistantAnswer
                        qs = queryTransformer.transformEnhanced(normalized, assistantAnswer);
                    }
                } catch (Exception e) {
                    log.warn("[Naver] queryTransformer.transformEnhanced failed. errorHash={} errorLength={}", SafeRedactor.hashValue(e.getMessage()), e.getMessage() == null ? 0 : e.getMessage().length());
                    qs = expandQueries(normalized);
                }

                int allowed = Math.max(1, Math.min(expandQueryCandidates, 4));
                qs = Q.filterSimilarQueries(qs, 0.6).stream()
                        .filter(StringUtils::hasText)
                        .distinct()
                        .limit(allowed)
                        .toList();

                AdaptiveSearchQueryVariants.Plan adaptivePlan = planNaverVariants(normalized, qs, false, false);
                qs = adaptivePlan.queries();
                traceNaverAdaptive(adaptivePlan, 0, 0);

                if (qs.isEmpty()) {
                    qs = List.of(effectiveQuery);
                }
                return qs;
            })
                    .subscribeOn(ioScheduler())
                    .timeout(Duration.ofMillis(queryTransformTimeoutMs))
                    .onErrorReturn(List.of(effectiveQuery));
            return qsMono.flatMap(qs -> {
                long perCallMs = Math.max(600, apiTimeoutMs / Math.max(1, qs.size()));
                int n = qs.size();
                long overallMs = Math.max(apiTimeoutMs, perCallMs * n + 150);
                return Flux.fromIterable(qs)
                        .flatMap(q -> withTraceContext(callerTraceContext,
                                () -> callNaverApiMono(q, policy, outboundDisplay))
                                .timeout(Duration.ofMillis(perCallMs)), 2)
                        .flatMapIterable(snippets -> snippets)
                        .map(snippet -> sanitizeSnippet((String) snippet))
                        .filter(s -> StringUtils.hasText(s))
                        .distinct()
                        .take(resultLimit)
                        .timeout(Duration.ofMillis(overallMs))
                        .collectList()
                        .onErrorReturn(Collections.emptyList());
            });
        }

        // 기본 확장 쿼리로 초기화

        Mono<List<String>> expandedQueriesMono = boundedRoute
                ? Mono.just(List.of(normalized))
                : Mono.fromCallable(() -> {
            List<String> base = expandQueries(normalized);
            if (queryTransformer == null)
                return base;

            try {
                // 검색용은 context 최소화(캐시 히트율↑, 비용↓)
                List<String> cand = queryTransformer.transform("", normalized);
                return (cand != null && !cand.isEmpty()) ? cand : base;
            } catch (Exception e) {
                log.warn("[Naver] queryTransformer.transform failed. errorHash={} errorLength={}", SafeRedactor.hashValue(e.getMessage()), e.getMessage() == null ? 0 : e.getMessage().length());
                return base;
            }
        })
                .subscribeOn(ioScheduler())
                .timeout(Duration.ofMillis(queryTransformTimeoutMs))
                .onErrorReturn(expandQueries(normalized));

        if (boundedRoute) {
            traceNaverAdaptiveSkipped("bounded-route");
        }

        return expandedQueriesMono.flatMap(qs -> {
            List<String> expandedQueries = qs;
            int limitQs2 = Math.min(MAX_QUERIES_PER_SEARCH, ratePolicy.allowedExpansions());
            expandedQueries = Q.filterSimilarQueries(expandedQueries, similarThreshold)
                    .stream()
                    .limit(limitQs2)
                    .toList();

            /* ② (개선) 키워드 동의어 확장 - “모두 붙이기” 금지, 별도 변형 구문 고정 */
            // ❌ 동의어 확장 제거 (확장은 상위 LLM 단계가 책임)

            /* ③ 도메인-스코프 프리픽스 완전 제거 (검색 편향 FIX) */
            expandedQueries = expandedQueries.stream()
                    .filter(q -> !DOMAIN_SCOPE_PREFIX.matcher(q).find())
                    .filter(q -> !q.toLowerCase(Locale.ROOT).startsWith("site "))
                    .toList();

            /* 🔽 모든 변형이 제거된 경우 - 원본 쿼리로 대체해 검색 공백 방지 */
            if (expandedQueries.isEmpty()) {
                expandedQueries = List.of(normalized);
            }

            /* ② 중복 차단 & early-exit */
            LinkedHashSet<String> acc = new LinkedHashSet<>();
            // ▶ 순차 실행 조기 종료 (일반 검색 브랜치)
            Flux<String> snippetFlux = Flux.fromIterable(expandedQueries)
                    .flatMap(q -> Mono.defer(() -> awaitCacheFuture(withTraceContext(callerTraceContext,
                                    () -> cache.get(cacheKeyFor(q, policy, outboundDisplay)))))
                            .subscribeOn(ioScheduler()), 3)
                    .flatMapIterable(list -> list)
                    .filter(acc::add) // 중복 제거(LinkedHashSet)
                    .onBackpressureBuffer()
                    .take(resultLimit); // ★ admitted topK 확보 시 즉시 종료

            // ▶ 전체 검색 파이프라인 타임아웃 (상한을 25초로 상향)
            // - perCallMs : 1.5s~3.0s 구간으로 완화 (apiTimeoutMs 기반)
            // - waves : 순차 실행 기준, 쿼리 개수만큼 파동 수 계산
            long perCallMs = Math.min(3000L, Math.max(1500L, apiTimeoutMs));
            int n = Math.max(1, expandedQueries.size());
            int waves = Math.max(1, n);
            // 상한 25.0s, 여유 시간(headroom) 2.0s
            long overallMs = Math.min(12000L, perCallMs * waves + 3000L); // [PATCH] 상한 12.0s, headroom 3.0s

            final String queryCopy2 = effectiveQuery; // capture for reinforcement
            if ("rrf".equalsIgnoreCase(fusionPolicy) && isGamePatchQuery(normalized)) {
                Map<String, Double> rrf = new java.util.HashMap<>();
                return Flux.fromIterable(expandedQueries)
                        .flatMap(
                                q -> Mono.defer(() -> awaitCacheFuture(withTraceContext(callerTraceContext,
                                                () -> cache.get(cacheKeyFor(q, policy, outboundDisplay)))))
                                        .subscribeOn(ioScheduler()),
                                3)
                        .doOnNext(list -> {
                            int rank = 0;
                            for (String s : list) {
                                double add = 1.0 / (60.0 + (++rank)); // k=60
                                rrf.merge(s, add, Double::sum);
                            }
                        })
                        .then(Mono.fromSupplier(() -> rrf.entrySet().stream()
                                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                                .map(Map.Entry::getKey)
                                .limit(resultLimit)
                                .toList()))
                        .timeout(Duration.ofMillis(overallMs))
                        .onErrorResume(e -> {
                            if (e instanceof TimeoutException) {
                                traceNaverFailure(queryCopy2, resultLimit, -1, "timeout", false, true, null, overallMs);
                                log.warn("[AWX2AF2][search][naver] timeout timeoutMs={} queryHash={} queryLength={}",
                                        overallMs, SafeRedactor.hashValue(queryCopy2),
                                        queryCopy2 == null ? 0 : queryCopy2.length());
                            } else {
                                traceNaverFailure(queryCopy2, resultLimit, naverHttpStatus(e), naverFailureReason(e),
                                        isNaverRateLimited(e), isNaverTimeoutFailure(e), naverErrorBody(e), 0L);
                                log.warn("[NaverSearch] Error during search failureReason={} errorType={} queryHash={} queryLength={}",
                                        naverFailureReason(e),
                                        naverErrorType(e),
                                        queryCopy2 == null ? "" : SafeRedactor.hashValue(queryCopy2),
                                        queryCopy2 == null ? 0 : queryCopy2.length());
                            }
                            if (debugEventStore != null) {
                                String fp = "naver.search.error.rrf."
                                        + (e == null ? "null" : e.getClass().getSimpleName());
                                debugEventStore.emit(
                                        DebugProbeType.NAVER_SEARCH,
                                        DebugEventLevel.WARN,
                                        fp,
                                        "NAVER search error (RRF branch)",
                                        "NaverSearchService.searchSnippets",
                                        java.util.Map.of(
                                                "overallMs", overallMs,
                                                "queryHash", SafeRedactor.hashValue(queryCopy2),
                                                "queryLength", queryCopy2 == null ? 0 : queryCopy2.length(),
                                                "queryTokenBucket", queryTokenBucket(queryCopy2)),
                                        null);
                            }
                            return Mono.just(Collections.emptyList());
                        })
                        .doOnNext(list -> reinforceRetrievedSnippets(queryCopy2, list));
            }
            return collectNaverAdaptive(expandedQueries, policy, resultLimit, outboundDisplay,
                    queryCopy2, overallMs, perCallMs,
                    callerTraceContext, boundedRoute, trace)
                    .collectList()
                    .timeout(Duration.ofMillis(overallMs))
                    .doOnNext(list -> {
                        if (list.isEmpty()) {
                            log.debug("[AWX2AF2][search][naver] zero-results-or-timeout timeoutMs={} queryHash={} queryLength={}",
                                    overallMs,
                                    SafeRedactor.hashValue(queryCopy2),
                                    queryCopy2 == null ? 0 : queryCopy2.length());
                        }
                    })
                    .onErrorResume(e -> {
                        if (e instanceof TimeoutException) {
                            traceNaverFailure(queryCopy2, resultLimit, -1, "timeout", false, true, null, overallMs);
                            log.warn("[AWX2AF2][search][naver] timeout timeoutMs={} queryHash={} queryLength={}",
                                    overallMs,
                                    SafeRedactor.hashValue(queryCopy2),
                                    queryCopy2 == null ? 0 : queryCopy2.length());
                        } else {
                            traceNaverFailure(queryCopy2, resultLimit, naverHttpStatus(e), naverFailureReason(e),
                                    isNaverRateLimited(e), isNaverTimeoutFailure(e), naverErrorBody(e), 0L);
                            log.warn("[NaverSearch] Error during search failureReason={} errorType={} queryHash={} queryLength={}",
                                    naverFailureReason(e),
                                    naverErrorType(e),
                                    queryCopy2 == null ? "" : SafeRedactor.hashValue(queryCopy2),
                                    queryCopy2 == null ? 0 : queryCopy2.length());
                        }
                        if (debugEventStore != null) {
                            String fp = "naver.search.error.normal."
                                    + (e == null ? "null" : e.getClass().getSimpleName());
                            debugEventStore.emit(
                                    DebugProbeType.NAVER_SEARCH,
                                    DebugEventLevel.WARN,
                                    fp,
                                    "NAVER search error (normal branch)",
                                    "NaverSearchService.searchSnippets",
                                    java.util.Map.of(
                                            "overallMs", overallMs,
                                            "queryHash", SafeRedactor.hashValue(queryCopy2),
                                            "queryLength", queryCopy2 == null ? 0 : queryCopy2.length(),
                                            "queryTokenBucket", queryTokenBucket(queryCopy2)),
                                    null);
                        }
                        return Mono.just(Collections.emptyList());
                    })
                    .doOnNext(list -> reinforceRetrievedSnippets(queryCopy2, list));
        });
    }

    private static void observeOutcome(SearchTrace trace, NaverAttemptOutcome outcome) {
        if (trace != null) trace.outcomeClass = outcome.failureClass() == null ? "NONE" : outcome.failureClass();
    }

    private Flux<String> collectNaverAdaptive(List<String> candidateQueries,
                                               SearchPolicy policy,
                                               int topK,
                                               int outboundDisplay,
                                               String originalQuery,
                                               long overallMs,
                                               long fallbackPerCallMs,
                                               Map<String, Object> callerTraceContext,
                                               boolean boundedRoute, SearchTrace trace) {
        if (boundedRoute) {
            if (candidateQueries == null || candidateQueries.isEmpty()) {
                return Flux.empty();
            }
            long perCallMs = Math.max(adaptivePerCallFloorMs, fallbackPerCallMs);
            return loadNaverAttempt(candidateQueries.get(0), policy, outboundDisplay, perCallMs, callerTraceContext)
                    .doOnNext(outcome -> observeOutcome(trace, outcome))
                    .flatMapIterable(NaverAttemptOutcome::snippets)
                    .map(snippet -> sanitizeSnippet((String) snippet))
                    .filter(StringUtils::hasText)
                    .take(topK);
        }
        AdaptiveSearchQueryVariants.Plan initialPlan = planNaverVariants(originalQuery, candidateQueries, false, false);
        List<String> queries = initialPlan.queries();
        traceNaverAdaptive(initialPlan, 0, 0);

        if (queries.isEmpty()) {
            return Flux.empty();
        }
        String baseQuery = queries.get(0);
        long firstPerCallMs = Math.max(adaptivePerCallFloorMs,
                Math.min(Math.max(adaptivePerCallFloorMs, fallbackPerCallMs), initialPlan.perCallMs()));
        long discoveryDeadlineNs = System.nanoTime()
                + Math.max(1L, Math.min(overallMs, initialPlan.budgetMs())) * 1_000_000L;

        return loadNaverAttempt(baseQuery, policy, outboundDisplay, firstPerCallMs, callerTraceContext)
                .doOnNext(outcome -> observeOutcome(trace, outcome))
                .flatMapMany(baseOutcome -> {
                    LinkedHashSet<String> acc = new LinkedHashSet<>();
                    if (baseOutcome.snippets() != null) {
                        for (String snippet : baseOutcome.snippets()) {
                            String sanitized = sanitizeSnippet(snippet);
                            if (StringUtils.hasText(sanitized)) {
                                acc.add(sanitized);
                            }
                            if (acc.size() >= topK) {
                                break;
                            }
                        }
                    }

                    boolean trueZero = baseOutcome.allowsBoundedVariant();
                    AdaptiveSearchQueryVariants.Plan resultPlan = planNaverVariants(
                            originalQuery,
                            queries,
                            trueZero,
                            false);
                    traceNaverAdaptive(resultPlan, acc.size(), acc.size());

                    List<String> resultQueries = resultPlan.queries();
                    List<String> variants = resultQueries.size() <= 1
                            ? List.of()
                            : resultQueries.subList(1, resultQueries.size());
                    boolean promotionDiscovery = adaptiveSearchEnabled
                            && GrokPromotionDiscovery.matches(originalQuery)
                            && (trueZero || baseOutcome.failureClass() == null);
                    boolean shouldRunVariants = !variants.isEmpty()
                            && (promotionDiscovery || acc.size() < topK)
                            && (promotionDiscovery || trueZero);

                    if (!shouldRunVariants) {
                        return Flux.fromIterable(acc);
                    }

                    long perCallMs = Math.max(adaptivePerCallFloorMs,
                            Math.min(Math.max(adaptivePerCallFloorMs, fallbackPerCallMs), resultPlan.perCallMs()));
                    if (promotionDiscovery) {
                        if (callerTraceContext != null) callerTraceContext.put("web.naver.promotionDiscovery.reason",
                                acc.isEmpty() ? "base_empty" : "base_results_not_exhaustive");
                        // Do not emit the full base first: downstream take(topK) would cancel discovery.
                        return Flux.fromIterable(variants)
                                .concatMap(q -> Mono.defer(() -> {
                                    long remainingMs = (discoveryDeadlineNs - System.nanoTime()) / 1_000_000L;
                                    if (remainingMs < Math.max(1L, adaptivePerCallFloorMs)) {
                                        if (callerTraceContext != null) callerTraceContext.put(
                                                "web.naver.promotionDiscovery.stopReason", "budget");
                                        return Mono.<NaverAttemptOutcome>empty();
                                    }
                                    return loadNaverAttempt(q, policy, outboundDisplay,
                                            Math.min(perCallMs, remainingMs), callerTraceContext);
                                }))
                                .takeUntil(outcome -> outcome.failureClass() != null && !outcome.allowsBoundedVariant())
                                .collectList()
                                .flatMapMany(outcomes -> {
                                    List<List<String>> lanes = new ArrayList<>();
                                    for (NaverAttemptOutcome outcome : outcomes) {
                                        lanes.add(outcome.snippets().stream()
                                                .map(NaverSearchService::sanitizeSnippet)
                                                .filter(StringUtils::hasText).toList());
                                    }
                                    lanes.add(new ArrayList<>(acc));
                                    return Flux.fromIterable(GrokPromotionDiscovery.mergeEvidence(lanes, topK));
                                });
                    }
                    return Flux.concat(
                            Flux.fromIterable(acc),
                            Flux.fromIterable(variants)
                                    .concatMap(q -> loadNaverAttempt(q, policy, outboundDisplay,
                                            perCallMs, callerTraceContext).doOnNext(outcome -> observeOutcome(trace, outcome)))
                                    .takeUntil(outcome -> !outcome.allowsBoundedVariant())
                                    .flatMapIterable(NaverAttemptOutcome::snippets)
                                    .map(snippet -> sanitizeSnippet((String) snippet))
                                    .filter(StringUtils::hasText)
                                    .filter(acc::add))
                            .take(topK);
                });
    }

    private Mono<NaverAttemptOutcome> loadNaverAttempt(String query,
                                                       SearchPolicy policy,
                                                       long timeoutMs,
                                                       Map<String, Object> callerTraceContext) {
        int defaultLimit = admitNaverTopK(webTopK);
        return loadNaverAttempt(query, policy, outboundDisplayFor(defaultLimit), timeoutMs, callerTraceContext);
    }

    private Mono<NaverAttemptOutcome> loadNaverAttempt(String query,
                                                       SearchPolicy policy,
                                                       int outboundDisplay,
                                                       long timeoutMs,
                                                       Map<String, Object> callerTraceContext) {
        final String cacheKey = cacheKeyFor(query, policy, outboundDisplay);
        return Mono.defer(() -> {
                    java.util.concurrent.CompletableFuture<List<String>> resultFuture =
                            withTraceContext(callerTraceContext, () -> cache.get(cacheKey));
                    resultFuture.whenComplete((snippets, error) -> {
                        if (error == null && (snippets == null || snippets.isEmpty())) {
                            cache.asMap().remove(cacheKey, resultFuture);
                        }
                    });
                    return awaitCacheFuture(resultFuture);
                })
                .subscribeOn(ioScheduler())
                .timeout(Duration.ofMillis(timeoutMs))
                .map(NaverAttemptOutcome::fromSnippets)
                .doOnNext(outcome -> {
                    if (outcome.allowsBoundedVariant()) {
                        withTraceContext(callerTraceContext, () -> {
                            traceNaverClassifiedOutcome("TRUE_ZERO", true, false);
                            return null;
                        });
                    }
                })
                .onErrorResume(error -> {
                    NaverClassifiedFailure classified = findNaverClassifiedFailure(error);
                    String failureClass = classified == null
                            ? (isNaverTimeoutFailure(error) ? "TIMEOUT_OR_BUDGET" : "PROVIDER_ERROR")
                            : classified.failureClass;
                    if (classified == null) {
                        traceNaverClassifiedOutcome(failureClass, true, true);
                    }
                    return Mono.just(NaverAttemptOutcome.failure(failureClass));
                });
    }

    private AdaptiveSearchQueryVariants.Plan planNaverVariants(String originalQuery,
                                                               List<String> baseQueries,
                                                               boolean providerEmpty,
                                                               boolean afterFilterStarved) {
        long maxOverallMs = Math.max(adaptivePerCallFloorMs, adaptiveMaxOverallTimeoutMs);
        return AdaptiveSearchQueryVariants.plan(
                originalQuery,
                baseQueries,
                new AdaptiveSearchQueryVariants.Options(
                        AdaptiveSearchQueryVariants.Provider.NAVER,
                        adaptiveSearchEnabled,
                        Math.min(MAX_QUERIES_PER_SEARCH, Math.max(1, adaptiveMaxQueries)),
                        maxOverallMs,
                        maxOverallMs,
                        Math.max(1L, adaptivePerCallFloorMs),
                        adaptiveRecallModeRequested(),
                        providerEmpty,
                        afterFilterStarved));
    }

    private static boolean adaptiveRecallModeRequested() {
        Object recall = TraceStore.get("chatApi.web.recallModeRequested");
        if (Boolean.TRUE.equals(recall)) {
            return true;
        }
        Object workflowRecall = TraceStore.get("web.query.rewrite.recallModeRequested");
        if (Boolean.TRUE.equals(workflowRecall)) {
            return true;
        }
        String searchMode = String.valueOf(TraceStore.get("chatApi.web.searchMode"));
        return "FORCE_DEEP".equalsIgnoreCase(searchMode)
                || "RECALL".equalsIgnoreCase(searchMode)
                || "RECALL".equalsIgnoreCase(String.valueOf(TraceStore.get("search.policy.mode")))
                || "exploratory".equalsIgnoreCase(String.valueOf(TraceStore.get("search.policy.rewriteTemperatureProfile")))
                || "exploratory".equalsIgnoreCase(String.valueOf(TraceStore.get("web.query.rewrite.requestedTemperatureProfile")));
    }

    private void traceNaverAdaptive(AdaptiveSearchQueryVariants.Plan plan, int returnedCount, int afterFilterCount) {
        try {
            if (plan == null) {
                return;
            }
            TraceStore.put("web.naver.adaptive.enabled", plan.enabled());
            TraceStore.put("web.naver.adaptive.triggerReason", SafeRedactor.traceLabelOrFallback(plan.triggerReason(), "unknown"));
            TraceStore.put("web.naver.adaptive.variantCount", Math.max(0, plan.queries().size() - 1));
            TraceStore.put("web.naver.adaptive.sliceCount", Math.max(0, plan.sliceCount()));
            TraceStore.put("web.naver.adaptive.expansionCount", Math.max(0, plan.expansionCount()));
            TraceStore.put("web.naver.adaptive.budgetMs", Math.max(0L, plan.budgetMs()));
            TraceStore.put("web.naver.adaptive.perCallMs", Math.max(0L, plan.perCallMs()));
            TraceStore.put("web.naver.adaptive.temperatureProfile",
                    SafeRedactor.traceLabelOrFallback(plan.temperatureProfile(), "unknown"));
            TraceStore.put("web.naver.adaptive.validationTemperature", plan.validationTemperature());
            TraceStore.put("web.naver.adaptive.explorationTemperature", plan.explorationTemperature());
            TraceStore.put("web.naver.adaptive.explorationRate", plan.explorationRate());
            AdaptiveSearchQueryVariants.Diagnostics diagnostics = plan.diagnostics();
            List<String> variantLaneTemperatureHints = plan.variantLaneTemperatureHints();
            TraceStore.put("web.naver.adaptive.querySeedHash12", diagnostics.querySeedHash12());
            TraceStore.put("web.naver.adaptive.variantSetHash12", diagnostics.variantSetHash12());
            TraceStore.put("web.naver.adaptive.verificationLaneCount", diagnostics.verificationLaneCount());
            TraceStore.put("web.naver.adaptive.explorationLaneCount", diagnostics.explorationLaneCount());
            TraceStore.put("web.naver.adaptive.laneLabels", diagnostics.laneLabels());
            TraceStore.put("web.naver.adaptive.variantLaneTemperatureHints", variantLaneTemperatureHints);
            TraceStore.put("web.naver.adaptive.returnedCount", Math.max(0, returnedCount));
            TraceStore.put("web.naver.adaptive.afterFilterCount", Math.max(0, afterFilterCount));
            TraceStore.put("web.query.variantCount", Math.max(0, plan.queries().size() - 1));
            TraceStore.put("web.query.rewrite.temperatureProfile",
                    SafeRedactor.traceLabelOrFallback(plan.temperatureProfile(), "unknown"));
            TraceStore.put("web.query.rewrite.validationTemperature", plan.validationTemperature());
            TraceStore.put("web.query.rewrite.explorationTemperature", plan.explorationTemperature());
            TraceStore.put("web.query.rewrite.explorationRate", plan.explorationRate());
            TraceStore.put("web.query.rewrite.querySeedHash12", diagnostics.querySeedHash12());
            TraceStore.put("web.query.rewrite.variantSetHash12", diagnostics.variantSetHash12());
            TraceStore.put("web.query.rewrite.verificationLaneCount", diagnostics.verificationLaneCount());
            TraceStore.put("web.query.rewrite.explorationLaneCount", diagnostics.explorationLaneCount());
            TraceStore.put("web.query.rewrite.laneLabels", diagnostics.laneLabels());
            TraceStore.put("web.query.rewrite.laneSummary", diagnostics.laneSummary());
            TraceStore.put("web.query.rewrite.variantLaneTemperatureHints", variantLaneTemperatureHints);
            TraceStore.put("web.rewritePlan.seedHash12", diagnostics.querySeedHash12());
            TraceStore.put("web.rewritePlan.variantHash12", diagnostics.variantSetHash12());
            TraceStore.put("web.rewritePlan.verificationCount", diagnostics.verificationLaneCount());
            TraceStore.put("web.rewritePlan.explorationCount", diagnostics.explorationLaneCount());
            TraceStore.put("web.rewritePlan.laneSummary", diagnostics.laneSummary());
            TraceStore.put("web.rewritePlan.variantLaneTemperatureHints", variantLaneTemperatureHints);
        } catch (Throwable ignore) {
            NaverTraceSuppressions.traceSuppressed("adaptive.telemetry", ignore);
        }
    }

    private void traceNaverAdaptiveSkipped(String reason) {
        try {
            TraceStore.put("web.naver.adaptive.enabled", false);
            TraceStore.put("web.naver.adaptive.triggerReason",
                    SafeRedactor.traceLabelOrFallback(reason, "skipped"));
            TraceStore.put("web.naver.adaptive.variantCount", 0);
            TraceStore.put("web.naver.adaptive.sliceCount", 0);
            TraceStore.put("web.naver.adaptive.expansionCount", 0);
            TraceStore.put("web.query.variantCount", 0);
        } catch (Throwable ignore) {
            NaverTraceSuppressions.traceSuppressed("adaptive.skippedTelemetry", ignore);
        }
    }

    /**
     * Merge RAG context (local + external) with web snippets.
     */
    public List<String> combinedContext(String query) {
        final String sid = String.valueOf(sessionIdProvider.get());
        List<String> localCtx = new ArrayList<>();
        List<String> remoteCtx = new ArrayList<>();
        if (!isBlank(query)) {
            // local RAG
            try {
                var localRetriever = EmbeddingStoreContentRetriever.builder()
                        .embeddingStore(embeddingStore)
                        .embeddingModel(embeddingModel)
                        .maxResults(ragTopK)
                        .build();
                // [HARDENING] build query with session metadata for isolation
                dev.langchain4j.rag.query.Query qObj = com.example.lms.service.rag.QueryUtils.buildQuery(
                        query,
                        java.util.Map.of(com.example.lms.service.rag.LangChainRAGService.META_SID, sid));
                localCtx = localRetriever.retrieve(qObj)
                        .stream()
                        .filter(c -> {
                            Map<?, ?> md = c.metadata();
                            return md != null && sid.equals(
                                    String.valueOf(md.get(com.example.lms.service.rag.LangChainRAGService.META_SID)));
                        })
                        .map(Content::toString)
                        .toList();
            } catch (Exception e) {
                NaverTraceSuppressions.traceSuppressed("combinedContext.localRag", e); log.warn("Local RAG retrieval failed. errorHash={} errorLength={}", SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length());
            }
            // include full conversation context stored in memory
            try {
                String memCtx = memorySvc.loadContext(sid);
                if (!isBlank(memCtx)) {
                    Arrays.stream(memCtx.split("\\r?\\n"))
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .forEach(localCtx::add);
                }
            } catch (Exception ignore) {
                NaverTraceSuppressions.traceSuppressed("combinedContext.memory", ignore);
            }
            // external RAG
            ContentRetriever ext = retrieverProvider.getIfAvailable();
            if (ext != null) {
                // [HARDENING] build query with session metadata for external retriever
                dev.langchain4j.rag.query.Query qExt = com.example.lms.service.rag.QueryUtils.buildQuery(
                        query,
                        java.util.Map.of(com.example.lms.service.rag.LangChainRAGService.META_SID, sid));
                remoteCtx = ext.retrieve(qExt).stream()
                        .limit(ragTopK)
                        .filter(c -> {
                            Map<?, ?> md = c.metadata();
                            return md != null && sid.equals(
                                    String.valueOf(md.get(com.example.lms.service.rag.LangChainRAGService.META_SID)));
                        })
                        .map(Content::toString)
                        .toList();
            }
        }
        /* A. 기존 호출부가 동기 컨텍스트를 기대하므로 임시로 block() */
        List<String> webCtx = searchSnippetsMono(query)
                .blockOptional()
                .orElseGet(List::of);
        return Stream.of(localCtx, remoteCtx, webCtx)
                .flatMap(Collection::stream)
                .distinct()
                .toList();
    }

    /* === Internal API call === */

    /**
     * Call the Naver API with the given query. No marketplace expansion or
     * site restriction is applied-only the query (plus optional suffix)
     * is used. Domain allow/deny and keyword filtering are enforced.
     */
    // ─── 기존 호환 ───
    private Mono<List<String>> callNaverApiMono(String query) {
        return callNaverApiMono(query, defaultPolicy(), configuredNaverMaximum());
    }

    // ─── 신규: 정책 파라미터 버전 ───
    private Mono<List<String>> callNaverApiMono(String query, SearchPolicy policy) {
        return callNaverApiMono(query, policy, configuredNaverMaximum());
    }

    private Mono<List<String>> callNaverApiMono(String query,
                                                SearchPolicy policy,
                                                int outboundDisplay) {
        int boundedDisplay = Math.min(configuredNaverMaximum(), clampNaverDisplay(outboundDisplay));
        return Mono.defer(() -> callNaverApiMonoSubscribed(query, policy, boundedDisplay));
    }

    /** One subscribed cache load owns one breaker permit through response parsing. */
    private Mono<List<String>> callNaverApiMonoSubscribed(String query,
                                                         SearchPolicy policy,
                                                         int fetch) {
        if (isBlank(query)) {
            return Mono.just(Collections.emptyList());
        }
        if (!hasCreds()) {
            // 네이버 키가 없으면 이 서비스는 조용히 빈 결과를 반환한다.
            // (폴백은 상위 Provider/Orchestrator가 담당)
            log.warn(
                    "Naver client id/secret not configured; returning empty results (provider fallback will handle). ");
            traceNaverCounts(query, 0, 0, 0, true, naverDisabledReason());
            traceNaverClassifiedOutcome("AUTH_OR_CONFIG", false, true);
            return Mono.error(new NaverClassifiedFailure("AUTH_OR_CONFIG"));
        }

        final TimeBudget requestBudget = TimeBudgetContext.get();
        if (requestBudget != null && requestBudget.expired()) {
            return expiredNaverSubscription(query, fetch, null, TraceStore.context());
        }
        String apiQuery = appendLocationSuffix(query);

        // Apply a short cooldown lock before invoking the external API. When a lock
        // cannot be acquired the service skips the Naver call and returns an empty
        // list.
        // (폴백은 상위 Provider/Orchestrator가 담당)
        // The key is
        // derived from the query to avoid locking unrelated requests. A TTL
        // of one second prevents thundering herd retries while allowing
        // subsequent calls to proceed quickly after the lock expires.
        if (cooldownService != null) {
            try {
                String lockKey = "naver:api:" + org.apache.commons.codec.digest.DigestUtils.md5Hex(apiQuery);
                boolean acquired = cooldownService.setNxEx(lockKey, "1", 1);
                if (!acquired) {
                    log.debug("[AWX2AF2][search][naver] cooldown active queryHash={} queryLength={} -> skipping API call",
                            SafeRedactor.hashValue(apiQuery),
                            apiQuery == null ? 0 : apiQuery.length());
                    traceNaverClassifiedOutcome("BREAKER_OR_COOLDOWN", false, true);
                    return Mono.error(new NaverClassifiedFailure("BREAKER_OR_COOLDOWN"));
                }
            } catch (Exception ignore) {
                NaverTraceSuppressions.traceSuppressed("cooldown.lock", ignore);
            }
        }

        // NOTE: Use absolute URL to remain resilient even if WebClient baseUrl
        // is misconfigured.
        URI uri = UriComponentsBuilder.fromHttpUrl("https://openapi.naver.com/v1/search/webkr.json")
                .queryParam("query", apiQuery)
                .queryParam("display", fetch)
                .queryParam("start", 1)
                .build()
                .encode(StandardCharsets.UTF_8)
                .toUri();
        NaverCredentialBridge.Credential first = nextKey();
        if (first == null) {
            traceNaverCounts(query, fetch, 0, 0, true, naverDisabledReason());
            traceNaverClassifiedOutcome("AUTH_OR_CONFIG", false, true);
            return Mono.error(new NaverClassifiedFailure("AUTH_OR_CONFIG"));
        }

        final NightmareBreaker.CallPermit permit;
        if (nightmareBreaker == null) {
            permit = null;
        } else {
            try {
                permit = nightmareBreaker.acquire(NightmareKeys.WEBSEARCH_NAVER, "wire");
            } catch (NightmareBreaker.OpenCircuitException e) {
                long remainingMs = nightmareBreaker.remainingOpenMs(NightmareKeys.WEBSEARCH_NAVER);
                NaverTraceSignals.traceBreakerOpen(remainingMs);
                log.warn("[Naver API] NightmareBreaker OPEN for Naver (remain={}ms) - skipping API call",
                        remainingMs);
                traceNaverClassifiedOutcome("BREAKER_OR_COOLDOWN", false, true);
                return Mono.error(new NaverClassifiedFailure("BREAKER_OR_COOLDOWN"));
            }
        }

        String randomAgent = USER_AGENTS[new java.util.Random().nextInt(USER_AGENTS.length)];
        final long startedNs = System.nanoTime();
        final Map<String, Object> capturedTraceContext = TraceStore.context();

        // Capture correlation IDs on the caller thread (before reactive/Netty
        // boundary).
        // This prevents rid-missing/sid-missing in outbound headers when MDC/TraceStore
        // is not
        // propagated onto the WebClient execution thread.
        final String capturedRid = firstNonBlank(
                org.slf4j.MDC.get("x-request-id"),
                org.slf4j.MDC.get("traceId"),
                org.slf4j.MDC.get("trace"),
                (String) TraceStore.get("trace.id"),
                (String) TraceStore.get("requestId"));
        final String capturedSid = firstNonBlank(
                org.slf4j.MDC.get("sid"),
                org.slf4j.MDC.get("sessionId"),
                (String) TraceStore.get("sid"),
                (String) TraceStore.get("sessionId"));

        Mono<ResponseEntity<String>> wire = web.get()
                .uri(uri)
                .headers(h -> {
                    if (capturedRid != null && !capturedRid.isBlank()) {
                        h.set("x-request-id", capturedRid);
                    }
                    if (capturedSid != null && !capturedSid.isBlank()) {
                        h.set("x-session-id", capturedSid);
                    }
                })
                .header("X-Naver-Client-Id", first.id())
                .header("X-Naver-Client-Secret", first.secret())
                .header("User-Agent", randomAgent)
                .retrieve()
                .toEntity(String.class);
        long subscriptionDelayMs = Math.max(0L, Math.min(200L, ratePolicy.currentDelayMs()));
        if (requestBudget != null) subscriptionDelayMs = requestBudget.capWaitMillis(subscriptionDelayMs);
        java.util.concurrent.atomic.AtomicReference<NaverObservation> latestObservation = new java.util.concurrent.atomic.AtomicReference<>();
        Mono<ObservedNaverResponse> primary = Mono.defer(() -> {
                    // Re-evaluated on initial subscription and every retry. Never cancel a wire
                    // operation already shared by cache waiters merely because one waiter left.
                    if (requestBudget != null && requestBudget.expired()) {
                        return expiredNaverSubscription(query, fetch, permit, capturedTraceContext);
                    }
                    var attemptContext = TraceStore.searchContext(capturedTraceContext, "providerAttemptId");
                    var observation = new NaverObservation(query, attemptContext, true);
                    latestObservation.set(observation);
                    return wire.map(entity -> new ObservedNaverResponse(entity, observation))
                            .doOnCancel(() -> observation.put("clientCancellationDelivered", true))
                            .doOnError(error -> {
                                int status = naverHttpStatus(error);
                                if (status > 0) observation.put("httpStatus", status);
                                observation.put("failureClass", classifyNaverFailureClass(status,
                                        isNaverRateLimited(error), isNaverTimeoutFailure(error), naverFailureReason(error)));
                                observation.finish();
                            });
                })
                // 구독 지연(레이트리밋/Retry-After 반영)
                .delaySubscription(Duration.ofMillis(subscriptionDelayMs))
                // ECO-FIX v3.0: 일관된 타임아웃만 적용하고 재시도는 상위 WebSearchRetriever에서 수행.
                .timeout(Duration.ofMillis(apiTimeoutMs));

        // Parse and retry logic for the primary call. Errors are mapped to an empty
        // list
        // so that the upper provider/orchestrator can decide whether to call another
        // engine.
        boolean boundedRoute = traceBool("web.boundedRoute");
        if (retryMaxAttempts > 0 && !boundedRoute) {
            primary = primary.retryWhen(
                    Retry.backoff(retryMaxAttempts, Duration.ofMillis(Math.max(0L, retryInitialBackoffMs)))
                            .maxBackoff(Duration.ofMillis(Math.max(retryInitialBackoffMs, retryMaxBackoffMs)))
                            .jitter(Math.max(0.0d, Math.min(1.0d, retryJitter)))
                            .filter(error -> (requestBudget == null || !requestBudget.expired())
                                    && isRetryableNaverError(error))
                            .doBeforeRetry(rs -> {
                                try {
                                    TraceStore.inc("web.naver.retry.count");
                                    TraceStore.put("web.naver.retry.last", String.valueOf(rs.totalRetries() + 1));
                                    TraceStore.put("web.naver.retry.lastHttpStatus", naverHttpStatus(rs.failure()));
                                    TraceStore.put("web.naver.retry.lastFailureClass", classifyNaverFailureClass(
                                            naverHttpStatus(rs.failure()), isNaverRateLimited(rs.failure()),
                                            isNaverTimeoutFailure(rs.failure()), naverFailureReason(rs.failure())));
                                } catch (Exception suppressed) { NaverTraceSuppressions.traceSuppressed("retry.countTrace", suppressed); }
                            }));
        } else if (retryMaxAttempts > 0) {
            try {
                TraceStore.put("web.naver.retry.skipped", true);
                TraceStore.put("web.naver.retry.skipReason", "bounded-route");
            } catch (Exception suppressed) {
                NaverTraceSuppressions.traceSuppressed("retry.boundedTrace", suppressed);
            }
        }

        Mono<List<String>> primaryParsed = primary
                .map(observed -> {
                    ResponseEntity<String> entity = observed.entity();
                    NaverObservation observation = observed.observation();
                    observation.put("httpStatus", entity.getStatusCode().value());
                    try {
                        ratePolicy.updateFromHeaders(entity.getHeaders());
                    } catch (Exception suppressed) { NaverTraceSuppressions.traceSuppressed("ratePolicy.headers.success", suppressed); }

                    String json = entity.getBody();
                    if (debugJson && json != null) {
                        log.debug("[AWX2AF2][search][naver] raw response received bodyLen={} bodyHash={} queryHash={}",
                                json.length(), SafeRedactor.hashValue(json), SafeRedactor.hashValue(query));
                    }
                    if (isBlank(json)) {
                        if (permit != null) {
                            permit.completeBlank("body");
                        }
                        traceNaverClassifiedOutcome("PROVIDER_ERROR", true, true);
                        observation.put("failureClass", "EMPTY_BODY");
                        observation.finish();
                        throw new NaverClassifiedFailure("PROVIDER_ERROR");
                    }
                    try {
                        List<String> parsed = withTraceContext(observation.context,
                                () -> parseNaverResponse(query, json, policy, observation));
                        if (permit != null) {
                            permit.completeSuccess(elapsedMs(startedNs));
                        }
                        return parsed;
                    } catch (NaverClassifiedFailure classified) {
                        if (permit != null) {
                            if ("FILTER_ZERO".equals(classified.failureClass)) {
                                permit.completeAbandoned("parse", "filter-zero");
                            } else {
                                permit.completeFailure(NightmareBreaker.FailureKind.UNKNOWN,
                                        classified, "parse");
                            }
                        }
                        throw classified;
                    }
                })

                .onErrorResume(WebClientResponseException.class, e -> {
                    int sc = e.getStatusCode().value();
                    long tookMs = elapsedMs(startedNs);
                    String failureClass = classifyNaverFailureClass(sc, sc == 429, false, "http-error");
                    String failureReason = sc == 429 ? "rate-limit" : "http-error";

                    // Even on error, we may still have Retry-After style headers.
                    try {
                        ratePolicy.updateFromHeaders(e.getHeaders());
                    } catch (Exception suppressed) { NaverTraceSuppressions.traceSuppressed("ratePolicy.headers.error", suppressed); }

                    long retryAfterMs = sc == 429 ? Math.max(0L, ratePolicy.retryAfterMs()) : 0L;
                    if (permit != null) {
                        if (sc == 429) {
                            permit.completeRateLimit(
                                    "wire",
                                    e,
                                    "http-429",
                                    retryAfterMs > 0 ? retryAfterMs : null);
                        } else {
                            NightmareBreaker.FailureKind kind;
                            if (sc >= 500) {
                                kind = NightmareBreaker.FailureKind.HTTP_5XX;
                            } else {
                                kind = NightmareBreaker.FailureKind.HTTP_4XX;
                            }
                            permit.completeFailure(kind, e, "wire");
                        }
                    }

                    if (sc == 429) {
                        TraceStore.put("web.rateLimited", true);
                        TraceStore.put("web.naver.429", true);
                    }

                    withTraceContext(capturedTraceContext, () -> {
                        if (sc == 429) {
                            traceNaverRemoteCooldown("rate-limit", retryAfterMs);
                        }
                        traceNaverFailure(query, fetch, sc, failureReason,
                                sc == 429, false, e.getResponseBodyAsString(), tookMs);
                        traceNaverClassifiedOutcome(failureClass, true, true);
                        return null;
                    });
                    log.warn("[AWX][search][naver] api failed failureReason={} httpStatus={} queryHash={} queryLength={} tookMs={}",
                            failureReason,
                            sc,
                            query == null ? "" : SafeRedactor.hashValue(query),
                            query == null ? 0 : query.length(),
                            tookMs);
                    return Mono.error(new NaverClassifiedFailure(failureClass));
                })
                .onErrorResume(t -> {
                    NaverClassifiedFailure classified = findNaverClassifiedFailure(t);
                    if (classified != null) {
                        return Mono.error(classified);
                    }
                    if (t instanceof InterruptedException) {
                        restoreInterruptFlag();
                    }

                    if (permit != null) {
                        NightmareBreaker.FailureKind kind = NightmareBreaker.classify(t);
                        if (kind == NightmareBreaker.FailureKind.INTERRUPTED) {
                            permit.completeCancelled(t, "wire");
                        } else if (kind == NightmareBreaker.FailureKind.RATE_LIMIT) {
                            permit.completeRateLimit("wire", t, "rate-limit", null);
                        } else {
                            permit.completeFailure(kind, t, "wire");
                        }
                    }

                    long tookMs = elapsedMs(startedNs);
                    String failureReason = naverFailureReason(t);
                    String failureClass = classifyNaverFailureClass(
                            naverHttpStatus(t),
                            isNaverRateLimited(t),
                            isNaverTimeoutFailure(t),
                            failureReason);
                    withTraceContext(capturedTraceContext, () -> {
                        traceNaverFailure(query, fetch, naverHttpStatus(t), failureReason,
                                isNaverRateLimited(t), isNaverTimeoutFailure(t), naverErrorBody(t), tookMs);
                        traceNaverClassifiedOutcome(failureClass, true, true);
                        return null;
                    });
                    log.warn("[AWX][search][naver] api failed failureReason={} errorType={} queryHash={} queryLength={} tookMs={}",
                            failureReason,
                            naverErrorType(t),
                            SafeRedactor.hashValue(query),
                            query == null ? 0 : query.length(),
                            tookMs);
                    return Mono.error(new NaverClassifiedFailure(failureClass));
                })
                .doFinally(signalType -> {
                    NaverObservation observation = latestObservation.get();
                    if (observation != null && !observation.published.get()) {
                        observation.put("failureClass", signalType == reactor.core.publisher.SignalType.CANCEL
                                ? "CLIENT_CANCELLED" : "TIMEOUT_OR_BUDGET");
                        observation.finish();
                    }
                    if (permit != null && signalType == reactor.core.publisher.SignalType.CANCEL) {
                        permit.completeCancelled(null, "reactor-cancel");
                    }
                });

        return primaryParsed.map(items -> items.stream().limit(fetch).toList());

    }

    private <T> Mono<T> expiredNaverSubscription(String query, int fetch,
                                                NightmareBreaker.CallPermit permit,
                                                Map<String, Object> capturedTraceContext) {
        if (permit != null) permit.completeAbandoned("wire", "request_budget_exhausted");
        withTraceContext(capturedTraceContext, () -> {
            traceNaverFailure(query, fetch, -1, "request_budget_exhausted", false, true, null, 0L);
            traceNaverClassifiedOutcome("TIMEOUT_OR_BUDGET", false, true);
            return null;
        });
        return Mono.error(new NaverClassifiedFailure("TIMEOUT_OR_BUDGET"));
    }

    private static boolean looksFresh(String q) {
        if (q == null)
            return false;
        String s = q.toLowerCase(java.util.Locale.ROOT);
        return s.matches(".*(최신|방금|오늘|금일|recent|today|now).*");
    }

    /**
     * JSON → 스니펫 파싱 (선택) 키워드 필터링.
     * - 두 번째 소스의 정규화/HTML 제거 로직을 반영(단, 출력 포맷은 기존 앵커 형식 유지)
     *
     * @param query 원본 검색어 (키워드 필터에 사용)
     * @param json  Naver API 응답 JSON 문자열
     */

    /**
     * JSON → 스니펫 파싱 및 필터링 로직 개선 (Fail-Soft & Adaptive)
     */

    /**
     * 통합 필터링 + Fail-Soft + 포맷팅 로직.
     * 동기(callNaverApi) / 비동기(parseNaverResponse) 경로 모두 이 메서드를 통해
     * 동일한 도메인 정책과 안전망을 적용한다.
     */
    // ─── 기존 호환 ───
    private List<String> filterAndFormatItems(List<NaverItem> items, String query) {
        return filterAndFormatItems(items, query, defaultPolicy());
    }

    // ─── 신규: 정책 파라미터 버전 ───
    private List<String> filterAndFormatItems(List<NaverItem> items, String query, SearchPolicy policy) {
        return filterAndFormatItems(items, query, policy, null);
    }

    private List<String> filterAndFormatItems(List<NaverItem> items, String query, SearchPolicy policy,
                                               NaverObservation observation) {
        if (items == null || items.isEmpty()) {
            return Collections.emptyList();
        }

        // 1) 검색 의도/정책 판정
        boolean isCommunityIntent = isCommunityPreferredQuery(query);
        boolean gamePatch = isGamePatchQuery(query);
        // [PATCH] PlanHint(officialOnly/domainProfile)를 실제 Web 필터에 연결
        GuardContext gctx = GuardContextHolder.get();
        String domainProfile = (gctx != null ? gctx.getDomainProfile() : null);
        boolean planOfficialOnly = (gctx != null && gctx.isOfficialOnly());

        // [UAW] When the web layer is partially degraded, demote domainProfile (but not officialOnly)
        // to avoid strict filtering starvation. High-risk queries keep strictness.
        boolean orchWebPartialDown = traceBool("orch.webPartialDown");
        boolean highRisk = isMedicalOfficialInfoQuery(query) || isLocationQuery(query);
        if (orchWebPartialDown && !highRisk && StringUtils.hasText(domainProfile)) {
            String originalProfile = domainProfile;
            domainProfile = null;
            try {
                TraceStore.put("web.naver.domainProfileDemoted.filterStage", true);
                TraceStore.put("web.naver.domainProfileDemoted.filterStage.reason", "orch.webPartialDown");
                TraceStore.put("web.naver.domainProfileDemoted.filterStage.original", SafeRedactor.hashValue(originalProfile));
            } catch (Throwable ignore) {
                NaverTraceSuppressions.traceSuppressed("filter.domainProfileDemotion", ignore);
            }
        }
        boolean strictByPlan = planOfficialOnly || StringUtils.hasText(domainProfile);
        String effectiveProfile = StringUtils.hasText(domainProfile) ? domainProfile
                : (planOfficialOnly ? "official" : null);
        boolean profileUsable = (domainProfileLoader != null && StringUtils.hasText(effectiveProfile));

        // [PATCH] strictMode는 policy뿐 아니라 plan 힌트로도 강제 가능
        boolean strictMode = ("filter".equalsIgnoreCase(policy.domainPolicy()) && policy.domainFilterEnabled())
                || strictByPlan;

        if (strictMode && profileUsable) {
            try {
                TraceStore.put("web.domainProfile", SafeRedactor.hashValue(effectiveProfile));
            } catch (Exception suppressed) { NaverTraceSuppressions.traceSuppressed("domainProfile.trace", suppressed); }
        }

        // 커뮤니티 성격 쿼리는 비공식 도메인(카페/블로그 등)이 핵심이므로 strict 모드 완화
        // 단, planHint로 strict가 강제된 경우는 완화하지 않는다.
        if (strictMode && isCommunityIntent && !strictByPlan) {
            strictMode = false;
            if (log.isDebugEnabled()) {
                log.debug("[AWX2AF2][search][naver] adaptive community-intent strict-domain-filter-disabled queryHash={} queryLength={}",
                        SafeRedactor.hashValue(query),
                        query == null ? 0 : query.length());
            }
            try {
                TraceStore.put("web.communityIntentRelaxed", true);
            } catch (Exception suppressed) { NaverTraceSuppressions.traceSuppressed("communityIntentRelaxed.trace", suppressed); }
        }

        // 게임 패치 쿼리는 공식 사이트 우선, 그 외에는 설정된 allowlist 사용
        String dynamicAllow = gamePatch && "filter".equalsIgnoreCase(policy.domainPolicy())
                ? "genshin.hoyoverse.com,hoyoverse.com,hoyolab.com"
                : allowlist;

        // 2) 도메인 필터/블록 리스트 적용
        final boolean finalStrictMode = strictMode;
        final boolean finalProfileUsable = profileUsable;
        final String finalEffectiveProfile = effectiveProfile;
        final String finalDynamicAllow = dynamicAllow;

        // ---- Domain filter stage counts (ops tracing) ----
        final long filterRunId = TraceStore.nextSequence("web.naver.filter.run");
        final int rawCount = items.size();

        int droppedBlocked = 0;
        int droppedStrictDomain = 0;

        ArrayList<NaverItem> afterBlocked = new ArrayList<>(Math.max(0, rawCount));
        for (NaverItem item : items) {
            if (item == null || item.link() == null) {
                continue;
            }
            String url = item.link();
            if (isBlockedDomain(url)) {
                droppedBlocked++;
                continue;
            }
            afterBlocked.add(item);
        }

        List<NaverItem> survivingItems;
        if (!finalStrictMode) {
            survivingItems = afterBlocked;
        } else {
            ArrayList<NaverItem> tmp = new ArrayList<>(afterBlocked.size());
            for (NaverItem item : afterBlocked) {
                if (item == null || item.link() == null) {
                    continue;
                }
                String url = item.link();
                boolean allowed = false;

                if (finalProfileUsable) {
                    try {
                        // DomainProfileLoader: (url, profile)
                        allowed = domainProfileLoader.isAllowedByProfile(url, finalEffectiveProfile);
                    } catch (Exception ignore) {
                        NaverTraceSuppressions.traceSuppressed("filter.domainProfileAllowed", ignore); allowed = false;
                    }
                }
                if (!allowed) {
                    allowed = isAllowedDomainWith(url, finalDynamicAllow);
                }

                if (allowed) {
                    tmp.add(item);
                } else {
                    droppedStrictDomain++;
                }
            }
            survivingItems = tmp;
        }

        final int afterBlockedCount = afterBlocked.size();
        final int afterStrictCount = survivingItems.size();
        final boolean starvedByStrictDomain = finalStrictMode && rawCount > 0 && afterStrictCount == 0;

        try {
            TraceStore.put("web.naver.filter.runId.last", String.valueOf(filterRunId));
            TraceStore.put("web.naver.filter.rawCount", rawCount);
            TraceStore.put("web.naver.filter.afterBlockedCount", afterBlockedCount);
            TraceStore.put("web.naver.filter.afterStrictCount", afterStrictCount);
            TraceStore.put("web.naver.filter.dropped.blocked", droppedBlocked);
            TraceStore.put("web.naver.filter.dropped.strictDomain", droppedStrictDomain);
            TraceStore.put("web.naver.filter.strictMode", finalStrictMode);
            TraceStore.put("web.naver.filter.strictByPlan", strictByPlan);
            TraceStore.put("web.naver.filter.policy.domainFilterEnabled", policy.domainFilterEnabled());
            TraceStore.put("web.naver.filter.policy.domainPolicy", policy.domainPolicy());
            TraceStore.put("web.naver.filter.profileUsable", finalProfileUsable);
            TraceStore.put("web.naver.filter.profile", finalProfileUsable ? SafeRedactor.hashValue(finalEffectiveProfile) : null);
            TraceStore.put("web.naver.filter.starvedByStrictDomain", starvedByStrictDomain);
        } catch (Exception ignore) {
            NaverTraceSuppressions.traceSuppressed("filter.summaryTrace", ignore);
        }

        // Append-only run snapshots so later calls don't clobber earlier diagnostic state.
        try {
            java.util.Map<String, Object> run = new java.util.LinkedHashMap<>();
            run.put("runId", filterRunId);
            run.put("rawCount", rawCount);
            run.put("afterBlockedCount", afterBlockedCount);
            run.put("afterStrictCount", afterStrictCount);
            run.put("droppedBlocked", droppedBlocked);
            run.put("droppedStrictDomain", droppedStrictDomain);
            run.put("strictMode", finalStrictMode);
            run.put("strictByPlan", strictByPlan);
            run.put("policy.domainFilterEnabled", policy.domainFilterEnabled());
            run.put("policy.domainPolicy", policy.domainPolicy());
            run.put("profileUsable", finalProfileUsable);
            if (finalProfileUsable && finalEffectiveProfile != null && !finalEffectiveProfile.isBlank()) {
                run.put("profile", finalEffectiveProfile);
            }
            run.put("starvedByStrictDomain", starvedByStrictDomain);
            if (observation == null) TraceStore.append("web.naver.filter.runs", run);
            else observation.putAll(run);
        } catch (Exception ignore) {
            NaverTraceSuppressions.traceSuppressed("filter.runAppendTrace", ignore);
        }

        // 3) [Fail-Soft] strict-by-plan thin rescue:
        // strictDomainRequired/officialOnly + domain filters can leave us with too few
        // results.
        // When that happens, we top-up from the raw set (still respecting blocked
        // domains),
        // and record explicit checkpoints for ops/CI.
        int targetMin = 2;
        try {
            // gctx 재사용 (이미 상단 1874행에서 선언됨)
            Integer minC = (gctx == null) ? null : gctx.getMinCitations();
            if (minC != null && minC > 0) {
                targetMin = Math.max(1, minC);
            }
        } catch (Exception suppressed) { NaverTraceSuppressions.traceSuppressed("thinRescue.targetMin", suppressed); }
        targetMin = Math.min(targetMin, 5);

        if (strictMode && strictByPlan && !items.isEmpty()
                && !survivingItems.isEmpty()
                && survivingItems.size() < targetMin) {

            int before = survivingItems.size();
            int need = targetMin - before;

            try {
                TraceStore.put("web.domainFilter.thinRescue", true);
                TraceStore.put("web.domainFilter.thinRescue.reason", "below_min_citations");
                TraceStore.put("web.domainFilter.thinRescue.before", before);
                TraceStore.put("web.domainFilter.thinRescue.targetMin", targetMin);
                TraceStore.put("web.domainFilter.thinRescue.strictByPlan", true);
                TraceStore.put("web.domainFilter.thinRescue.officialOnly", planOfficialOnly);

                // Naver-specific checkpoint (provider stage)
                TraceStore.put("web.naver.filter.thinRescue.used", true);
                TraceStore.put("web.naver.filter.thinRescue.reason", "below_min_citations");
                TraceStore.put("web.naver.filter.thinRescue.before", before);
                TraceStore.put("web.naver.filter.thinRescue.targetMin", targetMin);
                TraceStore.put("web.naver.filter.thinRescue.strictByPlan", true);
                TraceStore.put("web.naver.filter.thinRescue.officialOnly", planOfficialOnly);
            } catch (Exception suppressed) { NaverTraceSuppressions.traceSuppressed("thinRescue.startTrace", suppressed); }

            java.util.LinkedHashMap<String, NaverItem> merged = new java.util.LinkedHashMap<>();
            for (NaverItem it : survivingItems) {
                if (it != null && it.link() != null) {
                    merged.put(it.link(), it);
                }
            }

            int added = 0;
            for (NaverItem it : items) {
                if (added >= need) {
                    break;
                }
                if (it == null || it.link() == null) {
                    continue;
                }
                String url = it.link();
                if (merged.containsKey(url)) {
                    continue;
                }
                if (isBlockedDomain(url)) {
                    continue;
                }
                if (planOfficialOnly && isLowTrustUrlForThinRescue(url)) {
                    continue;
                }
                merged.put(url, it);
                added++;
            }

            survivingItems = new ArrayList<>(merged.values());

            try {
                TraceStore.put("web.domainFilter.thinRescue.added", added);
                TraceStore.put("web.domainFilter.thinRescue.after", survivingItems.size());

                TraceStore.put("web.naver.filter.thinRescue.added", added);
                TraceStore.put("web.naver.filter.thinRescue.after", survivingItems.size());
            } catch (Exception suppressed) { NaverTraceSuppressions.traceSuppressed("thinRescue.addedTrace", suppressed); }
        }

        // 4) [Fail-Soft] 결과 전멸 시 상위 5개 복구
        if (survivingItems.isEmpty() && !items.isEmpty()) {
            // [PATCH] strict 필터가 너무 선택적이면 결과가 전멸(starve)할 수 있다.
            // 이 경우 sid/blocked 도메인 보호는 유지하면서 도메인 strict만 완화한다.
            if (strictMode) {
                try {
                    TraceStore.put("web.domainFilter.starved", true);
                    TraceStore.put("web.domainFilter.thinRescue", true);
                    TraceStore.put("web.domainFilter.thinRescue.reason", "all_dropped");
                    TraceStore.put("web.domainFilter.thinRescue.before", 0);
                    TraceStore.put("web.domainFilter.thinRescue.targetMin", targetMin);
                    TraceStore.put("web.domainFilter.thinRescue.strictByPlan", strictByPlan);
                    TraceStore.put("web.domainFilter.thinRescue.officialOnly", planOfficialOnly);

                    // Naver-specific checkpoint (provider stage)
                    TraceStore.put("web.naver.filter.thinRescue.used", true);
                    TraceStore.put("web.naver.filter.thinRescue.reason", "all_dropped");
                    TraceStore.put("web.naver.filter.thinRescue.before", 0);
                    TraceStore.put("web.naver.filter.thinRescue.targetMin", targetMin);
                    TraceStore.put("web.naver.filter.thinRescue.strictByPlan", strictByPlan);
                    TraceStore.put("web.naver.filter.thinRescue.officialOnly", planOfficialOnly);
                } catch (Exception suppressed) { NaverTraceSuppressions.traceSuppressed("thinRescue.allDroppedStartTrace", suppressed); }
            }
            log.warn(
                    "🚨 [Fail-Soft] All {} results were dropped by filters for queryHash12={} queryLength={}. Resurrecting top 5 raw items.",
                    items.size(), SafeRedactor.hash12(query), query == null ? 0 : query.length());
            survivingItems = items.stream()
                    .filter(item -> !isBlockedDomain(item.link()))
                    .limit(5)
                    .toList();
            if (strictMode) {
                try {
                    TraceStore.put("web.domainFilter.thinRescue.added", survivingItems.size());
                    TraceStore.put("web.domainFilter.thinRescue.after", survivingItems.size());

                    TraceStore.put("web.naver.filter.thinRescue.added", survivingItems.size());
                    TraceStore.put("web.naver.filter.thinRescue.after", survivingItems.size());
                } catch (Exception suppressed) { NaverTraceSuppressions.traceSuppressed("thinRescue.allDroppedAddedTrace", suppressed); }
            }
        }

        // 4) 스니펫 문자열 변환
        List<String> lines = survivingItems.stream()
                .map(item -> String.format(
                        "- <a href=\"%s\" target=\"_blank\" rel=\"noopener\">%s</a>: %s",
                        item.link(),
                        stripHtml(item.title()),
                        stripHtml(item.description())))
                .distinct()
                .collect(Collectors.toList());

        // 5) 도메인 가중치 정렬 (커뮤니티 인텐트 또는 boost 정책일 때)
        if (isCommunityIntent || "boost".equalsIgnoreCase(policy.domainPolicy())) {
            lines.sort((a, b) -> Double.compare(
                    domainWeight(extractHref(b)),
                    domainWeight(extractHref(a))));
        }

        if (observation != null) {
            observation.put("formattedBeforeDedupCount", survivingItems.size());
            observation.put("afterDedupCount", lines.size());
        }
        return lines;
    }

    // ─── 기존 호환 ───
    private List<String> parseNaverResponse(String query, String json) {
        return parseNaverResponse(query, json, defaultPolicy());
    }

    // ─── 신규: 정책 파라미터 버전 ───
    private List<String> parseNaverResponse(String query, String json, SearchPolicy policy) {
        return parseNaverResponse(query, json, policy, new NaverObservation(query, TraceStore.context(), false));
    }

    private List<String> parseNaverResponse(String query, String json, SearchPolicy policy,
                                             NaverObservation observation) {
        if (isBlank(json)) {
            return Collections.emptyList();
        }
        try {
            // 1) items 존재 및 크기(원시) 확인
            JsonNode root = om.readTree(json);
            JsonNode itemsNode = root.path("items");
            if (!root.isObject() || !root.has("items") || !itemsNode.isArray()) {
                throw new IllegalArgumentException("invalid naver response shape");
            }
            int rawSize = itemsNode.isArray() ? itemsNode.size() : -1;
            observation.put("rawSize", rawSize);

            // 2) DTO 역직렬화
            NaverResponse resp = om.readValue(json, NaverResponse.class);
            List<NaverItem> items = (resp.items() == null)
                    ? Collections.emptyList()
                    : resp.items();
            observation.put("parsedCount", items.size());

            if (items.isEmpty()) {
                // Provider stage: distinguish "Naver returned 0 items" vs "items existed but were filtered".
                try {
                    TraceStore.put("web.naver.parse.itemsEmpty", true);
                    TraceStore.put("web.naver.parse.rawSize", rawSize);

                    long filterRunId = TraceStore.nextSequence("web.naver.filter.run");
                    TraceStore.put("web.naver.filter.runId.last", String.valueOf(filterRunId));
                    TraceStore.put("web.naver.filter.rawCount", 0);
                    TraceStore.put("web.naver.filter.afterBlockedCount", 0);
                    TraceStore.put("web.naver.filter.afterStrictCount", 0);
                    TraceStore.put("web.naver.filter.starvedByStrictDomain", false);

                    java.util.Map<String, Object> run = new java.util.LinkedHashMap<>();
                    run.put("runId", filterRunId);
                    run.put("rawSize", rawSize);
                    run.put("itemsEmpty", true);
                    run.put("rawCount", 0);
                    run.put("afterStrictCount", 0);
                    if (policy != null) {
                        run.put("policy.domainFilterEnabled", policy.domainFilterEnabled());
                        run.put("policy.domainPolicy", policy.domainPolicy());
                    }
                    observation.putAll(run);
                } catch (Exception ignore) {
                    NaverTraceSuppressions.traceSuppressed("filter.emptyRunTrace", ignore);
                }

                if (debugJson) {
                    log.debug("[AWX2AF2][search][naver] items empty rawSize={} bodyLen={} bodyHash={} queryHash={}",
                            rawSize, json == null ? 0 : json.length(), SafeRedactor.hashValue(json),
                            SafeRedactor.hashValue(query));
                }
                traceNaverCounts(query, display, 0, 0, false, null);
                for (String key : List.of("afterBlockedCount", "formattedBeforeDedupCount", "afterDedupCount", "afterFilterCount")) {
                    observation.put(key, 0);
                }
                observation.put("failureClass", "TRUE_ZERO");
                return Collections.emptyList();
            }

            // 3) 공통 필터 & Fail-Soft & 포맷팅
            List<String> lines = filterAndFormatItems(items, query, policy, observation);

            // 4) 기존 중의성/버전 필터 재적용
            lines = applyDisambiguationFilters(query, lines);
            if (isGamePatchQuery(query)) {
                String v = extractVersionToken(query);
                if (v != null) {
                    Pattern must = versionMustRegex(v);
                    lines = lines.stream()
                            .filter(sn -> must.matcher(sn).find())
                            .toList();
                }
            }

            // 5) (선택) 키워드 OR 필터
            if (policy.keywordFilterEnabled() && !lines.isEmpty()) {
                List<String> kws = keywords(query);
                int requiredHits = Math.max(1, Math.min(policy.keywordMinHits(), (kws.size() + 1) / 3));
                List<String> filtered = lines.stream()
                        .filter(sn -> hitCount(sn, kws) >= requiredHits)
                        .toList();
                if (!filtered.isEmpty()) {
                    lines = filtered;
                }
            }

            if (lines.isEmpty() && debugJson) {
                log.debug("[Naver Parse] 파싱 이후 스니펫 0개 (rawSize={})", rawSize);
            }
            if (lines.isEmpty()) {
                TraceStore.put("web.naver.zeroResults", true);
                TraceStore.put("web.naver.afterFilterCount", 0);
                log.debug("[AWX2AF2][search][zero-results] provider=naver returnedCount={} afterFilterCount=0 domainPolicy={} timeoutMs={}",
                        rawSize, policy == null ? null : policy.domainPolicy(), apiTimeoutMs);
            }
            traceNaverCounts(query, display, items.size(), lines.size(), false, null);
            observation.put("afterFilterCount", lines.size());
            observation.put("failureClass", lines.isEmpty() ? "FILTER_ZERO" : "NONE");
            if (items.size() > 0 && lines.isEmpty()) {
                traceNaverClassifiedOutcome("FILTER_ZERO", true, false);
                throw new NaverClassifiedFailure("FILTER_ZERO");
            }
            return lines;
        } catch (NaverClassifiedFailure classified) {
            throw classified;
        } catch (Exception e) {
            observation.put("failureClass", observation.get("parsedCount") instanceof Number ? "TRANSFORM_ERROR"
                    : observation.get("rawSize") instanceof Number ? "ITEM_MAPPING_ERROR" : "PARSE_ERROR");
            log.error("[Naver Parse] JSON parse failed errorType={} bodyHash={} bodyLength={}",
                    naverErrorType(e),
                    json == null ? "" : SafeRedactor.hashValue(json),
                    json == null ? 0 : json.length());
            if (debugJson) {
                log.debug("[AWX2AF2][search][naver] parse failed bodyLen={} bodyHash={} queryHash={}",
                        json == null ? 0 : json.length(), SafeRedactor.hashValue(json), SafeRedactor.hashValue(query));
            }
            traceNaverClassifiedOutcome("PARSE_ERROR", true, true);
            throw new NaverClassifiedFailure("PARSE_ERROR");
        } finally {
            observation.finish();
        }
    }

    // [REMOVED] 비공식 HTML 크롤링 폴백 제거: 폴백은 상위 Provider가 담당.

    // ▼ 이 메서드 전체를 교체하세요
    private List<String> callNaverApi(String query, SearchTrace trace) {
        Instant start = Instant.now();
        if (!hasCreds() || isBlank(query)) {
            return Collections.emptyList();
        }
        String qTrim = query.trim();
        if (qTrim.length() < 2) {
            return Collections.emptyList();
        }

        // Append suffix if configured
        String searchQuery = query;
        String suffix = deriveLocationSuffix(query);
        if (!isBlank(suffix)) {
            searchQuery = searchQuery + " " + suffix;
        }
        String apiQuery = searchQuery;

        if (trace != null) {
            trace.suffixApplied = deriveLocationSuffix(query);
            trace.domainFilterEnabled = enableDomainFilter;
            trace.reasonDomainFilterDisabled = enableDomainFilter ? null : "PROPERTY_FALSE";
            trace.keywordFilterEnabled = enableKeywordFilter;
            trace.reasonKeywordFilterDisabled = enableKeywordFilter ? null : "PROPERTY_FALSE";
        }

        int topK = (trace != null && !trace.steps.isEmpty())
                ? Math.max(1, trace.steps.get(0).afterFilter) // trace 모드면 직전 topK
                : webTopK;

        // NOTE: Use absolute URL to remain resilient even if WebClient baseUrl
        // is misconfigured.
        URI uri = UriComponentsBuilder.fromHttpUrl("https://openapi.naver.com/v1/search/webkr.json")
                .queryParam("query", apiQuery)
                .queryParam("display", clampNaverDisplay(Math.max(topK, display)))
                .queryParam("start", 1)
                .build()
                .encode(StandardCharsets.UTF_8)
                .toUri();

        boolean acquired = false;
        NightmareBreaker.CallPermit permit = null;
        try {
            REQUEST_SEMAPHORE.acquire(); // 동시에 2개까지만 호출
            acquired = true;
            String json = null;
            NaverCredentialBridge.Credential key = nextKey();
            if (key == null) {
                throw new IllegalStateException(
                        "NAVER API keys are not configured. Set env vars NAVER_CLIENT_ID / NAVER_CLIENT_SECRET (or NAVER_KEYS).");
            }
            if (nightmareBreaker != null) {
                try {
                    permit = nightmareBreaker.acquire(NightmareKeys.WEBSEARCH_NAVER, "wire-sync");
                } catch (NightmareBreaker.OpenCircuitException e) {
                    long remainingMs = nightmareBreaker.remainingOpenMs(NightmareKeys.WEBSEARCH_NAVER);
                    NaverTraceSignals.traceBreakerOpen(remainingMs);
                    log.warn("[Naver API] NightmareBreaker OPEN for Naver (remain={}ms) - skipping API call",
                            remainingMs);
                    return Collections.emptyList();
                }
            }
            String id = key.id();
            String secret = key.secret();

            try {
                String randomAgent = USER_AGENTS[new java.util.Random().nextInt(USER_AGENTS.length)];
                json = web.get()
                        .uri(uri)
                        .header("X-Naver-Client-Id", id)
                        .header("X-Naver-Client-Secret", secret)
                        .header("User-Agent", randomAgent)
                        .retrieve()
                        .bodyToMono(String.class)
                        .block(Duration.ofMillis(Math.max(1000L, apiTimeoutMs)));
            } catch (WebClientResponseException e) {
                NaverTraceSuppressions.traceSuppressed("api.webClientResponse", e);
                if (e.getStatusCode().value() == 429) {
                    try {
                        ratePolicy.updateFromHeaders(e.getHeaders());
                    } catch (Exception suppressed) { NaverTraceSuppressions.traceSuppressed("ratePolicy.headers.searchError", suppressed); }
                    TraceStore.put("web.rateLimited", true);
                    TraceStore.put("web.naver.429", true);
                    long retryAfterMs = Math.max(0L, ratePolicy.retryAfterMs());
                    traceNaverRemoteCooldown("rate-limit", retryAfterMs);
                    if (permit != null) {
                        permit.completeRateLimit(
                                "wire-sync",
                                e,
                                "http-429",
                                retryAfterMs > 0 ? retryAfterMs : null);
                        permit = null;
                    }
                    log.warn("Naver API 429 Too Many Requests; returning empty (provider fallback will handle)");
                    traceNaverFailure(query, display, 429, "rate-limit", true, false,
                            e.getResponseBodyAsString(), Duration.between(start, Instant.now()).toMillis());
                    return java.util.Collections.emptyList();
                }
                throw e;
            }

            if (json == null || isBlank(json)) {
                if (permit != null) {
                    permit.completeBlank("body");
                    permit = null;
                }
                return Collections.emptyList();
            }

            // 공통 파서(parseNaverResponse)를 사용해 도메인 필터 및 Fail-Soft를
            // 비동기 경로와 동일하게 적용한다.
            List<String> lines = parseNaverResponse(query, json);

            long tookMs = Duration.between(start, Instant.now()).toMillis();
            if (permit != null) {
                permit.completeSuccess(tookMs);
                permit = null;
            }

            log.info("[AWX2AF2][search][naver] api success queryHash={} queryLength={} returnedCount={} tookMs={}",
                    SafeRedactor.hashValue(query),
                    query == null ? 0 : query.length(),
                    lines.size(),
                    tookMs);

            // 추적 기록
            if (trace != null) {
                int afterFilter = lines.size();
                int returned = lines.size();
                trace.steps.add(new SearchStep(traceQueryLabel(query), returned, afterFilter, tookMs));
            }

            return lines;

        } catch (InterruptedException ie) {
            NaverTraceSuppressions.traceSuppressed("api.interrupted", ie);
            restoreInterruptFlag();
            if (permit != null) {
                permit.completeCancelled(ie, "wire-sync");
                permit = null;
            }
            traceNaverFailure(query, display, -1, "interrupted", false, false, null,
                    Duration.between(start, Instant.now()).toMillis());
            log.warn("Naver API call interrupted queryHash={} queryLength={}",
                    query == null ? "" : SafeRedactor.hashValue(query),
                    query == null ? 0 : query.length());
            return java.util.Collections.emptyList();
        } catch (Exception ex) {
            NaverTraceSuppressions.traceSuppressed("api.failure", ex);
            if (permit != null) {
                if (ex instanceof NaverClassifiedFailure classified
                        && "FILTER_ZERO".equals(classified.failureClass)) {
                    permit.completeAbandoned("parse", "filter-zero");
                } else {
                    NightmareBreaker.FailureKind kind = NightmareBreaker.classify(ex);
                    if (kind == NightmareBreaker.FailureKind.RATE_LIMIT) {
                        permit.completeRateLimit("wire-sync", ex, "rate-limit", null);
                    } else {
                        permit.completeFailure(kind, ex, "wire-sync");
                    }
                }
                permit = null;
            }
            traceNaverFailure(query, display, naverHttpStatus(ex), naverFailureReason(ex),
                    isNaverRateLimited(ex), isNaverTimeoutFailure(ex), naverErrorBody(ex),
                    Duration.between(start, Instant.now()).toMillis());
            log.error("Naver API call failed failureReason={} errorType={} queryHash={} queryLength={}",
                    naverFailureReason(ex),
                    naverErrorType(ex),
                    query == null ? "" : SafeRedactor.hashValue(query),
                    query == null ? 0 : query.length());
            return java.util.Collections.emptyList();
        } finally {
            if (permit != null) {
                permit.completeAbandoned("wire-sync", "non-terminal");
            }
            if (acquired) {
                REQUEST_SEMAPHORE.release();
            }
        }
    } // --- callNaverApi(String, SearchTrace) 끝 ---

    // [REMOVED] 비공식 HTML 파싱/캡차 감지/동기 폴백 제거.

    /* ================== NEW: 보수적 동의어 확장 & 중의성 필터 ================== */
    /**
     * 보수적 동의어 확장:
     * - 기존처럼 "모든 동의어를 한 쿼리에 합쳐 붙임" 금지
     * - 각 동의어는 별도 변형 쿼리로만 추가
     * - 공백/한글/'' 포함 시 따옴표로 감싸 구문 고정(phrase search 유도)
     */
    // ❌ 동의어 확장 메서드/관련 필드 전체 삭제

    /** 결과 라인에 대해 중의성(예: K8 자동차) 오염을 제거한다. */
    private List<String> applyDisambiguationFilters(String originalQuery, List<String> lines) {
        var profile = SearchDisambiguation.resolve(originalQuery);
        if (profile.negativeKeywords().isEmpty() && profile.blockedHosts().isEmpty())
            return lines;
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            // 1) 텍스트 상의 부정 키워드(hitCount 재활용)
            if (hitCount(line, new ArrayList<>(profile.negativeKeywords())) > 0)
                continue;
            // 2) 호스트 기반 차단
            boolean block = false;
            try {
                int i = line.indexOf("href=\"");
                if (i >= 0) {
                    int j = line.indexOf("\"", i + 6);
                    String url = j > 0 ? line.substring(i + 6, j) : "";
                    String host = URI.create(url).getHost();
                    if (host != null) {
                        for (String b : profile.blockedHosts()) {
                            if (host.contains(b)) {
                                block = true;
                                break;
                            }
                        }
                    }
                }
            } catch (Exception suppressed) { NaverTraceSuppressions.traceSuppressed("filterRunId.parse", suppressed); }
            if (!block)
                out.add(line);
        }
        return out;
    }

    /** 429 상태 코드 감지 - 재시도 필터 */
    private boolean isTooManyRequests(Throwable t) {
        return t instanceof WebClientResponseException
                && ((WebClientResponseException) t).getStatusCode().value() == 429;
    }

    /**
     * Naver/웹 검색용 재시도 조건 헬퍼
     * Timeout, 5xx, 429 에러만 재시도 대상으로 판단
     */
    private boolean isRetryable(Throwable ex) {
        // 1) Reactor timeout
        if (ex instanceof TimeoutException) {
            return true;
        }
        // 2) 소켓 타임아웃
        if (ex instanceof SocketTimeoutException) {
            return true;
        }
        // 3) HTTP 응답 에러
        if (ex instanceof WebClientResponseException w) {
            // 서버측 오류(5xx) 또는 Too Many Requests(429)는 재시도 대상으로 본다.
            int status = w.getStatusCode().value();
            return w.getStatusCode().is5xxServerError() || status == 429;
        }
        // 그 외는 재시도하지 않고 바로 폴백/실패 처리
        return false;
    }

    /** Single-shot fallback (no extra modifications). */
    private List<String> searchOnce(String q) throws IOException {
        return List.of(); // Simplified: unused
    }

    /* === Helper functions === */

    private void traceNaverCounts(String query,
                                  int requestedCount,
                                  int returnedCount,
                                  int afterFilterCount,
                                  boolean providerDisabled,
                                  String disabledReason) {
        try {
            int requested = Math.max(0, requestedCount);
            int returned = Math.max(0, returnedCount);
            int after = Math.max(0, afterFilterCount);
            TraceStore.put("web.naver.requestedCount", requested);
            TraceStore.put("web.naver.timeoutMs", Math.max(0L, apiTimeoutMs));
            TraceStore.put("web.naver.returnedCount", returned);
            TraceStore.put("web.naver.afterFilterCount", after);
            TraceStore.put("web.naver.zeroResults", returned == 0);
            TraceStore.put("web.naver.afterFilterStarved", returned > 0 && after == 0);
            TraceStore.put("web.naver.providerDisabled", providerDisabled);
            TraceStore.put("web.naver.providerEmpty", !providerDisabled && returned == 0);
            TraceStore.put("web.naver.queryHash", query == null ? "" : SafeRedactor.hashValue(query));
            TraceStore.put("web.naver.queryLength", query == null ? 0 : query.length());
            TraceStore.put("web.naver.queryTokenBucket", queryTokenBucket(query)); TraceStore.put("web.naver.httpStatus", null); TraceStore.put("web.naver.429", false); TraceStore.put("web.naver.rateLimited", false); TraceStore.put("web.naver.timeout", false); TraceStore.put("web.naver.cancelled", false);
            String failureReason = classifyNaverCountFailure(returned, after, providerDisabled);
            TraceStore.put("web.naver.failureReason", failureReason);
            traceCommonWebProviderCounts("naver", query, returned, after, providerDisabled, disabledReason,
                    failureReason);
            if (providerDisabled) {
                String reason = disabledReason == null || disabledReason.isBlank()
                        ? "disabled"
                        : disabledReason;
                String safeReason = SafeRedactor.traceLabelOrFallback(reason, "unknown");
                TraceStore.put("web.naver.providerDisabled", true);
                TraceStore.put("web.naver.disabledReason", safeReason);
                TraceStore.put("web.naver.disabledReasonCanonical", safeReason);
                TraceStore.put("web.naver.skipped", true);
                TraceStore.put("web.naver.skipped.reason", safeReason);
            }
        } catch (Exception ignore) {
            NaverTraceSuppressions.traceSuppressed("counts.trace", ignore);
        }
    }

    private static void traceCommonWebProviderCounts(String provider,
                                                     String query,
                                                     int returned,
                                                     int after,
                                                     boolean providerDisabled,
                                                     String disabledReason,
                                                     String failureReason) {
        String safeProvider = SafeRedactor.traceLabelOrFallback(provider, "unknown");
        TraceStore.put("web.provider.name", safeProvider);
        TraceStore.put("web.provider.enabled", !providerDisabled);
        TraceStore.put("web.provider.resultCount", Math.max(0, returned));
        TraceStore.put("web.provider.disabledReason", providerDisabled
                ? WebProviderTraceReasons.disabledReason(disabledReason)
                : null);
        TraceStore.put("web.query.hash", query == null || query.isBlank()
                ? null
                : SafeRedactor.hashValue(query));
        TraceStore.put("web.query.length", query == null ? 0 : query.length());
        TraceStore.putIfAbsent("web.query.variantCount", 0);
        if (returned > 0 && after <= 0) {
            TraceStore.put("web.filter.starvationReason", "after-filter-starvation");
        }
        String commonReason = commonFailSoftReason(failureReason);
        if (commonReason != null) {
            TraceStore.put("web.failsoft.reason", commonReason);
        }
    }

    private static String commonFailSoftReason(String reason) {
        String safe = SafeRedactor.traceLabelOrFallback(reason, "none");
        return switch (safe) {
            case "none" -> null;
            case "provider-empty" -> "empty-provider-output";
            default -> safe;
        };
    }

    private void traceNaverFailure(String query,
                                   int requestedCount,
                                   int httpStatus,
                                   String failureReason,
                                   boolean rateLimited,
                                   boolean timeout,
                                   String errorBody,
                                   long tookMs) {
        try {
            String safeReason = SafeRedactor.traceLabelOrFallback(failureReason == null || failureReason.isBlank() ? "unknown" : failureReason, "unknown");
            boolean cancelled = "cancelled".equals(safeReason);
            traceNaverCounts(query, requestedCount, 0, 0, false, null);
            TraceStore.put("web.naver.zeroResults", false);
            TraceStore.put("web.naver.providerEmpty", false);
            if (httpStatus > 0) {
                TraceStore.put("web.naver.httpStatus", httpStatus);
            }
            TraceStore.put("web.naver.429", httpStatus == 429 || rateLimited);
            TraceStore.put("web.naver.rateLimited", rateLimited);
            TraceStore.put("web.naver.timeout", timeout);
            TraceStore.put("web.naver.cancelled", cancelled); if (cancelled) TraceStore.put("web.naver.exceptionType", "cancelled");
            TraceStore.put("web.naver.failureReason", safeReason);
            TraceStore.put("web.naver.tookMs", Math.max(0L, tookMs));
            if (rateLimited) {
                TraceStore.put("web.rateLimited", true);
            }
            traceNaverErrorBody(errorBody);
            log.warn("[AWX][search][naver] provider failure failureReason={} httpStatus={} queryHash={} queryLength={} bodyHash={} bodyLength={} tookMs={}",
                    safeReason,
                    httpStatus > 0 ? httpStatus : "none",
                    query == null ? "" : SafeRedactor.hashValue(query),
                    query == null ? 0 : query.length(),
                    errorBody == null || errorBody.isBlank() ? "" : SafeRedactor.hashValue(errorBody),
                    errorBody == null ? 0 : errorBody.length(),
                    Math.max(0L, tookMs));
        } catch (Exception ignore) {
            NaverTraceSuppressions.traceSuppressed("failure.trace", ignore);
        }
    }

    private static void traceNaverErrorBody(String body) {
        TraceStore.put("web.naver.errorBodyLength", body == null ? 0 : body.length());
        if (body == null || body.isBlank()) {
            return;
        }
        TraceStore.put("web.naver.errorBodyHash", SafeRedactor.hashValue(body));
    }

    private static void traceNaverRemoteCooldown(String reason, long retryAfterMs) {
        try {
            long hintMs = Math.max(0L, retryAfterMs);
            TraceStore.put("web.naver.cooldown.reason",
                    SafeRedactor.traceLabelOrFallback(reason == null || reason.isBlank() ? "remote" : reason, "remote"));
            TraceStore.put("web.naver.retryAfterMs", hintMs);
            TraceStore.put("web.naver.cooldown.hintMs", hintMs);
        } catch (Throwable ignore) {
            NaverTraceSuppressions.traceSuppressed("remoteCooldown.trace", ignore);
        }
    }

    private static String classifyNaverCountFailure(int returned, int after, boolean providerDisabled) {
        if (providerDisabled) {
            return "provider-disabled";
        }
        if (returned <= 0) {
            return "provider-empty";
        }
        if (after <= 0) {
            return "after-filter-starvation";
        }
        return "none";
    }

    private static long elapsedMs(long startedNs) {
        return Math.max(0L, (System.nanoTime() - startedNs) / 1_000_000L);
    }

    private static <T> T withTraceContext(Map<String, Object> traceContext, Supplier<T> action) {
        if (action == null) {
            return null;
        }
        Map<String, Object> previous = TraceStore.context();
        try {
            if (traceContext != null) {
                TraceStore.installContext(traceContext);
            }
            return action.get();
        } finally {
            TraceStore.installContext(previous);
        }
    }

    private static int naverHttpStatus(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof WebClientResponseException w) {
                return w.getStatusCode().value();
            }
            cur = cur.getCause();
        }
        return -1;
    }

    private static boolean isNaverRateLimited(Throwable t) {
        return naverHttpStatus(t) == 429;
    }

    private static boolean isNaverTimeoutFailure(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof TimeoutException || cur instanceof SocketTimeoutException) {
                return true;
            }
            if (cur instanceof WebClientRequestException) {
                String msg = cur.getMessage() == null ? "" : cur.getMessage().toLowerCase(java.util.Locale.ROOT);
                if (msg.contains("timeout") || msg.contains("timed out")) {
                    return true;
                }
            }
            String type = cur.getClass().getName().toLowerCase(java.util.Locale.ROOT);
            String msg = cur.getMessage() == null ? "" : cur.getMessage().toLowerCase(java.util.Locale.ROOT);
            if (type.contains("timeout") || msg.contains("timeout") || msg.contains("timed out")) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    private static NaverClassifiedFailure findNaverClassifiedFailure(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof NaverClassifiedFailure classified) {
                return classified;
            }
            current = current.getCause();
        }
        return null;
    }

    private static String classifyNaverFailureClass(int httpStatus,
                                                    boolean rateLimited,
                                                    boolean timeout,
                                                    String failureReason) {
        if (httpStatus == 401 || httpStatus == 403) {
            return "AUTH_OR_CONFIG";
        }
        if (httpStatus == 429 || rateLimited) {
            return "RATE_LIMIT";
        }
        if (timeout || httpStatus == 408 || httpStatus == 504) {
            return "TIMEOUT_OR_BUDGET";
        }
        String safeReason = failureReason == null
                ? ""
                : failureReason.toLowerCase(java.util.Locale.ROOT);
        if (safeReason.contains("breaker") || safeReason.contains("cooldown")) {
            return "BREAKER_OR_COOLDOWN";
        }
        return "PROVIDER_ERROR";
    }

    private static void traceNaverClassifiedOutcome(String failureClass,
                                                    boolean providerAttemptObserved,
                                                    boolean unobservedCounts) {
        try {
            String safeClass = SafeRedactor.traceLabelOrFallback(failureClass, "PROVIDER_ERROR");
            TraceStore.put("web.naver.failureClass", safeClass);
            TraceStore.put("web.failureClass", safeClass);
            TraceStore.put("web.naver.providerAttemptObserved", providerAttemptObserved);
            if ("TRUE_ZERO".equals(safeClass) && providerAttemptObserved) {
                TraceStore.put("web.naver.providerResultCount", 0);
                TraceStore.put("web.naver.preFilterCount", 0);
                TraceStore.put("web.naver.postFilterCount", 0);
                TraceStore.put("web.naver.mergeCount", "unknown");
            } else if (unobservedCounts) {
                TraceStore.put("web.naver.providerResultCount", "unknown");
                TraceStore.put("web.naver.preFilterCount", "unknown");
                TraceStore.put("web.naver.postFilterCount", "unknown");
                TraceStore.put("web.naver.mergeCount", "unknown");
            }
        } catch (Exception suppressed) {
            NaverTraceSuppressions.traceSuppressed("classifiedOutcome.trace", suppressed);
        }
    }

    private static String naverFailureReason(Throwable t) {
        if (isNaverRateLimited(t)) {
            return "rate-limit";
        }
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof java.util.concurrent.CancellationException || cur instanceof InterruptedException) return "cancelled";
            String type = cur.getClass().getName().toLowerCase(java.util.Locale.ROOT);
            String msg = cur.getMessage() == null ? "" : cur.getMessage().toLowerCase(java.util.Locale.ROOT);
            if (type.contains("cancellationexception") || msg.contains("cancelled") || msg.contains("canceled")) return "cancelled";
            cur = cur.getCause();
        }
        if (isNaverTimeoutFailure(t)) {
            return "timeout";
        }
        if (naverHttpStatus(t) > 0) {
            return "http-error";
        }
        return "exception";
    }

    private static String naverErrorBody(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof WebClientResponseException w) {
                return w.getResponseBodyAsString();
            }
            cur = cur.getCause();
        }
        return null;
    }

    private static String naverErrorType(Throwable t) {
        return t == null ? "none" : t.getClass().getSimpleName();
    }

    private boolean hasCreds() {
        boolean ok = naverKeys != null && !naverKeys.isEmpty()
                && naverKeys.stream().anyMatch(k -> !ConfigValueGuards.isMissing(k.id())
                && !ConfigValueGuards.isMissing(k.secret()));
        if (!ok) {
            String reason = naverDisabledReason();
            TraceStore.put("web.naver.providerDisabled", true);
            TraceStore.put("web.naver.disabledReason", SafeRedactor.traceLabelOrFallback(reason, "unknown"));
            traceNaverCounts(null, 0, 0, 0, true, reason);
            log.warn("[AWX2AF2][search][naver] provider disabled enabled=false sourceName={} keysPresent={} clientPairPresent={} parsedCount={} disabledReason={}",
                    naverKeysPresent ? "naver.keys/NAVER_KEYS" : "naver.client-id/NAVER_CLIENT_ID",
                    naverKeysPresent,
                    naverClientPairPresent,
                    parsedNaverKeyCount,
                    SafeRedactor.traceLabelOrFallback(reason, "unknown"));
        }
        return ok;
    }

    private String naverDisabledReason() {
        return naverKeysPresent ? "invalid_naver_keys" : "missing_naver_client_credentials";
    }

    private static String queryTokenBucket(String query) {
        if (query == null || query.isBlank()) {
            return "0";
        }
        int tokens = query.trim().split("\\s+").length;
        if (tokens <= 4) {
            return "1-4";
        }
        if (tokens <= 12) {
            return "5-12";
        }
        if (tokens <= 32) {
            return "13-32";
        }
        return "33+";
    }

    private String traceQueryLabel(String query) {
        if (rawQueryTraceEnabled) {
            return SafeRedactor.diagnosticText("query", query, 160);
        }
        String hash = SafeRedactor.hashValue(query);
        return hash == null ? "" : hash;
    }

    private String deriveLocationSuffix(String q) {
        // [HARDENING] Simplify: if suffix not configured, return null.
        if (isBlank(querySuffix)) {
            return null;
        }
        // Use intent detector if available to determine if query is location-intent
        if (locationIntentDetector != null) {
            try {
                var intent = locationIntentDetector.detect(q);
                // Only attach suffix when intent is not NONE
                if (intent == com.example.lms.location.intent.LocationIntent.NONE) {
                    return null;
                }
            } catch (Exception ignore) {
                NaverTraceSuppressions.traceSuppressed("locationIntent.detect", ignore);
            }
        }
        return querySuffix;
    }

    /** Dynamically tighten/loosen similarity threshold. */
    private double adaptiveThreshold(String q) {
        int len = (q == null) ? 0
                : NON_ALNUM.matcher(q).replaceAll("").length();
        if (len <= 6)
            return Math.min(0.8, querySimThreshold + 0.2);
        if (len >= 30)
            return Math.max(0.2, querySimThreshold - 0.1);
        return querySimThreshold;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String firstNonBlank(String... xs) {
        if (xs == null) {
            return null;
        }
        for (String x : xs) {
            if (x != null && !x.isBlank()) {
                return x;
            }
        }
        return null;
    }

    private static void withTempMdc(String requestId, String sessionId, Runnable r) {
        // Bridge correlation into reactive threads for logging/debug events.
        // Netty event-loop threads are reused: always restore/clear what we set.
        String prevRid = null;
        String prevTrace = null;
        String prevTraceId = null;
        String prevSid = null;
        String prevSessionId = null;
        boolean putRid = false;
        boolean putTrace = false;
        boolean putTraceId = false;
        boolean putSid = false;
        boolean putSessionId = false;
        try {
            prevRid = org.slf4j.MDC.get("x-request-id");
            prevTrace = org.slf4j.MDC.get("trace");
            prevTraceId = org.slf4j.MDC.get("traceId");
            prevSid = org.slf4j.MDC.get("sid");
            prevSessionId = org.slf4j.MDC.get("sessionId");

            // If we have an X-Request-Id, make it available as trace/traceId too
            // so DebugEventStore can consistently populate traceId.
            if ((prevRid == null || prevRid.isBlank()) && requestId != null && !requestId.isBlank()) {
                org.slf4j.MDC.put("x-request-id", requestId);
                putRid = true;
            }
            if ((prevTrace == null || prevTrace.isBlank()) && requestId != null && !requestId.isBlank()) {
                org.slf4j.MDC.put("trace", requestId);
                putTrace = true;
            }
            if ((prevTraceId == null || prevTraceId.isBlank()) && requestId != null && !requestId.isBlank()) {
                org.slf4j.MDC.put("traceId", requestId);
                putTraceId = true;
            }

            // Session correlation (sid + sessionId)
            if ((prevSid == null || prevSid.isBlank()) && sessionId != null && !sessionId.isBlank()) {
                org.slf4j.MDC.put("sid", sessionId);
                putSid = true;
            }
            if ((prevSessionId == null || prevSessionId.isBlank()) && sessionId != null && !sessionId.isBlank()) {
                org.slf4j.MDC.put("sessionId", sessionId);
                putSessionId = true;
            }

            // Also best-effort install into TraceStore for components that fall back to it.
            try {
                if (sessionId != null && !sessionId.isBlank() && com.example.lms.search.TraceStore.get("sid") == null) {
                    com.example.lms.search.TraceStore.put("sid", SafeRedactor.hashValue(sessionId));
                }
                if (requestId != null && !requestId.isBlank()
                        && com.example.lms.search.TraceStore.get("trace.id") == null) {
                    com.example.lms.search.TraceStore.put("trace.id", SafeRedactor.hashValue(requestId));
                }
            } catch (Throwable ignore) {
                NaverTraceSuppressions.traceSuppressed("ctx.traceStoreSeed", ignore);
            }
            r.run();
        } finally {
            try {
                if (putSessionId) {
                    if (prevSessionId != null && !prevSessionId.isBlank()) {
                        org.slf4j.MDC.put("sessionId", prevSessionId);
                    } else {
                        org.slf4j.MDC.remove("sessionId");
                    }
                }
                if (putSid) {
                    if (prevSid != null && !prevSid.isBlank()) {
                        org.slf4j.MDC.put("sid", prevSid);
                    } else {
                        org.slf4j.MDC.remove("sid");
                    }
                }
                if (putTraceId) {
                    if (prevTraceId != null && !prevTraceId.isBlank()) {
                        org.slf4j.MDC.put("traceId", prevTraceId);
                    } else {
                        org.slf4j.MDC.remove("traceId");
                    }
                }
                if (putTrace) {
                    if (prevTrace != null && !prevTrace.isBlank()) {
                        org.slf4j.MDC.put("trace", prevTrace);
                    } else {
                        org.slf4j.MDC.remove("trace");
                    }
                }
                if (putRid) {
                    if (prevRid != null && !prevRid.isBlank()) {
                        org.slf4j.MDC.put("x-request-id", prevRid);
                    } else {
                        org.slf4j.MDC.remove("x-request-id");
                    }
                }
            } catch (Throwable ignore) {
                NaverTraceSuppressions.traceSuppressed("ctx.mdcRestore", ignore);
            }
        }
    }

    private static <T> T withTempMdc(String requestId, String sessionId, java.util.function.Supplier<T> s) {
        final java.util.concurrent.atomic.AtomicReference<T> ref = new java.util.concurrent.atomic.AtomicReference<>();
        withTempMdc(requestId, sessionId, () -> ref.set(s.get()));
        return ref.get();
    }

    /**
     * Split a comma-separated list while preserving quoted segments.
     *
     * <p>
     * Examples:
     * <ul>
     * <li>{@code a:b,c:d} → [a:b, c:d]</li>
     * <li>{@code "a:b,c:d"} → [a:b,c:d] (single token)</li>
     * </ul>
     */
    private static List<String> splitCsv(String s) {
        if (isBlank(s)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        char quoteChar = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if ((ch == '"' || ch == '\'')) {
                if (!inQuotes) {
                    inQuotes = true;
                    quoteChar = ch;
                } else if (quoteChar == ch) {
                    inQuotes = false;
                    quoteChar = 0;
                }
                cur.append(ch);
                continue;
            }
            if (ch == ',' && !inQuotes) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        out.add(cur.toString());
        return out;
    }

    private static String stripQuotes(String s) {
        if (s == null)
            return null;
        String t = s.trim();
        if (t.length() >= 2) {
            char a = t.charAt(0);
            char b = t.charAt(t.length() - 1);
            if ((a == '"' && b == '"') || (a == '\'' && b == '\'')) {
                return t.substring(1, t.length() - 1).trim();
            }
        }
        return t;
    }

    private boolean isAllowedDomain(String url) {
        if (isBlank(url))
            return true;
        if (!enableDomainFilter || isBlank(allowlist))
            return true;
        String host;
        try {
            host = URI.create(url).getHost();
        } catch (Exception ignore) {
            NaverTraceSuppressions.traceSuppressed("domain.allow.hostParse", ignore);
            return true;
        }
        if (host == null)
            return true;
        // 서브도메인 허용: *.eulji.ac.kr, *.eulji.or.kr 등
        return Arrays.stream(allowlist.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .anyMatch(host::endsWith);
    }

    /**
     * Check whether a URL belongs to any of the comma separated allowed domains.
     * This variant of {@link #isAllowedDomain(String)} accepts a dynamic
     * allowlist rather than relying on the global {@code allowlist} field.
     * When the supplied CSV is null or blank all domains are permitted.
     *
     * @param url      the full URL (may be null or blank)
     * @param allowCsv a comma separated list of domain suffixes
     * @return true if the host ends with any allowed suffix
     */
    private boolean isAllowedDomainWith(String url, String allowCsv) {
        if (isBlank(url))
            return true;
        if (isBlank(allowCsv))
            return true;
        String host;
        try {
            host = java.net.URI.create(url).getHost();
        } catch (Exception ignore) {
            NaverTraceSuppressions.traceSuppressed("domain.allowCsv.hostParse", ignore);
            return true;
        }
        if (host == null)
            return true;
        return java.util.Arrays.stream(allowCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .anyMatch(host::endsWith);
    }

    private boolean isBlockedDomain(String url) {
        if (isBlank(url) || isBlank(blockedDomainsCsv))
            return false;
        String host;
        try {
            host = URI.create(url).getHost();
        } catch (Exception ignore) {
            NaverTraceSuppressions.traceSuppressed("domain.block.hostParse", ignore);
            return false;
        }
        if (host == null)
            return false;
        return Arrays.stream(blockedDomainsCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .anyMatch(host::contains);
    }

    private static String stripHtml(String html) {
        if (isBlank(html)) {
            return "";
        }
        try {
            html = URLDecoder.decode(html, StandardCharsets.UTF_8);
        } catch (Exception ignore) {
            NaverTraceSuppressions.traceSuppressed("html.decode", ignore);
        }
        // Fast, dependency-free HTML tag stripping (Naver API returns <b> tags).
        String s = html.replaceAll("<[^>]*>", "");
        // Decode a few common entities (best-effort).
        s = s.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&#x27;", "'");
        return s.replaceAll("\\s{2,}", " ").trim();
    }

    private static List<String> keywords(String q) {
        return Arrays.stream(q.split("\\s+"))
                .map(String::toLowerCase)
                .map(t -> NON_ALNUM.matcher(t).replaceAll(""))
                .filter(t -> !t.isBlank())
                .filter(t -> t.length() > 1)
                // (제거) FILLER_WORDS 필터 - 상위 LLM 단계에서 처리
                .toList();
    }

    private static int hitCount(String text, List<String> kws) {
        String base = NON_ALNUM.matcher(text.toLowerCase()).replaceAll("");
        int cnt = 0;
        for (String k : kws) {
            if (base.contains(k))
                cnt++;
        }
        return cnt;
    }

    public enum SearchStatus {
        SUCCESS, API_FAILURE, NO_RESULTS
    }

    public record SearchResultWrapper(
            SearchStatus status,
            List<String> snippets,
            @Nullable String failureReason) {
    }

    private void reinforceRetrievedSnippets(String query, List<String> snippets) {
        // Optional enrichment must not turn successfully retrieved evidence into a search failure.
        try {
            Long sid = sessionIdProvider.get();
            if (sid != null) {
                reinforceSnippets(sid, query, snippets);
            }
        } catch (RuntimeException unavailable) {
            NaverTraceSuppressions.traceSuppressed("memory.reinforceRetrievedSnippets", unavailable);
        }
    }

    private void reinforceSnippets(Long sessionId, String query, List<String> snippets) {
        GuardContext ctx = GuardContextHolder.get();
        boolean memoryEnabled = ctx == null || !"NONE".equalsIgnoreCase(ctx.getMemoryProfile());
        if (!memoryEnabled) {
            return;
        }
        if (snippets == null || snippets.isEmpty()) {
            return;
        }

        for (int idx = 0; idx < snippets.size(); idx++) {
            String snippet = snippets.get(idx);
            if (recentSnippetCache.getIfPresent(DigestUtils.md5Hex(snippet)) != null) {
                continue;
            }

            double tmpScore;
            try {
                double sim = relevanceScorer.score(query, snippet);
                tmpScore = (sim > 0 ? sim : 0.01) / (idx + 1);
            } catch (Exception e) {
                NaverTraceSuppressions.traceSuppressed("memory.snippetScore", e); tmpScore = 1.0 / (idx + 1);
            }

            // ★ 람다에서 사용할 불변 변수
            final double score = tmpScore;
            final String snip = snippet;
            final Long sid = sessionId;
            final String qCopy = query;

            /* 개선 ① 독립 트랜잭션 & 중복 안전 처리 */
            Schedulers.boundedElastic()
                    .schedule(ContextPropagation.wrap(() -> txTemplate.executeWithoutResult(txStatus -> {
                        try {
                            memorySvc.reinforceWithSnippet(
                                    String.valueOf(sid),
                                    qCopy,
                                    snip,
                                    "WEB",
                                    score);
                        } catch (DataIntegrityViolationException dup) {
                            /* 동일 해시(UNIQUE) 중복 - 조용히 무시 */
                            log.debug("duplicate snippet ignored");
                        } catch (Exception e) {
                            log.warn("Failed to reinforce snippet. errorHash={} errorLength={}", SafeRedactor.hashValue(e.getMessage()), e.getMessage() == null ? 0 : e.getMessage().length());
                        }
                    })));

            /* 🔴 캐시 갱신도 snip 사용 */
            recentSnippetCache.put(DigestUtils.md5Hex(snip), Boolean.TRUE);
        }
    }

    // 선언형/접두어 제거(검색어: /* ... */, /* ... */입니다)
    private static String normalizeDeclaratives(String q) {
        if (q == null)
            return "";
        String s = q.replaceFirst("^\\s*검색어\\s*:\\s*", "");
        s = s.replace("입니다", "");
        // 문장 끝의 명령형 군더더기 제거
        s = s.replaceAll("\\s*(싹다|전부|모두)?\\s*(찾[아고]와|찾아와|검색해와)\\.?\\s*$", "");
        return s.trim();
    }

    /**
     * 두 번째 소스의 "교정된 문장/입력 문장/검색어1/* ... *&#47;" 접두사 제거용 정규화
     */
    private static String normalizeQuery(String q) {
        if (q == null)
            return "";
        String s = q;
        s = s.replaceAll("(?i)(교정된\\s*문장|입력\\s*문장|검색어\\s*\\d+|질문\\s*초안|요약)[:：]?", "");
        s = s.replaceAll("\\s+", " ").trim();
        // “5 8 패치/업데이트/버전/ver/v” → “5.8 /* ... *&#47;” 로 정규화
        s = s.replaceAll("(\\d)\\s+(\\d)(?=\\s*(?:패치|업데이트|버전|ver\\b|v\\b))", "$1.$2");
        return s;
    }

    /**
     * 두 번째 소스의 키워드 추출(간단 빈도 기반) - assistantAnswer 2-pass에 사용
     */
    private static String extractTopKeywords(String text, int max) {
        if (!StringUtils.hasText(text))
            return "";
        Set<String> stop = Set.of(
                "the", "and", "for", "with", "that", "this", "you", "your",
                "및", "그리고", "그러나", "또는", "등", "수", "것", "관련", "대한", "무엇", "뭐야", "뭐가", "어떤", "어떻게");
        Pattern p = Pattern.compile("[\\p{IsHangul}A-Za-z0-9]{2,}");
        Matcher m = p.matcher(text);

        Map<String, Integer> freq = new java.util.HashMap<>();
        while (m.find()) {
            String w = m.group().toLowerCase(Locale.ROOT);
            if (stop.contains(w))
                continue;
            freq.put(w, freq.getOrDefault(w, 0) + 1);
        }
        return freq.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(max)
                .map(Map.Entry::getKey)
                .collect(Collectors.joining(" "));
    }

    /**
     * 두 번째 소스의 간단 스니펫 포맷터(현재는 사용하지 않지만 호환성 위해 유지)
     * - 제목 - 요약 (호스트)
     */
    @SuppressWarnings("unused")
    private String toSnippetLegacy(String title, String description, String link) {
        String cleanTitle = stripHtml(title);
        String cleanDesc = stripHtml(description);
        String url = (link == null ? "" : link);
        String host;
        try {
            host = StringUtils.hasText(url) ? URI.create(url).getHost() : null;
        } catch (Exception e) {
            NaverTraceSuppressions.traceSuppressed("snippetLegacy.hostParse", e);
            host = null;
        }
        String text = (cleanTitle + " - " + cleanDesc).trim();
        if (text.length() < 10)
            return null;
        return "- " + text + " (" + (StringUtils.hasText(host) ? host : url) + ")";
    }

    /**
     * 검색 과정 패널을 만들기 위한 간단한 HTML 생성기
     */
    public String buildTraceHtml(SearchTrace t, List<String> snippets) {
        if (t == null)
            return "";
        StringBuilder sb = new StringBuilder();
        sb.append("<details class=\"search-trace\"><summary>🔎 검색 과정 (")
                .append(snippets != null ? snippets.size() : 0).append("개 스니펫) · ")
                .append(t.totalMs).append("ms</summary>");
        sb.append("<div class=\"trace-body\">");
        sb.append("<div class=\"trace-meta small text-muted\">")
                .append("도메인필터 ").append(t.domainFilterEnabled ? "ON" : "OFF")
                .append(" · 키워드필터 ").append(t.keywordFilterEnabled ? "ON" : "OFF");
        if (t.suffixApplied != null) {
            sb.append(" · 접미사: ").append(t.suffixApplied);
        }
        sb.append("</div>");
        sb.append("<ol class=\"trace-steps\">");
        for (SearchStep s : t.steps) {
            sb.append("<li><code>").append(escape(s.query)).append("</code>")
                    .append(" → 응답 ").append(s.returned)
                    .append("건, 필터 후 ").append(s.afterFilter)
                    .append("건 (").append(s.tookMs).append("ms)")
                    .append("</li>");
        }
        sb.append("</ol>");
        if (snippets != null && !snippets.isEmpty()) {
            if (traceSnippetsEnabled) {
            sb.append("<div class=\"trace-snippets\"><ul>");
            for (String line : snippets) {
                // 이미 a태그 포함된 라인 그대로 렌더
                sb.append("<li><code>")
                        .append(escape(String.valueOf(SafeRedactor.diagnosticValue("snippet", line, 600))))
                        .append("</code></li>");
            }
            sb.append("</ul></div>");
            } else {
                sb.append("<div class=\"trace-snippets\"><code>")
                        .append("snippetCount=").append(snippets.size())
                        .append(" snippetHash12=").append(escape(String.valueOf(snippetHashes(snippets))))
                        .append("</code></div>");
            }
        }
        sb.append("</div></details>");
        return sb.toString();
    }

    // UI-trace 특수문자 깨짐 방지
    private static String escape(String s) {
        if (s == null)
            return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private static java.util.List<String> snippetHashes(List<String> snippets) {
        if (snippets == null || snippets.isEmpty()) {
            return java.util.List.of();
        }
        java.util.List<String> hashes = new java.util.ArrayList<>();
        for (String snippet : snippets) {
            String hash = SafeRedactor.hash12(snippet);
            if (hash != null && !hash.isBlank()) {
                hashes.add(hash);
            }
            if (hashes.size() >= 20) {
                break;
            }
        }
        return java.util.List.copyOf(hashes);
    }

    @Override
    public List<String> search(String query, int topK) {
        return searchSnippetsSync(query, topK);
    }

    @Override
    public boolean isEnabled() {
        return hasCreds();
    }

    @Override
    public boolean isAvailable() {
        // Available when credentials are configured. Fine-grained rate-limit /
        // circuit-breaker
        // decisions are handled by the upstream orchestrator.
        return isEnabled();
    }

    @Override
    public int getPriority() {
        // Naver is preferred for Korean queries; orchestrator may still override.
        return 10;
    }

    @Override
    public String getName() {
        return "Naver";
    }

    @Override
    public String buildTraceHtml(Object traceObj, List<String> snippets) {
        if (traceObj instanceof SearchTrace) {
            return buildTraceHtml((SearchTrace) traceObj, snippets);
        }
        return WebSearchProvider.super.buildTraceHtml(traceObj, snippets);
    }

    /* === DTOs for JSON parsing === */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record NaverResponse(List<NaverItem> items) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record NaverItem(String title, String link, String description) {
    }

    @jakarta.annotation.PostConstruct
    private void initKeywordSets() {
        this.productKeywords = parseCsvToSet(productKeywordsCsv);
        this.foldKeywords = parseCsvToSet(foldKeywordsCsv);
        this.flipKeywords = parseCsvToSet(flipKeywordsCsv);
    }

    private Set<String> parseCsvToSet(String csv) {
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
    }

    /** 텍스트가 키워드 집합 중 하나라도 포함하는지 확인 */
    private boolean containsAny(String text, Set<String> keywords) {
        return keywords.stream().anyMatch(text::contains);
    }

    /** 세션별 대화 기록을 불러와 QueryTransformer에 전달 */
    private String getConversationContext() {
        Long sid = sessionIdProvider.get();
        if (sid == null)
            return "";
        try {
            return memorySvc.loadContext(String.valueOf(sid));
        } catch (Exception e) {
            NaverTraceSuppressions.traceSuppressed("conversationContext.load", e);
            return "";
        }
    }

    /**
     * Reinforce the assistant's response into translation memory.
     */

    /**
     * [Hardening] RAG 강화 저장 로직 보강
     * 할루시네이션이나 무의미한 답변("정보 없음")이 메모리에 오염되는 것을 방지.
     */
    public void reinforceAssistantResponse(String query, String answer) {
        GuardContext ctx = GuardContextHolder.get();
        boolean memoryEnabled = ctx == null || !"NONE".equalsIgnoreCase(ctx.getMemoryProfile());
        if (!memoryEnabled) {
            return;
        }

        if (!enableAssistantReinforcement || isBlank(answer) || isBlank(query)) {
            return;
        }

        // 1. 무의미한 답변 필터링 강화
        String cleanAnswer = answer.trim().toLowerCase();
        if (cleanAnswer.length() < 10
                || cleanAnswer.contains("정보 없음")
                || cleanAnswer.contains("죄송합니다")
                || cleanAnswer.contains("알 수 없습니다")) {
            // 너무 짧거나 사과/모름 응답은 메모리 강화 대상 아님
            return;
        }

        Long sessionId = sessionIdProvider.get();
        if (sessionId == null) {
            return;
        }

        // 2. 점수 산정 (기존 로직 유지)
        double score;
        try {
            double sim = relevanceScorer.score(query, answer);
            score = (sim > 0 ? sim : 1.0);
        } catch (Exception ignore) {
            NaverTraceSuppressions.traceSuppressed("assistant.score", ignore); score = 1.0;
        }
        final double finalScore = Math.max(0.01, score * assistantReinforceWeight);

        // 3. 저장 실행 (트랜잭션 + Fail-soft)
        try {
            txTemplate.executeWithoutResult(tx -> {
                try {
                    memorySvc.reinforceWithSnippet(
                            String.valueOf(sessionId),
                            query,
                            answer,
                            ASSISTANT_SOURCE,
                            finalScore);
                } catch (DataIntegrityViolationException dup) {
                    log.debug("duplicate assistant snippet ignored");
                }
            });
        } catch (Exception ignore) {
            log.debug("assistant reinforcement failed (ignored)");
        }
    }

    /** API 호출용으로 위치 접미사를 붙인 쿼리 문자열 생성 */
    private String appendLocationSuffix(String base) {
        String suffix = deriveLocationSuffix(base);
        return isBlank(suffix) ? base : base + " " + suffix;
    }

    /*
     * ────────────────────────────────────────
     * 유사 쿼리/정규화 유틸을 안전하게 별도 네임스페이스(Q)로 격리
     * ────────────────────────────────────────
     */
    private static @Nullable String extractVersionToken(String q) {
        if (q == null)
            return null;
        Matcher m = VERSION_PATTERN.matcher(q);
        return m.find() ? normalizeVersionToken(m.group()) : null;
    }

    private static Pattern versionMustRegex(String v) {
        String normalized = normalizeVersionToken(v);
        if (normalized == null) {
            return Pattern.compile("a^");
        }
        String core = Arrays.stream(normalized.split("\\."))
                .map(Pattern::quote)
                .collect(Collectors.joining("[\\.·\\-\\s]+"));
        return Pattern.compile("(?i)(?<![\\p{L}\\p{Nd}])v?" + core + "(?![\\p{L}\\p{Nd}])");
    }

    static @Nullable String normalizeVersionToken(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim()
                .replaceFirst("(?i)^v", "")
                .replaceAll("[·\\-\\s]+", ".")
                .replaceAll("\\.{2,}", ".");
        if (!normalized.matches("\\d+(?:\\.\\d+)+")) {
            return null;
        }
        return normalized;
    }

    static void restoreInterruptFlag() {
        Thread.currentThread().interrupt();
    }

    private static final class Q {
        static String canonical(String s) {
            if (s == null)
                return "";
            String t = NON_ALNUM_KO.matcher(s.toLowerCase()).replaceAll(" ").trim();
            return t.replaceAll("\\s{2,}", " ");
        }

        static Set<String> tokenSet(String s) {
            String t = canonical(s);
            if (t.isEmpty())
                return Set.of();
            return Arrays.stream(t.split("\\s+"))
                    .filter(w -> !w.isBlank())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        static double jaccard(Set<String> a, Set<String> b) {
            if (a.isEmpty() && b.isEmpty())
                return 1.0;
            if (a.isEmpty() || b.isEmpty())
                return 0.0;
            int inter = 0;
            for (String x : a)
                if (b.contains(x))
                    inter++;
            int union = a.size() + b.size() - inter;
            return union == 0 ? 0.0 : (double) inter / union;
        }

        static List<String> filterSimilarQueries(List<String> queries, double threshold) {
            List<String> kept = new ArrayList<>();
            List<Set<String>> keptTokens = new ArrayList<>();
            for (String qv : queries) {
                Set<String> tok = tokenSet(qv);
                boolean similar = false;
                for (Set<String> kt : keptTokens) {
                    if (jaccard(kt, tok) >= threshold) {
                        similar = true;
                        break;
                    }
                }
                if (!similar) {
                    kept.add(qv);
                    keptTokens.add(tok);
                }
            }
            return kept;
        }

    }

    private static String safeTrunc(String s, int max) {
        if (s == null)
            return "";
        if (s.length() <= max)
            return s;
        return s.substring(0, max) + "/* ... *&#47;";
    }

    private static String safeUriSummary(URI uri) {
        if (uri == null) {
            return "host=<null> path=<null> hasQuery=false";
        }
        String rawQuery = uri.getRawQuery();
        return "host=" + nullToEmpty(uri.getHost())
                + " path=" + nullToEmpty(uri.getPath())
                + " hasQuery=" + (rawQuery != null && !rawQuery.isBlank())
                + " queryHash=" + nullToEmpty(queryParamHash(rawQuery, "query"))
                + " display=" + nullToEmpty(queryParamScalar(rawQuery, "display"))
                + " start=" + nullToEmpty(queryParamScalar(rawQuery, "start"))
                + " sort=" + nullToEmpty(queryParamScalar(rawQuery, "sort"));
    }

    private static String queryParamHash(String rawQuery, String name) {
        String value = queryParamScalar(rawQuery, name);
        return value == null || value.isBlank() ? null : SafeRedactor.hashValue(value);
    }

    private static String queryParamScalar(String rawQuery, String name) {
        if (rawQuery == null || rawQuery.isBlank() || name == null || name.isBlank()) {
            return null;
        }
        String prefix = name + "=";
        for (String part : rawQuery.split("&")) {
            if (part == null || !part.startsWith(prefix)) {
                continue;
            }
            try {
                return URLDecoder.decode(part.substring(prefix.length()), StandardCharsets.UTF_8);
            } catch (Exception ignore) {
                NaverTraceSuppressions.traceSuppressed("queryParam.decode", ignore);
                return "(decode-failed)";
            }
        }
        return null;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** 캐시 솔트(정책/필터/리스트 변화가 키에 반영되도록) */

    /** Default policy derived from configuration fields (read-only). */
    private SearchPolicy defaultPolicy() {
        return SearchPolicy.fromConfig(
                this.enableDomainFilter,
                this.enableKeywordFilter,
                this.domainPolicy,
                this.keywordMinHits);
    }

    // ─── 신규: 정책 포함 버전 ───
    private String cacheSalt(SearchPolicy policy) {
        return policy.domainPolicy() + "|"
                + policy.domainFilterEnabled() + "|"
                + policy.keywordFilterEnabled() + "|"
                + policy.keywordMinHits() + "|"
                + this.allowlist + "|"
                + this.blockedDomainsCsv;
    }

    // ─── 기존 호환: 기본 정책으로 위임 ───
    private String cacheSalt() {
        return cacheSalt(defaultPolicy());
    }

    // ─── 신규: 정책 포함 버전 ───
    private String cacheKeyFor(String query, SearchPolicy policy) {
        return cacheKeyFor(query, policy, configuredNaverMaximum());
    }

    private String cacheKeyFor(String query, SearchPolicy policy, int outboundDisplay) {
        int boundedDisplay = Math.min(configuredNaverMaximum(), clampNaverDisplay(outboundDisplay));
        return cacheSalt(policy) + "||" + boundedDisplay + ":" + Q.canonical(query);
    }

    // ─── 기존 호환: 기본 정책으로 위임 ───
    private String cacheKeyFor(String q) {
        return cacheKeyFor(q, defaultPolicy());
    }

    private String queryFromCacheKey(String key) {
        String payload = key;
        int sep = key.lastIndexOf("||");
        if (sep >= 0 && sep + 2 < key.length()) {
            payload = key.substring(sep + 2);
        }
        int displaySeparator = payload.indexOf(':');
        if (displaySeparator >= 0 && displaySeparator + 1 < payload.length()) {
            payload = payload.substring(displaySeparator + 1);
        }
        return payload.replaceAll("^\\|+", "").trim();
    }

    private int outboundDisplayFromCacheKey(String key) {
        try {
            int sep = key.lastIndexOf("||");
            String payload = sep >= 0 ? key.substring(sep + 2) : key;
            int displaySeparator = payload.indexOf(':');
            if (displaySeparator > 0) {
                int parsed = Integer.parseInt(payload.substring(0, displaySeparator));
                return Math.min(configuredNaverMaximum(), clampNaverDisplay(parsed));
            }
        } catch (RuntimeException ignore) {
            NaverTraceSuppressions.traceSuppressed("cacheKey.display", ignore);
        }
        return configuredNaverMaximum();
    }

    private static <T> Mono<T> awaitCacheFuture(java.util.concurrent.CompletableFuture<T> sharedFuture) {
        java.util.concurrent.CompletableFuture<T> waiterFuture = sharedFuture.thenApply(value -> value);
        return Mono.fromFuture(waiterFuture);
    }

    /**
     * 캐시 키에서 정책을 파싱하여 복원
     * 로더가 전역 필드 대신 키 기반 정책을 사용하도록 함
     */
    private SearchPolicy policyFromCacheKey(String key) {
        try {
            int sep = key.lastIndexOf("||");
            String salt = (sep >= 0) ? key.substring(0, sep) : key;
            String[] parts = salt.split("\\|", 6);

            String policy = (parts.length > 0) ? parts[0] : this.domainPolicy;
            boolean domainFilter = (parts.length > 1) ? Boolean.parseBoolean(parts[1]) : this.enableDomainFilter;
            boolean keywordFilter = (parts.length > 2) ? Boolean.parseBoolean(parts[2]) : this.enableKeywordFilter;
            int minHits = (parts.length > 3) ? Integer.parseInt(parts[3]) : this.keywordMinHits;

            return SearchPolicy.fromConfig(domainFilter, keywordFilter, policy, minHits);
        } catch (Exception ignore) {
            NaverTraceSuppressions.traceSuppressed("policy.cacheKey", ignore);
            return defaultPolicy();
        }
    }

    private ExchangeFilterFunction injectCorrelationHeaders() {
        return (request, next) -> {
            String requestId = org.slf4j.MDC.get("x-request-id");
            if (isBlank(requestId)) {
                requestId = org.slf4j.MDC.get("trace");
            }
            String sessionId = org.slf4j.MDC.get("sessionId");
            if (isBlank(sessionId)) {
                sessionId = org.slf4j.MDC.get("sid");
            }

            // Fallback to TraceStore when MDC is missing (common across reactive/async
            // boundaries).
            boolean traceStoreProvidedRid = false;
            boolean traceStoreProvidedSid = false;
            try {
                if (isBlank(requestId)) {
                    Object v = TraceStore.get("trace.id");
                    if (v != null && !isBlank(String.valueOf(v))) {
                        requestId = String.valueOf(v);
                        traceStoreProvidedRid = true;
                    }
                }
                if (isBlank(sessionId)) {
                    Object v = TraceStore.get("sid");
                    if (v != null && !isBlank(String.valueOf(v))) {
                        sessionId = String.valueOf(v);
                        traceStoreProvidedSid = true;
                    }
                }
            } catch (Exception ignore) {
                NaverTraceSuppressions.traceSuppressed("ctx.traceStoreRead", ignore);
            }

            // Missing correlation ids here is a strong signal of context propagation
            // leakage (e.g., async boundaries without MDC propagation).
            boolean ridPlaceholder = requestId != null && requestId.startsWith("rid-missing-");
            boolean sidPlaceholder = sessionId != null && sessionId.startsWith("sid-missing-");
            boolean missingCorrelation = (isBlank(requestId) || isBlank(sessionId) || ridPlaceholder || sidPlaceholder);

            if (missingCorrelation) {
                // High-signal anchor for Trace UI + post-mortem debugging.
                // (Keep it compact: boolean + counter; details live in DebugEvent.)
                try {
                    TraceStore.put("ctx.propagation.missing", true);
                    TraceStore.put("ctx.propagation.missing.naver", true);
                    TraceStore.inc("ctx.propagation.missing.count");
                    if (ridPlaceholder || sidPlaceholder) {
                        TraceStore.put("ctx.propagation.generated", true);
                    }

                    // (G) Record a compact per-request event so Trace UI can show the exact
                    // boundary.
                    try {
                        java.util.Map<String, Object> ev = new java.util.LinkedHashMap<>();
                        ev.put("seq", TraceStore.nextSequence("ctx.propagation.missing.events"));
                        ev.put("ts", java.time.Instant.now().toString());
                        ev.put("kind", (ridPlaceholder || sidPlaceholder) ? "generated" : "missing");
                        ev.put("where", "naver.injectCorrelationHeaders");
                        ev.put("source", "NaverSearchService.injectCorrelationHeaders");
                        ev.put("hasRequestId", !isBlank(requestId));
                        ev.put("hasSessionId", !isBlank(sessionId));
                        ev.put("traceStoreProvidedRid", traceStoreProvidedRid);
                        ev.put("traceStoreProvidedSid", traceStoreProvidedSid);
                        ev.put("method", String.valueOf(request.method()));
                        ev.put("url", SafeRedactor.diagnosticValue("url", String.valueOf(request.url())));
                        if (!isBlank(requestId))
                            ev.put("rid", SafeRedactor.hashValue(requestId));
                        if (!isBlank(sessionId))
                            ev.put("sid", SafeRedactor.hashValue(sessionId));
                        TraceStore.append("ctx.propagation.missing.events", ev);
                    } catch (Throwable suppressed) { NaverTraceSuppressions.traceSuppressed("ctxPropagation.missingEvent", suppressed); }
                } catch (Throwable ignore) { NaverTraceSuppressions.traceSuppressed("ctx.propagationTrace", ignore); }
            }

            // Missing correlation ids here is a strong signal of context propagation
            // leakage
            // (e.g., async boundaries without MDC/TraceStore propagation). Only emit when
            // we still
            // need to generate local ids (to avoid noise when TraceStore successfully
            // recovered it).
            if (debugEventStore != null && missingCorrelation) {
                try {
                    AblationContributionTracker.recordPenaltyOnce("ctx.missing.naver.inject", "web_search",
                            "missing_naver_inject", 0.02, null);
                } catch (Throwable ignore) {
                    NaverTraceSuppressions.traceSuppressed("ctx.ablationPenalty", ignore);
                }

                debugEventStore.emit(
                        DebugProbeType.CONTEXT_PROPAGATION,
                        DebugEventLevel.WARN,
                        "context.mdc.missing.naver.inject",
                        "Missing correlation ids when building NAVER outbound request",
                        "NaverSearchService.injectCorrelationHeaders",
                        java.util.Map.of(
                                "hasRequestId", !isBlank(requestId),
                                "hasSessionId", !isBlank(sessionId),
                                "traceStoreProvidedRid", traceStoreProvidedRid,
                                "traceStoreProvidedSid", traceStoreProvidedSid,
                                "thread", Thread.currentThread().getName()),
                        null);
            }

            // Fail-soft: even if upstream correlation is missing, inject a local request id
            // so outbound calls are still traceable in logs.
            if (isBlank(requestId)) {
                requestId = "rid-missing-" + java.util.UUID.randomUUID();
                try {
                    TraceStore.put("ctx.propagation.generated", true);
                } catch (Throwable suppressed) { NaverTraceSuppressions.traceSuppressed("ctxPropagation.generatedTrace", suppressed); }
            }
            if (isBlank(sessionId)) {
                sessionId = "sid-missing-" + java.util.UUID.randomUUID();
                try {
                    TraceStore.put("ctx.propagation.generated", true);
                } catch (Throwable suppressed) { NaverTraceSuppressions.traceSuppressed("ctxPropagation.generatedTraceFallback", suppressed); }
            }

            ClientRequest.Builder b = ClientRequest.from(request);
            if (requestId != null && !requestId.isBlank() && !request.headers().containsKey("x-request-id")) {
                b.header("x-request-id", requestId);
            }
            if (sessionId != null && !sessionId.isBlank() && !request.headers().containsKey("x-session-id")) {
                b.header("x-session-id", sessionId);
            }
            return next.exchange(b.build());
        };
    }

    private ExchangeFilterFunction logOnError() {
        return ExchangeFilterFunction.ofResponseProcessor(clientResponse -> {
            // [FIX] 람다에서 참조할 수 있도록 final 복사본 생성
            String rid = org.slf4j.MDC.get("x-request-id");
            if (isBlank(rid)) {
                rid = org.slf4j.MDC.get("trace");
            }
            final String requestId = rid;

            String sid = org.slf4j.MDC.get("sessionId");
            if (isBlank(sid)) {
                sid = org.slf4j.MDC.get("sid");
            }
            final String sessionId = sid;
            if (clientResponse.statusCode().isError()) {
                // Non-consumptive logging: read body once, log, then rebuild response with the
                // same body.
                return clientResponse.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(body -> {
                            String bodyHash = SafeRedactor.hashValue(body == null ? "" : body);
                            int bodyLength = body == null ? 0 : body.length();
                            int status = clientResponse.statusCode().value();
                            log.warn("[AWX][search][naver] error status={} ridHash={} sidHash={} bodyHash={} bodyLength={}",
                                    status, SafeRedactor.hashValue(requestId), SafeRedactor.hashValue(sessionId), bodyHash, bodyLength);

                            if (debugEventStore != null) {
                                debugEventStore.emit(
                                        DebugProbeType.NAVER_SEARCH,
                                        DebugEventLevel.WARN,
                                        "naver.http.error." + status,
                                        "NAVER OpenAPI error response",
                                        "NaverSearchService.logOnError",
                                        java.util.Map.of(
                                                "status", status,
                                                "rid", requestId == null ? "" : requestId,
                                                "sid", sessionId == null ? "" : sessionId,
                                                "bodyHash", bodyHash,
                                                "bodyLength", bodyLength),
                                        null);
                            }
                            return Mono.just(clientResponse.mutate().body(body).build());
                        });
            }
            return Mono.just(clientResponse);
        });
    }

    private boolean isRetryableNaverError(Throwable t) {
        if (t == null) {
            return false;
        }
        // Do not retry cancellations; caller deadlines may have been reached.
        if (t instanceof java.util.concurrent.CancellationException) {
            return false;
        }
        if (t instanceof java.util.concurrent.TimeoutException) {
            // Timeouts are handled by tuned per-request timeout; do not retry here to avoid doubling latency.
            return false;
        }
        // WebClient I/O level exceptions (connect/read timeout, DNS, etc.)
        if (t instanceof org.springframework.web.reactive.function.client.WebClientRequestException) {
            return true;
        }
        if (t instanceof org.springframework.web.reactive.function.client.WebClientResponseException wex) {
            int sc = wex.getRawStatusCode();
            // Retry only on transient server errors (5xx). Do NOT retry 429/4xx here.
            return sc >= 500 && sc < 600;
        }
        return false;
    }

    private static boolean traceBool(String key) {
        try {
            Object v = TraceStore.get(key);
            if (v == null) {
                return false;
            }
            if (v instanceof Boolean b) {
                return b;
            }
            if (v instanceof Number n) {
                return n.intValue() != 0;
            }
            String s = String.valueOf(v).trim().toLowerCase(java.util.Locale.ROOT);
            return "true".equals(s) || "1".equals(s) || "yes".equals(s) || "y".equals(s);
        } catch (Throwable ignore) {
            NaverTraceSuppressions.traceSuppressed("traceFlag.parse", ignore);
            return false;
        }
    }

    /**
     * Preserve the formatted source anchor; its title/description were already stripped.
     */
    private static String sanitizeSnippet(String snippet) {
        if (snippet != null && snippet.startsWith("- <a href=\"")) {
            return snippet.trim();
        }
        return stripHtml(snippet);
    }
}
// RuleBreak: when active, caller may adjust opt; this service accepts rb param
// for future tuning.
