package com.example.lms.service;
import com.example.lms.prompt.PromptContext;
// 상단 import 블록에 추가
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CancellationException;

import com.example.lms.prompt.PromptBuilder;
import com.example.lms.model.ModelRouter;
import com.example.lms.service.rag.ContextOrchestrator;
import com.example.lms.service.rag.HybridRetriever;
import com.example.lms.service.verbosity.VerbosityDetector;
import com.example.lms.service.verbosity.VerbosityProfile;
import com.example.lms.service.verbosity.SectionSpecGenerator;
import com.example.lms.service.answer.LengthVerifierService;
import com.example.lms.service.answer.AnswerExpanderService;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import java.util.*;


import com.example.lms.search.QueryHygieneFilter;
import org.springframework.beans.factory.annotation.Qualifier;
import com.example.lms.domain.enums.RulePhase;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.entity.CurrentModel;
import com.example.lms.repository.CurrentModelRepository;
import com.example.lms.service.fallback.FallbackResult;
import com.example.lms.service.NaverSearchService;
import com.example.lms.service.rag.rerank.CrossEncoderReranker;
import java.util.LinkedHashSet;
import java.util.Collections;
import java.util.List;
import com.example.lms.service.QueryAugmentationService;
import com.example.lms.prompt.PromptEngine;
import org.springframework.cache.annotation.Cacheable;
import com.example.lms.service.fallback.SmartFallbackService;
import com.example.lms.service.disambiguation.QueryDisambiguationService;
import com.example.lms.service.disambiguation.DisambiguationResult;
import com.example.lms.service.ChatHistoryService;
import com.example.lms.service.fallback.FallbackHeuristics;
import static com.example.lms.service.rag.LangChainRAGService.META_SID;
import com.example.lms.service.rag.QueryComplexityGate;
import jakarta.annotation.PostConstruct;
import com.example.lms.service.rag.HybridRetriever;
import com.example.lms.service.rag.pre.QueryContextPreprocessor;
/* ---------- OpenAI-Java ---------- */
import com.example.lms.service.MemoryReinforcementService;
import com.example.lms.service.PromptService;
import com.example.lms.service.RuleEngine;
import java.util.function.Function;    // ✅ 새로 추가
import com.theokanning.openai.completion.chat.ChatCompletionRequest;
import com.theokanning.openai.completion.chat.ChatCompletionResult;
import com.theokanning.openai.completion.chat.ChatMessageRole;
import com.theokanning.openai.service.OpenAiService;

import com.example.lms.service.FactVerifierService;  // 검증 서비스 주입
// + 신규 공장
import com.example.lms.llm.DynamicChatModelFactory;
// (유지) dev.langchain4j.model.chat.ChatModel
// - chains 캐시용 Caffeine import들 제거

/* ---------- LangChain4j ---------- */
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
// import 블록
import java.util.stream.Stream;          // buildUnifiedContext 사용
import java.util.stream.Collectors;
// (정리) 미사용 OpenAiChatModel import 제거
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;



/* ---------- RAG ---------- */
import com.example.lms.service.rag.LangChainRAGService;
import dev.langchain4j.memory.chat.ChatMemoryProvider;    // OK

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

// ① import

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

// (다른 import 들 모여 있는 곳에 아래 한 줄을 넣어 주세요)



// import 블록 맨 아래쯤
import dev.langchain4j.memory.ChatMemory;        // ✔ 실제 버전에 맞게 교정
import com.example.lms.transform.QueryTransformer;          // ⬅️ 추가
import com.example.lms.search.SmartQueryPlanner;          // ⬅️ NEW: 지능형 쿼리 플래너
//  hybrid retrieval content classes
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import com.example.lms.service.rag.ContextOrchestrator;
// 🔹 NEW: ML correction util
import com.example.lms.util.MLCalibrationUtil;
import com.example.lms.service.correction.QueryCorrectionService;   // ★ 추가
import org.springframework.beans.factory.annotation.Qualifier; // Qualifier import 추가
import com.example.lms.search.SmartQueryPlanner;
import org.springframework.beans.factory.annotation.Autowired;   // ← 추가

import com.example.lms.service.rag.rerank.CrossEncoderReranker;
/**
 * 중앙 허브 – OpenAI-Java · LangChain4j · RAG 통합. (v7.2, RAG 우선 패치 적용)
 * <p>
 * - LangChain4j 1.0.1 API 대응
 * - "웹‑RAG 우선" 4‑Point 패치(프롬프트 강화 / 메시지 순서 / RAG 길이 제한 / 디버그 로그) 반영
 * </p>
 *
 * <p>
 * 2024‑08‑06: ML 기반 보정/보강/정제/증강 기능을 도입했습니다.  새로운 필드
 * {@code mlAlpha}, {@code mlBeta}, {@code mlGamma}, {@code mlMu},
 * {@code mlLambda} 및 {@code mlD0} 은 application.yml 에서 조정할 수
 * 있습니다.  {@link MLCalibrationUtil} 를 사용하여 LLM 힌트 검색 또는
 * 메모리 강화를 위한 가중치를 계산할 수 있으며, 본 예제에서는
 * {@link #reinforceAssistantAnswer(String, String, String)} 내에서
 * 문자열 길이를 거리 d 로 사용하여 가중치 점수를 보정합니다.
 * 실제 사용 시에는 도메인에 맞는 d 값을 입력해 주세요.
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {
    private final @Qualifier("queryTransformer") QueryTransformer queryTransformer;
    @Autowired
    @Qualifier("embeddingCrossEncoderReranker")
    private CrossEncoderReranker reranker;

    /* ───────────────────────────── DTO ───────────────────────────── */

    /**
     * 컨트롤러 ↔ 서비스 간 정형 응답 객체.
     */
    public static record ChatResult(String content, String modelUsed, boolean ragUsed, java.util.Set<String> evidence) {
        /**
         * @deprecated: modelUsed() 로 대체
         */
        @Deprecated
        public String model() {
            return modelUsed();
        }

        public static ChatResult of(String c, String m, boolean r) {
            return new ChatResult(c, m, r, java.util.Set.of());
        }
        public static ChatResult of(String c, String m, boolean r, java.util.Set<String> e) {
            return new ChatResult(c, m, r, e == null ? java.util.Set.of() : e);
        }
    } // ← record ChatResult 스코프 닫기 (필수)


    /* ───────────────────────────── DI ────────────────────────────── */

    private final ChatHistoryService chatHistoryService;
    private final QueryDisambiguationService disambiguationService;
    private final OpenAiService openAi;     // OpenAI-Java SDK
    private final ChatModel chatModel;  // 기본 LangChain4j ChatModel
    private final PromptService promptSvc;
    private final CurrentModelRepository modelRepo;
    private final RuleEngine ruleEngine;
    private final MemoryReinforcementService memorySvc;
    private final FactVerifierService verifier;     // ★ 신규 주입
    // - private final ChatModel chatModel;  // 고정 모델 주입 제거
// + 동적 모델 공장 주입
    private final DynamicChatModelFactory chatModelFactory;

// - 체인 캐시 삭제
// private final com.github.benmanes.caffeine.cache.LoadingCache<String, ConversationalRetrievalChain> chains = ...

    private final LangChainRAGService ragSvc;

    // 이미 있는 DI 필드 아래쪽에 추가
    private final NaverSearchService searchService;
    private final ChatMemoryProvider chatMemoryProvider; // 세션 메모리 Bean

    private final QueryContextPreprocessor qcPreprocessor; // ★ 동적 규칙 전처리기

    // ▼▼ 신규 DI
    private final com.example.lms.strategy.StrategySelectorService strategySelector;
    private final com.example.lms.strategy.StrategyDecisionTracker strategyTracker;
    private final com.example.lms.scoring.ContextualScorer contextualScorer;
    private final QueryAugmentationService augmentationSvc; // ★ 질의 향상 서비스

    private final SmartQueryPlanner smartQueryPlanner;     // ⬅️ NEW DI
    private final QueryCorrectionService correctionSvc;         // ★ 추가
    // 🔹 NEW: 다차원 누적·보강·합성기
    // 🔹 단일 패스 오케스트레이션을 위해 체인 캐시는 제거


    @Qualifier("defaultPromptEngine")
    private final PromptEngine promptEngine;

    private final SmartFallbackService fallbackSvc;
    // 🔧 신규 오케스트레이터 주입 (RequiredArgsConstructor로 자동 주입)
// 🔧 오케스트레이터 주입
    private final ContextOrchestrator contextOrchestrator;
    private final HybridRetriever hybridRetriever;
    private final PromptBuilder promptBuilder;
    private final ModelRouter modelRouter;
    // ▼ Verbosity & Expansion
    private final VerbosityDetector verbosityDetector;
    private final SectionSpecGenerator sectionSpecGenerator;
    private final LengthVerifierService lengthVerifier;
    private final AnswerExpanderService answerExpander;
    // ▼ Memory evidence I/O
    private final com.example.lms.service.rag.handler.MemoryHandler memoryHandler;
    private final com.example.lms.service.rag.handler.MemoryWriteInterceptor memoryWriteInterceptor;
    // 신규: 학습 기록 인터셉터
    private final com.example.lms.learning.gemini.LearningWriteInterceptor learningWriteInterceptor;
    // 신규: 이해 요약 및 기억 모듈 인터셉터
    private final com.example.lms.service.chat.interceptor.UnderstandAndMemorizeInterceptor understandAndMemorizeInterceptor;
    /** In‑flight cancel flags per session (best‑effort) */
    private final ConcurrentHashMap<Long, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();

    @Value("${rag.hybrid.top-k:50}") private int hybridTopK;
    @Value("${rag.rerank.top-n:10}") private int rerankTopN;
    // ▼ reranker keep-top-n by verbosity
    @Value("${reranker.keep-top-n.brief:5}")     private int keepNBrief;
    @Value("${reranker.keep-top-n.standard:8}")  private int keepNStd;
    @Value("${reranker.keep-top-n.deep:12}")     private int keepNDeep;
    @Value("${reranker.keep-top-n.ultra:16}")    private int keepNUltra;
    /**
     * 하이브리드 우회(진단용): true면 HybridRetriever를 건너뛰고 단일패스로 처리
     */
    @Value("${debug.hybrid.bypass:false}")
    private boolean bypassHybrid;

    /* ─────────────────────── 설정 (application.yml) ─────────────────────── */
    // 기존 상수 지워도 되고 그대로 둬도 상관없음

    @Value("${openai.web-context.max-tokens:8000}")
    private int defaultWebCtxMaxTokens;         // 🌐 Live-Web 최대 토큰

    @Value("${openai.mem-context.max-tokens:7500}")
    private int defaultMemCtxMaxTokens;     // ★

    @Value("${openai.rag-context.max-tokens:5000}")
    private int defaultRagCtxMaxTokens;     // ★
    @Value("${openai.api.key:}")
    private String openaiApiKey;
    @Value("${openai.api.model:gpt-3.5-turbo}")
    private String defaultModel;
    @Value("${openai.fine-tuning.custom-model-id:}")
    private String tunedModelId;
    @Value("${openai.api.temperature.default:0.7}") private double defaultTemp;
    @Value("${openai.api.top-p.default:1.0}")       private double defaultTopP;
    @Value("${openai.api.history.max-messages:6}")
    private int maxHistory;
    // ChatService 클래스 필드 섹션에
    @Value("${pinecone.index.name}")
    private String pineconeIndexName;

    /* ──────────────── Memory 패치: 프롬프트 ──────────────── */

    /* 🔸 공식 출처 도메인 화이트리스트(패치/공지류) */
    @Value("${search.official.domains:genshin.hoyoverse.com,hoyolab.com,youtube.com/@GenshinImpact,x.com/GenshinImpact}")
    private String officialDomainsCsv;

    // WEB 스니펫은 이미 HTML 링크 형태(- <a href="...">제목</a>: 요약)로 전달됨.
    // 아래 프리픽스는 모델용 컨텍스트 힌트이며, 실제 화면에는 ChatApiController가 따로 '검색 과정' 패널을 붙인다.
    private static final String WEB_PREFIX = """
                  ### LIVE WEB RESULTS
                  %s
                 
                  - Extract concrete dates (YYYY-MM-DD) if present.
                  - Cite site titles in parentheses.
                  """;

    /* 폴리싱용 시스템 프롬프트 (단일 정의) */
    private static final String POLISH_SYS_PROMPT =
            "다음 초안을 더 자연스럽고 전문적인 한국어로 다듬어 주세요. 새로운 정보는 추가하지 마세요.";
    /* ──────────────── RAG 패치: 프롬프트 강화 ──────────────── */
    private static final String RAG_PREFIX = """
                  ### CONTEXT
                  %s
                 
                  ### INSTRUCTIONS
                  - Synthesize an answer from all available sections (web, vector‑RAG, memory).
                  - When sources conflict, give higher weight to **official domains** (e.g., *.hoyoverse.com, hoyolab.com)
                    and be cautious with **community/fan sites** (e.g., fandom.com, personal blogs).
                  - Cite the source titles when you answer.
                            - Do NOT guess or invent facts. If the Context does not explicitly mention a named entity
                                                                                           (character/item/region), do NOT include it in the answer.
                                                                                         - For **pairing/synergy** questions:
                                                                                             * Recommend character pairs **only if** the Context explicitly states that they work well together
                                                                                               (e.g., "잘 어울린다", "시너지", "조합", "함께 쓰면 좋다").
                                                                                             * **Do NOT** recommend pairs based solely on stat comparisons, example lists, or mere co-mentions.
                  - If the information is insufficient or conflicting from low‑authority sources only, reply "정보 없음".
                  """;
    private static final String MEM_PREFIX = """
                  ### LONG-TERM MEMORY
                  %s
                  """;

    /* ═════════════════════ ML 보정 파라미터 ═════════════════════ */
    /**
     * Machine learning based correction parameters.  These values can be
     * configured via application.yml using keys under the prefix
     * {@code ml.correction.*}.  They correspond to the α, β, γ, μ,
     * λ, and d₀ coefficients described in the specification.  See
     * {@link MLCalibrationUtil} for details.
     */
    @Value("${ml.correction.alpha:0.0}")
    private double mlAlpha;
    @Value("${ml.correction.beta:0.0}")
    private double mlBeta;
    @Value("${ml.correction.gamma:0.0}")
    private double mlGamma;
    @Value("${ml.correction.mu:0.0}")
    private double mlMu;
    @Value("${ml.correction.lambda:1.0}")
    private double mlLambda;
    @Value("${ml.correction.d0:0.0}")
    private double mlD0;
    //  검증 기본 활성화 플래그 (application.yml: verification.enabled=true)
    @org.springframework.beans.factory.annotation.Value("${verification.enabled:true}")
    private boolean verificationEnabled;

    /* ═════════════════════ PUBLIC ENTRY ═════════════════════ */

    /**
     * 단일 엔드포인트. 요청 옵션에 따라 RAG, OpenAI-Java, LangChain4j 파이프라인으로 분기.
     */
    /* ───────────────────────── NEW ENTRY ───────────────────────── */
    /** RAG · Web 검색을 모두 끼워넣을 수 있는 확장형 엔드포인트 */
    // ✅ 외부 컨텍스트 없이 쓰는 단일 버전으로 교체
    // ChatService.java

    /**
     * RAG · WebSearch · Stand-Alone · Retrieval OFF 모두 처리하는 통합 메서드
     */
    // ① 1-인자 래퍼 ─ 컨트롤러가 호출
    @Cacheable(
            value = "chatResponses",
            // 캐시 키는 세션과 모델별로 격리: 동일 메시지라도 세션·모델이 다르면 별도 저장
            key = "#req.sessionId + ':' + #req.model + ':' + #req.message + ':' + #req.useRag + ':' + #req.useWebSearch"
    )
    public ChatResult continueChat(ChatRequestDto req) {
        Function<String, List<String>> defaultProvider =
                q -> searchService.searchSnippets(q, 5);    // 네이버 Top-5
        return continueChat(req, defaultProvider);        // ↓ ②로 위임
    }

    // ── intent/risk/로깅 유틸 ─────────────────────────────────────
    private String inferIntent(String q) {
        try { return qcPreprocessor.inferIntent(q); } catch (Exception e) { return "GENERAL"; }
    }

    private String detectRisk(String q) {
        if (q == null) return null;
        String s = q.toLowerCase(java.util.Locale.ROOT);
        return s.matches(".*(진단|처방|증상|법률|소송|형량|투자|수익률|보험금).*") ? "HIGH" : null;
    }

    private static String getModelName(dev.langchain4j.model.chat.ChatModel m) {
        return (m == null) ? "unknown" : m.getClass().getSimpleName();
    }

    private void reinforce(String sessionKey, String query, String answer) {
        try { reinforceAssistantAnswer(sessionKey, query, answer); } catch (Throwable ignore) {}
    }

    /**
     * 의도 분석을 통해 최종 검색 쿼리를 결정한다.
     */
    /**
     * 사용자의 원본 쿼리와 LLM이 재작성한 쿼리 중 최종적으로 사용할 쿼리를 결정합니다.
     * 재작성된 쿼리가 유효하고, 모델이 그 결과에 자신감을 보일 때만 재작성된 쿼리를 사용합니다.
     *
     * @param originalQuery 사용자의 원본 입력 쿼리
     * @param r             QueryRewriteResult, 재작성된 쿼리와 신뢰도 점수를 포함
     * @return 최종적으로 RAG 검색에 사용될 쿼리 문자열
     */

    private String decideFinalQuery(String originalQuery, Long sessionId) {
        if (originalQuery == null || originalQuery.isBlank()) return originalQuery;
        List<String> history = (sessionId != null)
                ? chatHistoryService.getFormattedRecentHistory(sessionId, 5)
                : java.util.Collections.emptyList();

        DisambiguationResult r = disambiguationService.clarify(originalQuery, history);
        if (r != null && r.isConfident() && r.getRewrittenQuery() != null && !r.getRewrittenQuery().isBlank()) {
            return r.getRewrittenQuery();
        }
        return originalQuery; // ← 이 줄이 반드시 있어야 함
    }

    // ② 2-인자 실제 구현 (헤더·중괄호 반드시 포함!)
    public ChatResult continueChat(ChatRequestDto req,
                                   Function<String, List<String>> externalCtxProvider) {

        // ── 세션키 정규화(단일 키 전파) ───────────────────────────────
        String sessionKey = Optional.ofNullable(req.getSessionId())
                .map(String::valueOf)
                .map(s -> s.startsWith("chat-") ? s : (s.matches("\\d+") ? "chat-" + s : s))
                .orElse(UUID.randomUUID().toString());

        // ── 0) 사용자 입력 확보 ─────────────────────────────────────
        final String userQuery = Optional.ofNullable(req.getMessage()).orElse("");
        if (userQuery.isBlank()) {
            return ChatResult.of("정보 없음", "lc:" + chatModel.getClass().getSimpleName(), true);
        }

        // ── 0-A) 세션ID 정규화 & 쿼리 재작성(Disambiguation) ─────────
        Long sessionIdLong = parseNumericSessionId(req.getSessionId());
        throwIfCancelled(sessionIdLong);  // ★ 추가
        final String finalQuery = decideFinalQuery(userQuery, sessionIdLong);
        // ── 0-1) Verbosity 감지 & 섹션 스펙 ─────────────────────────
        VerbosityProfile vp = verbosityDetector.detect(finalQuery);
        String intent = inferIntent(finalQuery);
        List<String> sections = sectionSpecGenerator.generate(intent, /*domain*/"", vp.hint());

        // ── 1) 검색/융합: Self-Ask → HybridRetriever → Cross-Encoder Rerank ─
        // 0‑2) Retrieval 플래그

        boolean useWeb = req.isUseWebSearch();
        boolean useRag = req.isUseRag();

        // 1) (옵션) 웹 검색 계획 및 실행
        List<String> planned = List.of();
        List<dev.langchain4j.rag.content.Content> fused = List.of();
        if (useWeb) {
            planned = smartQueryPlanner.plan(finalQuery, /*assistantDraft*/ null, /*maxBranches*/ 2);
            if (planned.isEmpty()) planned = List.of(finalQuery);
            fused = hybridRetriever.retrieveAll(planned, hybridTopK);
        }
        // planned / fused 생성한 다음쯤
        throwIfCancelled(sessionIdLong);  // ★ 추가
        Map<String, Set<String>> rules = qcPreprocessor.getInteractionRules(finalQuery);

        int keepN = switch (Objects.toString(vp.hint(), "standard").toLowerCase(Locale.ROOT)) {
            case "brief" -> keepNBrief;
            case "deep"  -> Math.max(rerankTopN, keepNDeep);
            case "ultra" -> Math.max(rerankTopN, keepNUltra);
            default      -> keepNStd;
        };

        List<dev.langchain4j.rag.content.Content> topDocs =
                (useWeb && !fused.isEmpty())
                        ? reranker.rerank(finalQuery, fused, keepN, rules)
                        : List.of();

        // 1‑b) (옵션) RAG(Vector) 조회
        List<dev.langchain4j.rag.content.Content> vectorDocs =
                useRag
                        ? ragSvc.asContentRetriever(pineconeIndexName)
                        .retrieve(dev.langchain4j.rag.query.Query.from(finalQuery))
                        : List.of();

        // 1-c) 메모리 컨텍스트(항상 시도) — 전담 핸들러 사용
        String memoryCtx = memoryHandler.loadForSession(req.getSessionId());

        // ── 2) 명시적 맥락 생성(Verbosity-aware) ────────────────────────
        // 세션 ID(Long) 파싱: 최근 assistant 답변 & 히스토리 조회에 사용

        String lastAnswer = (sessionIdLong == null)
                ? null
                : chatHistoryService.getLastAssistantMessage(sessionIdLong).orElse(null);
        String historyStr = (sessionIdLong == null)
                ? ""
                : String.join("\n", chatHistoryService.getFormattedRecentHistory(sessionIdLong, Math.max(2, Math.min(maxHistory, 8))));

        // PromptContext에 모든 상태를 '명시적으로' 수집
        var ctx = com.example.lms.prompt.PromptContext.builder()
                .userQuery(userQuery)
                .lastAssistantAnswer(lastAnswer)
                .history(historyStr)
                .web(topDocs)                // 웹/하이브리드 결과 (비어있을 수 있음)
                .rag(vectorDocs)             // 벡터 RAG 결과 (비어있을 수 있음)
                .memory(memoryCtx)           // 세션 장기 메모리 요약
                .interactionRules(rules)     // 동적 관계 규칙
                .verbosityHint(vp.hint())    // brief|standard|deep|ultra
                .minWordCount(vp.minWordCount())
                .sectionSpec(sections)
                .citationStyle("inline")
                .build();

        // PromptBuilder가 컨텍스트 본문과 시스템 인스트럭션을 분리 생성
        String ctxText  = promptBuilder.build(ctx);
        String instrTxt = promptBuilder.buildInstructions(ctx);
        // (기존 출력 정책과 병합 — 섹션 강제 등)
        String outputPolicy = buildOutputPolicy(vp, sections);
        String unifiedCtx   = ctxText; // 컨텍스트는 별도 System 메시지로

        // ── 3) 모델 라우팅(상세도/리스크/의도) ───────────────────────
        ChatModel model = modelRouter.route(
                intent,
                detectRisk(userQuery),           // "HIGH"|"LOW"|etc. (기존 헬퍼)
                vp.hint(),                       // brief|standard|deep|ultra
                vp.targetTokenBudgetOut()        // 출력 토큰 예산 힌트
        );

        // ── 4) 메시지 구성(출력정책 포함) ────────────────────────────
        var msgs = new ArrayList<dev.langchain4j.data.message.ChatMessage>();
        // ① 컨텍스트(자료 영역)
        msgs.add(dev.langchain4j.data.message.SystemMessage.from(unifiedCtx));
        // ② 빌더 인스트럭션(우선)  ③ 출력 정책(보조) — 분리 주입
        if (org.springframework.util.StringUtils.hasText(instrTxt)) {
            msgs.add(dev.langchain4j.data.message.SystemMessage.from(instrTxt));
        }
        if (org.springframework.util.StringUtils.hasText(outputPolicy)) {
            msgs.add(dev.langchain4j.data.message.SystemMessage.from(outputPolicy));
        }
        // ④ 사용자 질문
        msgs.add(dev.langchain4j.data.message.UserMessage.from(finalQuery));

        // ── 5) 단일 호출 → 초안 ─────────────────────────────────────
        // 모델 라우팅을 마친 뒤, 실제 chat() 호출 바로 직전
        throwIfCancelled(sessionIdLong);  // ★ 추가
        String draft = model.chat(msgs).aiMessage().text();

        String verified = shouldVerify(unifiedCtx, req)
                ? verifier.verify(finalQuery, /*context*/ unifiedCtx, /*memory*/ memoryCtx, draft, "gpt-4o",
                isFollowUpQuery(finalQuery, lastAnswer))
                : draft;

        // ── 6) 길이 검증 → 조건부 1회 확장 ───────────────────────────
        String out = verified;
        if (lengthVerifier.isShort(out, vp.minWordCount())) {
            out = Optional.ofNullable(answerExpander.expandWithLc(out, vp, model)).orElse(out);
        }

        // ── 7) 후처리/강화/리턴 ──────────────────────────────────────
        // (항상 저장) – 인터셉터  + 기존 강화 로직 병행 허용
        try {
            // 먼저 학습용 인터셉터에 전달하여 구조화된 지식 학습을 수행합니다.
            learningWriteInterceptor.ingest(sessionKey, userQuery, out, /*score*/ 0.5);
        } catch (Throwable ignore) {
            // swallow errors to avoid breaking the chat flow
        }
        try {
            memoryWriteInterceptor.save(sessionKey, userQuery, out, /*score*/ 0.5);
        } catch (Throwable ignore) {}
        // 이해 요약 및 기억 인터셉터: 검증/확장된 최종 답변을 구조화 요약하여 저장하고 SSE로 전송
        try {
            understandAndMemorizeInterceptor.afterVerified(
                    sessionKey,
                    userQuery,
                    out,
                    req.isUnderstandingEnabled());
        } catch (Throwable ignore) {
            // swallow errors to avoid breaking the chat flow
        }
        reinforce(sessionKey, userQuery, out);
        // ✅ 실제 모델명으로 보고 (실패 시 안전 폴백)
        String modelUsed;
        try {
            modelUsed = modelRouter.resolveModelName(model);
        } catch (Exception e) {
            modelUsed = "lc:" + getModelName(model);
        }
        // 증거 집합 정리
        java.util.LinkedHashSet<String> evidence = new java.util.LinkedHashSet<>();
        if (useWeb && !topDocs.isEmpty()) evidence.add("WEB");
        if (useRag && !vectorDocs.isEmpty()) evidence.add("RAG");
        if (memoryCtx != null && !memoryCtx.isBlank()) evidence.add("MEMORY");
        boolean ragUsed = evidence.contains("WEB") || evidence.contains("RAG");
        clearCancel(sessionIdLong);       // ★ 추가

        return ChatResult.of(out, modelUsed, ragUsed, java.util.Collections.unmodifiableSet(evidence));
    } // ② 메서드 끝!  ←★★ 반드시 닫는 중괄호 확인


    /**
     * 세션 ID(Object) → Long 변환. "123" 형태만 Long, 그외는 null.
     */
    private static Long parseNumericSessionId(Object raw) {
        if (raw == null) return null;
        String s = String.valueOf(raw).trim();
        return s.matches("\\d+") ? Long.valueOf(s) : null;
    }

// ------------------------------------------------------------------------

    private static String buildOutputPolicy(VerbosityProfile vp, List<String> sections) {
        String vh = Objects.toString(vp.hint(), "standard");
        if (!("deep".equalsIgnoreCase(vh) || "ultra".equalsIgnoreCase(vh))) return "";
        StringBuilder sb = new StringBuilder("### OUTPUT POLICY\n");
        sb.append("- Do not be brief; respond with rich details.\n");
        if (vp.minWordCount() > 0) {
            sb.append("- Minimum length: ").append(vp.minWordCount()).append(" Korean words.\n");
        }
        if (sections != null && !sections.isEmpty()) {
            sb.append("- Required sections (use these headers in Korean): ")
                    .append(String.join(", ", sections)).append('\n');
        }
        return sb.toString();
    }

    // (삭제) loadMemoryContext(...) — MemoryHandler로 일원화




    /* ───────────────────────── BACKWARD-COMPAT ───────────────────────── */

    /**
     * (호환용) 외부 컨텍스트 없이 사용하던 기존 시그니처
     */



    /* ---------- 편의 one‑shot ---------- */
    public ChatResult ask(String userMsg) {
        return continueChat(ChatRequestDto.builder()
                .message(userMsg)
                .build());
    }

    /* ═════════ OpenAI‑Java 파이프라인 (2‑Pass + 검증) ═════════ */

    /**
     * OpenAI‑Java 파이프라인 – 단일 unifiedCtx 인자 사용
     */
    private ChatResult invokeOpenAiJava(ChatRequestDto req, String unifiedCtx) {

        /* 세션 키 일관 전파 – 메모리 강화에서 필수 */
        String sessionKey = extractSessionKey(req);
        // OFF 경로(단독 호출)에서는 여기서 교정 1회 적용
        final String originalMsg = Optional.ofNullable(req.getMessage()).orElse("");
        final String correctedMsg = correctionSvc.correct(originalMsg);

        String modelId = chooseModel(req.getModel(), false);

        List<com.theokanning.openai.completion.chat.ChatMessage> msgs = new ArrayList<>();
        addSystemPrompt(msgs, req.getSystemPrompt());
        /* 병합된 컨텍스트 한 번만 주입 */
        addContextOai(msgs, "%s", unifiedCtx, defaultWebCtxMaxTokens + defaultRagCtxMaxTokens + defaultMemCtxMaxTokens);
        appendHistoryOai(msgs, req.getHistory());
        appendUserOai(msgs, correctedMsg);

        ChatCompletionRequest apiReq = ChatCompletionRequest.builder()
                .model(modelId)
                .messages(msgs)
                .temperature(Optional.ofNullable(req.getTemperature()).orElse(defaultTemp))
                .topP(Optional.ofNullable(req.getTopP()).orElse(defaultTopP))
                .frequencyPenalty(req.getFrequencyPenalty())
                .presencePenalty(req.getPresencePenalty())
                .maxTokens(req.getMaxTokens())
                .build();

        try {
            ChatCompletionResult res = openAi.createChatCompletion(apiReq);
            String draft = res.getChoices().get(0).getMessage().getContent();

            /* ─── ① LLM-힌트 기반 보강 검색 ─── */
            List<String> hintSnippets = searchService.searchSnippets(
                    correctedMsg,       // 교정된 질문
                    draft,              // 1차 초안
                    5);                 // top-k
            String hintWebCtx = hintSnippets.isEmpty()
                    ? null
                    : String.join("\n", hintSnippets);

            /* ─── ② 컨텍스트 병합 & 검증 ─── */
            String joinedContext = Stream.of(unifiedCtx, hintWebCtx)
                    .filter(StringUtils::hasText)
                    .collect(Collectors.joining("\n"));
            String memCtx = Optional.ofNullable(memoryHandler.loadForSession(req.getSessionId())).orElse("");
            boolean followUp = isFollowUpQuery(correctedMsg, /*lastAnswer*/ memCtx);
            String verified = shouldVerify(joinedContext, req)
                    ? verifier.verify(correctedMsg, joinedContext, memCtx, draft, "gpt-4o", followUp)
                    : draft;
            /* ─── ② (선택) 폴리싱 ─── */
            /* ─── ② 경고‑배너 & (선택) 폴리싱 ─── */
            boolean insufficientContext = !StringUtils.hasText(joinedContext);

            boolean verifiedUsed = shouldVerify(joinedContext, req);
            boolean fallbackHappened = verifiedUsed
                    && StringUtils.hasText(joinedContext)
                    && verified.equals(draft);          // 검증이 변화 못 줌

            String warning = "\n\n⚠️ 본 답변은 검증된 정보가 부족하거나 부정확할 수 있습니다. 참고용으로 활용해 주세요.";
// ★ 스마트 폴백: '정보 없음' 또는 컨텍스트 빈약 시, 친절한 교정/대안 제시
            FallbackResult fb = fallbackSvc.maybeSuggestDetailed(correctedMsg, joinedContext, verified);
            String smart = (fb != null ? fb.suggestion() : null);
            String toPolish = pickForPolish(smart, verified, insufficientContext, fallbackHappened, warning);


            String finalText = req.isPolish()
                    ? polishAnswerOai(toPolish, modelId,
                    req.getMaxTokens(),
                    req.getTemperature(),
                    req.getTopP())
                    : toPolish;

            /* ─── ③ 후처리 & 메모리 ─── */
            String out = ruleEngine.apply(finalText, "ko", RulePhase.POST);
            //  폴백 여부에 따라 태깅하여 강화
            String srcTag = (fb != null && fb.isFallback()) ? "SMART_FALLBACK" : "ASSISTANT";
            // 이해 요약 및 기억 인터셉터를 먼저 실행하여 요약을 저장하고 SSE로 전송한다.
            try {
                understandAndMemorizeInterceptor.afterVerified(
                        sessionKey,
                        correctedMsg,
                        out,
                        req.isUnderstandingEnabled());
            } catch (Throwable ignore) {
                // swallow errors to avoid breaking the chat flow
            }
            try {
                memorySvc.reinforceWithSnippet(sessionKey, correctedMsg, out, srcTag, /*score*/ 0.5);
            } catch (Throwable ignore) {}
            return ChatResult.of(out, modelId, req.isUseRag());

        } catch (Exception ex) {
            log.error("[OpenAI-Java] 호출 실패", ex);
            return ChatResult.of("OpenAI 오류: " + ex.getMessage(), modelId, req.isUseRag());
        }
    }

    /* ---------- 공통 util : context 주입 ---------- */
    private void addContextOai(
            List<com.theokanning.openai.completion.chat.ChatMessage> l,
            String prefix,
            String ctx,
            int limit) {
        if (StringUtils.hasText(ctx)) {
            l.add(new com.theokanning.openai.completion.chat.ChatMessage(
                    ChatMessageRole.SYSTEM.value(),
                    String.format(prefix, truncate(ctx, limit))
            ));
        }

    }

    //  검증 여부 결정 헬퍼
    private boolean shouldVerify(String joinedContext, com.example.lms.dto.ChatRequestDto req) {
        boolean hasContext = org.springframework.util.StringUtils.hasText(joinedContext);
        Boolean flag = req.isUseVerification(); // null 가능
        boolean enabled = (flag == null) ? verificationEnabled : Boolean.TRUE.equals(flag);
        return hasContext && enabled;
    }


    /* ═════════ LangChain4j 파이프라인 (2‑Pass + 검증) ═════════ */
    private ChatResult invokeLangChain(ChatRequestDto req, String unifiedCtx) {
        String sessionKey = extractSessionKey(req);
        // OFF 경로(단독 호출)에서는 여기서 교정 1회 적용
        final String originalMsg = Optional.ofNullable(req.getMessage()).orElse("");
        final String correctedMsg = correctionSvc.correct(originalMsg);
// 🔸 5) 단일 LLM 호출로 답변 생성
        String cleanModel = chooseModel(req.getModel(), true);
        List<ChatMessage> msgs = buildLcMessages(req, unifiedCtx); // (히스토리는 원문 유지)

        // ChatModel 인스턴스 생성을 팩토리에 위임하여 중앙 관리
        ChatModel dynamicChatModel = chatModelFactory.lc(
                cleanModel,
                Optional.ofNullable(req.getTemperature()).orElse(defaultTemp),
                Optional.ofNullable(req.getTopP()).orElse(defaultTopP),
                req.getMaxTokens()
        );

        try {
            /* ① 초안 생성 */
            if (log.isTraceEnabled()) {
                log.trace("[LC] final messages for draft → {}", msgs);
            }
            // ✔ LC4j 1.0.1 API: generate(...) → chat(...).aiMessage().text()
            String draft = dynamicChatModel.chat(msgs).aiMessage().text();

            /* ①-b LLM-힌트 기반 보강 검색 (Deep-Research) */
            List<String> hintSnippets = searchService.searchSnippets(
                    correctedMsg, draft, 5); // 질문과 초안을 바탕으로 추가 정보 검색
            String hintWebCtx = hintSnippets.isEmpty()
                    ? null
                    : String.join("\n", hintSnippets);

            /* ② 컨텍스트 병합 및 검증 */
            String joinedContext = Stream.of(unifiedCtx, hintWebCtx)
                    .filter(StringUtils::hasText)
                    .collect(Collectors.joining("\n\n"));

            String memCtx = Optional.ofNullable(memoryHandler.loadForSession(req.getSessionId())).orElse("");
            String verified = shouldVerify(joinedContext, req)
                    ? verifier.verify(correctedMsg, joinedContext, memCtx, draft, "gpt-4o")
                    : draft;

            /* ③ 경고 배너 추가 및 (선택적) 답변 폴리싱 */
            boolean insufficientContext = !StringUtils.hasText(joinedContext);
            boolean verifiedUsed = shouldVerify(joinedContext, req);
            boolean fallbackHappened = verifiedUsed
                    && StringUtils.hasText(joinedContext)
                    && verified.equals(draft);

            String warning = "\n\n⚠️ 본 답변은 검증된 정보가 부족하거나 부정확할 수 있습니다. 참고용으로 활용해 주세요.";
// ★ 스마트 폴백: '정보 없음' 또는 컨텍스트 빈약 시, 친절한 교정/대안 제시
            FallbackResult fb = fallbackSvc.maybeSuggestDetailed(correctedMsg, joinedContext, verified);
            String smart = (fb != null ? fb.suggestion() : null);
            String toPolish = pickForPolish(smart, verified, insufficientContext, fallbackHappened, warning);

            String finalText = req.isPolish()
                    ? polishAnswerLc(toPolish, dynamicChatModel) // 폴리싱 옵션 활성화 시 답변 다듬기
                    : toPolish;

            /* ④ 후처리 및 메모리 강화 */
            String out = ruleEngine.apply(finalText, "ko", RulePhase.POST);

            reinforceAssistantAnswer(sessionKey, correctedMsg, out);
            String modelUsed = modelRouter.resolveModelName(dynamicChatModel);
            return ChatResult.of(out, modelUsed, req.isUseRag());

        } catch (Exception ex) {
            log.error("[LangChain4j] API 호출 중 심각한 오류 발생. SessionKey: {}", sessionKey, ex);
            return ChatResult.of("LangChain 처리 중 오류가 발생했습니다: " + ex.getMessage(), "lc:" + cleanModel, req.isUseRag());
        }
    }

    /* ═════════ 2‑Pass Helper – 폴리싱 ═════════ */

    private String polishAnswerOai(String draft, String modelId,
                                   Integer maxTokens, Double temp, Double topP) {
        List<com.theokanning.openai.completion.chat.ChatMessage> polishMsgs = List.of(
                new com.theokanning.openai.completion.chat.ChatMessage(ChatMessageRole.SYSTEM.value(), POLISH_SYS_PROMPT),
                new com.theokanning.openai.completion.chat.ChatMessage(ChatMessageRole.USER.value(), draft)
        );

        ChatCompletionRequest polishReq = ChatCompletionRequest.builder()
                .model(modelId)
                .messages(polishMsgs)
                .temperature(Optional.ofNullable(temp).orElse(defaultTemp))
                .topP(Optional.ofNullable(topP).orElse(defaultTopP))
                .maxTokens(maxTokens)
                .build();

        return openAi.createChatCompletion(polishReq)
                .getChoices().get(0).getMessage().getContent();
    }

    private String polishAnswerLc(String draft, ChatModel chatModel) {
        List<ChatMessage> polishMsgs = List.of(
                SystemMessage.from(POLISH_SYS_PROMPT),
                UserMessage.from(draft)
        );
        return chatModel.chat(polishMsgs).aiMessage().text();
    }

    /* ════════════════ 메시지 빌더 – OpenAI‑Java ════════════════ */


    private void addSystemPrompt(List<com.theokanning.openai.completion.chat.ChatMessage> l, String custom) {
        String sys = Optional.ofNullable(custom).filter(StringUtils::hasText).orElseGet(promptSvc::getSystemPrompt);
        if (StringUtils.hasText(sys)) {
            l.add(new com.theokanning.openai.completion.chat.ChatMessage(ChatMessageRole.SYSTEM.value(), sys));
        }
    }

    // ① RAG(OpenAI-Java) 컨텍스트
    private void addRagContext(List<com.theokanning.openai.completion.chat.ChatMessage> l,
                               String ragCtx,
                               int limit) {
        if (StringUtils.hasText(ragCtx)) {
            String ctx = truncate(ragCtx, limit);
            l.add(new com.theokanning.openai.completion.chat.ChatMessage(
                    ChatMessageRole.SYSTEM.value(),
                    String.format(RAG_PREFIX, ctx)));
        }
    }


    private void appendUserOai(List<com.theokanning.openai.completion.chat.ChatMessage> l, String msg) {
        String user = ruleEngine.apply(msg, "ko", RulePhase.PRE);
        l.add(new com.theokanning.openai.completion.chat.ChatMessage(ChatMessageRole.USER.value(), user));
    }


    private void appendHistoryLc(List<dev.langchain4j.data.message.ChatMessage> l, List<ChatRequestDto.Message> hist) {
        if (!CollectionUtils.isEmpty(hist)) {
            hist.stream()
                    .skip(Math.max(0, hist.size() - maxHistory))
                    .forEach(m -> l.add("user".equalsIgnoreCase(m.getRole())
                            ? UserMessage.from(m.getContent())
                            : AiMessage.from(m.getContent())));
        }
    }


    /* 메모리 컨텍스트 */
// ② Memory(OpenAI-Java)
    private void addMemoryContextOai(List<com.theokanning.openai.completion.chat.ChatMessage> l,
                                     String memCtx,
                                     int limit) {
        if (StringUtils.hasText(memCtx)) {
            String ctx = truncate(memCtx, limit);
            l.add(new com.theokanning.openai.completion.chat.ChatMessage(
                    ChatMessageRole.SYSTEM.value(),
                    String.format(MEM_PREFIX, ctx)));
        }
    }

// - @PostConstruct initRetrievalChain() 제거
// - private ConversationalRetrievalChain createChain(String sessionKey) 제거
// - private ConversationalRetrievalChain buildChain(ChatMemory mem) 변경



    /* ════════════════ 메시지 빌더 – LangChain4j ════════════════ */

    private List<ChatMessage> buildLcMessages(ChatRequestDto req,
                                              String unifiedCtx) {

        List<ChatMessage> list = new ArrayList<>();

        /* ① 커스텀 / 기본 시스템 프롬프트 */
        addSystemPromptLc(list, req.getSystemPrompt());

        /* ② 웹RAG메모리 합산 컨텍스트 – 그대로 주입  */
        if (StringUtils.hasText(unifiedCtx)) {
            list.add(SystemMessage.from(unifiedCtx));
        }

        /* ③ 대화 히스토리 */
        appendHistoryLc(list, req.getHistory());

        /* ④ 사용자 발화 */
        appendUserLc(list, req.getMessage());

        return list;
    }

    private void addSystemPromptLc(List<ChatMessage> l, String custom) {
        String sys = Optional.ofNullable(custom).filter(StringUtils::hasText).orElseGet(promptSvc::getSystemPrompt);
        if (StringUtils.hasText(sys)) {
            l.add(SystemMessage.from(sys));
        }
    }

    private static String pickForPolish(String smart, String verified,
                                        boolean insufficientContext, boolean fallbackHappened, String warning) {
        if (smart != null && !smart.isBlank()) return smart;
        if (insufficientContext || fallbackHappened) return verified + warning;
        return verified;
    }


    private void addMemoryContextLc(List<ChatMessage> l, String memCtx, int limit) {
        if (StringUtils.hasText(memCtx)) {
            String ctx = truncate(memCtx, limit);
            l.add(SystemMessage.from(String.format(MEM_PREFIX, ctx)));
        }
    }


    private void addRagContextLc(List<ChatMessage> l, String ragCtx, int limit) {
        if (StringUtils.hasText(ragCtx)) {
            String ctx = truncate(ragCtx, limit);
            l.add(SystemMessage.from(String.format(RAG_PREFIX, ctx)));
        }
    }


    private void appendUserLc(List<ChatMessage> l, String msg) {
        String user = ruleEngine.apply(msg, "ko", RulePhase.PRE);
        l.add(UserMessage.from(user));
    }

    /* ════════════════ Utility & Helper ════════════════ */

    /**
     * 애플리케이션 기동 시 한 번만 로드해서 캐싱
     */
    private volatile String defaultModelCached;

    @PostConstruct
    private void initDefaultModel() {
        this.defaultModelCached =
                modelRepo.findById(1L)
                        .map(CurrentModel::getModelId)
                        .orElse(defaultModel);
    }

    private String chooseModel(String requested, boolean stripLcPrefix) {
        if (StringUtils.hasText(requested)) {
            return stripLcPrefix ? requested.replaceFirst("^lc:", "") : requested;
        }
        if (StringUtils.hasText(tunedModelId)) {
            return tunedModelId;
        }
        return defaultModelCached;          // DB 재조회 없음
    }

    /* ─────────────────────── 새 헬퍼 메서드 ─────────────────────── */

    // ChatService.java (헬퍼 메서드 모음 근처)

    /**
     * 패치/공지/배너/버전 질의 간단 판별
     */
    private static boolean isLivePatchNewsQuery(String s) {
        if (!org.springframework.util.StringUtils.hasText(s)) return false;
        return java.util.regex.Pattern
                .compile("(?i)(패치\\s*노트|업데이트|공지|배너|스케줄|일정|버전\\s*\\d+(?:\\.\\d+)*)")
                .matcher(s)
                .find();
    }


    /**
     * 모든 컨텍스트(web → rag → mem)를 우선순위대로 합산한다.
     */
    private String buildUnifiedContext(String webCtx, String ragCtx, String memCtx) {
        List<String> parts = new ArrayList<>();
        if (StringUtils.hasText(webCtx)) {
            parts.add(String.format(WEB_PREFIX, truncate(webCtx, defaultWebCtxMaxTokens)));
        }
        if (StringUtils.hasText(ragCtx)) {
            parts.add(String.format(RAG_PREFIX, truncate(ragCtx, defaultRagCtxMaxTokens)));
        }
        if (StringUtils.hasText(memCtx)) {
            parts.add(String.format(MEM_PREFIX, truncate(memCtx, defaultMemCtxMaxTokens)));
        }
        return parts.isEmpty() ? null : String.join("\n\n", parts);
    }

    /**
     * 간단 휴리스틱: 사람/의료진 질의 여부
     */
    private static boolean isPersonQuery(String s) {
        if (s == null) return false;
        return Pattern.compile("(교수|의사|의료진|전문의|님)").matcher(s).find();
    }


    /* ✅ 웹 스니펫 묶음에 공식 도메인이 포함되어 있는지 검사 (인스턴스 버전만 유지) */
    private boolean containsOfficialSource(String webCtx) {
        if (!org.springframework.util.StringUtils.hasText(webCtx)) return false;
        for (String d : officialDomainsCsv.split(",")) {
            String dom = d.trim();
            if (!dom.isEmpty() && webCtx.contains(dom)) return true;
        }
        return false;
    }

    // ───────────────────────────────────────────────
    // 멀티 쿼리 집계 검색(NaverSearchService 변경 없이 사용)
    // ───────────────────────────────────────────────
    private List<String> aggregateSearch(List<String> queries, int topKPerQuery) {
        if (queries == null || queries.isEmpty()) return List.of();
        LinkedHashSet<String> acc = new LinkedHashSet<>();
        for (String q : queries) {
            if (!StringUtils.hasText(q)) continue;
            try {
                List<String> snippets = searchService.searchSnippets(q, topKPerQuery);
                if (snippets != null) acc.addAll(snippets);
            } catch (Exception e) {
                log.warn("[aggregateSearch] query '{}' 실패: {}", q, e.toString());
            }
        }
        return new ArrayList<>(acc);
    }

    private static String concatIfNew(String base, String extra) {
        if (!StringUtils.hasText(base)) return extra;
        if (!StringUtils.hasText(extra)) return base;
        if (base.contains(extra)) return base;
        return base + "\n" + extra;
    }

    private static String defaultString(String s) {
        return (s == null) ? "" : s;
    }

    private void reinforceMemory(ChatRequestDto req) {
        Optional.ofNullable(req.getMessage())
                .filter(StringUtils::hasText)
                .ifPresent(memorySvc::reinforceMemoryWithText);

        Optional.ofNullable(req.getHistory()).orElse(List.of())
                .stream()
                .filter(m -> "user".equalsIgnoreCase(m.getRole()) && StringUtils.hasText(m.getContent()))
                .forEach(m -> memorySvc.reinforceMemoryWithText(m.getContent()));
    }


    /**
     * RAG 컨텍스트를 길이 제한(RAG_CTX_MAX_TOKENS)까지 잘라 준다.
     */
    private static String truncate(String text, int max) {
        return text != null && text.length() > max ? text.substring(0, max) : text;
    }

    /**
     * ② 히스토리(OAI 전용) – 최근 maxHistory 개만 전송
     */
    private void appendHistoryOai(
            List<com.theokanning.openai.completion.chat.ChatMessage> l,
            List<ChatRequestDto.Message> hist) {

        if (!CollectionUtils.isEmpty(hist)) {
            hist.stream()
                    .skip(Math.max(0, hist.size() - maxHistory))
                    .map(m -> new com.theokanning.openai.completion.chat.ChatMessage(
                            m.getRole().toLowerCase(),   // "system" | "user" | "assistant"
                            m.getContent()))
                    .forEach(l::add);
        }
    }

    /**
     * 세션 스코프  가중치 보존 정책 준수
     */
    private void reinforceAssistantAnswer(String sessionKey, String query, String answer,
                                          double contextualScore,
                                          com.example.lms.strategy.StrategySelectorService.Strategy chosen) {
        if (!StringUtils.hasText(answer) || "정보 없음".equals(answer.trim())) return;
        /*
         * 기존에는 고정된 감쇠 가중치(예: 0.18)를 적용했습니다.  이제는
         * MLCalibrationUtil을 통해 동적으로 보정된 값을 사용합니다.
         * 현재 구현에서는 질문 문자열 길이를 거리 d 로 간주하여
         * 보정값을 계산합니다.  실제 환경에서는 질의의 중요도나 다른
         * 거리 측정값을 입력하여 더욱 정교한 가중치를 얻을 수 있습니다.
         */
        double d = (query != null ? query.length() : 0);
        boolean add = true;
        double score = com.example.lms.util.MLCalibrationUtil.finalCorrection(
                d, mlAlpha, mlBeta, mlGamma, mlD0, mlMu, mlLambda, add);

        // ML 보정값과 컨텍스트 스코어 절충(0.5:0.5)
        double normalizedScore = Math.max(0.0, Math.min(1.0, 0.5 * score + 0.5 * contextualScore));

        try {
            memorySvc.reinforceWithSnippet(sessionKey, query, answer, "ASSISTANT", normalizedScore);
        } catch (Throwable t) {
            log.debug("[Memory] reinforceWithSnippet 실패: {}", t.toString());
        }
    }



    /** 세션 키 정규화 유틸 */
    private static String extractSessionKey(ChatRequestDto req) {
        return Optional.ofNullable(req.getSessionId())
                .map(String::valueOf)
                .map(s -> s.startsWith("chat-") ? s : (s.matches("\\d+") ? "chat-"+s : s))
                .orElse(UUID.randomUUID().toString());
    }
    // 기존 호출부(3-인자)와의 하위호환을 위한 오버로드
    private void reinforceAssistantAnswer(String sessionKey, String query, String answer) {
        // 기본값: 컨텍스트 점수 0.5, 전략 정보는 아직 없으므로 null
        reinforceAssistantAnswer(sessionKey, query, answer, 0.5, null);
    }

    /** 후속 질문(팔로업) 감지: 마지막 답변 존재 + 패턴 기반 */
    private static boolean isFollowUpQuery(String q, String lastAnswer) {
        if (q == null || q.isBlank()) return false;
        if (lastAnswer != null && !lastAnswer.isBlank()) return true;
        String s = q.toLowerCase(java.util.Locale.ROOT).trim();
        return s.matches("^(더|조금|좀)\\s*자세히.*")
                || s.matches(".*자세히\\s*말해줘.*")
                || s.matches(".*예시(도|를)\\s*들(어|어서)?\\s*줘.*")
                || s.matches("^왜\\s+그렇(게|지).*")
                || s.matches(".*근거(는|가)\\s*뭐(야|지).*")
                || s.matches("^(tell me more|more details|give me an example|why is that).*");
    }
    /** Called by /api/chat/cancel */
    public void cancelSession(Long sessionId) {
        if (sessionId == null) return;
        cancelFlags.computeIfAbsent(sessionId, id -> new AtomicBoolean(false)).set(true);
    }

    private boolean isCancelled(Long sessionId) {
        AtomicBoolean f = (sessionId == null) ? null : cancelFlags.get(sessionId);
        return f != null && f.get();
    }

    private void clearCancel(Long sessionId) {
        if (sessionId != null) cancelFlags.remove(sessionId);
    }

    private void throwIfCancelled(Long sessionId) {
        if (isCancelled(sessionId)) {
            clearCancel(sessionId);
            throw new CancellationException("cancelled by client");
        }
    }

}