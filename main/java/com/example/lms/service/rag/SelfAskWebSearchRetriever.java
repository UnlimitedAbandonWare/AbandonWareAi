// src/main/java/com/example/lms/service/rag/SelfAskWebSearchRetriever.java
package com.example.lms.service.rag;

import ai.abandonware.nova.orch.trace.OrchEventEmitter;
import com.example.lms.debug.DebugEventLevel;
import com.example.lms.debug.DebugEventStore;
import com.example.lms.debug.DebugProbeType;
import com.example.lms.prompt.QueryKeywordPromptBuilder;
import com.example.lms.search.provider.WebSearchProvider;
import com.example.lms.search.probe.BranchQualityProbe;
import com.example.lms.search.TraceStore;
import com.example.lms.nova.burst.QueryBurstExpander;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.service.reinforcement.SnippetPruner;
import com.example.lms.service.rag.query.SelfAskRewriteRiskScorer;
import com.example.lms.trace.SafeRedactor;
import org.springframework.beans.factory.annotation.Qualifier;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import java.util.*;
import java.util.regex.Pattern;
import jakarta.annotation.PreDestroy;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;


// SelfAskWebSearchRetriever.java


import com.example.lms.service.rag.pre.QueryContextPreprocessor;      // 🆕 전처리기 클래스 import
import com.example.lms.service.rag.detector.GameDomainDetector;       // + 도메인 감지
import com.example.lms.search.TypoNormalizer;                         // NEW: typo normalizer
import java.util.concurrent.*;                                        // 중복 정리: 한 번만 남김
@Component                          // ➍
@RequiredArgsConstructor            // ➋ 모든 final 필드 주입
public class SelfAskWebSearchRetriever implements ContentRetriever {
    private static final Logger log = LoggerFactory.getLogger(SelfAskWebSearchRetriever.class);
    private static final QueryKeywordPromptBuilder QUERY_KEYWORD_PROMPT_BUILDER = new QueryKeywordPromptBuilder();
    private final WebSearchProvider webSearchProvider;
    @Qualifier("fastChatModel")
    private final ChatModel chatModel;
        private final QueryContextPreprocessor preprocessor;
    private final GameDomainDetector domainDetector; // + GENSHIN 감지용

    // Optional typo normalizer for hygiene. Injected if available.
    @Autowired(required = false)
    private TypoNormalizer typoNormalizer;

    @Autowired(required = false)
    private SelfAskPlanner threeWayPlanner;

    @Autowired(required = false)
    private SnippetPruner snippetPruner;

    @Autowired(required = false)
    private com.example.lms.infra.resilience.NightmareBreaker nightmareBreaker;

    @Autowired(required = false)
    private DebugEventStore debugEventStore;

    @Autowired(required = false)
    private BranchQualityProbe branchQualityProbe;

    @Autowired(required = false)
    private ai.abandonware.nova.orch.anchor.AnchorNarrower anchorNarrower;

    @Autowired(required = false)
    private com.example.lms.service.rag.budget.RetrievalBudgetGovernor retrievalBudgetGovernor;

    @Autowired(required = false)
    private com.example.lms.service.rag.offline.OfflineTextureSnapshotLoader offlineTextureSnapshotLoader;

    private final QueryBurstExpander zero100QueryBurstExpander = new QueryBurstExpander();

    /* 선택적 Tavily 폴백(존재 시에만 사용) */
    @Autowired(required = false)
    @Qualifier("tavilyWebSearchRetriever")
    private ContentRetriever tavily;
    /* ---------- 튜닝 가능한 기본값(프로퍼티 주입) ---------- */
    @Value("${selfask.max-depth:2}")                private int maxDepth;                 // Self-Ask 재귀 깊이
    @Value("${selfask.web-top-k:8}")                private int webTopK;                  // 키워드당 검색 스니펫 수
    @Value("${selfask.overall-top-k:10}")           private int overallTopK;              // 최종 반환 상한
    @Value("${selfask.max-api-calls-per-query:8}")  private int maxApiCallsPerQuery;      // 질의당 최대 호출
    @Value("${selfask.followups-per-level:2}")      private int followupsPerLevel;        // 레벨별 추가 키워드
    @Value("${selfask.first-hit-stop-threshold:3}") private int firstHitStopThreshold;    // 1차 검색이 n개 이상이면 종료
    @Value("${selfask.timeout-seconds:12}")         private int selfAskTimeoutSec;        // 레벨 타임박스(초)
    @Value("${selfask.per-request-timeout-ms:5000}") private int perRequestTimeoutMs; // 개별 검색 타임아웃(ms)
    @Value("${selfask.use-llm-followups:false}")     private boolean useLlmFollowups;  // 하위 키워드 LLM 사용 여부
    @Value("${selfask.use-llm-seeds:false}")         private boolean useLlmSeeds;      // 시드 키워드 LLM 사용 여부
    /**
     * Search I/O executor.
     *
     * <p>Do not use {@code ForkJoinPool.commonPool} for blocking I/O (web search/HTTP).
     */
    @Value("${selfask.enabled:true}")                 private boolean selfAskEnabled;
    @Value("${selfask.three-way.enabled:true}")       private boolean threeWayEnabled;
    @Value("${selfask.lane-top-k:4}")                 private int laneTopK;
    @Value("${selfask.final-top-k:${selfask.overall-top-k:10}}") private int finalTopK;
    @Value("${selfask.logic-dag.enabled:false}")      private boolean logicDagEnabled;
    @Value("${selfask.logic-dag.max-nodes:5}")        private int logicDagMaxNodes;
    @Value("${selfask.logic-dag.prune-duplicates:true}") private boolean logicDagPruneDuplicates;
    @Value("${selfask.risk-rewrite.enabled:true}")    private boolean riskRewriteEnabled;
    @Value("${selfask.risk-rewrite.min-temperature:0.12}") private double riskRewriteMinTemperature;
    @Value("${selfask.risk-rewrite.max-temperature:0.35}") private double riskRewriteMaxTemperature;
    @Value("${selfask.risk-rewrite.emergent.enabled:true}") private boolean riskEmergentEnabled;
    @Value("${selfask.risk-rewrite.emergent.max-risk-delta:0.06}") private double riskEmergentMaxRiskDelta;
    @Value("${selfask.risk-rewrite.emergent.max-temperature-delta:0.03}") private double riskEmergentMaxTemperatureDelta;
    @Value("${selfask.risk-rewrite.emergent.max-lane-weight-delta:0.12}") private double riskEmergentMaxLaneWeightDelta;
    @Value("${selfask.risk-rewrite.emergent.max-search-range-delta:0.08}") private double riskEmergentMaxSearchRangeDelta;
    @Value("${selfask.branch-quality.enabled:true}") private boolean branchQualityEnabled;
    @Value("${selfask.branch-quality.retry.enabled:true}") private boolean branchQualityRetryEnabled;
    @Value("${selfask.branch-quality.retry.max-per-lane:1}") private int branchQualityRetryMaxPerLane;
    @Value("${selfask.branch-quality.min-context-contribution:0.35}") private double branchQualityMinContextContribution;
    @Value("${selfask.branch-quality.max-risk-penalty:0.65}") private double branchQualityMaxRiskPenalty;
    @Value("${selfask.branch-quality.moe-temperature:0.65}") private double branchQualityMoeTemperature;
    @Value("${rag.diversity.lambda:0.7}") private double branchQualityBaseDppLambda;
    @Value("${selfask.branch-quality.dpp.min-lambda:0.35}") private double branchQualityDppMinLambda;
    @Value("${selfask.branch-quality.dpp.max-lambda:0.85}") private double branchQualityDppMaxLambda;
    @Value("${rag.anchor.enabled:true}") private boolean ragAnchorEnabled;
    @Value("${rag.anchor.max-anchors:3}") private int ragAnchorMaxAnchors;
    @Value("${rag.anchor.max-drift-score:0.65}") private double ragAnchorMaxDriftScore;

    @Autowired
    @Qualifier("searchIoExecutor")
    private ExecutorService searchExecutor;
    /** 간이 복잡도 추정 → Self-Ask 깊이(1..3) */
    private int estimateDepthByComplexity(String q) {
        if (!StringUtils.hasText(q)) return 1;
        int len = q.codePointCount(0, q.length());
        long spaces = q.chars().filter(ch -> ch == ' ').count();
        // vs (대소문자 무관) 도 비교/차이 질문의 한 형태이므로 패턴에 포함한다.
        boolean hasWh = q.matches(".*(?i)(누가|언제|어디|무엇|왜|어떻게|비교|차이|원리|vs).*");
        int score = 0;
        if (len > 30) score++;
        if (spaces > 6) score++;
        if (hasWh) score++;
        return Math.min(3, Math.max(1, score)); // 1..3
    }
    /**
     * 편의 생성자(Bean 기본형)
     */
      /* ➎ Lombok이 생성자를 자동 생성하므로
       명시적 생성자 블록을 전부 삭제합니다. */

    /* ───────────── 휴리스틱 키워드 규칙 ───────────── */
    private static final Set<String> STOPWORDS = Set.of(
            "그리고", "또는", "그러나", "하지만", "에서", "으로", "에게", "대한", "관련",
            "무엇", "어떻게", "알려줘", "정리", "설명", "해주세요", "해주세요."
    );

    /* ───────────── 정규화 유틸 ───────────── */
    private static final Pattern LEADING_TRAILING_PUNCT =
            Pattern.compile("^[\\p{Punct}\\s]+|[\\p{Punct}\\s]+$");
    private static final Pattern URL_PATTERN =
            Pattern.compile("https?://[^\\s\\]\\)\"']+");

    private record LaneSeed(String lane, String query, double weight) {
    }

    private record SearchAttempt(List<String> results, String failureClass, boolean fallback) {
        private SearchAttempt {
            results = results == null ? List.of() : List.copyOf(results);
            String safeFailure = normalizedFailureLabel(failureClass);
            failureClass = StringUtils.hasText(safeFailure)
                    ? safeFailure
                    : (results.isEmpty() ? "zero-results" : "none");
        }
    }

    private record SearchAttemptMeta(String lane, String query, double weight, int requestedTopK, long laneTimeboxMs) {
    }

    private record LaneBudget(int initial, int remaining) {
        LaneBudget consume() {
            return new LaneBudget(initial, Math.max(0, remaining - 1));
        }

        LaneBudget moveOut() {
            return new LaneBudget(initial, Math.max(0, remaining - 1));
        }

        LaneBudget receiveOne() {
            return new LaneBudget(initial, Math.max(0, remaining) + 1);
        }
    }

    static final class Zero100RolloverState {
        private String preferredLane;
        private String cooledLane;
        private int movedCalls;

        Zero100RolloverState(String activeLane) {
            this.preferredLane = normalizeLane(activeLane);
            this.cooledLane = "";
            this.movedCalls = 0;
        }

        String preferredLane() {
            return preferredLane;
        }

        String cooledLane() {
            return cooledLane;
        }

        int movedCalls() {
            return movedCalls;
        }

        boolean apply(String lane, String failureClass) {
            if (!isZero100RolloverFailure(failureClass)) {
                return false;
            }
            String current = normalizeLane(lane);
            this.cooledLane = current;
            this.preferredLane = nextZero100Lane(current);
            this.movedCalls = Math.min(32, this.movedCalls + 1);
            return true;
        }
    }

    /**
     * Canonicalize keyword by removing whitespace and lowercasing for duplicate detection.
     */
    private static String canonicalKeyword(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private static String normalize(String raw) {
        if (!StringUtils.hasText(raw)) return "";
        String s = LEADING_TRAILING_PUNCT.matcher(raw).replaceAll("")   // 앞뒤 특수문자
                .replace("\"", "")                                      // 따옴표
                .replace("?", "")                                       // 물음표
                .replaceAll("\\s{2,}", " ")                             // 다중 공백
                .trim();
        // 선언형/접두 제거
        s = s.replaceFirst("^검색어\\s*:\\s*", "");
        s = s.replace("입니다", "");
        return s;
    }

    /* ───────────── ContentRetriever 구현 ───────────── */
    @Override
    public List<Content> retrieve(Query query) {
        // 입력 검증


        String qText = (query != null) ? query.text() : null;
        java.util.Map<String, Object> meta = new java.util.HashMap<>(toMetaMap(query));
        meta.putIfAbsent("purpose", "WEB_SEARCH");
        boolean explicitPlanSelfAskOverride = explicitPlanSelfAskOverride(meta);
        if (!selfAskEnabled && !explicitPlanSelfAskOverride) {
            TraceStore.put("selfask.disabled.reason", "global-disabled-no-plan-override");
            TraceStore.put("selfask.planOverride.enabled", false);
            log.debug("[SelfAsk] disabled by config without explicit plan override");
            return java.util.List.of();
        }
        if (explicitPlanSelfAskOverride) {
            TraceStore.put("selfask.planOverride.enabled", true);
            TraceStore.put("selfask.planOverride.reason",
                    SafeRedactor.traceLabelOrFallback(
                            meta.getOrDefault("selfask.planOverride.reason", "selfask.enabled"),
                            "selfask.enabled"));
        }
        // Apply typo normalization if configured
        if (typoNormalizer != null && qText != null) {
            qText = typoNormalizer.normalize(qText);
        }
        // ① Guardrail: 오타 교정/금칙어/중복 정리 (중복 호출 제거 + NPE 가드)
        qText = (preprocessor != null) ? preprocessor.enrich(qText, meta) : qText;
        if (!StringUtils.hasText(qText)) {
            log.debug("[SelfAsk] empty query -> []");
            return List.of();
        }

        int reqWebTopK = metaInt(meta, "webTopK", this.webTopK);
        long webBudgetMs = metaLong(meta, "webBudgetMs", -1L);
        if (webBudgetMs <= 0L) {
            webBudgetMs = traceLong("zero100.web.timeboxMs", -1L);
        }
        double rewriteTemperature = metaDouble(meta, "resource.rewriteTemperatureWeighted",
                metaDouble(meta, "resource.rewriteTemperature", 0.2d));
        rewriteTemperature = creativeRequestedRewriteTemperature(rewriteTemperature);
        boolean allowWeb = metaBool(meta, "allowWeb", true);
        if (!allowWeb) {
            return java.util.List.of();
        }
        boolean enableSelfAskHint = metaBool(meta, "enableSelfAsk", true);
        boolean nightmareMode = metaBool(meta, "nightmareMode", false);
        boolean auxLlmDown = metaBool(meta, "auxLlmDown", false);
        boolean cheapSearchMode = isCheapSearchModeActive(meta);

        int reqPerRequestTimeoutMs = this.perRequestTimeoutMs;
        int reqSelfAskTimeoutSec = this.selfAskTimeoutSec;
        if (webBudgetMs > 0) {
            reqPerRequestTimeoutMs = (int) Math.min((long) reqPerRequestTimeoutMs, Math.max(300L, webBudgetMs));
            reqSelfAskTimeoutSec = (int) Math.min((long) reqSelfAskTimeoutSec, Math.max(1L, (webBudgetMs + 999L) / 1000L));
        }

        /* 1) 빠른 1차 검색 */
        java.util.List<String> firstSnippets = safeSearch(qText, reqWebTopK);
        if (Thread.currentThread().isInterrupted()) {
            return List.of();
        }
        SelfAskRewriteRiskScorer.Score rewriteRisk = refreshRewriteRisk(
                qText, meta, firstSnippets.size(), Math.max(1, reqWebTopK), rewriteTemperature);
        rewriteTemperature = rewriteRisk.rewriteTemperatureWeighted();

        if (cheapSearchMode && !explicitPlanSelfAskOverride) {
            traceCheapSearchModeSelfAskSkip(qText, firstSnippets.size(), reqWebTopK);
            if (firstSnippets.isEmpty()) {
                return java.util.List.of();
            }
            return toSelfAskContents(firstSnippets, qText, "direct", qText,
                    Math.max(1, Math.min(overallTopK, reqWebTopK)));
        }

        // 질의 복잡도 간단 판정
        boolean enableSelfAsk = qText.length() > 25
                || qText.chars().filter(ch -> ch == ' ').count() > 3
                // '비교', '차이' 또는 ' vs '가 포함되면 Self-Ask가 필요하다.
                || qText.contains("비교")
                || qText.contains("차이")
                || qText.toLowerCase(Locale.ROOT).contains(" vs ");
        if (explicitPlanSelfAskOverride && enableSelfAskHint && !nightmareMode) {
            enableSelfAsk = true;
        }
        if (!enableSelfAskHint || nightmareMode) {
            enableSelfAsk = false;
        }
        final boolean useLlmSeedsHere = this.useLlmSeeds && enableSelfAskHint && !nightmareMode && !auxLlmDown;
        final boolean useLlmFollowupsHere = this.useLlmFollowups && enableSelfAskHint && !nightmareMode && !auxLlmDown;

        // 질의 복잡도 기반 동적 깊이(1..maxDepth)
        final int depthLimit = Math.max(1, Math.min(maxDepth, estimateDepthByComplexity(qText)));

        /* 1-B) Self-Ask 조기 종료 결정 (품질 평가는 LLM 키워드 확장에서 수행) */
        if (!enableSelfAsk) {
            if (firstSnippets.isEmpty()) return List.of();
            // 단순 질의면서 1차에서 충분히 많이 맞으면(=조기 종료)
            if (firstSnippets.size() >= firstHitStopThreshold) {
                return toSelfAskContents(firstSnippets, qText, "direct", qText, overallTopK);
            }
            // 아니면 얕게 한 번만 확장
            if (depthLimit <= 1) {
                return toSelfAskContents(firstSnippets, qText, "direct", qText, overallTopK);
            }
        }

        // 2) 휴리스틱 키워드 시드 구성 → BFS 확장
        java.util.List<LaneSeed> branchSeeds = branch3Seeds(qText, webBudgetMs,
                enableSelfAsk && enableSelfAskHint && !nightmareMode && !auxLlmDown,
                rewriteTemperature,
                rewriteRisk.laneWeights());
        java.util.LinkedHashSet<String> seedSet = new java.util.LinkedHashSet<>();
        java.util.Map<String, String> seedLaneByCanon = new java.util.HashMap<>();
        java.util.Map<String, String> seedQueryByCanon = new java.util.HashMap<>();
        java.util.Map<String, Double> seedWeightByCanon = new java.util.HashMap<>();
        for (LaneSeed seed : branchSeeds) {
            String norm = normalize(seed.query());
            if (!StringUtils.hasText(norm)) continue;
            seedSet.add(norm);
            String canon = canonicalKeyword(norm);
            seedLaneByCanon.putIfAbsent(canon, seed.lane());
            seedQueryByCanon.putIfAbsent(canon, norm);
            seedWeightByCanon.putIfAbsent(canon, seed.weight() * zero100LaneWeight(seed.lane()));
        }
        for (String seed : basicKeywords(qText, useLlmSeedsHere)) {
            String norm = normalize(seed);
            if (!StringUtils.hasText(norm)) continue;
            seedSet.add(norm);
            String canon = canonicalKeyword(norm);
            seedLaneByCanon.putIfAbsent(canon, "bfs");
            seedQueryByCanon.putIfAbsent(canon, norm);
        }
        applyZero100QueryBurst(qText, branchSeeds, seedSet, seedLaneByCanon, seedQueryByCanon, seedWeightByCanon);
        Zero100RolloverState zero100RolloverState = new Zero100RolloverState(traceString("zero100.activeLane", ""));
        java.util.List<String> seeds = orderZero100Keywords(
                new java.util.ArrayList<>(seedSet),
                seedLaneByCanon,
                zero100RolloverState.preferredLane(),
                zero100RolloverState.cooledLane());
        try {
            TraceStore.put("selfask.branch3.seedCount", branchSeeds.size());
            TraceStore.put("selfask.branch3.seeds", branchSeeds.stream()
                    .map(seed -> java.util.Map.of(
                            "lane", seed.lane(),
                            "seedHash12", SafeRedactor.hash12(seed.query()),
                            "weight", seed.weight()))
                    .toList());
        } catch (Exception ignore) {
            traceSuppressed("branch3.seedTrace", ignore);
        }
        traceRequerySummary(branchSeeds, seeds.size(), rewriteRisk);
        log.debug("[SelfAsk][branch3] enabled={}, lanes={}, totalSeeds={}",
                threeWayEnabled, branchSeeds.size(), seeds.size());

        // Seed queue with canonical uniqueness to avoid duplicate/synonym searches
        Deque<String> queue = new ArrayDeque<>();
        Set<String> visitedCanon = new HashSet<>();
        for (String s : seeds) {
            String norm = normalize(s);
            if (StringUtils.hasText(norm)) {
                String canon = canonicalKeyword(norm);
                if (visitedCanon.add(canon)) {
                    queue.add(norm);
                }
            }
        }


        // 3) BFS(Self-Ask) + 네이버 검색
        LinkedHashSet<String> snippets = new LinkedHashSet<>(firstSnippets);
        java.util.Map<String, String> snippetLane = new java.util.LinkedHashMap<>();
        java.util.Map<String, String> snippetQuery = new java.util.LinkedHashMap<>();
        for (String s : firstSnippets) {
            snippetLane.put(s, "direct");
            snippetQuery.put(s, qText);
        }
        int depth = 0;
        java.util.Map<String, Integer> branchRetryCounts = new java.util.HashMap<>();
        SelfAskSearchBudget budget = new SelfAskSearchBudget(maxApiCallsPerQuery); // ✅ 호출 상한 제어
        java.util.Map<String, LaneBudget> laneBudgets = zero100LaneBudgets(maxApiCallsPerQuery);

        while (!queue.isEmpty() && snippets.size() < overallTopK && depth < depthLimit) {
            int levelSize = queue.size();
            List<String> currentKeywords = new ArrayList<>();
            while (levelSize-- > 0) {
                String kw = normalize(queue.poll());
                if (StringUtils.hasText(kw)) currentKeywords.add(kw);
            }
            currentKeywords = orderZero100Keywords(currentKeywords, seedLaneByCanon,
                    zero100RolloverState.preferredLane(), zero100RolloverState.cooledLane());


            // 해당 depth의 키워드들을 병렬 검색 (상한 적용)
                List<Future<SearchAttempt>> futures = new ArrayList<>();
            List<SearchAttemptMeta> futureMeta = new ArrayList<>();
            for (String kw : currentKeywords) {
                String kwCanonForLane = canonicalKeyword(kw);
                String laneForKw = seedLaneByCanon.getOrDefault(kwCanonForLane, "bfs");
                double laneWeightForKw = seedWeightByCanon.getOrDefault(kwCanonForLane, 1.0d);
                int topKForKw = ("BQ".equals(laneForKw) || "ER".equals(laneForKw) || "RC".equals(laneForKw))
                        ? Math.max(1, Math.min(Math.max(reqWebTopK, laneTopK),
                                (int) Math.ceil(laneTopK * laneWeightForKw)))
                        : reqWebTopK;
                if (traceBool("zero100.enabled", false) && isThreeWayLane(laneForKw)) {
                    LaneBudget laneBudget = laneBudgets.getOrDefault(laneForKw, new LaneBudget(1, 1));
                    if (laneBudget.remaining() <= 0) {
                        traceRequeryAttempt(laneForKw, kw, laneWeightForKw, rewriteTemperature, 0L,
                                topKForKw, 0, 0, true, "skipped:lane_budget_exhausted", null, false);
                        traceZero100LaneBudget(laneForKw, laneBudget);
                        continue;
                    }
                }
                if (!budget.tryConsume()) break; // ✅ 상한
                if (traceBool("zero100.enabled", false) && isThreeWayLane(laneForKw)) {
                    LaneBudget consumed = laneBudgets.getOrDefault(laneForKw, new LaneBudget(1, 1)).consume();
                    laneBudgets.put(laneForKw, consumed);
                    traceZero100LaneBudget(laneForKw, consumed);
                }
                log.debug("[SelfAsk][d{}] queryHash={}", depth, SafeRedactor.hash12(kw));
                log.debug("[SelfAsk][d{}] lane={}, qHash={}",
                        depth, laneForKw, SafeRedactor.hash12(kw));
                Future<SearchAttempt> f = submitSearchAttempt(kw, topKForKw);
                futures.add(f);
                futureMeta.add(new SearchAttemptMeta(laneForKw, kw, laneWeightForKw, topKForKw,
                        zero100LaneTimeboxMs(laneForKw, reqPerRequestTimeoutMs)));

            }

            // Level-wide budget for this depth.
            // Important: we do NOT rely on CompletableFuture.orTimeout(), because it only times out the
            // future result and may leave the underlying work running ("zombie" tasks).
            final long levelDeadlineMs = System.currentTimeMillis() + java.util.concurrent.TimeUnit.SECONDS.toMillis(reqSelfAskTimeoutSec);

            // 결과 병합 및 다음 레벨 키워드 생성
            for (int i = 0; i < futures.size(); i++) {
                SearchAttemptMeta attemptMeta = i < futureMeta.size()
                        ? futureMeta.get(i)
                        : new SearchAttemptMeta("bfs", "", 1.0d, reqWebTopK, reqPerRequestTimeoutMs);
                String kw = attemptMeta.query();
                long waitMs = Math.min(reqPerRequestTimeoutMs,
                        Math.max(0L, levelDeadlineMs - System.currentTimeMillis()));
                waitMs = Math.min(waitMs, attemptMeta.laneTimeboxMs());
                SearchAttempt attempt = getWithHardTimeout(
                        futures.get(i),
                        waitMs,
                        kw
                );
                if (Thread.currentThread().isInterrupted()) {
                    cancelSearchAttempts(futures);
                    return interruptedPartialContents(snippets, snippetLane, snippetQuery, qText);
                }
                List<String> results = attempt.results();
                String kwCanon = canonicalKeyword(kw);
                String lane = seedLaneByCanon.getOrDefault(kwCanon, attemptMeta.lane());
                String branchQuery = seedQueryByCanon.getOrDefault(kwCanon, kw);
                int beforeSize = snippets.size();
                for (String result : results) {
                    if (snippets.add(result)) {
                        snippetLane.put(result, lane);
                        snippetQuery.put(result, branchQuery);
                    }
                }
                int afterFilterCount = Math.max(0, snippets.size() - beforeSize);
                BranchQualityProbe.BranchQualityMetrics branchMetric = branchQualityAttempt(
                        lane,
                        attemptMeta.requestedTopK(),
                        results.size(),
                        afterFilterCount,
                        attemptMeta.weight(),
                        rewriteTemperature,
                        attempt.fallback(),
                        attempt.failureClass());
                traceRequeryAttempt(lane, branchQuery, attemptMeta.weight(), rewriteTemperature, waitMs,
                        attemptMeta.requestedTopK(), results.size(), afterFilterCount,
                        attempt.fallback(), attempt.failureClass(), branchMetric, false);
                if (traceZero100Rollover(zero100RolloverState, lane, attempt.failureClass(), budget.remaining(), depth)) {
                    moveOneLaneBudget(laneBudgets, lane, zero100RolloverState.preferredLane(), attempt.failureClass(), budget.remaining());
                }
                if (shouldRetryBranch(lane, branchMetric, branchRetryCounts)) {
                    String retryQuery = regenerateBranchQuery(qText, lane, branchQuery, waitMs, branchMetric);
                    int retryTopK = Math.max(1, Math.min(attemptMeta.requestedTopK(), branchMetric.adjustedTopK()));
                    String skipReason = retrySkipReason(qText, branchQuery, retryQuery, visitedCanon);
                    if (skipReason != null) {
                        traceRequeryAttempt(lane, retryQuery, attemptMeta.weight(), branchMetric.adjustedTemperature(), waitMs,
                                retryTopK, 0, 0, true, "skipped:" + skipReason, branchMetric, true);
                        traceZero100Rollover(zero100RolloverState, lane, "skipped:" + skipReason, budget.remaining(), depth);
                    } else if (!budget.tryConsume()) {
                        traceRequeryAttempt(lane, retryQuery, attemptMeta.weight(), branchMetric.adjustedTemperature(), waitMs,
                                retryTopK, 0, 0, true, "skipped:budget_exhausted", branchMetric, true);
                        traceZero100Rollover(zero100RolloverState, lane, "skipped:budget_exhausted", budget.remaining(), depth);
                    } else {
                        branchRetryCounts.merge(lane, 1, Integer::sum);
                        String retryCanon = canonicalKeyword(retryQuery);
                        if (StringUtils.hasText(retryCanon)) {
                            visitedCanon.add(retryCanon);
                        }
                        Future<SearchAttempt> retryFuture = submitSearchAttempt(retryQuery, retryTopK);
                        long retryWaitMs = zero100LaneTimeboxMs(lane, waitMs);
                        SearchAttempt retryAttempt = getWithHardTimeout(retryFuture, retryWaitMs, retryQuery);
                        if (Thread.currentThread().isInterrupted()) {
                            cancelSearchAttempt(retryFuture);
                            cancelSearchAttempts(futures);
                            return interruptedPartialContents(snippets, snippetLane, snippetQuery, qText);
                        }
                        int retryBeforeSize = snippets.size();
                        for (String result : retryAttempt.results()) {
                            if (snippets.add(result)) {
                                snippetLane.put(result, lane);
                                snippetQuery.put(result, retryQuery);
                            }
                        }
                        int retryAfterFilterCount = Math.max(0, snippets.size() - retryBeforeSize);
                        BranchQualityProbe.BranchQualityMetrics retryMetric = branchQualityAttempt(
                                lane,
                                retryTopK,
                                retryAttempt.results().size(),
                                retryAfterFilterCount,
                                attemptMeta.weight(),
                                branchMetric.adjustedTemperature(),
                                retryAttempt.fallback(),
                                retryAttempt.failureClass());
                        traceRequeryAttempt(lane, retryQuery, attemptMeta.weight(), branchMetric.adjustedTemperature(), retryWaitMs,
                                retryTopK, retryAttempt.results().size(), retryAfterFilterCount,
                                retryAttempt.fallback(), retryAttempt.failureClass(), retryMetric, true);
                        if (traceZero100Rollover(zero100RolloverState, lane, retryAttempt.failureClass(), budget.remaining(), depth)) {
                            moveOneLaneBudget(laneBudgets, lane, zero100RolloverState.preferredLane(),
                                    retryAttempt.failureClass(), budget.remaining());
                        }
                    }
                }

                if (depth + 1 <= maxDepth && snippets.size() < overallTopK) {
                    int used = 0;
                    // LLM 호출 최소화: 기본은 휴리스틱, 필요 시에만 LLM
                    java.util.List<String> children = useLlmFollowupsHere
                            ? followUpKeywords(kw)
                            : heuristicFollowups(kw);
                    for (String child : children) {
                        if (used >= followupsPerLevel) break;  // per-level 제한
                        String norm = normalize(child);
                        String canon = canonicalKeyword(norm);
                        if (StringUtils.hasText(norm) && visitedCanon.add(canon)) {
                            queue.add(norm);
                            seedLaneByCanon.putIfAbsent(canon, lane);
                            seedQueryByCanon.putIfAbsent(canon, norm);
                            used++;
                        }
                    }
                }
            }

            // Cancel any straggling tasks once this depth budget is exhausted.
            cancelSearchAttempts(futures);
            depth++;
        }

        // 3-B) 결과 부족 시 Tavily로 보강
        if (snippets.size() < overallTopK && tavily != null) {
            try {
                int need = Math.max(0, overallTopK - snippets.size());
                // [HARDENING] Always propagate existing query metadata (e.g. session sid) when
                // constructing new Query objects for the Tavily fallback.  This ensures that
                // downstream retrievers enforce per-session isolation and do not pollute
                // transient or public namespaces.  When the original query has no metadata
                // attached, the builder will accept a null and Tavily will treat it as
                // __PRIVATE__ internally.  Avoid the deprecated Query.from API.
                // [HARDENING] use builder API to propagate metadata and avoid deprecated Query.from
                Query fallbackQuery = QueryUtils.rebuild(query, qText);
                final String parentQText = qText;
                tavily.retrieve(fallbackQuery).stream()
                        .map(Content::toString)
                        .filter(StringUtils::hasText)
                        .limit(need)
                        .forEach(s -> {
                            if (snippets.add(s)) {
                                snippetLane.put(s, "tavily");
                                snippetQuery.put(s, parentQText);
                            }
                        });
            } catch (Exception e) {
                log.debug("[SelfAsk] Tavily fallback skipped errorHash={} errorLength={}", SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length());
            }
        }
// 4) Content 변환(비어있을 경우 안전 폴백)
        if (snippets.isEmpty()) {
            if (!firstSnippets.isEmpty()) {
                return toSelfAskContents(firstSnippets, qText, "direct", qText,
                        Math.max(1, Math.min(overallTopK, reqWebTopK)));
            }
            // No snippets found at all. Return an empty list instead of a placeholder to avoid polluting the vector store.
            return java.util.List.of();
        }
        java.util.List<Content> out = toSelfAskContents(snippets, snippetLane, snippetQuery, qText,
                Math.max(1, finalTopK > 0 ? finalTopK : overallTopK));
        TraceStore.put("rag.selfask.count", out.size());
        return out;
    }



    private java.util.List<LaneSeed> branch3Seeds(String question, long budgetMs, boolean allowed,
            double rewriteTemperature, java.util.Map<String, Double> laneWeights) {
        if (!allowed || !threeWayEnabled || threeWayPlanner == null || !StringUtils.hasText(question)) {
            return java.util.List.of();
        }
        try {
            long timeoutMs = budgetMs > 0 ? Math.max(250L, Math.min(1500L, budgetMs)) : 1000L;
            java.util.LinkedHashMap<String, LaneSeed> out = new java.util.LinkedHashMap<>();
            for (SelfAskPlanner.SubQuestion sq : threeWayPlanner.generateThreeLanes(
                    question, timeoutMs, rewriteTemperature, laneWeights)) {
                if (sq == null || sq.type == null || !StringUtils.hasText(sq.text)) continue;
                String norm = normalize(sq.text);
                if (!StringUtils.hasText(norm)) continue;
                out.putIfAbsent(canonicalKeyword(norm),
                        new LaneSeed(sq.type.name(), norm, laneWeight(sq.type.name(), laneWeights)));
                if (out.size() >= 3) break;
            }
            return applyLogicDag(question, new java.util.ArrayList<>(out.values()));
        } catch (Exception e) {
            log.debug("[SelfAsk][branch3] fallback errorHash={} errorLength={}", SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length());
            return java.util.List.of();
        }
    }

    private java.util.List<LaneSeed> applyLogicDag(String question, java.util.List<LaneSeed> seeds) {
        java.util.List<LaneSeed> safeSeeds = seeds == null ? java.util.List.of() : seeds;
        if (!logicDagEnabled) {
            traceLogicDag(java.util.Map.of(
                    "selfask.logicDag.enabled", false,
                    "selfask.logicDag.failureClass", "disabled"));
            return safeSeeds;
        }
        LogicRagPlanner.Result planned = logicRagPlanner().plan(
                question,
                safeSeeds.stream()
                        .map(seed -> new LogicRagPlanner.Seed(seed.lane(), seed.query(), seed.weight()))
                        .toList(),
                logicDagMaxNodes,
                logicDagPruneDuplicates);
        traceLogicDag(planned.trace());
        if (!"none".equals(planned.failureClass())) {
            return safeSeeds;
        }
        return planned.seeds().stream()
                .map(seed -> new LaneSeed(seed.lane(), seed.query(), seed.weight()))
                .toList();
    }

    LogicRagPlanner logicRagPlanner() {
        return new LogicRagPlanner();
    }

    private static void traceLogicDag(java.util.Map<String, Object> trace) {
        if (trace == null || trace.isEmpty()) {
            return;
        }
        for (java.util.Map.Entry<String, Object> entry : trace.entrySet()) {
            if (entry.getKey() != null && entry.getKey().startsWith("selfask.logicDag.")) {
                TraceStore.put(entry.getKey(), entry.getValue());
            }
        }
    }

    private SelfAskRewriteRiskScorer.Score refreshRewriteRisk(
            String question,
            java.util.Map<String, Object> meta,
            int observedEvidenceCount,
            int targetEvidenceK,
            double baseRewriteTemperature) {
        boolean creative = creativeProfileActive();
        double effectiveRiskMax = creative
                ? Math.max(0.0d, Math.min(1.0d, baseRewriteTemperature))
                : riskRewriteMaxTemperature;
        double effectiveRiskMin = creative
                ? Math.min(Math.max(0.0d, riskRewriteMinTemperature), effectiveRiskMax)
                : riskRewriteMinTemperature;
        SelfAskRewriteRiskScorer.Score risk = SelfAskRewriteRiskScorer.score(
                question,
                meta,
                TraceStore.getAll(),
                observedEvidenceCount,
                targetEvidenceK,
                baseRewriteTemperature,
                effectiveRiskMin,
                effectiveRiskMax,
                riskRewriteEnabled,
                riskEmergentConfig());
        if (creative) {
            GuardContext context = GuardContextHolder.get();
            if (context != null) {
                double effective = risk.rewriteTemperatureWeighted();
                context.putPlanOverride("creative.emergence.selfAsk.effectiveTemperature", effective);
                String effectiveHash = SafeRedactor.hashValue(String.format(Locale.ROOT,
                        "%s|selfAsk|%.2f",
                        context.getPlanOverride("creative.emergence.profile"), effective));
                context.putPlanOverride("creative.emergence.selfAsk.effectiveOptionsHash", effectiveHash);
                TraceStore.put("creative.emergence.selfAsk.effectiveOptionsHash", effectiveHash);
                double requested = creativeRequestedRewriteTemperature(baseRewriteTemperature);
                if (effective + 0.000_001d < requested) {
                    context.putPlanOverride("creative.emergence.suppressedReason", "selfask-risk-cap");
                    TraceStore.put("creative.emergence.suppressedReason", "selfask-risk-cap");
                }
            }
        }
        try {
            meta.put("resource.rewriteRiskScore", risk.rewriteRiskScore());
            meta.put("resource.rewriteRiskAccumulated", risk.accumulatedRiskScore());
            meta.put("resource.rewriteRiskBand", risk.rewriteRiskBand());
            meta.put("resource.rewriteRiskPrimaryFactor", risk.primaryFactor());
            meta.put("resource.rewriteTemperatureWeighted", risk.rewriteTemperatureWeighted());
            meta.put("resource.rewriteValidationTemperature", risk.validationTemperature());
            meta.put("resource.rewriteExplorationTemperature", risk.explorationTemperature());
            meta.put("selfask.rewrite.policy", risk.policy());
            meta.put("selfask.rewrite.weights", risk.laneWeights());
            meta.put("selfask.rewrite.honestyStatus", risk.rewriteHonestyStatus());
            meta.put("selfask.rewrite.overreachType", risk.rewriteOverreachType());
            meta.put("selfask.rewrite.overreachScore", risk.rewriteOverreachScore());
            meta.put("selfask.rewrite.sourceHash", risk.rewriteSourceHash12());
            String safeEmergentReason = SafeRedactor.traceLabelOrFallback(risk.emergentAdjustment().reason(), "unknown");
            meta.put("resource.riskEmergentDelta", risk.emergentAdjustment().riskDelta());
            meta.put("resource.riskEmergentReason", safeEmergentReason);
            meta.put("resource.riskEmergentSearchRangeDelta", risk.emergentAdjustment().searchRangeDelta());
            TraceStore.put("resource.rewriteRiskScore", String.valueOf(risk.rewriteRiskScore()));
            TraceStore.put("resource.rewriteRiskAccumulated", String.valueOf(risk.accumulatedRiskScore()));
            TraceStore.put("resource.rewriteRiskBand", risk.rewriteRiskBand());
            TraceStore.put("resource.rewriteRiskPrimaryFactor", risk.primaryFactor());
            TraceStore.put("resource.rewriteTemperatureWeighted", String.valueOf(risk.rewriteTemperatureWeighted()));
            TraceStore.put("resource.rewriteValidationTemperature", String.valueOf(risk.validationTemperature()));
            TraceStore.put("resource.rewriteExplorationTemperature", String.valueOf(risk.explorationTemperature()));
            TraceStore.put("selfask.rewrite.policy", risk.policy());
            TraceStore.put("selfask.3way.weights", risk.laneWeights());
            TraceStore.put("selfask.rewrite.honestyStatus", risk.rewriteHonestyStatus());
            TraceStore.put("selfask.rewrite.overreachType", risk.rewriteOverreachType());
            TraceStore.put("selfask.rewrite.overreachScore", String.valueOf(risk.rewriteOverreachScore()));
            TraceStore.put("selfask.rewrite.sourceHash", risk.rewriteSourceHash12());
            TraceStore.put("ml.risk.rewrite.score", String.valueOf(risk.rewriteRiskScore()));
            TraceStore.put("ml.risk.rewrite.currentScore", String.valueOf(risk.currentRiskScore()));
            TraceStore.put("ml.risk.rewrite.accumulatedScore", String.valueOf(risk.accumulatedRiskScore()));
            TraceStore.put("ml.risk.rewrite.band", risk.rewriteRiskBand());
            TraceStore.put("ml.risk.rewrite.pre", String.valueOf(risk.preRisk()));
            TraceStore.put("ml.risk.rewrite.evidence", String.valueOf(risk.evidenceRisk()));
            TraceStore.put("ml.risk.rewrite.components", risk.components());
            TraceStore.put("ml.risk.rewrite.temperature", String.valueOf(risk.rewriteTemperatureWeighted()));
            TraceStore.put("ml.risk.rewrite.validationTemperature", String.valueOf(risk.validationTemperature()));
            TraceStore.put("ml.risk.rewrite.explorationTemperature", String.valueOf(risk.explorationTemperature()));
            TraceStore.put("ml.risk.rewrite.policy", risk.policy());
            TraceStore.put("ml.risk.rewrite.primaryFactor", risk.primaryFactor());
            TraceStore.put("ml.risk.rewrite.honestyStatus", risk.rewriteHonestyStatus());
            TraceStore.put("ml.risk.rewrite.overreachType", risk.rewriteOverreachType());
            TraceStore.put("ml.risk.rewrite.overreachScore", String.valueOf(risk.rewriteOverreachScore()));
            TraceStore.put("ml.risk.rewrite.sourceHash", risk.rewriteSourceHash12());
            TraceStore.put("resource.riskEmergentDelta", String.valueOf(risk.emergentAdjustment().riskDelta()));
            TraceStore.put("resource.riskEmergentReason", safeEmergentReason);
            TraceStore.put("resource.riskEmergentSearchRangeDelta",
                    String.valueOf(risk.emergentAdjustment().searchRangeDelta()));
            TraceStore.put("ml.risk.emergent.policy", risk.emergentAdjustment().policy());
            TraceStore.put("ml.risk.emergent.riskDelta", String.valueOf(risk.emergentAdjustment().riskDelta()));
            TraceStore.put("ml.risk.emergent.temperatureDelta",
                    String.valueOf(risk.emergentAdjustment().temperatureDelta()));
            TraceStore.put("ml.risk.emergent.searchRangeDelta",
                    String.valueOf(risk.emergentAdjustment().searchRangeDelta()));
            TraceStore.put("ml.risk.emergent.reason", safeEmergentReason);
            TraceStore.put("ml.risk.emergent.components", risk.emergentAdjustment().components());
            TraceStore.put("ml.risk.emergent.laneDeltas", risk.emergentAdjustment().laneWeightDeltas());
            java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
            data.put("seq", TraceStore.nextSequence("ml.risk.rewrite"));
            data.put("phase", "refresh");
            data.put("queryHash12", SafeRedactor.hash12(question));
            data.put("queryLen", question == null ? 0 : question.length());
            data.put("score", risk.currentRiskScore());
            data.put("accumulatedScore", risk.accumulatedRiskScore());
            data.put("band", risk.rewriteRiskBand());
            data.put("preRisk", risk.preRisk());
            data.put("evidenceRisk", risk.evidenceRisk());
            data.put("evidenceAvailable", risk.evidenceAvailable());
            data.put("primaryFactor", risk.primaryFactor());
            data.put("components", risk.components());
            data.put("honestyStatus", risk.rewriteHonestyStatus());
            data.put("overreachType", risk.rewriteOverreachType());
            data.put("overreachScore", risk.rewriteOverreachScore());
            data.put("sourceHash12", risk.rewriteSourceHash12());
            data.put("temperature", risk.rewriteTemperatureWeighted());
            data.put("validationTemperature", risk.validationTemperature());
            data.put("explorationTemperature", risk.explorationTemperature());
            data.put("softmaxTemperature", risk.softmaxTemperature());
            data.put("laneWeights", risk.laneWeights());
            data.put("observedEvidenceCount", Math.max(0, observedEvidenceCount));
            data.put("emergent", java.util.Map.of(
                    "riskDelta", risk.emergentAdjustment().riskDelta(),
                    "temperatureDelta", risk.emergentAdjustment().temperatureDelta(),
                    "searchRangeDelta", risk.emergentAdjustment().searchRangeDelta(),
                    "reason", safeEmergentReason,
                    "components", risk.emergentAdjustment().components(),
                    "laneDeltas", risk.emergentAdjustment().laneWeightDeltas()));
            TraceStore.append("ml.risk.rewrite.events", data);
            OrchEventEmitter.breadcrumbAndDebug(
                    debugEventStore,
                    DebugProbeType.ORCHESTRATION,
                    "HIGH".equals(risk.rewriteRiskBand()) ? DebugEventLevel.WARN : DebugEventLevel.INFO,
                    "ml.risk.rewrite." + risk.rewriteRiskBand().toLowerCase(Locale.ROOT),
                    "Self-Ask rewrite risk refreshed",
                    "SelfAskWebSearchRetriever.refreshRewriteRisk",
                    "ml.risk.rewrite",
                    "selfask",
                    "refresh",
                    data,
                    null);
        } catch (Exception ignore) {
            log.debug("[SelfAskWebSearchRetriever] fail-soft stage={}", "refreshRewriteRisk.trace");
            // Observability is best-effort.
        }
        return risk;
    }

    static double creativeRequestedRewriteTemperature(double fallback) {
        GuardContext context = GuardContextHolder.get();
        if (!creativeProfileActive(context)) {
            return fallback;
        }
        double requested = context.planDouble("creative.emergence.selfAsk.temperature", Double.NaN);
        String profile = String.valueOf(context.getPlanOverride("creative.emergence.profile"));
        if (!validSelfAskBand(profile, requested)) {
            return fallback;
        }
        Double mandatoryCap = effectiveSelfAskCap(context);
        return mandatoryCap == null ? requested : Math.min(requested, mandatoryCap);
    }

    private static boolean creativeProfileActive() {
        return creativeProfileActive(GuardContextHolder.get());
    }

    private static boolean creativeProfileActive(GuardContext context) {
        return validCompleteCreativeProfile(context);
    }

    private static boolean validCompleteCreativeProfile(GuardContext context) {
        if (context == null || context.isSensitiveTopic()
                || context.planBool("privacy.boundary.enforce", false)
                || !context.planBool("creative.emergence.active", false)
                || !"explore".equals(context.getPlanOverride("promptPose.application.intentSlot"))) {
            return false;
        }
        String requestedHash = String.valueOf(
                context.getPlanOverride("creative.emergence.requestedOptionsHash"))
                .toLowerCase(Locale.ROOT);
        if (!requestedHash.matches("hash:[0-9a-f]{12}")) {
            return false;
        }
        return switch (String.valueOf(context.getPlanOverride("creative.emergence.profile"))) {
            case "VIVID" -> creativeProfileValuesInRange(context,
                    0.85d, 0.90d, 0.70d, 0.76d,
                    1.10d, 1.25d, 0.95d, 0.97d,
                    1.05d, 1.20d, 0.95d, 0.97d,
                    0.80d, 0.88d);
            case "WILD" -> creativeProfileValuesInRange(context,
                    0.91d, 0.97d, 0.77d, 0.83d,
                    1.26d, 1.45d, 0.97d, 0.99d,
                    1.21d, 1.40d, 0.97d, 0.99d,
                    0.89d, 0.97d);
            case "FERAL" -> creativeProfileValuesInRange(context,
                    0.98d, 1.00d, 0.84d, 0.85d,
                    1.46d, 1.50d, 0.99d, 1.00d,
                    1.41d, 1.50d, 0.99d, 1.00d,
                    0.98d, 1.00d);
            default -> false;
        };
    }

    private static boolean creativeProfileValuesInRange(
            GuardContext context,
            double searchTempMin, double searchTempMax,
            double searchRateMin, double searchRateMax,
            double candidateTempMin, double candidateTempMax,
            double candidateTopPMin, double candidateTopPMax,
            double finalTempMin, double finalTempMax,
            double finalTopPMin, double finalTopPMax,
            double selfAskMin, double selfAskMax) {
        return creativeValueInRange(context, "creative.emergence.search.temperature", searchTempMin, searchTempMax)
                && creativeValueInRange(context, "creative.emergence.search.rate", searchRateMin, searchRateMax)
                && creativeValueInRange(context, "creative.emergence.candidate.temperature",
                        candidateTempMin, candidateTempMax)
                && creativeValueInRange(context, "creative.emergence.candidate.topP",
                        candidateTopPMin, candidateTopPMax)
                && creativeValueInRange(context, "creative.emergence.final.temperature",
                        finalTempMin, finalTempMax)
                && creativeValueInRange(context, "creative.emergence.final.topP",
                        finalTopPMin, finalTopPMax)
                && creativeValueInRange(context, "creative.emergence.selfAsk.temperature",
                        selfAskMin, selfAskMax);
    }

    private static boolean creativeValueInRange(
            GuardContext context, String key, double min, double max) {
        double value = context.planDouble(key, Double.NaN);
        return Double.isFinite(value) && value >= min && value <= max;
    }

    private static Double effectiveSelfAskCap(GuardContext context) {
        if (context == null) {
            return null;
        }
        Double selfAsk = finiteNonNegative(context.planDouble("llm.selfAsk.temperature.max"));
        Double explore = finiteNonNegative(context.planDouble("llm.explore.temperature.max"));
        if (selfAsk == null) {
            return explore;
        }
        return explore == null ? selfAsk : Math.min(selfAsk, explore);
    }

    private static Double finiteNonNegative(Double value) {
        return value != null && Double.isFinite(value) ? Math.max(0.0d, value) : null;
    }

    private static boolean validSelfAskBand(String profile, double value) {
        if (!Double.isFinite(value)) {
            return false;
        }
        return switch (profile) {
            case "VIVID" -> value >= 0.80d && value <= 0.88d;
            case "WILD" -> value >= 0.89d && value <= 0.97d;
            case "FERAL" -> value >= 0.98d && value <= 1.00d;
            default -> false;
        };
    }

    private SelfAskRewriteRiskScorer.EmergentConfig riskEmergentConfig() {
        return new SelfAskRewriteRiskScorer.EmergentConfig(
                riskEmergentEnabled,
                riskEmergentMaxRiskDelta,
                riskEmergentMaxTemperatureDelta,
                riskEmergentMaxLaneWeightDelta,
                riskEmergentMaxSearchRangeDelta);
    }

    private static double laneWeight(String lane, java.util.Map<String, Double> laneWeights) {
        if (lane == null || laneWeights == null) {
            return 1.0d;
        }
        Double value = laneWeights.get(lane);
        if (value == null || !Double.isFinite(value)) {
            return 1.0d;
        }
        return Math.max(0.25d, Math.min(2.50d, value));
    }

    private BranchQualityProbe.BranchQualityMetrics branchQualityAttempt(
            String lane,
            int requestedTopK,
            int returnedCount,
            int afterFilterCount,
            double laneWeight,
            double baseTemperature,
            boolean fallback,
            String failureClass) {
        if (!branchQualityEnabled || branchQualityProbe == null || !isThreeWayLane(lane)) {
            return null;
        }
        try {
            return branchQualityProbe.evaluateAttempt(
                    lane,
                    requestedTopK,
                    returnedCount,
                    afterFilterCount,
                    laneWeight,
                    baseTemperature,
                    fallback,
                    failureClass,
                    branchQualityThresholds(Math.max(1, requestedTopK), baseTemperature));
        } catch (Exception e) {
            log.debug("[SelfAsk][branchQuality] attempt probe fail-soft errorHash={} errorLength={}", SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length());
            return null;
        }
    }

    private BranchQualityProbe.Thresholds branchQualityThresholds(int baseTopK, double baseTemperature) {
        return new BranchQualityProbe.Thresholds(
                branchQualityMinContextContribution,
                branchQualityMaxRiskPenalty,
                branchQualityMoeTemperature,
                branchQualityBaseDppLambda,
                branchQualityDppMinLambda,
                branchQualityDppMaxLambda,
                Math.max(1, baseTopK),
                baseTemperature);
    }

    private boolean shouldRetryBranch(
            String lane,
            BranchQualityProbe.BranchQualityMetrics metric,
            java.util.Map<String, Integer> retryCounts) {
        if (!branchQualityRetryEnabled || metric == null || !metric.shouldRetry() || !isThreeWayLane(lane)) {
            return false;
        }
        int max = Math.max(0, branchQualityRetryMaxPerLane);
        if (max <= 0) {
            return false;
        }
        return retryCounts == null || retryCounts.getOrDefault(lane, 0) < max;
    }

    private String retrySkipReason(
            String parentQuery,
            String branchQuery,
            String retryQuery,
            java.util.Set<String> visitedCanon) {
        String retryCanon = canonicalKeyword(retryQuery);
        if (!StringUtils.hasText(retryCanon)) {
            return "empty_query";
        }
        if (retryCanon.equals(canonicalKeyword(parentQuery))) {
            return "same_parent_query";
        }
        if (retryCanon.equals(canonicalKeyword(branchQuery))) {
            return "same_branch_query";
        }
        if (visitedCanon != null && visitedCanon.contains(retryCanon)) {
            return "already_visited";
        }
        return null;
    }

    private String regenerateBranchQuery(
            String parentQuery,
            String lane,
            String fallbackQuery,
            long timeoutMs,
            BranchQualityProbe.BranchQualityMetrics metric) {
        if (threeWayPlanner != null && isThreeWayLane(lane)) {
            try {
                java.util.Optional<SelfAskPlanner.SubQuestion> regenerated = threeWayPlanner.regenerateLane(
                        parentQuery,
                        SelfAskPlanner.SubQuestionType.valueOf(lane),
                        Math.max(250L, timeoutMs),
                        metric == null ? 0.2d : metric.adjustedTemperature(),
                        metric == null ? 1.0d : Math.max(0.25d, metric.rrfWeight()));
                if (regenerated.isPresent() && StringUtils.hasText(regenerated.get().text)) {
                    return normalize(regenerated.get().text);
                }
            } catch (Exception e) {
                log.debug("[SelfAsk][branchQuality] regenerate lane={} fail-soft errorHash={} errorLength={}", lane, SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length());
            }
        }
        return normalize(fallbackQuery);
    }

    private static boolean isThreeWayLane(String lane) {
        return "BQ".equals(lane) || "ER".equals(lane) || "RC".equals(lane);
    }

    private void applyZero100QueryBurst(
            String parentQuery,
            java.util.List<LaneSeed> branchSeeds,
            java.util.LinkedHashSet<String> seedSet,
            java.util.Map<String, String> seedLaneByCanon,
            java.util.Map<String, String> seedQueryByCanon,
            java.util.Map<String, Double> seedWeightByCanon) {
        if (!zero100BurstPhase() || seedSet == null) {
            return;
        }
        int max = SelfAskNumbers.clampInt(traceInt("zero100.queryBurst.max", 0), 0, 32);
        if (max <= 0) {
            return;
        }
        String activeLane = normalizeLane(traceString("zero100.activeLane", "ER"));
        String sourceSeed = zero100BurstSeed(parentQuery, branchSeeds, activeLane);
        ai.abandonware.nova.orch.anchor.AnchorNarrowingResult anchorResult = null;
        if (ragAnchorEnabled && anchorNarrower != null) {
            anchorResult = anchorNarrower.narrow(sourceSeed, java.util.List.of(sourceSeed),
                    ragAnchorMaxAnchors, ragAnchorMaxDriftScore);
        }
        com.example.lms.service.rag.offline.OfflineTextureSnapshotLoader.TextureLookup textureLookup =
                com.example.lms.service.rag.offline.OfflineTextureSnapshotLoader.TextureLookup.disabled("loader_unavailable");
        boolean freshnessQuery = com.example.lms.service.rag.offline.OfflineTextureSnapshotLoader
                .looksFreshnessQuery(sourceSeed);
        if (offlineTextureSnapshotLoader != null) {
            java.util.List<String> anchors = anchorResult == null ? java.util.List.of() : anchorResult.anchors();
            textureLookup = offlineTextureSnapshotLoader.lookup(sourceSeed, anchors, freshnessQuery);
        }
        if (retrievalBudgetGovernor != null) {
            com.example.lms.service.rag.budget.RetrievalBudgetDecision decision =
                    retrievalBudgetGovernor.decide(anchorResult, textureLookup.hitRate(), freshnessQuery,
                            webTopK, overallTopK, 0, max);
            max = Math.min(max, Math.max(1, decision.queryBurstCount()));
            try {
                TraceStore.put("zero100.queryBurst.budget.reason", SafeRedactor.traceLabelOrFallback(decision.reason(), "unknown"));
                TraceStore.put("zero100.queryBurst.budget.max", max);
            } catch (Exception ignore) {
                log.debug("[SelfAskWebSearchRetriever] fail-soft stage={}", "zero100.queryBurst.budget");
                // Observability is best-effort.
            }
        }
        java.util.List<String> expanded = zero100QueryBurstExpander.expand(sourceSeed, Math.min(3, max), max);
        if (ragAnchorEnabled && anchorNarrower != null) {
            java.util.List<String> filtered = anchorNarrower.filterCandidates(
                    sourceSeed, expanded, anchorResult, ragAnchorMaxDriftScore);
            int dropped = Math.max(0, expanded.size() - filtered.size());
            expanded = filtered;
            try {
                TraceStore.put("zero100.queryBurst.anchorDroppedCount", dropped);
            } catch (Exception ignore) {
                log.debug("[SelfAskWebSearchRetriever] fail-soft stage={}", "zero100.queryBurst.anchorDroppedCount");
                // Observability is best-effort.
            }
        }
        int added = 0;
        java.util.List<String> hashes = new java.util.ArrayList<>();
        for (String candidate : expanded) {
            String norm = normalize(candidate);
            if (!StringUtils.hasText(norm)) {
                continue;
            }
            String canon = canonicalKeyword(norm);
            hashes.add(SafeRedactor.hash12(norm));
            if (seedSet.add(norm)) {
                added++;
            }
            seedLaneByCanon.putIfAbsent(canon, activeLane);
            seedQueryByCanon.putIfAbsent(canon, norm);
            seedWeightByCanon.putIfAbsent(canon, zero100LaneWeight(activeLane));
        }
        try {
            TraceStore.put("zero100.queryBurst.seedCount", expanded.size());
            TraceStore.put("zero100.queryBurst.addedCount", added);
            TraceStore.put("zero100.queryBurst.activeLane", activeLane);
            TraceStore.put("zero100.queryBurst.seedHashes", java.util.List.copyOf(hashes));
        } catch (Exception ignore) {
            log.debug("[SelfAskWebSearchRetriever] fail-soft stage={}", "zero100.queryBurst.seedTrace");
            // Observability is best-effort.
        }
    }

    private static boolean zero100BurstPhase() {
        if (!traceBool("zero100.enabled", false)) {
            return false;
        }
        String phase = traceString("zero100.phase", "");
        return "DIVERGE".equalsIgnoreCase(phase) || "CROSS_VERIFY".equalsIgnoreCase(phase);
    }

    private static String zero100BurstSeed(String parentQuery, java.util.List<LaneSeed> branchSeeds, String activeLane) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        parts.add(parentQuery);
        if (branchSeeds != null) {
            for (LaneSeed seed : branchSeeds) {
                if (seed != null && activeLane.equals(seed.lane()) && StringUtils.hasText(seed.query())) {
                    parts.add(seed.query());
                    break;
                }
            }
        }
        for (String key : java.util.List.of(
                "resource.rewriteRiskPrimaryFactor",
                "selfask.rewrite.overreachType",
                "web.brave.skipped.reason",
                "web.naver.skipped.reason",
                "web.serpapi.skipped.reason",
                "web.tavily.skipped.reason")) {
            String label = normalizedFailureLabel(TraceStore.get(key));
            if (StringUtils.hasText(label)) {
                parts.add(label);
            }
        }
        return parts.stream()
                .filter(StringUtils::hasText)
                .limit(5)
                .reduce((a, b) -> a + " " + b)
                .orElse(parentQuery == null ? "" : parentQuery);
    }

    static java.util.List<String> orderZero100Keywords(
            java.util.Collection<String> keywords,
            java.util.Map<String, String> seedLaneByCanon,
            String activeLane) {
        return orderZero100Keywords(keywords, seedLaneByCanon, activeLane, "");
    }

    static java.util.List<String> orderZero100Keywords(
            java.util.Collection<String> keywords,
            java.util.Map<String, String> seedLaneByCanon,
            String activeLane,
            String cooledLane) {
        if (keywords == null || keywords.isEmpty()) {
            return java.util.List.of();
        }
        String lane = normalizeLane(activeLane);
        if (!isThreeWayLane(lane)) {
            return new java.util.ArrayList<>(keywords);
        }
        String cooled = laneOrEmpty(cooledLane);
        java.util.List<String> ordered = new java.util.ArrayList<>(keywords);
        ordered.sort(java.util.Comparator.comparingInt(keyword -> zero100LaneOrderRank(
                seedLaneByCanon == null ? "" : seedLaneByCanon.getOrDefault(canonicalKeyword(keyword), ""),
                lane,
                cooled)));
        return ordered;
    }

    private static int zero100LaneOrderRank(String candidateLane, String preferredLane, String cooledLane) {
        String candidate = laneOrEmpty(candidateLane);
        if (candidate.equals(preferredLane)) {
            return 0;
        }
        if (StringUtils.hasText(cooledLane) && candidate.equals(cooledLane)) {
            return 2;
        }
        return isThreeWayLane(candidate) ? 1 : 3;
    }

    private static String laneOrEmpty(String lane) {
        if (!StringUtils.hasText(lane)) {
            return "";
        }
        String s = lane.trim().toUpperCase(Locale.ROOT);
        return isThreeWayLane(s) ? s : "";
    }

    static boolean traceZero100Rollover(String lane, String failureClass, int remainingBudget) {
        return traceZero100Rollover(
                new Zero100RolloverState(traceString("zero100.activeLane", lane)),
                lane,
                failureClass,
                remainingBudget,
                -1);
    }

    static boolean traceZero100Rollover(
            Zero100RolloverState state,
            String lane,
            String failureClass,
            int remainingBudget,
            int depth) {
        if (!traceBool("zero100.enabled", false) || !isZero100RolloverFailure(failureClass)) {
            return false;
        }
        String safeLane = normalizeLane(lane);
        Zero100RolloverState effectiveState = state == null
                ? new Zero100RolloverState(traceString("zero100.activeLane", safeLane))
                : state;
        boolean applied = effectiveState.apply(safeLane, failureClass);
        java.util.Map<String, Object> event = new java.util.LinkedHashMap<>();
        event.put("seq", TraceStore.nextSequence("zero100.rollover"));
        event.put("applied", applied);
        event.put("currentLane", safeLane);
        event.put("nextLane", effectiveState.preferredLane());
        event.put("failureClass", normalizedFailureLabel(failureClass));
        event.put("movedCalls", applied ? 1 : 0);
        event.put("remainingGlobalBudget", Math.max(0, remainingBudget));
        event.put("depth", Math.max(-1, depth));
        try {
            TraceStore.append("zero100.rollover.events", event);
        } catch (Exception ignore) {
            log.debug("[SelfAskWebSearchRetriever] fail-soft stage={}", "zero100.rollover.events");
            // Observability is best-effort.
        }
        return applied;
    }

    static boolean isZero100RolloverFailure(String failureClass) {
        String f = normalizedFailureLabel(failureClass);
        return "timeout".equals(f)
                || "rate-limit".equals(f)
                || "missing_future".equals(f)
                || "provider-disabled".equals(f)
                || "missing-key-or-unauthorized".equals(f)
                || "skipped:budget_exhausted".equals(f);
    }

    private static String nextZero100Lane(String lane) {
        return switch (normalizeLane(lane)) {
            case "BQ" -> "ER";
            case "ER" -> "RC";
            default -> "BQ";
        };
    }

    private static int laneOrderIndex(String lane) {
        return switch (normalizeLane(lane)) {
            case "BQ" -> 0;
            case "ER" -> 1;
            case "RC" -> 2;
            default -> 3;
        };
    }

    private static java.util.Map<String, LaneBudget> zero100LaneBudgets(int totalCalls) {
        int total = Math.max(0, totalCalls);
        java.util.Map<String, Double> ratios = traceDoubleMap("zero100.branch.callRatios");
        double bq = ratios.getOrDefault("BQ", 0.34d);
        double er = ratios.getOrDefault("ER", 0.33d);
        double rc = ratios.getOrDefault("RC", 0.33d);
        double sum = bq + er + rc;
        if (!Double.isFinite(sum) || sum <= 0.0d) {
            bq = 0.34d;
            er = 0.33d;
            rc = 0.33d;
            sum = 1.0d;
        }
        java.util.Map<String, Double> normalized = new java.util.LinkedHashMap<>();
        normalized.put("BQ", Math.max(0.0d, bq) / sum);
        normalized.put("ER", Math.max(0.0d, er) / sum);
        normalized.put("RC", Math.max(0.0d, rc) / sum);

        java.util.Map<String, Integer> calls = new java.util.LinkedHashMap<>();
        java.util.Map<String, Double> fractions = new java.util.LinkedHashMap<>();
        int allocated = 0;
        for (java.util.Map.Entry<String, Double> entry : normalized.entrySet()) {
            double scaled = total * entry.getValue();
            int floor = (int) Math.floor(scaled);
            calls.put(entry.getKey(), floor);
            fractions.put(entry.getKey(), scaled - floor);
            allocated += floor;
        }
        int remainder = Math.max(0, total - allocated);
        String activeLane = normalizeLane(traceString("zero100.activeLane", ""));
        java.util.List<String> order = new java.util.ArrayList<>(java.util.List.of("BQ", "ER", "RC"));
        order.sort((left, right) -> {
            int byFraction = Double.compare(
                    fractions.getOrDefault(right, 0.0d),
                    fractions.getOrDefault(left, 0.0d));
            if (byFraction != 0) {
                return byFraction;
            }
            if (left.equals(activeLane) && !right.equals(activeLane)) {
                return -1;
            }
            if (right.equals(activeLane) && !left.equals(activeLane)) {
                return 1;
            }
            return Integer.compare(laneOrderIndex(left), laneOrderIndex(right));
        });
        for (int i = 0; i < remainder; i++) {
            String lane = order.get(i % order.size());
            calls.put(lane, calls.getOrDefault(lane, 0) + 1);
        }

        java.util.Map<String, LaneBudget> out = new java.util.LinkedHashMap<>();
        out.put("BQ", new LaneBudget(calls.getOrDefault("BQ", 0), calls.getOrDefault("BQ", 0)));
        out.put("ER", new LaneBudget(calls.getOrDefault("ER", 0), calls.getOrDefault("ER", 0)));
        out.put("RC", new LaneBudget(calls.getOrDefault("RC", 0), calls.getOrDefault("RC", 0)));
        return out;
    }

    private static void moveOneLaneBudget(
            java.util.Map<String, LaneBudget> budgets,
            String from,
            String to,
            String failureClass,
            int remainingGlobalBudget) {
        if (budgets == null) {
            return;
        }
        String src = normalizeLane(from);
        String dst = normalizeLane(to);
        if (!isThreeWayLane(src) || !isThreeWayLane(dst) || src.equals(dst)) {
            return;
        }
        LaneBudget source = budgets.getOrDefault(src, new LaneBudget(0, 0));
        LaneBudget target = budgets.getOrDefault(dst, new LaneBudget(0, 0));
        boolean moved = remainingGlobalBudget > 0 && source.remaining() > 0;
        LaneBudget updatedSource = moved ? source.moveOut() : source;
        LaneBudget updatedTarget = moved ? target.receiveOne() : target;
        budgets.put(src, updatedSource);
        budgets.put(dst, updatedTarget);
        try {
            TraceStore.append("zero100.branch.budgetRollover.events", java.util.Map.of(
                    "from", src,
                    "to", dst,
                    "failureClass", normalizedFailureLabel(failureClass),
                    "movedCalls", moved ? 1 : 0,
                    "remainingGlobalBudget", Math.max(0, remainingGlobalBudget),
                    "fromRemaining", updatedSource.remaining(),
                    "toRemaining", updatedTarget.remaining()));
        } catch (Exception ignore) {
            log.debug("[SelfAskWebSearchRetriever] fail-soft stage={}", "zero100.branch.budgetRollover");
            // Observability is best-effort.
        }
    }

    private static long zero100LaneTimeboxMs(String lane, long fallbackMs) {
        long safeFallback = Math.max(100L, fallbackMs);
        java.util.Map<String, Long> timeboxes = traceLongMap("zero100.branch.timeboxMs");
        long value = timeboxes.getOrDefault(normalizeLane(lane), safeFallback);
        return Math.max(100L, Math.min(safeFallback, value));
    }

    private static void traceZero100LaneBudget(String lane, LaneBudget budget) {
        if (!traceBool("zero100.enabled", false) || budget == null) {
            return;
        }
        try {
            TraceStore.append("zero100.branch.callBudget.events", java.util.Map.of(
                    "lane", normalizeLane(lane),
                    "initial", Math.max(0, budget.initial()),
                    "remaining", Math.max(0, budget.remaining())));
        } catch (Exception ignore) {
            log.debug("[SelfAskWebSearchRetriever] fail-soft stage={}", "zero100.branch.callBudget");
            // Trace is best-effort only.
        }
    }

    private static java.util.Map<String, Double> traceDoubleMap(String key) {
        java.util.Map<String, Double> out = new java.util.HashMap<>();
        Object raw = TraceStore.get(key);
        if (raw instanceof java.util.Map<?, ?> map) {
            for (java.util.Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                String lane = laneOrEmpty(String.valueOf(entry.getKey()));
                if (lane.isBlank()) {
                    continue;
                }
                double value = SelfAskNumbers.parseDouble(entry.getValue(), Double.NaN);
                if (Double.isFinite(value) && value >= 0.0d) {
                    out.put(lane, value);
                }
            }
        }
        return out;
    }

    private static java.util.Map<String, Long> traceLongMap(String key) {
        java.util.Map<String, Long> out = new java.util.HashMap<>();
        Object raw = TraceStore.get(key);
        if (raw instanceof java.util.Map<?, ?> map) {
            for (java.util.Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                String lane = laneOrEmpty(String.valueOf(entry.getKey()));
                if (lane.isBlank()) {
                    continue;
                }
                long value = SelfAskNumbers.parseLong(entry.getValue(), -1L);
                if (value > 0L) {
                    out.put(lane, value);
                }
            }
        }
        return out;
    }

    private static double zero100LaneWeight(String lane) {
        Object raw = TraceStore.get("zero100.branch.weights");
        if (raw instanceof java.util.Map<?, ?> map) {
            Object v = map.get(normalizeLane(lane));
            if (v instanceof Number n) {
                return SelfAskNumbers.clampDouble(n.doubleValue(), 0.05d, 1.25d);
            }
            if (v != null) {
            try {
                return SelfAskNumbers.clampDouble(Double.parseDouble(String.valueOf(v).trim()), 0.05d, 1.25d);
            } catch (NumberFormatException ignore) {
                log.debug("[SelfAskWebSearchRetriever] fail-soft stage={} errorType={}",
                        "zero100LaneWeight", "invalid_number");
                return 1.0d;
            }
            }
        }
        return 1.0d;
    }

    private static String normalizeLane(String lane) {
        if (!StringUtils.hasText(lane)) {
            return "ER";
        }
        String s = lane.trim().toUpperCase(Locale.ROOT);
        return isThreeWayLane(s) ? s : "ER";
    }

    private static String normalizedFailureLabel(Object raw) {
        if (raw == null) {
            return "";
        }
        String s = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        if (s.isBlank()) {
            return "";
        }
        s = s.replaceAll("[^a-z0-9_.:-]+", "_");
        while (s.contains("__")) {
            s = s.replace("__", "_");
        }
        if (s.length() > 48) {
            s = s.substring(0, 48);
        }
        if ("rate_limit".equals(s) || "rate_limit_local".equals(s) || "rate-limit-local".equals(s)
                || "ratelimit".equals(s)) {
            return "rate-limit";
        }
        if ("provider_disabled".equals(s)) {
            return "provider-disabled";
        }
        if ("missing-key".equals(s) || "missing_key".equals(s)
                || "missing-external-key".equals(s) || "missing_external_key".equals(s)
                || "unauthorized".equals(s) || "missing-api-key".equals(s) || "missing_api_key".equals(s)) {
            return "missing-key-or-unauthorized";
        }
        return s;
    }

    private static boolean traceBool(String key, boolean defaultValue) {
        Object v = TraceStore.get(key);
        if (v instanceof Boolean b) {
            return b;
        }
        if (v == null) {
            return defaultValue;
        }
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        if ("true".equals(s) || "1".equals(s) || "yes".equals(s)) {
            return true;
        }
        if ("false".equals(s) || "0".equals(s) || "no".equals(s)) {
            return false;
        }
        return defaultValue;
    }

    private static int traceInt(String key, int defaultValue) {
        Object v = TraceStore.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException ignore) {
            log.debug("[SelfAskWebSearchRetriever] fail-soft stage={} errorType={}", "traceInt", "invalid_number");
            return defaultValue;
        }
    }

    private static long traceLong(String key, long defaultValue) {
        Object v = TraceStore.get(key);
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException ignore) {
            log.debug("[SelfAskWebSearchRetriever] fail-soft stage={} errorType={}", "traceLong", "invalid_number");
            return defaultValue;
        }
    }

    private static String traceString(String key, String defaultValue) {
        Object v = TraceStore.get(key);
        if (v == null) {
            return defaultValue;
        }
        String s = String.valueOf(v);
        return s.isBlank() ? defaultValue : s;
    }

    static void traceRequeryAttempt(String lane,
            String seedQuery,
            double weight,
            double temperature,
            long timeoutMs,
            int requestedTopK,
            int returnedCount,
            int afterFilterCount,
            boolean fallback,
            String failureClass) {
        traceRequeryAttempt(lane, seedQuery, weight, temperature, timeoutMs, requestedTopK,
                returnedCount, afterFilterCount, fallback, failureClass, null, false);
    }

    static void traceRequeryAttempt(String lane,
            String seedQuery,
            double weight,
            double temperature,
            long timeoutMs,
            int requestedTopK,
            int returnedCount,
            int afterFilterCount,
            boolean fallback,
            String failureClass,
            BranchQualityProbe.BranchQualityMetrics branchMetric,
            boolean retry) {
        try {
            java.util.Map<String, Object> event = new java.util.LinkedHashMap<>();
            event.put("seq", TraceStore.nextSequence("selfask.requery"));
            event.put("lane", StringUtils.hasText(lane) ? lane : "unknown");
            event.put("seedHash12", SafeRedactor.hash12(seedQuery));
            event.put("weight", round4(weight));
            event.put("temperature", round4(temperature));
            event.put("timeoutMs", Math.max(0L, timeoutMs));
            event.put("requestedTopK", Math.max(0, requestedTopK));
            event.put("returnedCount", Math.max(0, returnedCount));
            event.put("afterFilterCount", Math.max(0, afterFilterCount));
            event.put("fallback", fallback);
            event.put("failureClass", StringUtils.hasText(failureClass) ? failureClass : "none");
            event.put("retry", retry);
            if (branchMetric != null) {
                event.put("branchId", branchMetric.branchId());
                event.put("intentAxis", branchMetric.intentAxis());
                event.put("retrievedCount", branchMetric.retrievedCount());
                event.put("duplicateRatio", branchMetric.duplicateRatio());
                event.put("authorityScore", branchMetric.authorityScore());
                event.put("rerankConfidence", branchMetric.rerankConfidence());
                event.put("contextContribution", branchMetric.contextContribution());
                event.put("riskPenalty", branchMetric.riskPenalty());
                event.put("matrixTile", branchMetric.matrixTile());
                event.put("action", branchMetric.action().name());
                event.put("reason", SafeRedactor.traceLabelOrFallback(branchMetric.reason(), "unknown"));
                event.put("adjustedTopK", branchMetric.adjustedTopK());
                event.put("adjustedTemperature", branchMetric.adjustedTemperature());
                event.put("dppLambda", branchMetric.dppLambda());
            }
            TraceStore.append("selfask.requery.attempts", event);
        } catch (Exception ignore) {
            log.debug("[SelfAskWebSearchRetriever] fail-soft stage={}", "traceRequeryAttempt");
            // Observability is best-effort.
        }
    }

    private static void traceRequerySummary(java.util.List<LaneSeed> branchSeeds,
            int uniqueSeedCount,
            SelfAskRewriteRiskScorer.Score risk) {
        try {
            java.util.Set<String> lanes = new java.util.LinkedHashSet<>();
            if (branchSeeds != null) {
                for (LaneSeed seed : branchSeeds) {
                    if (seed != null && StringUtils.hasText(seed.lane())) {
                        lanes.add(seed.lane());
                    }
                }
            }
            boolean confirmed = lanes.contains("BQ") && lanes.contains("ER") && lanes.contains("RC");
            boolean required = branchSeeds != null && !branchSeeds.isEmpty();
            java.util.Map<String, Object> summary = new java.util.LinkedHashMap<>();
            summary.put("laneCoverage", lanes.size());
            summary.put("lanes", java.util.List.copyOf(lanes));
            summary.put("seedCount", branchSeeds == null ? 0 : branchSeeds.size());
            summary.put("uniqueSeedCount", Math.max(0, uniqueSeedCount));
            summary.put("requeryRequired", required);
            summary.put("requeryConfirmed", confirmed);
            summary.put("primaryFactor", risk == null ? "" : risk.primaryFactor());
            summary.put("policy", risk == null ? "" : risk.policy());
            summary.put("honestyStatus", risk == null ? "" : risk.rewriteHonestyStatus());
            summary.put("overreachType", risk == null ? "" : risk.rewriteOverreachType());
            TraceStore.put("selfask.requery.summary", summary);
            TraceStore.put("selfask.3way.requery.required", required);
            TraceStore.put("selfask.3way.requery.confirmed", confirmed);
        } catch (Exception ignore) {
            log.debug("[SelfAskWebSearchRetriever] fail-soft stage={}", "traceRequerySummary");
            // Observability is best-effort.
        }
    }

    private static String classifySearchFailure(Throwable e) {
        String msg = e == null ? "" : String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
        String simpleName = e == null ? "" : e.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        if (e instanceof java.util.concurrent.CancellationException
                || e instanceof InterruptedException
                || simpleName.contains("cancel")
                || simpleName.contains("interrupt")
                || msg.contains("cancelled")
                || msg.contains("canceled")
                || msg.contains("interrupted")) {
            return "cancelled";
        }
        if (msg.contains("429") || msg.contains("rate")) {
            return "rate-limit";
        }
        if (msg.contains("timeout") || msg.contains("timed out")) {
            return "timeout";
        }
        if (msg.contains("provider disabled") || msg.contains("provider-disabled")
                || msg.contains("disabled") || simpleName.contains("disabled")) {
            return "provider-disabled";
        }
        if (msg.contains("401") || msg.contains("403") || msg.contains("api key")
                || msg.contains("unauthorized") || msg.contains("missing")) {
            return "missing-key-or-unauthorized";
        }
        if (msg.contains("500") || msg.contains("502") || msg.contains("503") || msg.contains("504")) {
            return "server-error";
        }
        if (msg.contains("parse") || msg.contains("malformed") || msg.contains("schema")) {
            return "malformed-response";
        }
        return e == null ? "unknown" : e.getClass().getSimpleName();
    }

    private static double round4(double value) {
        if (!Double.isFinite(value)) {
            return 0.0d;
        }
        return Math.round(value * 10_000.0d) / 10_000.0d;
    }

    private java.util.List<Content> toSelfAskContents(
            java.util.Collection<String> snippets,
            String parentQuery,
            String branch,
            String retrievalQuery,
            int limit) {
        java.util.Map<String, String> lanes = new java.util.LinkedHashMap<>();
        java.util.Map<String, String> queries = new java.util.LinkedHashMap<>();
        if (snippets != null) {
            for (String s : snippets) {
                lanes.put(s, branch);
                queries.put(s, retrievalQuery);
            }
        }
        return toSelfAskContents(snippets, lanes, queries, parentQuery, limit);
    }

    private java.util.List<Content> toSelfAskContents(
            java.util.Collection<String> snippets,
            java.util.Map<String, String> snippetLane,
            java.util.Map<String, String> snippetQuery,
            String parentQuery,
            int limit) {
        if (snippets == null || snippets.isEmpty()) {
            return java.util.List.of();
        }
        java.util.List<Content> out = new java.util.ArrayList<>();
        int rank = 0;
        int max = Math.max(1, limit);
        for (String raw : snippets) {
            if (out.size() >= max) break;
            String pruned = pruneSelfAskSnippet(parentQuery, raw);
            if (!StringUtils.hasText(pruned)) continue;
            rank++;
            String lane = snippetLane != null ? snippetLane.getOrDefault(raw, "unknown") : "unknown";
            String branchQuery = snippetQuery != null ? snippetQuery.getOrDefault(raw, parentQuery) : parentQuery;
            out.add(toSelfAskContent(pruned, lane, parentQuery, branchQuery, rank));
        }
        return out;
    }

    private Content toSelfAskContent(String snippet, String branch, String parentQuery, String retrievalQuery, int rank) {
        java.util.Map<String, Object> md = new java.util.LinkedHashMap<>();
        String lane = StringUtils.hasText(branch) ? branch : "unknown";
        boolean threeWayLane = "BQ".equals(lane) || "ER".equals(lane) || "RC".equals(lane);
        md.put("retrieval_stage", threeWayLane ? "selfask3way" : "selfask");
        md.put("retrieval_lane", lane);
        md.put("retrieval_lane_role", laneRole(lane));
        md.put("branchId", lane);
        md.put("intentAxis", laneRole(lane));
        md.put("query_branch", lane);
        md.put("retrieval_query", SafeRedactor.diagnosticText("retrieval_query", retrievalQuery, 160));
        md.put("retrieval_query_hash12", SafeRedactor.hash12(retrievalQuery));
        md.put("parent_query", SafeRedactor.diagnosticText("parent_query", parentQuery, 160));
        md.put("parent_query_hash12", SafeRedactor.hash12(parentQuery));
        md.put("rank", rank);
        md.put("source", "web:selfask");
        md.put("provider", webSearchProvider != null ? webSearchProvider.getName() : "web");
        md.put("verified", "false");
        putTraceMetadata(md, "selfask.rewrite.honestyStatus");
        putTraceMetadata(md, "selfask.rewrite.overreachType");
        putTraceMetadata(md, "selfask.rewrite.overreachScore");
        putTraceMetadata(md, "selfask.rewrite.sourceHash");
        String url = extractUrl(snippet);
        if (StringUtils.hasText(url)) {
            md.put("url", url);
        }
        return Content.from(TextSegment.from(snippet, Metadata.from(md)));
    }

    private static String laneRole(String lane) {
        if ("BQ".equals(lane)) {
            return "domain_definition";
        }
        if ("ER".equals(lane)) {
            return "alias_synonym";
        }
        if ("RC".equals(lane)) {
            return "relation_hypothesis";
        }
        return (lane == null || lane.isBlank()) ? "unknown" : lane.toLowerCase(java.util.Locale.ROOT);
    }

    private static void putTraceMetadata(java.util.Map<String, Object> metadata, String key) {
        if (metadata == null || key == null || key.isBlank()) {
            return;
        }
        try {
            Object value = TraceStore.get(key);
            if (value != null) {
                metadata.put(key, value);
            }
        } catch (Exception ignore) {
            log.debug("[SelfAskWebSearchRetriever] fail-soft stage={}", "putTraceMetadata");
            // Metadata enrichment is best-effort only.
        }
    }

    private String pruneSelfAskSnippet(String query, String snippet) {
        if (snippetPruner == null) {
            return snippet;
        }
        try {
            SnippetPruner.Result r = snippetPruner.prune(query, snippet);
            return r != null ? r.refined() : snippet;
        } catch (Exception e) {
            log.debug("[SelfAskWebSearchRetriever] fail-soft stage={}", "pruneSelfAskSnippet");
            log.debug("[SelfAsk][prune] pass-through errorHash={} errorLength={}", SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length());
            return snippet;
        }
    }

    private static String extractUrl(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        java.util.regex.Matcher m = URL_PATTERN.matcher(text);
        return m.find() ? m.group() : "";
    }

    private static String safeClip(String text, int max) {
        if (text == null) {
            return "";
        }
        String t = text.replaceAll("\\s+", " ").trim();
        if (t.length() <= max) {
            return t;
        }
        return t.substring(0, Math.max(0, max));
    }

    // ---- per-request metadata helpers (OrchestrationHints bridge) ----
    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Object> toMetaMap(Query query) {
        if (query == null) return java.util.Collections.emptyMap();
        return QueryUtils.metadata(query);
    }

    private static int metaInt(java.util.Map<String, Object> meta, String key, int def) {
        if (meta == null) return def;
        Object v = meta.get(key);
        if (v instanceof Number n) {
            double numeric = n.doubleValue();
            if (Double.isFinite(numeric)) return n.intValue();
            traceSuppressed("meta.int", new NumberFormatException("non_finite"));
            return def;
        }
        if (v instanceof String s) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ignore) { traceSuppressed("meta.int", ignore); }
        }
        return def;
    }

    private static long metaLong(java.util.Map<String, Object> meta, String key, long def) {
        if (meta == null) return def;
        Object v = meta.get(key);
        if (v instanceof Number n) {
            double numeric = n.doubleValue();
            if (Double.isFinite(numeric)) return n.longValue();
            traceSuppressed("meta.long", new NumberFormatException("non_finite"));
            return def;
        }
        if (v instanceof String s) {
            try { return Long.parseLong(s.trim()); } catch (NumberFormatException ignore) { traceSuppressed("meta.long", ignore); }
        }
        return def;
    }

    private static double metaDouble(java.util.Map<String, Object> meta, String key, double def) {
        if (meta == null) return def;
        Object v = meta.get(key);
        if (v instanceof Number n) {
            double numeric = n.doubleValue();
            if (Double.isFinite(numeric)) return numeric;
            traceSuppressed("meta.double", new NumberFormatException("non_finite"));
            return def;
        }
        if (v instanceof String s) {
            try {
                double parsed = Double.parseDouble(s.trim());
                if (Double.isFinite(parsed)) return parsed;
                traceSuppressed("meta.double", new NumberFormatException("non_finite"));
            } catch (NumberFormatException ignore) { traceSuppressed("meta.double", ignore); }
        }
        return def;
    }

    private static void traceSuppressed(String stage, Exception ignored) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        TraceStore.put("selfask.suppressed." + safeStage, true);
        TraceStore.put("selfask.suppressed." + safeStage + ".errorType", errorType(ignored));
    }

    private static String errorType(Exception ignored) {
        return ignored instanceof NumberFormatException ? "invalid_number"
                : SafeRedactor.traceLabelOrFallback(
                        ignored == null ? "unknown" : ignored.getClass().getSimpleName(), "unknown");
    }

    private static boolean metaBool(java.util.Map<String, Object> meta, String key, boolean def) {
        if (meta == null) return def;
        Object v = meta.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.intValue() != 0;
        if (v instanceof String s) {
            String t = s.trim().toLowerCase();
            if (t.equals("true") || t.equals("1") || t.equals("yes")) return true;
            if (t.equals("false") || t.equals("0") || t.equals("no")) return false;
        }
        return def;
    }

    private static boolean isCheapSearchModeActive(java.util.Map<String, Object> meta) {
        if (metaBool(meta, "cheapSearchMode", false)
                || metaBool(meta, "search.mode.lightAuxBypass", false)) {
            return true;
        }
        Object searchMode = meta == null ? null : firstPresent(meta, "searchMode", "search_mode");
        if (searchMode != null) {
            String normalized = String.valueOf(searchMode).trim()
                    .replace('-', '_')
                    .toUpperCase(Locale.ROOT);
            if ("FORCE_LIGHT".equals(normalized)) {
                return true;
            }
        }
        try {
            GuardContext ctx = GuardContextHolder.get();
            if (ctx != null && ctx.isCheapSearchMode()) {
                return true;
            }
        } catch (Exception ignore) {
            traceSuppressed("cheapSearchMode.contextRead", ignore);
        }
        return traceBool("search.mode.lightAuxBypass", false);
    }

    private static Object firstPresent(java.util.Map<String, Object> meta, String... keys) {
        if (meta == null || meta.isEmpty() || keys == null) {
            return null;
        }
        for (String key : keys) {
            Object value = meta.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static void traceCheapSearchModeSelfAskSkip(String query, int firstResultCount, int requestedTopK) {
        try {
            TraceStore.put("selfask.cheapSearchMode.skipped", Boolean.TRUE);
            TraceStore.put("selfask.cheapSearchMode.skipReason", "cheap-search-mode");
            TraceStore.put("selfask.cheapSearchMode.firstResultCount", Math.max(0, firstResultCount));
            TraceStore.put("selfask.cheapSearchMode.requestedTopK", Math.max(0, requestedTopK));
            TraceStore.put("selfask.cheapSearchMode.queryHash12", SafeRedactor.hash12(query));
            TraceStore.put("selfask.cheapSearchMode.queryLength", query == null ? 0 : query.length());
        } catch (Exception ignore) {
            traceSuppressed("cheapSearchMode.skipTrace", ignore);
        }
    }

    private static boolean explicitPlanSelfAskOverride(java.util.Map<String, Object> meta) {
        if (meta == null || meta.isEmpty()) return false;
        if (metaBool(meta, "selfask.enabled", false)) return true;
        if (metaInt(meta, "expand.selfAsk.count", 0) > 0) return true;
        Object reason = meta.get("selfask.planOverride.reason");
        return reason != null && StringUtils.hasText(String.valueOf(reason));
    }
    // Executor lifecycle is managed by Spring (SearchExecutorConfig.searchIoExecutor).

    /* ───────────── 키워드 Helper (휴리스틱) ───────────── */
    /** 얕은 1~3개 시드 키워드 */
    /**
     * LLM 한 번으로 1~3개 핵심 키워드를 추출
     */
    private List<String> basicKeywords(String question, boolean allowLlm) {
        if (!StringUtils.hasText(question)) return List.of();
        if (!allowLlm) return heuristicSeeds(question);

        String seedPrompt = QUERY_KEYWORD_PROMPT_BUILDER.buildSelfAskSeedPrompt(question.trim());
        String reply = "";
        try {
            if (nightmareBreaker != null) {
                reply = nightmareBreaker.execute(
                        com.example.lms.infra.resilience.NightmareKeys.SELFASK_SEED,
                        seedPrompt,
                        () -> chatModel.chat(List.of(
                                SystemMessage.from("당신은 최고의 검색 전문가입니다."),
                                UserMessage.from(seedPrompt)
                        )).aiMessage().text(),
                        com.example.lms.infra.resilience.FriendShieldPatternDetector::looksLikeSilentFailure,
                        () -> ""
                );
            } else {
                reply = chatModel.chat(List.of(
                        SystemMessage.from("당신은 최고의 검색 전문가입니다."),
                        UserMessage.from(seedPrompt)
                )).aiMessage().text();
            }
        } catch (Exception e) {
            log.warn("LLM keyword generation failed. errorHash={} errorLength={}", SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length());
        }

        List<String> out = splitLines(reply).stream().limit(3).toList();
        if (out == null || out.isEmpty()) return heuristicSeeds(question);
        return out;
    }

    private List<String> heuristicSeeds(String question) {
        if (!StringUtils.hasText(question)) return List.of();
        String cleaned = question.replaceAll("[\\p{Punct}]+", " ").trim();
        if (!StringUtils.hasText(cleaned)) {
            return List.of(question.trim());
        }

        java.util.LinkedHashSet<String> uniq = new java.util.LinkedHashSet<>();
        for (String t : cleaned.split("\\s+")) {
            String norm = normalize(t);
            if (!StringUtils.hasText(norm)) continue;
            String canon = canonicalKeyword(norm);
            if (!StringUtils.hasText(canon)) continue;
            uniq.add(norm);
            if (uniq.size() >= 3) break;
        }
        if (uniq.isEmpty()) return List.of(question.trim());
        return uniq.stream().limit(3).toList();
    }

    /**
     * Self-Ask 하위 키워드를 LLM으로 1~2개 생성
     */
    private List<String> followUpKeywords(String parent) {
        if (!StringUtils.hasText(parent)) return List.of();
        String followupPrompt = QUERY_KEYWORD_PROMPT_BUILDER.buildSelfAskFollowupPrompt(parent.trim());
        String reply = "";
        try {
            if (nightmareBreaker != null) {
                reply = nightmareBreaker.execute(
                        com.example.lms.infra.resilience.NightmareKeys.SELFASK_FOLLOWUP,
                        followupPrompt,
                        () -> chatModel.chat(List.of(
                                SystemMessage.from("검색어를 더 구체화하세요."),
                                UserMessage.from(followupPrompt)
                        )).aiMessage().text(),
                        com.example.lms.infra.resilience.FriendShieldPatternDetector::looksLikeSilentFailure,
                        () -> ""
                );
            } else {
                reply = chatModel.chat(List.of(
                        SystemMessage.from("검색어를 더 구체화하세요."),
                        UserMessage.from(followupPrompt)
                )).aiMessage().text();
            }
        } catch (Exception e) {
            log.warn("LLM follow-up generation failed. errorHash={} errorLength={}", SafeRedactor.hashValue(String.valueOf(e)), String.valueOf(e).length());
        }

        List<String> out = splitLines(reply).stream().limit(Math.max(1, followupsPerLevel)).toList();
        if (out == null || out.isEmpty()) return heuristicFollowups(parent);
        return out;
    }

    /* ───────────── LLM 프롬프트 상수 및 검색 예산 ───────────── */

    /**
     * 질의별 API 호출 예산 관리
     */
    private Future<SearchAttempt> submitSearchAttempt(String keyword, int topK) {
        FutureTask<SearchAttempt> task = new FutureTask<>(() -> safeSearchAttempt(keyword, topK));
        searchExecutor.execute(task);
        return task;
    }

    private static boolean cancelSearchAttempt(Future<SearchAttempt> future) {
        return future != null && !future.isDone() && future.cancel(true);
    }

    private static void cancelSearchAttempts(List<Future<SearchAttempt>> futures) {
        if (futures == null) {
            return;
        }
        for (Future<SearchAttempt> future : futures) {
            cancelSearchAttempt(future);
        }
    }

    private List<Content> interruptedPartialContents(
            LinkedHashSet<String> snippets,
            Map<String, String> snippetLane,
            Map<String, String> snippetQuery,
            String parentQuery) {
        if (snippets == null || snippets.isEmpty()) {
            return List.of();
        }
        return toSelfAskContents(
                snippets,
                snippetLane,
                snippetQuery,
                parentQuery,
                Math.max(1, finalTopK > 0 ? finalTopK : overallTopK));
    }

    /**
     * Hard timeout for the caller-owned Self-Ask task handle.
     */
    private SearchAttempt getWithHardTimeout(Future<SearchAttempt> future, long timeoutMs, String keyword) {
        if (future == null) {
            return new SearchAttempt(List.of(), "missing_future", true);
        }
        boolean completed = future.isDone();
        if (timeoutMs <= 0 && !completed) {
            boolean cancelled = cancelSearchAttempt(future);
            SelfAskTimeoutTrace.recordCancellationRequested(
                    "deadline_exhausted", timeoutMs, keyword, false, cancelled);
            return new SearchAttempt(List.of(), "timeout", true);
        }
        try {
            SearchAttempt attempt = completed ? future.get() : future.get(timeoutMs, TimeUnit.MILLISECONDS);
            return attempt == null ? new SearchAttempt(List.of(), "zero-results", false) : attempt;
        } catch (TimeoutException te) {
            boolean cancelled = cancelSearchAttempt(future);
            SelfAskTimeoutTrace.recordCancellationRequested(
                    "hard_timeout", timeoutMs, keyword, false, cancelled);
            log.debug("[SelfAsk] hard timeout ({}ms) qHash={}", timeoutMs, SafeRedactor.hash12(keyword));
            return new SearchAttempt(List.of(), "timeout", true);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            boolean cancelled = cancelSearchAttempt(future);
            SelfAskTimeoutTrace.recordCancellationRequested(
                    "interrupted_wait", timeoutMs, keyword, true, cancelled);
            log.debug("[SelfAsk] interrupted while waiting qHash={} (interrupt restored)", SafeRedactor.hash12(keyword));
            return new SearchAttempt(List.of(), "interrupted", true);
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause() == null ? ee : ee.getCause();
            String failureClass = classifySearchFailure(cause);
            log.debug("[SelfAsk] keyword search failed qHash={} failureClass={}", SafeRedactor.hash12(keyword), failureClass);
            return new SearchAttempt(List.of(), failureClass, true);
        } catch (Exception e) {
            cancelSearchAttempt(future);
            String failureClass = classifySearchFailure(e);
            log.debug("[SelfAsk] keyword search failed qHash={} failureClass={}", SafeRedactor.hash12(keyword), failureClass);
            return new SearchAttempt(List.of(), failureClass, true);
        }
    }

    private List<String> safeSearch(String q, int k) {
        return safeSearchAttempt(q, k).results();
    }

    private SearchAttempt safeSearchAttempt(String q, int k) {
        try {
            if (!StringUtils.hasText(q)) {
                return new SearchAttempt(List.of(), "zero-results", false);
            }
            if (Thread.currentThread().isInterrupted()) {
                return new SearchAttempt(List.of(), "cancelled", true);
            }
            if (webSearchProvider == null || !webSearchProvider.isEnabled()) {
                return new SearchAttempt(List.of(), "provider-disabled", true);
            }
            List<String> out = webSearchProvider.search(q, k);
            if (Thread.currentThread().isInterrupted()) {
                return new SearchAttempt(List.of(), "cancelled", true);
            }
            return new SearchAttempt(out, out == null || out.isEmpty() ? "zero-results" : "none", false);
        } catch (Exception e) {
            String failureClass = classifySearchFailure(e);
            log.warn("[SelfAsk] search failed qHash={} failureClass={}", SafeRedactor.hash12(q), failureClass);
            return new SearchAttempt(List.of(), failureClass, true);
        }
    }
    /** LLM 호출 없이 간단 확장(최대 followupsPerLevel개) - 도메인 민감 */
    private List<String> heuristicFollowups(String parent) {
        if (!StringUtils.hasText(parent)) return List.of();
        boolean isGenshin = (domainDetector != null)
                && "GENSHIN".equalsIgnoreCase(domainDetector.detect(parent));
        List<String> cands = isGenshin
                ? List.of(parent + " 파티 조합", parent + " 시너지", parent + " 상성", parent + " 추천 파티")
                : List.of(parent + " 개요", parent + " 핵심 포인트");
        return cands.stream()
                .limit(Math.max(1, followupsPerLevel))
                .toList();
    }

    /**
     * 로컬 휴리스틱: 실시간 패치/공지 질의 여부
     */


    private static List<String> splitLines(String raw) {
        if (!StringUtils.hasText(raw)) return List.of();
        return Arrays.stream(raw.split("\\R+"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();
    }

    private List<String> rephrase(String q) {
        if (q == null || q.isBlank()) return List.of();
        // 필요하면 더 똑똑하게 확장
        return List.of(q, q + " 후기", q + " 정리", q + " 요약");
    }




    // [HARDENING] ensure SID metadata is present on every query
    private dev.langchain4j.rag.query.Query ensureSidMetadata(dev.langchain4j.rag.query.Query original, String sessionKey) {
        java.util.Map<String, Object> md = new java.util.LinkedHashMap<>(QueryUtils.metadata(original));
        md.put(com.example.lms.service.rag.LangChainRAGService.META_SID, sessionKey);
        return QueryUtils.buildQuery(original.text(), md);
    }

}
