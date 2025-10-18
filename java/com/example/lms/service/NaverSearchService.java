package com.example.lms.service;

import org.springframework.lang.Nullable;
import java.util.concurrent.atomic.AtomicLong;
import reactor.core.publisher.Flux;
import com.fasterxml.jackson.databind.JsonNode;
import reactor.core.publisher.Mono;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.http.HttpHeaders;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.http.ResponseEntity;
import com.example.lms.search.RateLimitPolicy;
import org.springframework.dao.DataIntegrityViolationException;
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
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.Query;
import com.example.lms.config.NaverFilterProperties;
import dev.langchain4j.store.embedding.EmbeddingStore;
import java.util.regex.Matcher;
import java.io.IOException;
import java.net.URI;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.util.StringUtils;
import reactor.util.retry.Retry;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import com.example.lms.guard.rulebreak.RuleBreakContext;
import com.example.lms.guard.rulebreak.RuleBreakPolicy;


import com.example.lms.service.search.SearchDisambiguation; //  중의성(자동차 등) 필터
import java.util.concurrent.Semaphore;   // 세마포어 클래스 :contentReference[oaicite:0]{

    private boolean hedgeEnabled = true;
    private int hedgeDelayMs = 120;
    private int timeoutMs = 1800;

    public void configureHedging(boolean enabled, int delayMs, int timeoutMs) {
        this.hedgeEnabled = enabled;
        this.hedgeDelayMs = delayMs;
        this.timeoutMs = timeoutMs;
    }
    index=0}
import java.util.Locale;                        // ★ Locale 누락

import org.springframework.transaction.PlatformTransactionManager;         // ─ 트랜잭션 템플릿 추가
import com.example.lms.service.rag.pre.QueryContextPreprocessor;   // ⭐ NEW
import reactor.core.scheduler.Schedulers;         // B. Schedulers 임포트 추가
import java.time.Duration;                       // ▲ Sync Facade에서 block 타임아웃에 사용
import java.util.Objects;                        // NEW – distinct/limit 필터
import com.github.benmanes.caffeine.cache.LoadingCache;   // recentSnippetCache 용
import dev.langchain4j.data.embedding.Embedding;        // NEW – batch embedAll
import java.net.URLEncoder;                        // + DuckDuckGo 쿼리 인코딩
// - Lombok RequiredArgsConstructor는 명시 생성자와 충돌
import org.jsoup.Jsoup;                 // HTML 파서
import org.jsoup.nodes.Document;        //  DuckDuckGo HTML 파싱
import org.jsoup.nodes.Element;         //  DuckDuckGo HTML 파싱
import org.jsoup.select.Elements;       //  DuckDuckGo HTML 파싱

/**
 * Simplified NaverSearchService that does not automatically append
 * marketplace keywords (e.g., 번개장터, 중고나라) or site restrictions.
 * It processes queries, applies an optional location suffix,
 * filters by allow/deny lists and stores snippets into memory with
 * cosine‑similarity scores.
 */
@Service
@Primary
public class NaverSearchService {
    private static final Logger log = LoggerFactory.getLogger(NaverSearchService.class);

    /**
     * LLM 답변을 활용한 검색 (딥 리서치 모드)
     */

    // 🔑 Naver API 키 CSV(생성자에서 주입) & 키 회전용 변수들
    private final String naverKeysCsv;          // keys 1:a1b2c3,2:d4e5f6 …
    private List<ApiKey> naverKeys = List.of(); // 초기값은 빈 리스트
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
        public final List<SearchStep> steps = new ArrayList<>();
        public boolean domainFilterEnabled;
        public boolean keywordFilterEnabled;
        public String suffixApplied;
        public long totalMs;
        /** Reason why the domain filter was disabled, or null when enabled. */
        public String reasonDomainFilterDisabled;
        /** Reason why the keyword filter was disabled, or null when enabled. */
        public String reasonKeywordFilterDisabled;

        /** Whether an organisation was resolved for this query. */
        public boolean orgResolved;
        /** The canonical name of the resolved organisation, if any. */
        public String orgCanonical;
        /** List of site filters applied during org‑aware search. */
        public java.util.List<String> siteFiltersApplied = new java.util.ArrayList<>();
    }

    /**
     * 스니펫 + 추적 묶음
     */
    public record SearchResult(List<String> snippets, SearchTrace trace) { }

    public static final class MetadataKeys {
        /**
         * Unified metadata key used across services.  To ensure that RAG, web, and memory
         * retrievals all reference the same session metadata field, reuse the
         * constant defined in {@link LangChainRAGService}.  The original value
         * "sessionId" is replaced with {@link LangChainRAGService#META_SID}, which
         * resolves to "sid".  This eliminates mismatches where one component
         * writes "sid" metadata but another reads "sessionId", resulting in
         * cross‑session bleed.
         */
        public static final String SESSION_ID = LangChainRAGService.META_SID;
        private MetadataKeys() {}
    }

    /* === Dependencies === */
    private final MemoryReinforcementService memorySvc;
    private final ObjectProvider<ContentRetriever> retrieverProvider;
    private final EmbeddingStore<TextSegment> embeddingStore;
    @Qualifier("guardrailQueryPreprocessor")   // ◀ 정확한 bean 이름
    private final QueryContextPreprocessor preprocessor;           // ⭐ NEW
    private final EmbeddingModel embeddingModel;
    private final WebClient web;
    private final WebClient duck;     // + DuckDuckGo HTML용
    private final ObjectMapper om;
    /** Cache for normalized queries to web snippet lists. */
    /** 비동기 캐시 (block 금지) */
    private final AsyncLoadingCache<String, List<String>> cache;

    // [HARDENING] optional detector for location intent; auto-wired when present
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.lms.location.intent.LocationIntentDetector locationIntentDetector;
    /** Cache to prevent reinforcing duplicate snippets. */
    private final LoadingCache<String, Boolean> recentSnippetCache;
    /** Cache for location token embeddings (memoization) */
    private final LoadingCache<String, float[]> locationEmbedCache;
    /** Scorer for cosine similarity. */
    private final RelevanceScorer relevanceScorer;
    /** 레이트리밋 정책(헤더 기반 동적 제어) */
    private final RateLimitPolicy ratePolicy;
    /** NEW – 별도 트랜잭션 컨텍스트용 */
    private final TransactionTemplate txTemplate;
    /** Supplier of the current session id. */
    private final Supplier<Long> sessionIdProvider;

    /**
     * Optional Redis‑based cooldown service.  When provided this service
     * guards external API calls with a short‑lived lock to prevent
     * thundering herd behaviour and excessive concurrent requests.  When
     * null no cooldown is applied and calls proceed immediately.  The
     * implementation is provided via Spring and may be absent when Redis
     * is unavailable or not configured.
     */
    private final com.example.lms.service.redis.RedisCooldownService cooldownService;

    /* === Configuration properties === */
    // (client-id / client-secret 개별 프로퍼티는 더 이상 사용하지 않는다)
    /** 단순화된 호출 타임아웃(ms) */
    private static final long API_TIMEOUT_MS = 3000;
    @Value("${naver.search.web-top-k:8}")
    private int webTopK;   // LLM에 넘길 개수
    @Value("${naver.search.rag-top-k:5}")
    private int ragTopK;   // 벡터 RAG top‑k
    /** (NEW) 네이버 API에서 한 번에 받아올 검색 결과 수(1‑100) */

    @Value("${naver.search.display:20}")
    private int display;
    @Value("${naver.search.query-suffix:}")
    private String querySuffix;
    @Value("${naver.search.query-sim-threshold:0.3}")
    private double querySimThreshold;
    // Domain filtering enabled flag.  This value is initialised from {@link NaverFilterProperties}.
    private volatile boolean enableDomainFilter; // [HARDENING] default true

    /* ---------- 2. ApiKey 헬퍼 타입 ---------- */
    private record ApiKey(String id, String secret) { }

    // 기본 허용 목록에 서브도메인 포함 도메인 추가(부재 시 0개 스니펫 방지)
    // Comma separated allowlist of domain suffixes.  Populated from {@link NaverFilterProperties}.
    private volatile String allowlist;
    // Keyword filtering enabled flag (initialised from NaverFilterProperties).
    private boolean enableKeywordFilter;
    // 키워드 필터는 OR(하나 이상 매칭)로 완화
    // Minimum number of keyword hits required; populated from {@link NaverFilterProperties}.
    private int keywordMinHits;
    /* === Configuration properties === */
    @Value("${naver.search.debug:false}")          // ⬅ 추가
    private boolean debugSearchApi;                // ⬅ 추가

    /** Comma-separated blacklist of domains to exclude entirely. */
    @Value("${naver.search.blocked-domains:}")
    private String blockedDomainsCsv;

    /** (선택) 대화 문맥에 따라 쿼리를 재작성하는 Transformer – 존재하지 않으면 주입 안 됨 */
    /** 오타·맞춤법 교정을 담당하는 Transformer */
    private final QueryTransformer queryTransformer;

    /* 최대 동시 네이버 API 호출량 (429 방지) */
    private static final int MAX_CONCURRENT_API = 2;
    /** 네이버 API 429 방지를 위한 전역 세마포어 */
    private static final Semaphore REQUEST_SEMAPHORE =
            new Semaphore(MAX_CONCURRENT_API);
    /* ★ NEW: 한 검색당 최대 변형 쿼리 수
       assistantAnswer 기반 딥-서치에서 QueryTransformer가 생성하는
       변형 쿼리 폭주를 안전하게 제한한다. */
    private static final int MAX_QUERIES_PER_SEARCH = 9;

    /* ────────────────────────────────────────────────
     * “site eulji ac kr …” 류 도메인-스코프 변형 차단용 패턴
     *  - ‘site ’ 로 시작하거나
     *  - ac kr 등 TLD 조각이 앞머리에 노출되는 경우
     * ────────────────────────────────────────────────*/
    private static final Pattern DOMAIN_SCOPE_PREFIX =
            Pattern.compile("(?i)^\\s*(site\\s+)?\\S+\\s+ac\\s+kr\\b");

        /* ── 헤징(동시 이중 발사) 관련: 기본 OFF, 필요 시 지연 헤징만 허용 ── */
        /* 🔵 다중-키 헤징 전략 제거: 항상 첫 번째 네이버 키만 사용 */
        /**
         * When true a hedged search will trigger a delayed DuckDuckGo fallback in
         * parallel with the primary Naver request.  The first source to return
         * wins, shortening perceived latency when Naver is slow or returns no
         * results.  Controlled via the naver.hedge.enabled property.  Defaults
         * to false to preserve sequential behaviour unless explicitly enabled.
         */
        @org.springframework.beans.factory.annotation.Value("${naver.hedge.enabled:false}")
        private boolean hedgeEnabled;
        /**
         * Maximum timeout (milliseconds) to wait for the primary Naver API call
         * before giving up.  Reduced from the previous default of 3000ms to
         * 1000ms to tighten per-call bounds.  Configurable via
         * naver.hedge.timeout-ms.
         */
        @Value("${naver.hedge.timeout-ms:1000}")
        private long hedgeTimeoutMs;   // primary 타임아웃 계산엔 그대로 사용
        /**
         * Delay before issuing a parallel DuckDuckGo fallback when hedging is
         * enabled.  A smaller delay allows the fallback to race with a slow
         * primary call.  Configurable via naver.hedge.delay-ms; defaults to
         * 200ms when unspecified.
         */
        @Value("${naver.hedge.delay-ms:200}")
        private long hedgeDelayMs;
        @Value("${naver.search.timeout-ms:5000}")
        private long apiTimeoutMs;

    @Value("${naver.search.debug-json:false}")
    private boolean debugJson;
    @Value("${naver.search.expansion-policy:conservative}")
    private String expansionPolicy;  //  동의어 확장 정책 (conservative|none)



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

    /** reinforcement 시 적용할 감쇠 가중치 (0.0 ~ 1.0) – 높을수록 더 많이 반영 */
    @Value("${naver.reinforce-assistant.weight:0.4}")
    private double assistantReinforceWeight;

    // Domain policy controlling filter/boost behaviour.  Initialised from {@link NaverFilterProperties}.
    private String domainPolicy;

    /**
     * Toggle for the DuckDuckGo fallback.  When false (default) all
     * fallback behaviour is disabled and empty results are returned
     * instead.  This property can be overridden via
     * `naver.fallback.duckduckgo.enabled` in application properties.
     */
    @Value("${naver.fallback.duckduckgo.enabled:false}")
    private boolean fallbackDuckDuckGo;
    @Value("${naver.search.fusion:none}") // none|rrf
    private String fusionPolicy;

    /** Centralised filter properties bean */
    @org.springframework.beans.factory.annotation.Autowired
    private NaverFilterProperties naverFilterProperties;

    /**
     * Initialise filter flags from {@link NaverFilterProperties}.  This method runs after
     * dependency injection and assigns the internal filtering fields to the
     * values supplied by the centralised configuration bean.  When no allowlist
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
            this.domainPolicy = naverFilterProperties.getDomainPolicy();
        }
    }
    private Set<String> productKeywords;
    private Set<String> foldKeywords;
    private Set<String> flipKeywords;
// ❌ 별칭/규칙 기반 전처리 제거: 의도/재작성은 ChatService 상단의 LLM 단계에서 끝낸다.

    /* === Patterns and stop words === */
    // ❌ 불용어/접두사/필러 제거 로직 삭제 (단순 검색 전용으로 축소)
    // [HARDENING] Require both intent keywords and geographic suffix tokens
    private static final Pattern LOCATION_PATTERN =
            Pattern.compile(
                    "(?=.*(근처|가까운|주변|길찾기|경로|지도|주소|위치|시간|얼마나\\s*걸려|가는\\s*법))" +
                    "(?=.*(시|구|동|읍|면|군|로|길|거리|역|정류장))",
                    Pattern.UNICODE_CHARACTER_CLASS | Pattern.CASE_INSENSITIVE);
    private static final Pattern NON_ALNUM =
            Pattern.compile("[^\\p{IsHangul}\\p{L}\\p{Nd}]");
    // () 캐시키/유사도 정규화에 사용할 패턴 (한글/영문/숫자만 유지)
    private static final Pattern NON_ALNUM_KO =
            Pattern.compile("[^\\p{IsHangul}\\p{L}\\p{Nd}]+");
    private static final Pattern MEDICAL_PATTERN = Pattern.compile(
            "(?i)(병원|의료진|교수|진료과|의사|전문의|센터|클리닉)");
    private static final Pattern OFFICIAL_INFO_PATTERN = Pattern.compile(
            "(?i)(병원|의료|의사|전문의|교수|대학교|대학|학과|연구실|연구소|센터|학교|공공기관|정부기관|학회|세미나|논문)");

    /** 학술·논문 검색어 감지용 */
    private static final Pattern ACADEMIC_PATTERN = Pattern.compile(
            "(?i)(논문|학술|저널|학회|conference|publication|research)");

    /* 봇/캡차 페이지 감지 패턴 */
    private static final Pattern CAPTCHA_HINT = Pattern.compile(
            "(?i)(captcha|are you (a )?robot|unusual\\s*traffic|verify you are human|duckduckgo\\.com/captcha|bots\\s*use\\s*duckduckgo)");

    /** Source tag for assistant-generated responses stored into memory. */
    private static final String ASSISTANT_SOURCE = "ASSISTANT";
    // Match version tokens such as "5.8" or "5·8".  The pattern captures two
    // numeric groups separated by either a dot or a middot character.  This
    // allows us to detect version references in Korean and English queries
    // regardless of the delimiter used.
    // 5.8 / 5·8 / 5-8 / 5 8까지 허용
    private static final Pattern VERSION_PATTERN = Pattern.compile("(\\d+)[\\s\\.·-]?(\\d+)");

    /**
     * Determine whether the supplied query appears to reference a game
     * patch version.  A game patch query should contain the game name
     * (either "원신" or "genshin"), a patch related keyword ("패치",
     * "업데이트", or "버전") and a version token matching
     * {@link #VERSION_PATTERN}.  When any of these are absent the method
     * returns false.
     *
     * @param q the user query (may be null)
     * @return true if the query looks like a Genshin Impact patch query
     */
    private static boolean isGamePatchQuery(String q) {
        if (q == null || q.isBlank()) return false;
        String s = q.toLowerCase(java.util.Locale.ROOT);
        boolean hasGame = s.contains("원신") || s.contains("genshin");
        boolean hasPatch = s.contains("패치") || s.contains("업데이트") || s.contains("버전");
        boolean hasVer = VERSION_PATTERN.matcher(s).find();
        return hasGame && hasPatch && hasVer;
    }

    // (+) 유사 쿼리로 판정할 Jaccard 임계값 (운영에서 조정 가능)
    @Value("${naver.search.similar-threshold:0.86}")
    private double similarThreshold;

    /* ───── E. 외부 클래스에서 여전히 참조하는 상수/유틸 복구 ───── */
    /** 의료 OR 공공 패턴(간단합치기) */
    public static final Pattern MEDICAL_OR_OFFICIAL_PATTERN =
            Pattern.compile(MEDICAL_PATTERN.pattern() + "|" +
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
        // When the query is blank simply return a single empty string so that downstream
        // logic can handle it gracefully.
        if (!org.springframework.util.StringUtils.hasText(query)) {
            return java.util.List.of("");
        }
        java.util.regex.Matcher m = VERSION_PATTERN.matcher(query);
        if (!m.find()) {
            // No version token present; return the query unchanged.
            return java.util.List.of(query);
        }
        // Construct a canonical version string using a dot separator.  The matcher
        // captures two numeric groups; join them with a period for quoting.
        String version = m.group(1) + "." + m.group(2);
        String quoted = "\"" + version + "\"";
        // Base queries include quoting and various patch related phrases in both
        // Korean and English.  The quoted version helps search engines match
        // the exact version token.
        java.util.List<String> base = new java.util.ArrayList<>(java.util.List.of(
                query,
                query.replace(version, quoted),
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
                "원신 " + quoted + " 업데이트 안내"
        ));
        // When the query is clearly a game patch request, append domain scoped
        // variants to prioritise official sources.  These additional queries
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
            QueryContextPreprocessor preprocessor,                 // ⭐ NEW
            /* 🔴 키 CSV를 생성자 파라미터로 주입받는다 */
            @Value("${naver.keys:}") String naverKeysCsv,
            @Value("${naver.web.cache.max-size:2000}") long maxSize,
            @Value("${naver.web.cache.ttl-sec:300}") long ttlSec,
            PlatformTransactionManager txManager,
            RateLimitPolicy ratePolicy,
            // Inject the shared Naver WebClient bean.  Qualifier ensures
            // that the correct bean is selected when multiple WebClients
            // are available.
            @Qualifier("naverWebClient") WebClient web,
            @Autowired(required = false) com.example.lms.service.redis.RedisCooldownService cooldownService) {
        // cooldownService assignment deferred below
        this.queryTransformer = queryTransformer;
        this.memorySvc = memorySvc;
        this.retrieverProvider = retrieverProvider;
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
        this.sessionIdProvider = sessionIdProvider;
        this.preprocessor = preprocessor;          // ⭐ NEW
        this.naverKeysCsv = naverKeysCsv;           // 🔴 저장
        this.relevanceScorer = new RelevanceScorer(embeddingModel);
        this.ratePolicy = ratePolicy;

        // Assign the optional cooldown service.  When null no Redis‑backed
        // cooldown is applied and requests will proceed without gating.
        this.cooldownService = cooldownService;

        /* ───────────────────────────────
         * ① 공통 HTTP 요청‑응답 로그 필터
         *    debugSearchApi=true 일 때만 TRACE/DEBUG 레벨로 출력
         * ─────────────────────────────── */
        ExchangeFilterFunction logFilter = (req, next) -> {
            if (debugSearchApi && log.isDebugEnabled()) {
                log.debug("[HTTP] → {} {}", req.method(), req.url());
                req.headers().forEach((k, v) -> log.debug("[HTTP] → {}: {}", k, v));
            }
            return next.exchange(req)
                    .doOnNext(res -> {
                        if (debugSearchApi && log.isDebugEnabled()) {
                            log.debug("[HTTP] ← {}", res.statusCode());   // 200 OK·404 NOT_FOUND 형태로 출력
                            res.headers().asHttpHeaders()
                                    .forEach((k, v) -> log.debug("[HTTP] ← {}: {}", k, v));
                        }
                    });
        };

        /* ② NAVER Open API 클라이언트 */
        // Use the provided WebClient and attach our logging filter.  We
        // mutate the builder to avoid modifying the original shared instance.
        this.web = web.mutate().filter(logFilter).build();

        /* ③ DuckDuckGo(HTML) 폴백 클라이언트  ⚠️ 미초기화로 인한 컴파일 오류 해결 */
        this.duck = WebClient.builder()
                .baseUrl("https://html.duckduckgo.com")
                .defaultHeader(HttpHeaders.USER_AGENT,
                        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
                                + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
                .defaultHeader(HttpHeaders.ACCEPT_LANGUAGE, "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7")
                .filter(logFilter)
                .build();

        this.om = new ObjectMapper();
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(Duration.ofSeconds(ttlSec))
                // ✅ 캐시 키는 "salt||canonical(query)". 로더에는 순수 query만 전달.
                .buildAsync((key, executor) -> {
                    String q = key;
                    int sep = key.indexOf("||");
                    if (sep >= 0 && sep + 2 < key.length()) {
                        q = key.substring(sep + 2);
                    }
                    return callNaverApiMono(q).toFuture();
                });

        this.recentSnippetCache = Caffeine.newBuilder()
                .maximumSize(4_096)
                .expireAfterWrite(Duration.ofMinutes(10))
                .build(k -> Boolean.TRUE);

        // Cache for (text → vector) to eliminate repeated remote embedding calls
        this.locationEmbedCache = Caffeine.newBuilder()
                .maximumSize(4_096)
                .build(key -> embeddingModel.embed(key)              // Response<Embedding>
                        .content()                  // → Embedding
                        .vector());                 // → float[]

        // Snippet 저장 시 독립 트랜잭션 사용
        this.txTemplate = new TransactionTemplate(txManager);

        // 🔴 생성자에서 바로 CSV → ApiKey 리스트 초기화
        if (!isBlank(this.naverKeysCsv)) {
            naverKeys = Arrays.stream(this.naverKeysCsv.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty() && s.contains(":"))
                    .map(s -> {
                        String[] p = s.split(":");
                        return new ApiKey(p[0], p[1]);
                    })
                    .toList();
        }
    }

    private static double domainWeight(String url) {
        if (url == null) return 0.0;
        double w = 0.0;
        if (url.contains("genshin.hoyoverse.com") || url.contains("hoyolab.com")) w += 2.0;
        if (url.contains("hoyoverse.com")) w += 1.5;
        if (url.contains("wikipedia.org") || url.endsWith(".go.kr") || url.endsWith(".ac.kr")) w += 1.0;
        return w;
    }
    private static @Nullable String extractHref(String line) {
        int i = (line == null) ? -1 : line.indexOf("href=\"");
        if (i < 0) return null;
        int s = i + 6, e = line.indexOf('"', s);
        return (e > s) ? line.substring(s, e) : null;
    }


    /* ---------- 4. 키 순환 유틸 ---------- */
    private @Nullable ApiKey nextKey() {
        if (naverKeys.isEmpty()) return null;
        long idx = keyCursor.getAndUpdate(i -> (i + 1) % naverKeys.size());
        return naverKeys.get((int) idx);
    }

    /* === Public API === */

    /** Search using the default topK (LLM 힌트 미사용). */
    /*───────────────────────────────────────────────
     * 1) ──  Reactive(Mono) 이름 → *Mono 로 변경  ──
     *──────────────────────────────────────────────*/

    /** Mono 버전(기존 구현) – 새 코드에서만 호출 */
    public Mono<List<String>> searchSnippetsMono(String query) {
        return searchSnippetsInternal(query, webTopK, null, null);
    }

    // ──────────────────────────────────────────────
    //  Sync Facade (기존 호출부 호환용 · 임시 block)
    // ──────────────────────────────────────────────

    /** (임시) 동기 호출을 원하는 곳에서 사용 – block ≤ 5 초 */
    public List<String> searchSnippetsSync(String query, int topK) {
        return searchSnippetsMono(query, topK)
                .blockOptional(Duration.ofSeconds(5))
                .orElseGet(List::of);
    }

    /** 기본 top-K(webTopK) 동기 검색 */
    public List<String> searchSnippetsSync(String query) {
        return searchSnippetsSync(query, webTopK);
    }

    /** Trace 결과를 동기로 돌려주는 Facade */
    public SearchResult searchWithTraceSync(String query, int topK) {
        return searchWithTraceMono(query, topK).block(Duration.ofSeconds(5));
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
        long t0 = System.nanoTime();
        return searchSnippetsInternal(query, topK, trace, null)
                .map(snippets -> {
                    trace.totalMs = (System.nanoTime() - t0) / 1_000_000L;
                    if (!hasCreds()) {
                        trace.steps.add(new SearchStep("키 미설정으로 호출 생략", 0, 0, 0));
                    }
                    return new SearchResult(snippets, trace);
                });
    }

    /*───────────────────────────────────────────────
     * 2) ──  Sync Facade —  “옛 API” 유지  ────────────
     *──────────────────────────────────────────────*/

    /** default top-K 동기 검색(List) */
    public List<String> searchSnippets(String query) {
        return searchSnippetsSync(query, webTopK);
    }

    /** top-K 지정 동기 검색(List) */
    public List<String> searchSnippets(String query, int topK) {
        return searchSnippetsSync(query, topK);
    }

    /** (질문·답변 동시 전달) 동기 검색 */
    public List<String> searchSnippets(String userPrompt,
                                       String assistantAnswer,
                                       int topK) {
        return searchSnippetsMono(userPrompt, assistantAnswer, topK)
                .blockOptional(Duration.ofSeconds(5))
                .orElseGet(List::of);
    }

    /** Trace 포함 동기 버전 */
    public SearchResult searchWithTrace(String query, int topK) {
        return searchWithTraceMono(query, topK).block(Duration.ofSeconds(5));
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
        // ✔ 의도/별칭 정규화는 상위 LLM 단계에서 처리됨 (여긴 입력 그대로 사용)
        if (trace != null) {
            trace.domainFilterEnabled = enableDomainFilter;
            trace.reasonDomainFilterDisabled = enableDomainFilter ? null : "PROPERTY_FALSE";
            trace.keywordFilterEnabled = enableKeywordFilter;
            trace.reasonKeywordFilterDisabled = enableKeywordFilter ? null : "PROPERTY_FALSE";
            trace.suffixApplied = deriveLocationSuffix(query);
        }


        // ① Guardrail 전처리 적용 ------------------------------------------------
        if (preprocessor != null) {
            query = preprocessor.enrich(query);
        }

        // assistantAnswer(딥-리서치) 브랜치 – QueryTransformer + 키워드 힌트 통합
        if (assistantAnswer != null && !assistantAnswer.isBlank()) {
            // 기본 변형 쿼리 생성
            List<String> qs = (queryTransformer != null)
                    ? queryTransformer.transformEnhanced(query, assistantAnswer)
                    : List.of(query);

            // 두 번째 소스의 키워드 힌트 추출(간단 2‑pass)
            String hint = extractTopKeywords(assistantAnswer, 4);
            if (StringUtils.hasText(hint)) {
                String base = normalizeQuery(query);
                List<String> tmp = new ArrayList<>(qs);
                tmp.add(base + " " + hint);
                qs = tmp;
            }

            qs = (qs == null) ? List.of() : qs;
            qs = qs.stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .distinct()
                    .toList();

            // 유사 변형 쿼리 제거 후 상한 적용
            int limitQs = Math.min(MAX_QUERIES_PER_SEARCH, ratePolicy.allowedExpansions());
            qs = Q.filterSimilarQueries(qs, similarThreshold)
                    .stream()
                    .limit(limitQs)
                    .toList();

            LinkedHashSet<String> acc2 = new LinkedHashSet<>();
            final String queryCopy1 = query;  // for reinforcement capture

            // ▶ 순차 실행: 가장 가능성 높은 쿼리부터 하나씩 시도하고,
            //    누적 스니펫이 topK에 도달하는 즉시 상류 취소(early exit)
                return Flux.fromIterable(qs)
                        .flatMap(q -> Mono.fromFuture(cache.get(cacheKeyFor(q)))
                                .subscribeOn(Schedulers.boundedElastic()), 3)
                        .flatMapIterable(list -> list)   // 각 쿼리 결과를 줄 단위로
                        .filter(acc2::add)               // 중복 제거(LinkedHashSet)
                        .onBackpressureBuffer()
                        .take(topK)                      // ★ topK 채워지면 즉시 종료
                    .collect(Collectors.toCollection(LinkedHashSet::new))
                    .<List<String>>map(set -> new ArrayList<>(set))   // ✔ 제네릭 교정
                    .doOnNext(snips -> {
                        Long sid = sessionIdProvider.get();
                        if (sid != null) reinforceSnippets(sid, queryCopy1, snips);
                        if (snips.isEmpty()) {
                            log.debug("[Search] 결과 스니펫 0개 (assistantAnswer-branch)");
                        }
                    });
        }

        if (isBlank(query)) {
            return Mono.just(Collections.emptyList());
        }

        // (assistantAnswer 브랜치 바로 밑, prevFilter 선언 **앞**)
        // 두 번째 소스의 normalizeQuery + 기존 선언형 정리(normalizeDeclaratives) 결합
        String cleaned = normalizeQuery(query == null ? "" : query.trim());
        String normalized = normalizeDeclaratives(cleaned);

        /* 0) 신학·학술 키워드는 도메인‑필터를 잠시 끈다 --------------------- */
        boolean prevFilter = enableDomainFilter;               // 원래 상태 저장
        boolean academic = isAcademicQuery(normalized);
        if (academic) enableDomainFilter = false;              // OFF

        // 기본 확장 쿼리로 초기화
        List<String> expandedQueries = expandQueries(normalized);

        // QueryTransformer가 존재할 경우 시도하되 예외 발생 시 fallback
        if (queryTransformer != null) {
            try {
                // 대화 맥락을 함께 넘겨 정확도 향상
                List<String> candidateQueries = queryTransformer.transform(getConversationContext(), normalized);
                if (candidateQueries != null && !candidateQueries.isEmpty()) {
                    expandedQueries = candidateQueries;
                }
            } catch (Exception e) {
                // LLM 호출 실패 또는 인터럽트 발생 시 경고 로그 후 기본 확장으로 대체
                log.warn("QueryTransformer failed for query '{}': {}", query, e.toString());
            }
        }

        // ❌ LLM 교정 메타 접두사 필터 제거

        // () LLM 교정 메타로 시작하는 쿼리는 호출 생략
        // (제거) LLM 교정 메타 접두사 필터는 상위 단계에서 처리
        expandedQueries = expandedQueries.stream().toList();

        // () 유사 변형 제거
        int limitQs2 = Math.min(MAX_QUERIES_PER_SEARCH, ratePolicy.allowedExpansions());
        expandedQueries = Q.filterSimilarQueries(expandedQueries, similarThreshold)
                .stream()
                .limit(limitQs2)
                .toList();

        /* ② (개선) 키워드 동의어 확장 — “모두 붙이기” 금지, 별도 변형  구문 고정 */
        // ❌ 동의어 확장 제거 (확장은 상위 LLM 단계가 책임)

        /* ③ 도메인-스코프 프리픽스 완전 제거 (검색 편향 FIX) */
        expandedQueries = expandedQueries.stream()
                .filter(q -> !DOMAIN_SCOPE_PREFIX.matcher(q).find())
                .filter(q -> !q.toLowerCase(Locale.ROOT).startsWith("site "))
                .toList();

        /* 🔽 모든 변형이 제거된 경우 – 원본 쿼리로 대체해 검색 공백 방지 */
        if (expandedQueries.isEmpty()) {
            expandedQueries = List.of(normalized);
        }

        /* ② 중복 차단 & early-exit */
        LinkedHashSet<String> acc = new LinkedHashSet<>();
        // ▶ 순차 실행  조기 종료 (일반 검색 브랜치)
            Flux<String> snippetFlux =
                    Flux.fromIterable(expandedQueries)
                            .flatMap(q -> Mono.fromFuture(cache.get(cacheKeyFor(q)))
                                    .subscribeOn(Schedulers.boundedElastic()), 3)
                            .flatMapIterable(list -> list)
                            .filter(acc::add)   // 중복 제거(LinkedHashSet)
                            .onBackpressureBuffer()
                            .take(topK);        // ★ topK 확보 시 즉시 종료

        // ▶ 전체 검색 파이프라인 타임아웃(동적 계산, 상한 4.5s) + 폴백
            long perCallMs = Math.min(1200L, Math.max(500L, hedgeTimeoutMs)); // per-call 상한 1.2s
        int n = Math.max(1, expandedQueries.size());
        // 순차 실행이므로 waves = 쿼리 개수 (상한 4.5s)
        int waves = Math.max(1, n);
            long overallMs = Math.min(3000L, perCallMs * waves + 300L); // 상한 3.0s, headroom 0.3s

        final String queryCopy2 = query;            // capture for reinforcement


        if ("rrf".equalsIgnoreCase(fusionPolicy) && isGamePatchQuery(normalized)) {
            Map<String,Double> rrf = new java.util.HashMap<>();
                return Flux.fromIterable(expandedQueries)
                        .flatMap(q -> Mono.fromFuture(cache.get(cacheKeyFor(q))).subscribeOn(Schedulers.boundedElastic()), 3)
                    .doOnNext(list -> {
                        int rank = 0;
                        for (String s : list) {
                            double add = 1.0 / (60.0 + (++rank)); // k=60
                            rrf.merge(s, add, Double::sum);
                        }
                    })
                    .then(Mono.fromSupplier(() -> rrf.entrySet().stream()
                            .sorted((a,b) -> Double.compare(b.getValue(), a.getValue()))
                            .map(Map.Entry::getKey)
                            .limit(topK)
                            .toList()))
                    .timeout(Duration.ofMillis(overallMs), Mono.just(List.of()))
                    .onErrorReturn(Collections.emptyList())
                    .doFinally(sig -> enableDomainFilter = prevFilter)
                    .doOnNext(list -> {
                        Long sid = sessionIdProvider.get();
                        if (sid != null) reinforceSnippets(sid, queryCopy2, list);
                    });
        }
        return snippetFlux
                .collectList()
                .timeout(Duration.ofMillis(overallMs), Mono.just(List.of()))
                .onErrorReturn(Collections.emptyList())
                .doFinally(sig -> enableDomainFilter = prevFilter)
                .doOnNext(list -> {
                    Long sid = sessionIdProvider.get();
                    if (sid != null) reinforceSnippets(sid, queryCopy2, list);
                });
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
                dev.langchain4j.rag.query.Query qObj =
                        dev.langchain4j.rag.query.Query.builder()
                                .text(query)
                                .metadata(dev.langchain4j.data.document.Metadata.from(
                                        java.util.Map.of(
                                                com.example.lms.service.rag.LangChainRAGService.META_SID,
                                                sid
                                        )))
                                .build();
                localCtx = localRetriever.retrieve(qObj)
                        .stream()
                        .filter(c -> {
                            Map<?, ?> md = c.metadata();
                            return md != null && sid.equals(String.valueOf(md.get(com.example.lms.service.rag.LangChainRAGService.META_SID)));
                        })
                        .map(Content::toString)
                        .toList();
            } catch (Exception e) {
                log.warn("Local RAG retrieval failed", e);
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
                // ignore failures when loading memory
            }
            // external RAG
            ContentRetriever ext = retrieverProvider.getIfAvailable();
            if (ext != null) {
                // [HARDENING] build query with session metadata for external retriever
                dev.langchain4j.rag.query.Query qExt =
                        dev.langchain4j.rag.query.Query.builder()
                                .text(query)
                                .metadata(dev.langchain4j.data.document.Metadata.from(
                                        java.util.Map.of(
                                                com.example.lms.service.rag.LangChainRAGService.META_SID,
                                                sid
                                        )))
                                .build();
                remoteCtx = ext.retrieve(qExt).stream()
                        .limit(ragTopK)
                        .filter(c -> {
                            Map<?, ?> md = c.metadata();
                            return md != null && sid.equals(String.valueOf(md.get(com.example.lms.service.rag.LangChainRAGService.META_SID)));
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
     * site restriction is applied—only the query (plus optional suffix)
     * is used.  Domain allow/deny and keyword filtering are enforced.
     */
    private Mono<List<String>> callNaverApiMono(String query) {
        //  키가 아예 없을 때도 DuckDuckGo로 폴백
        if (isBlank(query)) return Mono.just(Collections.emptyList());
        if (!hasCreds()) {
            log.warn("No NAVER creds → fallback");
            // Only invoke DuckDuckGo fallback when enabled.  Otherwise
            // return an empty result so that other providers may run.
            if (fallbackDuckDuckGo) {
                return callDuckDuckGoMono(appendLocationSuffix(query));
            }
            return Mono.just(Collections.emptyList());
        }

        String apiQuery = appendLocationSuffix(query);

        // Apply a short cooldown lock before invoking the external API.  When a lock
        // cannot be acquired the service skips the Naver call and falls back to
        // DuckDuckGo (when enabled) or returns an empty list.  The key is
        // derived from the query to avoid locking unrelated requests.  A TTL
        // of one second prevents thundering herd retries while allowing
        // subsequent calls to proceed quickly after the lock expires.
        if (cooldownService != null) {
            try {
                String lockKey = "naver:api:" + org.apache.commons.codec.digest.DigestUtils.md5Hex(apiQuery);
                boolean acquired = cooldownService.setNxEx(lockKey, "1", 1);
                if (!acquired) {
                    log.debug("[Naver API] cooldown active for {} → skipping API call", apiQuery);
                    if (fallbackDuckDuckGo) {
                        return callDuckDuckGoMono(apiQuery);
                    }
                    return Mono.just(Collections.emptyList());
                }
            } catch (Exception ignore) {
                // On any error acquiring the lock proceed without gating.
            }
        }

        /* topK보다 적게 받아와 결과가 부족해지는 문제 → {스터프2} 전략 반영 */
        int fetch = Math.min(100, Math.max(display, webTopK));
        boolean byDate = looksFresh(query) || isGamePatchQuery(query);
        String uri = UriComponentsBuilder.fromPath("/v1/search/webkr.json")
                .queryParam("query", apiQuery)
                .queryParam("display", fetch)
                .queryParam("start", 1)
                .queryParam("sort", byDate ? "date" : "sim") // 단일 지정
                .build(false)
                .toUriString();
        ApiKey first = nextKey();
        if (first == null) return Mono.just(List.of());

        String keyLabel1 = first.id().length() > 4 ? first.id().substring(first.id().length() - 4) : first.id();
        Mono<ResponseEntity<String>> primary = web.get()
                .uri(uri)
                .header("X-Naver-Client-Id", first.id())
                .header("X-Naver-Client-Secret", first.secret())
                .header("X-Key-Label", "K-" + keyLabel1)
                .retrieve()
                .toEntity(String.class)
                // 구독 지연(레이트리밋/Retry-After 반영)
                    .delaySubscription(Duration.ofMillis(Math.max(0, Math.min(200, ratePolicy.currentDelayMs()))))
                // 일관된 타임아웃
                .timeout(Duration.ofMillis(apiTimeoutMs));


            /* 🔵 단일 키 모드 – 실패 시 DuckDuckGo 폴백 및 헤징 지원 */

            // Parse and retry logic for the primary call.  Errors are mapped to an empty list
            // so that the hedging logic below can determine when to trigger fallbacks.
            Mono<List<String>> primaryParsed = primary
                    .map(entity -> {
                        try { ratePolicy.updateFromHeaders(entity.getHeaders()); } catch (Exception ignore) {}
                        String json = entity.getBody();
                        if (debugJson && json != null) {
                            log.debug("[Naver RAW] {} chars: {}", json.length(), safeTrunc(json, 4000));
                        }
                        return parseNaverResponse(query, json);
                    })
                    // Retry up to two additional times on 5xx errors before giving up
                    .retryWhen(Retry.backoff(2, Duration.ofMillis(200))
                            .filter(ex -> ex instanceof WebClientResponseException w && w.getStatusCode().is5xxServerError()))
                    .onErrorResume(WebClientResponseException.class, e -> {
                        // On error return empty list; fallback is handled below
                        log.warn("Naver API {} failed: {}", e.getStatusCode().value(), e.toString());
                        if (!hedgeEnabled && fallbackDuckDuckGo) {
                            return callDuckDuckGoMono(apiQuery);
                        }
                        return Mono.just(Collections.emptyList());
                    })
                    .onErrorResume(t -> {
                        log.warn("Naver API '{}' failed: {}", query, t.toString());
                        if (!hedgeEnabled && fallbackDuckDuckGo) {
                            return callDuckDuckGoMono(apiQuery);
                        }
                        return Mono.just(Collections.emptyList());
                    })
                    .flatMap(list -> {
                        // When hedging is disabled, invoke the fallback only if the primary returned no results
                        if (!hedgeEnabled && fallbackDuckDuckGo && list.isEmpty()) {
                            return callDuckDuckGoMono(apiQuery);
                        }
                        return Mono.just(list);
                    })
                    .onErrorReturn(Collections.emptyList());

            if (hedgeEnabled && fallbackDuckDuckGo) {
                // Race the primary against a delayed fallback.  The first completed signal wins.
                return Mono.firstWithSignal(
                        primaryParsed,
                        callDuckDuckGoMono(apiQuery)
                                .delaySubscription(Duration.ofMillis(Math.max(0L, Math.min(1000L, hedgeDelayMs))))
                );
            } else {
                return primaryParsed;
            }

    }
    private static boolean looksFresh(String q) {
        if (q == null) return false;
        String s = q.toLowerCase(java.util.Locale.ROOT);
        return s.matches(".*(최신|방금|오늘|금일|recent|today|now).*");
    }
    /**
     * JSON → 스니펫 파싱  (선택) 키워드 필터링.
     * - 두 번째 소스의 정규화/HTML 제거 로직을 반영(단, 출력 포맷은 기존 앵커 형식 유지)
     *
     * @param query 원본 검색어 (키워드 필터에 사용)
     * @param json  Naver API 응답 JSON 문자열
     */
    private List<String> parseNaverResponse(String query, String json) {
        if (isBlank(json)) return Collections.emptyList();
        try {
            // 1) items 존재 및 크기(원시) 확인
            JsonNode root = om.readTree(json);
            JsonNode itemsNode = root.path("items");
            int rawSize = itemsNode.isArray() ? itemsNode.size() : -1;

            // 2) DTO 역직렬화
            NaverResponse resp = om.readValue(json, NaverResponse.class);
            List<NaverItem> items = (resp.items() == null) ? Collections.emptyList() : resp.items();

            if (items.isEmpty()) {
                if (debugJson) {
                    log.debug("[Naver Parse] items empty (rawSize={}) → 원문 일부: {}", rawSize, safeTrunc(json, 800));
                }
                return Collections.emptyList();
            }

            // 3) 스니펫 변환 + 도메인 필터/블록
            // Determine whether to apply the domain filter for this query.  The filter
            // is enabled when the global toggle is on and the query falls into one
            // of the special categories: medical, official info, academic or
            // game patch.  For game patch queries, the allowlist is switched to
            // official HoYoverse domains to prioritise authoritative results.
            boolean gamePatch = isGamePatchQuery(query);
            boolean applyFilter = "filter".equalsIgnoreCase(domainPolicy) && enableDomainFilter;
            String dynamicAllow = gamePatch && "filter".equalsIgnoreCase(domainPolicy)
                    ? "genshin.hoyoverse.com,hoyoverse.com,hoyolab.com"
                    : allowlist;
            List<String> lines = items.stream()
                    .filter(item -> !applyFilter || isAllowedDomainWith(item.link(), dynamicAllow))
                    .filter(item -> !isBlockedDomain(item.link()))
                    .map(item -> "- <a href=\"%s\" target=\"_blank\" rel=\"noopener\">%s</a>: %s"
                            .formatted(item.link(),
                                    stripHtml(item.title()),
                                    stripHtml(item.description())))
                    .distinct()
                    .toList();
            // ★ 부스트 정렬(필터 대신): gamePatch 또는 boost 정책일 때
            if (gamePatch || "boost".equalsIgnoreCase(domainPolicy)) {
                lines = lines.stream()
                        .sorted((a,b) -> Double.compare(
                                domainWeight(extractHref(b)), domainWeight(extractHref(a))))
                        .toList();
            }
            //  (개선) 제품/개념 중의성 오염 제거 (예: K8Plus ↔ 자동차)
            lines = applyDisambiguationFilters(query, lines);
            // ★ 버전 강제: 게임 패치 질의면 5.8 등 버전 토큰 포함된 라인만
            if (isGamePatchQuery(query)) {
                String v = extractVersionToken(query);
                if (v != null) {
                    Pattern must = versionMustRegex(v);
                    lines = lines.stream().filter(sn -> must.matcher(sn).find()).toList();
                }
            }

            // 4) (선택) 키워드 OR 필터
            if (enableKeywordFilter && !lines.isEmpty()) {
                List<String> kws = keywords(query);
                int requiredHits = Math.max(1, Math.min(keywordMinHits, (kws.size() + 1) / 3));
                List<String> filtered = lines.stream()
                        .filter(sn -> hitCount(sn, kws) >= requiredHits)
                        .toList();
                if (!filtered.isEmpty()) {
                    return filtered;
                }
            }

            if (lines.isEmpty() && debugJson) {
                log.debug("[Naver Parse] 파싱 이후 스니펫 0개 (rawSize={})", rawSize);
            }
            return lines;
        } catch (Exception e) {
            log.error("[Naver Parse] JSON 파싱 실패: {}", e.toString(), e);
            if (debugJson) {
                log.debug("[Naver Parse] 원본 일부: {}", safeTrunc(json, 1200));
            }
            return Collections.emptyList();
        }
    }


    //
    //  DuckDuckGo(HTML) Fallback(비동기)
    //
    private Mono<List<String>> callDuckDuckGoMono(String rawQuery) {
        if (isBlank(rawQuery)) return Mono.just(Collections.emptyList());
        String q = URLEncoder.encode(rawQuery, StandardCharsets.UTF_8);
        String uri = "/html/?q=" + q + "&kl=kr-ko&kp=-1"; // 한국/세이프서치 off
        return duck.get()
                .uri(uri)
                .retrieve()
                .bodyToMono(String.class)
                    .timeout(Duration.ofMillis(1200))
                .map(html -> {
                    if (isCaptchaHtml(html)) {
                        log.warn("DuckDuckGo CAPTCHA/봇 차단 감지 → 빈 결과 반환");
                        return List.<String>of();
                    }
                    return parseDuckDuckGoHtml(rawQuery, html);
                })
                .onErrorResume(e -> {
                    log.warn("DuckDuckGo fallback failed: {}", e.toString());
                    return Mono.just(Collections.emptyList());
                });
    }

    // ▼ 이 메서드 전체를 교체하세요
    private List<String> callNaverApi(String query, SearchTrace trace) {
        final boolean prevFilter = this.enableDomainFilter; // ← 원복용 캡처
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
                ? Math.max(1, trace.steps.get(0).afterFilter)  // trace 모드면 직전 topK
                : webTopK;

        boolean byDate = looksFresh(query) || isGamePatchQuery(query);
        String uri = UriComponentsBuilder.fromPath("/v1/search/webkr.json")
                .queryParam("query", apiQuery)
                .queryParam("display", Math.max(topK, Math.min(display, 100)))
                .queryParam("start", 1)
                .queryParam("sort", byDate ? "date" : "sim")
                .build(false)
                .toUriString();

        try {
            REQUEST_SEMAPHORE.acquire();   // 동시에 2개까지만 호출
            String json = null;
            ApiKey key = nextKey();
            if (key == null) return Collections.emptyList();
            String id = key.id();
            String secret = key.secret();

            try {
                json = web.get()
                        .uri(uri)
                        .header("X-Naver-Client-Id", id)
                        .header("X-Naver-Client-Secret", secret)
                        .header("X-Key-Label", "K-" + (id.length() > 4 ? id.substring(id.length() - 4) : id))
                        .retrieve()
                        .bodyToMono(String.class)
                        .block(Duration.ofSeconds(10));
            } catch (WebClientResponseException e) {
                if (e.getStatusCode().value() == 429) {
                    log.warn("Naver API 429 – fallback");
                    // Only fallback to DuckDuckGo when enabled; otherwise return empty
                    if (fallbackDuckDuckGo) {
                        return callDuckDuckGoSync(query, trace); // 시그니처 고정(String, SearchTrace)
                    }
                    return java.util.Collections.emptyList();
                } else {
                    throw e;
                }
            }

            if (json == null || isBlank(json)) {
                return Collections.emptyList();
            }

            NaverResponse resp = om.readValue(json, NaverResponse.class);
            if (resp.items() == null) {
                return Collections.emptyList();
            }

            // Convert items to lines and apply domain filters (하드 필터).
            // 게임 패치 질의면 allowlist만 공식 도메인으로 스위칭.
            boolean gamePatch = isGamePatchQuery(query);
            boolean applyFilter = "filter".equalsIgnoreCase(domainPolicy) && enableDomainFilter;
            String dynamicAllow = gamePatch
                    ? "genshin.hoyoverse.com,hoyoverse.com,hoyolab.com"
                    : allowlist;

            List<String> lines = resp.items().stream()
                    .filter(item -> !applyFilter || isAllowedDomainWith(item.link(), dynamicAllow))
                    .filter(item -> !isBlockedDomain(item.link()))
                    .map(item -> {
                        String title = stripHtml(item.title());
                        String desc = stripHtml(item.description());
                        String link = item.link();
                        return String.format(
                                "- <a href=\"%s\" target=\"_blank\" rel=\"noopener\">%s</a>: %s",
                                link, title, desc
                        ).trim();
                    })
                    .filter(s -> !s.isEmpty())
                    .distinct()
                    .toList();

            // 키워드 필터 (선택)
            if (enableKeywordFilter && !lines.isEmpty()) {
                List<String> kws = keywords(query);
                int requiredHits = Math.max(1, Math.min(keywordMinHits, (kws.size() + 1) / 3));
                List<String> filtered = lines.stream()
                        .filter(sn -> hitCount(sn, kws) >= requiredHits)
                        .toList();
                if (!filtered.isEmpty()) {
                    lines = filtered;
                }
            }

            log.info("Naver API '{}' → {} lines in {}ms",
                    query,
                    lines.size(),
                    Duration.between(start, Instant.now()).toMillis());

            // 추적 기록
            if (trace != null) {
                int afterFilter = lines.size();
                int returned = Math.min(100, (resp.items() != null ? resp.items().size() : 0));
                long took = Duration.between(start, Instant.now()).toMillis();
                trace.steps.add(new SearchStep(query, returned, afterFilter, took));
            }

            return lines;

            } catch (Exception ex) {
            log.error("Naver API call failed", ex);
            // 동기 경로에서도 DDG 폴백, unless disabled
            if (fallbackDuckDuckGo) {
                return callDuckDuckGoSync(query, trace);
            }
            return java.util.Collections.emptyList();
        } finally {
            // trace-mode sync 호출이므로 항상 세마포어 반환
            this.enableDomainFilter = prevFilter; // 원복
            REQUEST_SEMAPHORE.release();
        }
    } // --- callNaverApi(String, SearchTrace) 끝 ---

    //
    //  DuckDuckGo(HTML) 파싱
    //
    private List<String> parseDuckDuckGoHtml(String originalQuery, String html) {
        if (isBlank(html)) return Collections.emptyList();
        /* CAPTCHA/Anti-bot 페이지 즉시 폐기 */
        if (CAPTCHA_HINT.matcher(html).find()) {
            log.warn("DuckDuckGo returned CAPTCHA/anti-bot page; dropping.");
            return Collections.emptyList();
        }
        try {
            // 빠른 차단: CAPTCHA/봇 페이지라면 즉시 빈 결과
            if (isCaptchaHtml(html)) return Collections.emptyList();
            Document doc = Jsoup.parse(html);
            // 마크업이 종종 바뀌므로 넉넉한 셀렉터
            Elements results = doc.select("div.result, div.results_links, div.result__body, div.web-result");
            List<String> lines = new ArrayList<>();
            for (Element r : results) {
                Element a = r.selectFirst("a.result__a, a.result__url, a[href]");
                if (a == null) continue;
                String link = a.attr("href");
                if (isBlank(link)) continue;
                // '/l/?uddg=' 형태 리디렉션 정리
                if (link.startsWith("/l/?")) {
                    try {
                        URI u = URI.create("https://duckduckgo.com" + link);
                        String q = u.getQuery();
                        if (q != null) {
                            for (String kv : q.split("&")) {
                                String[] p = kv.split("=", 2);
                                if (p.length == 2 && "uddg".equals(p[0])) {
                                    link = URLDecoder.decode(p[1], StandardCharsets.UTF_8);
                                    break;
                                }

                            }

                        }
                    } catch (Exception ignore) {
                    }
                }
                String title = r.select("a.result__a").text();
                if (isBlank(title)) title = a.text();
                String snippet = r.select("div.result__snippet, a.result__snippet, .snippet").text();
                // filter 정책일 때는 허용 도메인 외 차단(하드 필터)
                if ("filter".equalsIgnoreCase(domainPolicy) && enableDomainFilter && !isAllowedDomain(link)) continue;
                if (isBlockedDomain(link)) continue;
                String line = "- <a href=\"%s\" target=\"_blank\" rel=\"noopener\">%s</a>: %s"
                        .formatted(link, stripHtml(title), stripHtml(snippet));
                if (!isBlank(line)) lines.add(line);
            }
            lines = lines.stream().distinct().toList();
            if ("boost".equalsIgnoreCase(domainPolicy) || isGamePatchQuery(originalQuery)) {
                lines = lines.stream()
                        .sorted((a,b) -> Double.compare(
                                domainWeight(extractHref(b)), domainWeight(extractHref(a))))
                        .toList();
            }
            // ★ 버전 강제
            if (isGamePatchQuery(originalQuery)) {
                String v = extractVersionToken(originalQuery);
                if (v != null) {
                    Pattern must = versionMustRegex(v);
                    lines = lines.stream().filter(sn -> must.matcher(sn).find()).toList();
                }
            }
            //  (개선) 제품/개념 중의성 오염 제거 (예: K8Plus ↔ 자동차)
            lines = applyDisambiguationFilters(originalQuery, lines);
            if (enableKeywordFilter && !lines.isEmpty()) {
                List<String> kws = keywords(originalQuery);
                int requiredHits = Math.max(1, Math.min(keywordMinHits, (kws.size() + 1) / 3));
                List<String> filtered = lines.stream()
                        .filter(sn -> hitCount(sn, kws) >= requiredHits)
                        .toList();
                if (!filtered.isEmpty()) return filtered;
            }
            return lines;
        } catch (Exception e) {
            log.error("DuckDuckGo HTML parse error", e);
            return Collections.emptyList();
        }
    }  /** DuckDuckGo 등에서 반환되는 CAPTCHA/봇 차단 페이지 감지 */
    private boolean isCaptchaHtml(String html) {
        if (html == null) return false;
        String s = html.toLowerCase(Locale.ROOT);
        return s.contains("captcha")
                || s.contains("are you a robot")
                || s.contains("verify you are human")
                || s.contains("bot detection")
                || s.contains("unusual traffic")
                || s.contains("enable javascript");
    }

    //  DuckDuckGo(HTML) 동기 폴백 (trace 지원)
    private List<String> callDuckDuckGoSync(String rawQuery, @Nullable SearchTrace trace) {
        Instant t0 = Instant.now();
        try {
            String q = URLEncoder.encode(appendLocationSuffix(rawQuery), StandardCharsets.UTF_8);
            String uri = "/html/?q=" + q + "&kl=kr-ko&kp=-1";
            String html = duck.get().uri(uri).retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(3));
            if (isCaptchaHtml(html)) {
                if (trace != null) {
                    trace.steps.add(new SearchStep("Fallback: DuckDuckGo CAPTCHA detected", 0, 0,
                            Duration.between(t0, Instant.now()).toMillis()));
                }
                return Collections.emptyList();
            }
            List<String> lines = parseDuckDuckGoHtml(rawQuery, html);
            if (trace != null) {
                long took = Duration.between(t0, Instant.now()).toMillis();
                trace.steps.add(new SearchStep("Fallback: DuckDuckGo(HTML)", lines.size(), lines.size(), took));
            }
            return lines == null ? Collections.emptyList() : lines;
        } catch (Exception e) {
            log.warn("DuckDuckGo sync fallback failed: {}", e.toString());
            if (trace != null) {
                trace.steps.add(new SearchStep("Fallback: DuckDuckGo(HTML) failed", 0, 0,
                        Duration.between(t0, Instant.now()).toMillis()));
            }
            return Collections.emptyList();
        }
    }

    // (-) Bing/Google 폴백 제거, DDG로 통일

    /* ================== NEW: 보수적 동의어 확장 & 중의성 필터 ================== */
    /**
     * 보수적 동의어 확장:
     *  - 기존처럼 "모든 동의어를 한 쿼리에 합쳐 붙임" 금지
     *  - 각 동의어는 별도 변형 쿼리로만 추가
     *  - 공백/한글/'' 포함 시 따옴표로 감싸 구문 고정(phrase search 유도)
     */
    // ❌ 동의어 확장 메서드/관련 필드 전체 삭제

    /** 결과 라인에 대해 중의성(예: K8 자동차) 오염을 제거한다. */
    private List<String> applyDisambiguationFilters(String originalQuery, List<String> lines) {
        var profile = SearchDisambiguation.resolve(originalQuery);
        if (profile.negativeKeywords().isEmpty() && profile.blockedHosts().isEmpty()) return lines;
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            // 1) 텍스트 상의 부정 키워드(hitCount 재활용)
            if (hitCount(line, new ArrayList<>(profile.negativeKeywords())) > 0) continue;
            // 2) 호스트 기반 차단
            boolean block = false;
            try {
                int i = line.indexOf("href=\"");
                if (i >= 0) {
                    int j = line.indexOf("\"", i+  6);
                    String url = j > 0 ? line.substring(i  + 6, j) : "";
                    String host = URI.create(url).getHost();
                    if (host != null) {
                        for (String b : profile.blockedHosts()) {
                            if (host.contains(b)) { block = true; break; }
                        }
                    }
                }
            } catch (Exception ignore) {}
            if (!block) out.add(line);
        }
        return out;
    }

    /** 429 상태 코드 감지 – 재시도 필터 */
    private boolean isTooManyRequests(Throwable t) {
        return t instanceof WebClientResponseException
                && ((WebClientResponseException) t).getStatusCode().value() == 429;
    }

    /** Single-shot fallback (no extra modifications). */
    private List<String> searchOnce(String q) throws IOException {
        return List.of(); // Simplified: unused
    }

    /* === Helper functions === */

    private boolean hasCreds() {
        boolean ok = naverKeys != null && !naverKeys.isEmpty()
                && naverKeys.stream().anyMatch(k -> !isBlank(k.id()) && !isBlank(k.secret()));
        if (!ok) {
            log.warn("[NaverSearch] no API keys loaded. property 'naver.keys'='{}'", naverKeysCsv);
        }
        return ok;
    }

    private String deriveLocationSuffix(String q) {
        // [HARDENING] Simplify: if suffix not configured, return null.
        if (isBlank(querySuffix)) {
            return null;
        }
        // Use intent detector if available to determine if query is location‑intent
        if (locationIntentDetector != null) {
            try {
                var intent = locationIntentDetector.detect(q);
                // Only attach suffix when intent is not NONE
                if (intent == com.example.lms.location.intent.LocationIntent.NONE) {
                    return null;
                }
            } catch (Exception ignore) {
                // on failure, fall through
            }
        }
        return querySuffix;
    }

    /** Dynamically tighten/loosen similarity threshold. */
    private double adaptiveThreshold(String q) {
        int len = (q == null) ? 0
                : NON_ALNUM.matcher(q).replaceAll("").length();
        if (len <= 6)  return Math.min(0.8, querySimThreshold + 0.2);
        if (len >= 30) return Math.max(0.2, querySimThreshold - 0.1);
        return querySimThreshold;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private boolean isAllowedDomain(String url) {
        if (isBlank(url)) return true;
        if (!enableDomainFilter || isBlank(allowlist)) return true;
        String host;
        try {
            host = URI.create(url).getHost();
        } catch (Exception ignore) {
            return true;
        }
        if (host == null) return true;
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
     * @param url the full URL (may be null or blank)
     * @param allowCsv a comma separated list of domain suffixes
     * @return true if the host ends with any allowed suffix
     */
    private boolean isAllowedDomainWith(String url, String allowCsv) {
        if (isBlank(url)) return true;
        if (isBlank(allowCsv)) return true;
        String host;
        try {
            host = java.net.URI.create(url).getHost();
        } catch (Exception ignore) {
            return true;
        }
        if (host == null) return true;
        return java.util.Arrays.stream(allowCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .anyMatch(host::endsWith);
    }

    private boolean isBlockedDomain(String url) {
        if (isBlank(url) || isBlank(blockedDomainsCsv)) return false;
        String host;
        try {
            host = URI.create(url).getHost();
        } catch (Exception ignore) {
            return false;
        }
        if (host == null) return false;
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
            // ignore
        }
        return Jsoup.parse(html).text();
    }

    private static List<String> keywords(String q) {
        return Arrays.stream(q.split("\\s+"))
                .map(String::toLowerCase)
                .map(t -> NON_ALNUM.matcher(t).replaceAll(""))
                .filter(t -> !t.isBlank())
                .filter(t -> t.length() > 1)
                // (제거) FILLER_WORDS 필터 — 상위 LLM 단계에서 처리
                .toList();
    }

    private static int hitCount(String text, List<String> kws) {
        String base = NON_ALNUM.matcher(text.toLowerCase()).replaceAll("");
        int cnt = 0;
        for (String k : kws) {
            if (base.contains(k)) cnt++;
        }
        return cnt;
    }

    public enum SearchStatus {SUCCESS, API_FAILURE, NO_RESULTS}

    public record SearchResultWrapper(
            SearchStatus status,
            List<String> snippets,
            @Nullable String failureReason
    ) { }

    private void reinforceSnippets(Long sessionId, String query, List<String> snippets) {
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
                tmpScore = 1.0 / (idx + 1);
            }

            // ★ 람다에서 사용할 불변 변수
            final double score = tmpScore;
            final String snip = snippet;
            final Long sid = sessionId;
            final String qCopy = query;

            /* 개선 ①  독립 트랜잭션 & 중복 안전 처리 */
            Schedulers.boundedElastic().schedule(() ->
                    txTemplate.executeWithoutResult(txStatus -> {
                        try {
                            memorySvc.reinforceWithSnippet(
                                    String.valueOf(sid),
                                    qCopy,
                                    snip,
                                    "WEB",
                                    score);
                        } catch (DataIntegrityViolationException dup) {
                            /* 동일 해시(UNIQUE) 중복 – 조용히 무시 */
                            log.debug("duplicate snippet ignored");
                        } catch (Exception e) {
                            log.warn("Failed to reinforce snippet: {}", e.getMessage());
                        }
                    })
            );

            /* 🔴 캐시 갱신도 snip 사용 */
            recentSnippetCache.put(DigestUtils.md5Hex(snip), Boolean.TRUE);
        }
    }

    // 선언형/접두어 제거(검색어: …, …입니다)
    private static String normalizeDeclaratives(String q) {
        if (q == null) return "";
        String s = q.replaceFirst("^\\s*검색어\\s*:\\s*", "");
        s = s.replace("입니다", "");
        // 문장 끝의 명령형 군더더기 제거
        s = s.replaceAll("\\s*(싹다|전부|모두)?\\s*(찾[아고]와|찾아와|검색해와)\\.?\\s*$", "");
        return s.trim();
    }

    /**
     * 두 번째 소스의 "교정된 문장/입력 문장/검색어1..." 접두사 제거용 정규화
     */
    private static String normalizeQuery(String q) {
        if (q == null) return "";
        String s = q;
        s = s.replaceAll("(?i)(교정된\\s*문장|입력\\s*문장|검색어\\s*\\d+|질문\\s*초안|요약)[:：]?", "");
        s = s.replaceAll("\\s+", " ").trim();
            // “5 8 패치/업데이트/버전/ver/v” → “5.8 …” 로 정규화
                   s = s.replaceAll("(\\d)\\s+(\\d)(?=\\s*(?:패치|업데이트|버전|ver\\b|v\\b))", "$1.$2");
        return s;
    }

    /**
     * 두 번째 소스의 키워드 추출(간단 빈도 기반) – assistantAnswer 2‑pass에 사용
     */
    private static String extractTopKeywords(String text, int max) {
        if (!StringUtils.hasText(text)) return "";
        Set<String> stop = Set.of(
                "the","and","for","with","that","this","you","your",
                "및","그리고","그러나","또는","등","수","것","관련","대한","무엇","뭐야","뭐가","어떤","어떻게"
        );
        Pattern p = Pattern.compile("[\\p{IsHangul}A-Za-z0-9]{2,}");
        Matcher m = p.matcher(text);

        Map<String,Integer> freq = new java.util.HashMap<>();
        while (m.find()) {
            String w = m.group().toLowerCase(Locale.ROOT);
            if (stop.contains(w)) continue;
            freq.put(w, freq.getOrDefault(w, 0) + 1);
        }
        return freq.entrySet().stream()
                .sorted((a,b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(max)
                .map(Map.Entry::getKey)
                .collect(Collectors.joining(" "));
    }

    /**
     * 두 번째 소스의 간단 스니펫 포맷터(현재는 사용하지 않지만 호환성 위해 유지)
     *  - 제목 — 요약 (호스트)
     */
    @SuppressWarnings("unused")
    private String toSnippetLegacy(String title, String description, String link) {
        String cleanTitle = stripHtml(title);
        String cleanDesc  = stripHtml(description);
        String url   = (link == null ? "" : link);
        String host;
        try {
            host = StringUtils.hasText(url) ? URI.create(url).getHost() : null;
        } catch (Exception e) {
            host = null;
        }
        String text = (cleanTitle + " — " + cleanDesc).trim();
        if (text.length() < 10) return null;
        return "- " + text + " (" + (StringUtils.hasText(host) ? host : url) + ")";
    }

    /**
     * 검색 과정 패널을 만들기 위한 간단한 HTML 생성기
     */
    public String buildTraceHtml(SearchTrace t, List<String> snippets) {
        if (t == null) return "";
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
            sb.append("<div class=\"trace-snippets\"><ul>");
            for (String line : snippets) {
                // 이미 a태그 포함된 라인 그대로 렌더
                sb.append("<li>").append(line).append("</li>");
            }
            sb.append("</ul></div>");
        }
        sb.append("</div></details>");
        return sb.toString();
    }

    // UI‑trace 특수문자 깨짐 방지
    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
    /* === DTOs for JSON parsing === */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record NaverResponse(List<NaverItem> items) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record NaverItem(String title, String link, String description) { }

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
        if (sid == null) return "";
        try {
            return memorySvc.loadContext(String.valueOf(sid));
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Reinforce the assistant's response into translation memory.
     */
    public void reinforceAssistantResponse(String query, String answer) {
        if (!enableAssistantReinforcement || isBlank(answer) || isBlank(query)) return;
        if ("정보 없음".equals(answer.trim()) || "정보 없음.".equals(answer.trim())) return;
        Long sessionId = sessionIdProvider.get();
        if (sessionId == null) return;
        double score;
        try {
            double sim = relevanceScorer.score(query, answer);
            score = (sim > 0 ? sim : 1.0);
        } catch (Exception ignore) {
            score = 1.0;
        }
        final double finalScore = Math.max(0.01, score * assistantReinforceWeight);

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
        } catch (DataIntegrityViolationException ignore) {
            log.debug("duplicate snippet ignored");
        }
    }

    /** API 호출용으로 위치 접미사를 붙인 쿼리 문자열 생성 */
    private String appendLocationSuffix(String base) {
        String suffix = deriveLocationSuffix(base);
        return isBlank(suffix) ? base : base + " " + suffix;
    }

    /* ────────────────────────────────────────
     * 유사 쿼리/정규화 유틸을 안전하게 별도 네임스페이스(Q)로 격리
     * ────────────────────────────────────────*/
    private static @Nullable String extractVersionToken(String q) {
        if (q == null) return null;
        Matcher m = VERSION_PATTERN.matcher(q);
        return m.find() ? (m.group(1) + "." + m.group(2)) : null;
    }
    private static Pattern versionMustRegex(String v) {
        String core = v.replace(".", "[\\.·\\s]");
        return Pattern.compile("(?<!\\d)" + core + "(?!\\d)");
    }
    private static final class Q {
        static String canonical(String s) {
            if (s == null) return "";
            String t = NON_ALNUM_KO.matcher(s.toLowerCase()).replaceAll(" ").trim();
            return t.replaceAll("\\s{2,}", " ");
        }
        static Set<String> tokenSet(String s) {
            String t = canonical(s);
            if (t.isEmpty()) return Set.of();
            return Arrays.stream(t.split("\\s+"))
                    .filter(w -> !w.isBlank())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
        static double jaccard(Set<String> a, Set<String> b) {
            if (a.isEmpty() && b.isEmpty()) return 1.0;
            if (a.isEmpty() || b.isEmpty()) return 0.0;
            int inter = 0;
            for (String x : a) if (b.contains(x)) inter++;
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
                    if (jaccard(kt, tok) >= threshold) { similar = true; break; }
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
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "…";
    }

    /** 캐시 솔트(정책/필터/리스트 변화가 키에 반영되도록) */
    private String cacheSalt() {
        return domainPolicy + "|" + enableDomainFilter + "|" + String.valueOf(allowlist) + "|" + String.valueOf(blockedDomainsCsv);
    }
    /** 캐시 키 생성: salt || canonical(query) */
    private String cacheKeyFor(String q) {
        return cacheSalt() + "||" + Q.canonical(q);
    }

}
// RuleBreak: when active, caller may adjust opt; this service accepts rb param for future tuning.