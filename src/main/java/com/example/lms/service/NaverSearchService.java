package com.example.lms.service;
import org.springframework.lang.Nullable;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.Semaphore;          // ← 신규 추가
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;   // ★ Mono 제네릭 타입을 위해 반드시 필요
import org.springframework.transaction.PlatformTransactionManager;         // ─ 트랜잭션 템플릿 추가
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.scheduler.Schedulers;         // B. Schedulers 임포트 추가
import java.time.Duration;                       // ▲ Sync Facade에서 block 타임아웃에 사용
import java.util.stream.Collectors;
import java.util.Objects;                        // NEW – distinct/limit 필터

import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.dao.DataIntegrityViolationException;
import com.example.lms.transform.QueryTransformer;
import com.example.lms.util.RelevanceScorer;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.LoadingCache;   // recentSnippetCache 용
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import com.example.lms.service.rag.LangChainRAGService;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.Query;
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
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;   // ← 추가
import org.apache.commons.codec.digest.DigestUtils;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Simplified NaverSearchService that does not automatically append
 * marketplace keywords (e.g., 번개장터, 중고나라) or site restrictions.
 * It processes queries, applies an optional location suffix,
 * filters by allow/deny lists and stores snippets into memory with
 * cosine‑similarity scores.
 */
@Service
@Primary
@Slf4j
@RequiredArgsConstructor
public class NaverSearchService {
    /** LLM 답변을 활용한 검색 (딥 리서치 모드) */


// 🔑 Naver API 키 CSV(생성자에서 주입) & 키 회전용 변수들
    private final String naverKeysCsv;          // keys 1:a1b2c3,2:d4e5f6 …
    private List<ApiKey> naverKeys = List.of(); // 초기값은 빈 리스트
    private final AtomicLong keyCursor = new AtomicLong(); // 라운드-로빈 인덱스
    /** 검색 단계(시도) 로그 */
    public static final class SearchStep {
        public final String query;
        public final int returned;
        public final int afterFilter;
        public final long tookMs;
        public SearchStep(String query, int returned, int afterFilter, long tookMs) {
            this.query = query; this.returned = returned; this.afterFilter = afterFilter; this.tookMs = tookMs;
        }
    }
    /** 한 번의 사용자 질의에 대한 전체 검색 추적 */
    public static final class SearchTrace {
        public final List<SearchStep> steps = new ArrayList<>();
        public boolean domainFilterEnabled;
        public boolean keywordFilterEnabled;
        public String suffixApplied;
        public long totalMs;
    }
    /** 스니펫 + 추적 묶음 */
    public record SearchResult(List<String> snippets, SearchTrace trace) {}

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
    private final EmbeddingModel embeddingModel;
    private final WebClient web;
    private final ObjectMapper om;
    /** Cache for normalized queries to web snippet lists. */
    /** 비동기 캐시 (block 금지) */
    private final AsyncLoadingCache<String, List<String>> cache;
    /** Cache to prevent reinforcing duplicate snippets. */
    private final LoadingCache<String, Boolean> recentSnippetCache;
    /** Scorer for cosine similarity. */
    private final RelevanceScorer relevanceScorer;
    /** NEW – 별도 트랜잭션 컨텍스트용 */
    private final TransactionTemplate txTemplate;
    /** Supplier of the current session id. */
    private final Supplier<Long> sessionIdProvider;

    /* === Configuration properties === */
    // (client-id / client-secret 개별 프로퍼티는 더 이상 사용하지 않는다)
    /** 단순화된 호출 타임아웃(ms) */
    private static final long API_TIMEOUT_MS = 3000;
    @Value("${naver.search.web-top-k:5}")   private int webTopK;   // LLM에 넘길 개수
    @Value("${naver.search.rag-top-k:5}")   private int ragTopK;   // 벡터 RAG top‑k
    /** (NEW) 네이버 API에서 한 번에 받아올 검색 결과 수(1‑100) */
    @Value("${naver.search.display:5}")
    private int display;
    @Value("${naver.search.query-suffix:}") private String querySuffix;
    @Value("${naver.search.query-sim-threshold:0.3}") private double querySimThreshold;
    @Value("${naver.filters.enable-domain-filter:false}") private volatile boolean enableDomainFilter;

    /* ---------- 2. ApiKey 헬퍼 타입 ---------- */
    private record ApiKey(String id, String secret) {}

    // 기본 허용 목록에 서브도메인 포함 도메인 추가(부재 시 0개 스니펫 방지)
    @Value("${naver.filters.domain-allowlist:eulji.ac.kr,eulji.or.kr}") private volatile String allowlist;
    @Value("${naver.filters.enable-keyword-filter:false}") private boolean enableKeywordFilter;
    // 키워드 필터는 OR(하나 이상 매칭)로 완화
    @Value("${naver.filters.keyword-min-hits:1}") private int keywordMinHits;
    /** Comma-separated blacklist of domains to exclude entirely. */
    @Value("${naver.search.blocked-domains:}") private String blockedDomainsCsv;

    /** (선택) 대화 문맥에 따라 쿼리를 재작성하는 Transformer – 존재하지 않으면 주입 안 됨 */
    /** 오타·맞춤법 교정을 담당하는 Transformer */
    private final QueryTransformer queryTransformer;

    /* 최대 동시 네이버 API 호출량 (429 방지) */
    private static final int MAX_CONCURRENT_API = 2;

    /* ★ NEW: 한 검색당 최대 변형 쿼리 수 */
    private static final int MAX_QUERIES_PER_SEARCH = 4;
    private final Semaphore requestSemaphore = new Semaphore(MAX_CONCURRENT_API); // trace-mode sync 호출용

    /* ── 헤징(동시 이중 발사) 관련: 기본 OFF, 필요 시 지연 헤징만 허용 ── */
    /* 🔵 다중-키 헤징 전략 제거: 항상 첫 번째 네이버 키만 사용 */
    private final boolean hedgeEnabled = false;
    @Value("${naver.hedge.timeout-ms:3000}")
    private long hedgeTimeoutMs;   // primary 타임아웃 계산엔 그대로 사용

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

    private Set<String> productKeywords;
    private Set<String> foldKeywords;
    private Set<String> flipKeywords;
    /* === Query alias map used to normalize user input. */
    private static final Map<String, String> QUERY_ALIAS = Map.ofEntries(
            Map.entry("폴드7",  "갤럭시 Z 폴드 7"),
            Map.entry("폴드6",  "갤럭시 Z 폴드 6"),
            Map.entry("K8Plus", "K8 Plus"),
            Map.entry("케이8플러스", "K8 Plus"),
            Map.entry("케이8 플러스", "K8 Plus"),
            Map.entry("k8 플러스", "K8 Plus"),
            // 뮤직전생 → 음악 전생
            Map.entry("뮤직전생", "음악 전생"),
            Map.entry("뮤직전생에 대해", "음악 전생")
            // 🔽 NEW: include common variants of Galaxy Fold 6 to avoid accidental fallback
            ,Map.entry("갤럭시z폴드6", "갤럭시 Z 폴드 6")
            ,Map.entry("galaxy z fold6", "갤럭시 Z 폴드 6")
            ,Map.entry("galaxy z fold 6", "갤럭시 Z 폴드 6")
    );

    /* === Patterns and stop words === */
    private static final Set<String> STOP_WORDS_SEARCH = Set.of("plus", "플러스", "discount", "할인");
    /*  교정 메타가 붙은 쿼리는 네이버 API 호출 자체를 생략 */
    private static final Set<String> STOP_QUERY_PREFIXES = Set.of(
            "틀렸습니다", "틀렸어요", "틀렸네요",
            "올바른 표기", "올바른 표현"
    );
    private static final Set<String> FILLER_WORDS = Set.of(
            "대해", "찾아봐", "찾아바", "해줘", "해주세요", "해바", "해봐",
            "소개", "알려", "정보", "관련", "검색", "검색해줘",
            "해드려요", "해줄래", "에", "어요", "주세요", "어줘"
    );
    private static final Pattern LOCATION_PATTERN =
            Pattern.compile(".*(역|정류장|도로|길|거리|로|시|구|동|읍|면|군).*", Pattern.UNICODE_CASE);
    private static final Pattern NON_ALNUM =
            Pattern.compile("[^\\p{IsHangul}\\p{L}\\p{Nd}]");
    private static final Pattern MEDICAL_PATTERN = Pattern.compile(
            "(?i)(병원|의료진|교수|진료과|의사|전문의|센터|클리닉)");
    private static final Pattern OFFICIAL_INFO_PATTERN = Pattern.compile(
            "(?i)(병원|의료|의사|전문의|교수|대학교|대학|학과|연구실|연구소|센터|학교|공공기관|정부기관|학회|세미나|논문)");
    /** Source tag for assistant-generated responses stored into memory. */
    private static final String ASSISTANT_SOURCE = "ASSISTANT";
    private static final Pattern VERSION_PATTERN = Pattern.compile("(\\d+\\.\\d+)");

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
        Matcher matcher = VERSION_PATTERN.matcher(query);
        if (!matcher.find()) {
            return List.of(query);
        }

        String version = matcher.group(1);
        return List.of(
                query,
                query + " 패치 노트",
                query + " 업데이트 내용",
                query + " 업데이트 일정",
                query + " 변경사항",
                query + " 버전 정보"
        );
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

    /** Constructor with dependency injection. */
    @Autowired
    public NaverSearchService(
            QueryTransformer queryTransformer,
            MemoryReinforcementService memorySvc,
            ObjectProvider<ContentRetriever> retrieverProvider,
            EmbeddingStore<TextSegment> embeddingStore,
            EmbeddingModel embeddingModel,
            @Lazy Supplier<Long> sessionIdProvider,
            /* 🔴 키 CSV를 생성자 파라미터로 주입받는다 */
            @Value("${naver.keys:}") String naverKeysCsv,
            @Value("${naver.web.cache.max-size:2000}") long maxSize,
            @Value("${naver.web.cache.ttl-sec:300}") long ttlSec,
            PlatformTransactionManager txManager) {                     // NEW – 주입
        this.queryTransformer  = queryTransformer;
        this.memorySvc         = memorySvc;
        this.retrieverProvider = retrieverProvider;
        this.embeddingStore    = embeddingStore;
        this.embeddingModel    = embeddingModel;
        this.sessionIdProvider = sessionIdProvider;
        this.naverKeysCsv      = naverKeysCsv;           // 🔴 저장
        this.relevanceScorer   = new RelevanceScorer(embeddingModel);
        this.web = WebClient.builder()
                .baseUrl("https://openapi.naver.com")
                // ⚠️ 필터 체인에 헤더 주입을 고정 등록하지 않는다(이중 주입 사고 방지).
                .build();
        this.om  = new ObjectMapper();
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(Duration.ofSeconds(ttlSec))
                .buildAsync((key, ex) -> callNaverApiMono(key).toFuture());
        this.recentSnippetCache = Caffeine.newBuilder()

                .maximumSize(4_096)
                .expireAfterWrite(Duration.ofMinutes(10))
                .build(k -> Boolean.TRUE);


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
        return searchWithTraceMono(query, topK)
                .block(Duration.ofSeconds(5));
    }


    /** LLM 답변까지 받아서 ‘딥 리서치’ 검색을 수행하는 Mono 버전 */
    public Mono<List<String>> searchSnippetsMono(String userPrompt,
                                                 String assistantAnswer,
                                                 int topK) {
        return searchSnippetsInternal(userPrompt, topK, null, assistantAnswer);
    }
    /**
     * 사용자의 쿼리를 검색하면서 동시에 어시스턴트가 생성한 최종 답변을
     * 메모리 서비스에 강화(Reinforce)합니다. 이렇게 하면 같은 세션에서 후속 질문을 할 때
     * 이전에 제공한 답변이 컨텍스트에 포함되어 RAG 체인이 참고할 수 있습니다.
     * ChatService는 답변을 생성한 이후, 사용자의 질문과 답변을 이 메서드에 넘겨주세요.
     *
     * @param query  사용자의 원본 질문
     * @param answer 어시스턴트의 최종 답변
     * @return 검색된 웹 스니펫 목록
     */
    public Mono<List<String>> searchAndReinforce(String query, String answer) {
        return searchAndReinforce(query, webTopK, answer);
    }

    /**
     * topK를 지정하여 검색을 수행한 뒤 답변을 메모리에 강화합니다.
     *
     * @param query  사용자의 원본 질문
     * @param topK   가져올 웹 스니펫의 개수
     * @param answer 어시스턴트의 최종 답변
     * @return 검색된 웹 스니펫 목록
     */
    public Mono<List<String>> searchAndReinforce(String query, int topK, String answer) {
        return searchSnippetsInternal(query, topK, null, answer)
                .doOnNext(list -> {
                    // {스터프2} 장점: 웹 근거가 없거나 옵션이 꺼져 있으면 reinforcement 건너뜀
                    if (enableAssistantReinforcement && !list.isEmpty()) {
                        reinforceAssistantResponse(query, answer);
                    }
                });
    }

    /**
     * Perform a web search without adding any marketplace keywords or site
     * restrictions. Only the normalized query and an optional location
     * suffix are sent to the Naver API. Snippets are reinforced into memory.
     */
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
                    if (!hasCreds()) {                       // 🔴 보조 설명
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
        return searchWithTraceMono(query, topK)
                .block(Duration.ofSeconds(5));
    }

    /** 실제 검색 본체(일반/추적 공용) */
    private Mono<List<String>> searchSnippetsInternal(String query,
                                                      int topK,
                                                      SearchTrace trace,
                                                      @Nullable String assistantAnswer) {


        /* ─ assistantAnswer(딥-리서치) 브랜치도 동일한 Reactive flow ─ */
        if (assistantAnswer != null && !assistantAnswer.isBlank()) {
            List<String> qs = (queryTransformer != null)
                    ? queryTransformer.transformEnhanced(query, assistantAnswer)
                    : List.of(query);
            qs = qs == null ? List.of() : qs;
            qs = qs.stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .distinct()
                    .limit(MAX_QUERIES_PER_SEARCH)
                    .toList();

            LinkedHashSet<String> acc2 = new LinkedHashSet<>();
            return Flux.fromIterable(qs)
                    .flatMap(q -> Mono.fromFuture(cache.get(q))
                                    .subscribeOn(Schedulers.boundedElastic()),
                            MAX_CONCURRENT_API)
                    .flatMapIterable(list -> list)
                    .filter(acc2::add)
                    .take(topK)
                    .collect(Collectors.toCollection(() -> acc2))
                    .<List<String>>map(set -> new ArrayList<>(set)) // ❶ 명시적으로 List 로 캐스팅
                    .doOnNext(snips -> {
                        Long sid = sessionIdProvider.get();
                        if (sid != null) reinforceSnippets(sid, query, snips);
                    });
        }

        if (isBlank(query) || !hasCreds()) {
            return Mono.just(Collections.emptyList());
        }
        // 별칭 적용 + 선언형 접두/접미 제거
        String normalized = normalizeDeclaratives(QUERY_ALIAS.getOrDefault(query.trim(), query.trim()));

        /* 1) 대화 컨텍스트 기반 QueryTransformer 적용 */
        List<String> expandedQueries;
        // 기본 확장 쿼리로 초기화
        expandedQueries = expandQueries(normalized);
        // QueryTransformer가 존재할 경우 시도하되 예외 발생 시 fallback
        if (queryTransformer != null) {
            try {
                // ❸ assistantAnswer 존재 시 transformEnhanced 사용
                List<String> candidateQueries;
                if (assistantAnswer != null && !assistantAnswer.isBlank()) {
                    candidateQueries = queryTransformer.transformEnhanced(query, assistantAnswer);
                } else {
                    // () 대화 맥락을 함께 넘겨 정확도 향상
                    candidateQueries = queryTransformer.transform(getConversationContext(), normalized);
                }
                if (candidateQueries != null && !candidateQueries.isEmpty()) {
                    expandedQueries = candidateQueries;
                }
            } catch (Exception e) {
                // LLM 호출 실패 또는 인터럽트 발생 시 경고 로그 후 기본 확장으로 대체
                log.warn("QueryTransformer failed for query '{}': {}", query, e.toString());
                // expandedQueries 이미 기본값이므로 그대로 사용
            }
        }

        /* ① 폭주 차단: 중복 제거 후 8개까지만 */
        expandedQueries = expandedQueries.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .limit(MAX_QUERIES_PER_SEARCH)
                .toList();
        /* ② 디바이스·상품 키워드(Fold·Flip 등) 포함 시 동의어 자동 부착 */
        /*
         *  ② 키워드 기반 동의어 부착: fold/flip/product 키워드가 들어갈 때
         *  해당 세트에 포함된 키워드만 부착한다. 이전 코드에서는 모든 세트의 키워드를
         *  무조건 추가하여 검색 문구가 지나치게 길어져 엉뚱한 결과(예: 강아지 정보)가
         *  노출되는 문제가 있었다. 이를 수정하여, 폴드 키워드가 포함된 경우엔
         *  fold 관련 키워드만, 플립 키워드가 포함된 경우엔 flip 관련 키워드만, 제품 키워드가
         *  포함된 경우엔 product 관련 키워드만 부착한다.
         */
        {
            Set<String> extraKeywords = new LinkedHashSet<>();
            String qLower = normalized.toLowerCase();
            if (containsAny(qLower, productKeywords)) {
                extraKeywords.addAll(productKeywords);
            }
            if (containsAny(qLower, foldKeywords)) {
                extraKeywords.addAll(foldKeywords);
            }
            if (containsAny(qLower, flipKeywords)) {
                extraKeywords.addAll(flipKeywords);
            }
            if (!extraKeywords.isEmpty()) {
                final String extras = String.join(" ", extraKeywords);
                expandedQueries = expandedQueries.stream()
                        .map(q -> q  +" " + extras)
                        .toList();
            }
        }
        /* ② 중복 차단 & early-exit */
        LinkedHashSet<String> acc = new LinkedHashSet<>();
        Flux<String> snippetFlux =
                Flux.fromIterable(expandedQueries)
                        .flatMap(q -> Mono.fromFuture(cache.get(q.trim()))
                                        .subscribeOn(Schedulers.boundedElastic()),
                                MAX_CONCURRENT_API)
                        .flatMapIterable(list -> list)
                        .filter(acc::add)   // 한 번만 누적
                        .take(topK);

        // ▶ 전체 검색 파이프라인 타임아웃(동적 계산, 상한 4.5s) + 폴백
        long perCallMs = Math.max(500L, hedgeTimeoutMs); // 각 API 호출 타임아웃
        int n = Math.max(1, expandedQueries.size());
        int waves = Math.max(1, (int) Math.ceil(n / (double) MAX_CONCURRENT_API));
        long overallMs = Math.min(4500L, perCallMs * waves + 500L); // headroom 0.5s

        return snippetFlux
                .collectList()
                .timeout(Duration.ofMillis(overallMs), Mono.just(List.of()))
                .doOnNext(list -> {
                    Long sid = sessionIdProvider.get();
                    if (sid != null) reinforceSnippets(sid, query, list);
                });
    }



    /**
     * Merge RAG context (local + external) with web snippets.
     */
    public List<String> combinedContext(String query) {
        final String sid = String.valueOf(sessionIdProvider.get());
        List<String> localCtx  = new ArrayList<>();
        List<String> remoteCtx = new ArrayList<>();
        if (!isBlank(query)) {
            // local RAG
            try {
                var localRetriever = EmbeddingStoreContentRetriever.builder()
                        .embeddingStore(embeddingStore)
                        .embeddingModel(embeddingModel)
                        .maxResults(ragTopK)
                        .build();
                localCtx = localRetriever.retrieve(Query.from(query))
                        .stream()
                        .filter(c -> {
                            Map<?, ?> md = c.metadata();
                            // Use unified metadata key "sid" rather than "sessionId"
                            return md != null && sid.equals(md.get(LangChainRAGService.META_SID));
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
                final double simThreshold = querySimThreshold;
                remoteCtx = ext.retrieve(Query.from(query)).stream()
                        .limit(ragTopK)
                        .filter(c -> {
                            Map<?, ?> md = c.metadata();
                            // Compare using unified key
                            boolean sameSession = sid.equals(md != null ? md.get(LangChainRAGService.META_SID) : null);
                            if (sameSession) return true;
                            try {
                                double sim = relevanceScorer.score(query, c.toString());
                                return sim >= simThreshold;
                            } catch (Exception ignore) {
                                return false;
                            }
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
    /* ===== 비동기 API 콜 ===== */
    private Mono<List<String>> callNaverApiMono(String query) {
        if (!hasCreds() || isBlank(query)) {
            return Mono.just(Collections.emptyList());
        }

        String apiQuery = appendLocationSuffix(query);

        /* topK보다 적게 받아와 결과가 부족해지는 문제 → {스터프2} 전략 반영 */
        int fetch = Math.min(100, Math.max(display, webTopK));
        String uri = UriComponentsBuilder.fromPath("/v1/search/webkr.json")
                .queryParam("query", apiQuery)
                .queryParam("display", fetch)
                .queryParam("start", 1)
                .queryParam("sort", "sim")
                .build(false)
                .toUriString();
        ApiKey first = nextKey();
        if (first == null) return Mono.just(List.of());

        String keyLabel1 = first.id().length() > 4 ? first.id().substring(first.id().length()-4) : first.id();
        Mono<String> primary = web.get()
                .uri(uri)
                .header("X-Naver-Client-Id", first.id())
                .header("X-Naver-Client-Secret", first.secret())
                .header("X-Key-Label", "K-" + keyLabel1)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofMillis(hedgeTimeoutMs));
        /* 🔵 단일 키 모드 – 실패 시 Bing 훅으로 넘김 (헤징 블록 완전 제거) */
        return primary
                // 1️⃣ JSON → 스니펫 변환 시 원본 쿼리를 함께 전달해야 키워드 필터링이 가능하다.
                .map(json -> parseNaverResponse(query, json))
                // 2️⃣ 429·5xx 등 치명 오류 → Bing Fallback(Stub)으로 전환
                .onErrorResume(WebClientResponseException.class, e -> {
                    int sc = e.getStatusCode().value();
                    if (sc == 429 || sc >= 500) {
                        log.warn("Naver API {} - falling back to Bing (TODO)", sc);
                        return callBingApiMono(apiQuery);
                    }
                    return Mono.empty();
                })
                // 3️⃣ 기타 예외 → 역시 Bing Fallback
                .onErrorResume(t -> {
                    log.warn("Naver API '{}' failed: {}", query, t.toString());
                    return callBingApiMono(apiQuery);
                });
    }





    /**
     * JSON → 스니펫 파싱  (선택) 키워드 필터링.
     * @param query 원본 검색어 (키워드 필터에 사용)
     * @param json  Naver API 응답 JSON 문자열
     */
    private List<String> parseNaverResponse(String query, String json) {
        if (isBlank(json)) return Collections.emptyList();
        try {
            NaverResponse resp = om.readValue(json, NaverResponse.class);
            if (resp.items() == null) return Collections.emptyList();

            List<String> lines = resp.items().stream()
                    .filter(item -> !enableDomainFilter || isAllowedDomain(item.link()))
                    .filter(item -> !isBlockedDomain(item.link()))
                    .map(item -> "- <a href=\"%s\" target=\"_blank\" rel=\"noopener\">%s</a>: %s"
                            .formatted(item.link(),
                                    stripHtml(item.title()),
                                    stripHtml(item.description())))
                    .distinct()
                    .toList();

            // 🔍 키워드 OR 필터 복원
            if (enableKeywordFilter && !lines.isEmpty()) {
                List<String> kws = keywords(query);

                int requiredHits = Math.max(1,
                        Math.min(keywordMinHits, (kws.size()+ 1) / 3));
                List<String> filtered = lines.stream()
                        .filter(sn -> hitCount(sn, kws) >= requiredHits)
                        .toList();
                if (!filtered.isEmpty()) {
                    return filtered;
                }
            }
            return lines;
        } catch (Exception e) {
            log.error("Parse error", e);
            return Collections.emptyList();
        }
    }

    /* Bing Fallback Stub (미구현) */
    private Mono<List<String>> callBingApiMono(String query) {
        log.warn("Bing Fallback for query '{}' (Not Implemented)", query);
        return Mono.just(Collections.emptyList());
    }

    /** 추적 지원 버전 */
    private List<String> callNaverApi(String query, SearchTrace trace) {
        Instant start = Instant.now();
        if (!hasCreds() || isBlank(query)) {
            return Collections.emptyList();
        }
        String qTrim = query.trim();
        if (qTrim.length() < 3 || STOP_WORDS_SEARCH.contains(qTrim.toLowerCase())) {
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
            trace.keywordFilterEnabled = enableKeywordFilter;
        }
        int topK = (trace != null && !trace.steps.isEmpty())
                ? Math.max(1, trace.steps.get(0).afterFilter)  // trace 모드면 직전 topK
                : webTopK;

        String uri = UriComponentsBuilder.fromPath("/v1/search/webkr.json")
                .queryParam("query", apiQuery)
                .queryParam("display",
                        Math.max(topK, Math.min(display, 100)))
                .queryParam("start", 1)
                .queryParam("sort", "sim")
                .build(false)
                .toUriString();
        try {
            requestSemaphore.acquire();   // 동시에 2개까지만 호출
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
                        .header("X-Key-Label", "K-" + (id.length()>4?id.substring(id.length()-4):id))
                        .retrieve()
                        .bodyToMono(String.class)
                        .block(Duration.ofSeconds(10));
            } catch (WebClientResponseException e) {
                if (e.getStatusCode().value() == 429) {
                    /* 🔵 세컨드 네이버 키 재시도 코드 완전 제거 → Bing으로 즉시 전환 */
                    log.warn("Naver API 429 – Bing fallback (TODO)");
                    return callBingApi(query, trace);
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
            // Convert items to lines, apply domain filters
            List<String> lines = resp.items().stream()
                    .filter(item -> !enableDomainFilter || isAllowedDomain(item.link()))
                    .filter(item -> !isBlockedDomain(item.link()))
                    .map(item -> {
                        String title = stripHtml(item.title());
                        String desc = stripHtml(item.description());
                        String link = item.link();
                        // 목록 형식과 클릭 가능한 링크를 제공하여 프런트에서 가독성을 높임
                        // 예: "- <a href=\"https://example.com\" target=\"_blank\">기사제목</a>: 기사요약"
                        return String.format("- <a href=\"%s\" target=\"_blank\" rel=\"noopener\">%s</a>: %s", link, title, desc).trim();
                    })
                    .filter(s -> !s.isEmpty())
                    .distinct()
                    .toList();
            // Apply keyword filtering if enabled
            if (enableKeywordFilter && !lines.isEmpty()) {
                List<String> kws = keywords(query);
                int requiredHits = Math.max(1,
                        Math.min(keywordMinHits, (kws.size() + 1) / 3));
                List<String> filtered = lines.stream()
                        .filter(sn -> hitCount(sn, kws) >= requiredHits)
                        .toList();
                // OR 규칙: 하나라도 맞으면 통과(너무 엄격해서 0건 되는 것 방지)
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
            return callBingApi(query, trace);   // 실패 → Bing(스텁)
        } finally {
            // trace-mode sync 호출이므로 항상 세마포어 반환
            requestSemaphore.release();
        }
    } // --- callNaverApi(String, SearchTrace) 끝 ---


//  ⬆︎ Duplicate stub removed – original definition already exists above.

    private List<String> callBingApi(String query, @Nullable SearchTrace trace) {
        // TODO: Bing Search API 연동 (동기)
        if (trace != null) {
            trace.steps.add(new SearchStep("Bing Fallback – 미구현", 0, 0, 0));
        }
        return Collections.emptyList();
    }


    /** 429 상태 코드 감지 – 재시도 필터 */
    private boolean isTooManyRequests(Throwable t) {
        return t instanceof WebClientResponseException
                && ((WebClientResponseException) t).getStatusCode().value() == 429;
    }

    /** Single-shot fallback (no extra modifications). */
    private List<String> searchOnce(String q) throws IOException {
        // In this simplified version, searchOnce is not used because we no
        // longer apply site-specific fallbacks.
        return List.of();
    }

    /* === Helper functions === */

    private boolean hasCreds() {
        boolean ok = naverKeys != null && !naverKeys.isEmpty()
                && naverKeys.stream().anyMatch(k -> !isBlank(k.id()) && !isBlank(k.secret()));
        if (!ok) {
            log.warn("[NaverSearch] no API keys loaded. property 'naver.keys'='{}'", naverKeysCsv); // 🔴 원인 로깅
        }
        return ok;
    }

    private String deriveLocationSuffix(String q) {
        if (isBlank(querySuffix)) {
            return null;
        }
        if (!isLocationQuery(q)) {
            return querySuffix;
        }
        String sid = String.valueOf(sessionIdProvider.get());
        String memCtx;
        try {
            memCtx = memorySvc.loadContext(sid);
        } catch (Exception ignore) {
            memCtx = null;
        }
        if (isBlank(memCtx)) {
            return querySuffix;
        }
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        for (String token : memCtx.split("\\s+")) {
            if (LOCATION_PATTERN.matcher(token).find()) {
                candidates.add(token);
            }
        }
        if (candidates.isEmpty()) {
            return querySuffix;
        }
        double bestSim = 0.0;
        String bestCandidate = null;
        try {
            var qVec = embeddingModel.embed(q).content().vector();
            for (String cand : candidates) {
                try {
                    var cVec = embeddingModel.embed(cand).content().vector();
                    if (qVec.length != cVec.length) continue;
                    double dot = 0, nq = 0, nc = 0;
                    for (int i = 0; i < qVec.length; i++) {
                        dot += qVec[i] * cVec[i];
                        nq  += qVec[i] * qVec[i];
                        nc  += cVec[i] * cVec[i];
                    }
                    if (nq == 0 || nc == 0) continue;
                    double sim = dot / (Math.sqrt(nq) * Math.sqrt(nc) + 1e-9);
                    if (sim > bestSim) {
                        bestSim = sim;
                        bestCandidate = cand;
                    }
                } catch (Exception ignore) {
                    // ignore
                }
            }
        } catch (Exception ignore) {
            // ignore
        }
        if (bestCandidate != null && bestSim >= querySimThreshold) {
            return bestCandidate;
        }
        return querySuffix;
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
                .filter(t -> !FILLER_WORDS.contains(t))
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

    public enum SearchStatus { SUCCESS, API_FAILURE, NO_RESULTS }

    public record SearchResultWrapper(
            SearchStatus status,
            List<String> snippets,
            @Nullable String failureReason
    ) {}

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
            /* 🔴 NEW: 루프별 스니펫 값을 불변 변수로 캡처 */
            final String snip = snippet;        // 🔴

            /* 🔴 NEW: 세션·쿼리도 불변 변수로 캡처 */
            final Long   sid   = sessionId;     // 🔴
            final String qCopy = query;         // 🔴

            /* 개선 ①  독립 트랜잭션 & 중복 안전 처리 */
            Schedulers.boundedElastic().schedule(() ->
                    txTemplate.executeWithoutResult(txStatus -> {
                        try {
                            memorySvc.reinforceWithSnippet(
                                    String.valueOf(sid),    // 🔴
                                    qCopy,                  // 🔴
                                    snip,          // 🔴 변경
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
            recentSnippetCache.put(DigestUtils.md5Hex(snip), Boolean.TRUE);  // 🔴
        }
    }

    // 선언형/접두어 제거(검색어: …, …입니다)
    private static String normalizeDeclaratives(String q) {
        if (q == null) return "";
        String s = q.replaceFirst("^\\s*검색어\\s*:\\s*", "");
        s = s.replace("입니다", "");
        // 문장 끝의 명령형 군더더기 제거: "싹다/전부/모두 찾고와|찾아와|검색해와"
        s = s.replaceAll("\\s*(싹다|전부|모두)?\\s*(찾[아고]와|찾아와|검색해와)\\.?\\s*$", "");
        return s.trim();
    }

    /** 검색 과정 패널을 만들기 위한 간단한 HTML 생성기 */
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
    private record NaverResponse(List<NaverItem> items) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record NaverItem(String title, String link, String description) {}


    @Autowired
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
     * Reinforce the assistant's response into translation memory so that it can be
     * retrieved in future context. The score is computed from the cosine similarity
     * between the original query and the assistant's answer. If computation fails,
     * the score defaults to 1.0.
     *
     * @param query  the user query that produced the assistant response
     * @param answer the assistant's final answer text
     */
    public void reinforceAssistantResponse(String query, String answer) {

        /* 옵션이 꺼져 있으면 바로 종료 → {스터프2} 장점 */
        if (!enableAssistantReinforcement || isBlank(answer) || isBlank(query)) return;
        // ▲ "정보 없음"은 저장 금지
        if ("정보 없음".equals(answer.trim()) || "정보 없음.".equals(answer.trim())) return;
        Long sessionId = sessionIdProvider.get();
        if (sessionId == null) return;
        double score;           // 원래 점수
        try {
            double sim = relevanceScorer.score(query, answer);
            score = (sim > 0 ? sim : 1.0);
        } catch (Exception ignore) {
            score = 1.0;
        }
        /* 과신을 방지하기 위해 가중치를 감쇠 – 기본 0.4 (설정 가능)
         *  ⚠ score를 다시 대입하면 ‘effectively final’ 조건을 깨므로
         *    감쇠-적용 값을 새로 final 변수로 만들어 준다. */
        final double finalScore = Math.max(0.01, score * assistantReinforceWeight);

        // 어시스턴트 응답을 메모리에 저장하여 후속 RAG 검색에서 사용할 수 있도록 함
        try {
            /* 개선 ②  ASSISTANT 강화도 동일 전략 적용 */
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
}