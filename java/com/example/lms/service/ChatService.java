package com.example.lms.service;
import com.example.lms.search.QueryHygieneFilter;
import org.springframework.beans.factory.annotation.Qualifier;
import com.example.lms.domain.enums.RulePhase;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.entity.CurrentModel;
import com.example.lms.repository.CurrentModelRepository;
import com.example.lms.service.fallback.FallbackResult;
import com.example.lms.service.NaverSearchService;
import com.example.lms.service.rag.CrossEncoderReranker;   // ★ 누락된 import 추가
import java.util.LinkedHashSet;
import java.util.Collections;
import java.util.List;
import com.example.lms.service.QueryAugmentationService;
import com.example.lms.prompt.PromptEngine;

import com.example.lms.service.fallback.SmartFallbackService;
 import com.example.lms.service.disambiguation.QueryDisambiguationService;
import com.example.lms.service.disambiguation.DisambiguationResult;
import com.example.lms.service.ChatHistoryService;
import com.example.lms.service.fallback.FallbackHeuristics;
import static com.example.lms.service.rag.LangChainRAGService.META_SID;
import com.example.lms.service.rag.QueryComplexityGate;
import jakarta.annotation.PostConstruct;
import com.example.lms.service.rag.HybridRetriever;
/* ---------- OpenAI-Java ---------- */
import com.example.lms.service.MemoryReinforcementService;
import com.example.lms.service.PromptService;
import com.example.lms.service.RuleEngine;
import java.util.function.Function;   // ✅ 새로 추가
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
import java.util.stream.Stream;          // buildUnifiedContext 사용
import java.util.stream.Collectors;
// (정리) 미사용 OpenAiChatModel import 제거
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;


/* ---------- RAG ---------- */
import com.example.lms.service.rag.LangChainRAGService;
import dev.langchain4j.memory.chat.ChatMemoryProvider;   // OK

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
import com.example.lms.transform.QueryTransformer;            // ⬅️ 추가
//  hybrid retrieval content classes
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import com.example.lms.service.rag.ContextOrchestrator;
// 🔹 NEW: ML correction util
import com.example.lms.util.MLCalibrationUtil;
import com.example.lms.service.correction.QueryCorrectionService;   // ★ 추가

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

    /* ───────────────────────────── DTO ───────────────────────────── */

    /**
     * 컨트롤러 ↔ 서비스 간 정형 응답 객체.
     */
    public static record ChatResult(String content, String modelUsed, boolean ragUsed) {
        /**
         * @deprecated: modelUsed() 로 대체
         */
        @Deprecated
        public String model() {
            return modelUsed();
        }

        public static ChatResult of(String c, String m, boolean r) {
            return new ChatResult(c, m, r);
        }
    }


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
    private final QueryTransformer queryTransformer;
    private final HybridRetriever hybridRetriever; // ★ 하이브리드 리트리버
    // ▼▼ 신규 DI
    private final com.example.lms.strategy.StrategySelectorService strategySelector;
    private final com.example.lms.strategy.StrategyDecisionTracker strategyTracker;
    private final com.example.lms.scoring.ContextualScorer contextualScorer;
    private final QueryAugmentationService augmentationSvc; // ★ 질의 향상 서비스
    private final QueryCorrectionService correctionSvc;             // ★ 추가
    // 🔹 NEW: 다차원 누적·보강·합성기
    // 🔹 단일 패스 오케스트레이션을 위해 체인 캐시는 제거
    private final CrossEncoderReranker reranker;

    @Qualifier("defaultPromptEngine")
    private final PromptEngine promptEngine;

    private final SmartFallbackService fallbackSvc;
    // 🔧 신규 오케스트레이터 주입 (RequiredArgsConstructor로 자동 주입)
    private final ContextOrchestrator contextOrchestrator;

    @Value("${rag.hybrid.top-k:50}")
    private int hybridTopK;
    @Value("${rag.rerank.top-n:10}")
    private int rerankTopN;
    /**
     * 하이브리드 우회(진단용): true면 HybridRetriever를 건너뛰고 단일패스로 처리
     */
    @Value("${debug.hybrid.bypass:false}")
    private boolean bypassHybrid;

    /* ─────────────────────── 설정 (application.yml) ─────────────────────── */
    // 기존 상수 지워도 되고 그대로 둬도 상관없음

    @Value("${openai.web-context.max-tokens:8000}")
    private int defaultWebCtxMaxTokens;        // 🌐 Live-Web 최대 토큰

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
    @Value("${openai.api.temperature.default:0.7}")
    private double defaultTemp;
    @Value("${openai.api.top-p.default:1.0}")
    private double defaultTopP;
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
    public ChatResult continueChat(ChatRequestDto req) {
        Function<String, List<String>> defaultProvider =
                q -> searchService.searchSnippets(q, 5);   // 네이버 Top-5
        return continueChat(req, defaultProvider);         // ↓ ②로 위임
    }

    /**
     * 의도 분석을 통해 최종 검색 쿼리를 결정한다.
     */
    private String decideFinalQuery(String originalQuery, Long sessionId) {
        if (originalQuery == null || originalQuery.isBlank()) return originalQuery;
        List<String> history = (sessionId != null)
                ? chatHistoryService.getFormattedRecentHistory(sessionId, 5)
                : Collections.emptyList();
        if (log.isDebugEnabled()) {
            log.debug("[decideFinalQuery] sid={}, histSize={}, q='{}'",
                    sessionId, (history != null ? history.size() : 0), originalQuery);
        }

        DisambiguationResult r = disambiguationService.clarify(originalQuery, history);
        if (r != null && r.isConfident()
                && r.getRewrittenQuery() != null
                && !r.getRewrittenQuery().isBlank()) {
            log.debug("[decideFinalQuery] rewritten='{}' (confident)", r.getRewrittenQuery());
            return r.getRewrittenQuery();
        }
        log.debug("[decideFinalQuery] use-original");
        return originalQuery;
    }

    // ------------------------------------------------------------------------
// ② 2-인자 실제 구현 (헤더·중괄호 반드시 포함!)
    public ChatResult continueChat(ChatRequestDto req,
                                   Function<String, List<String>> externalCtxProvider) {

        /* 0) 플래그 */
        boolean useRetrieval = req.isUseWebSearch() || req.isUseRag();
        boolean ragStandalone = req.isUseRag() && Boolean.TRUE.equals(req.getRagStandalone());

        /* 0-1) 사용자 입력 교정 (한 번만) */
        final String originalMsg = Optional.ofNullable(req.getMessage()).orElse("");
        final String correctedMsg = correctionSvc.correct(originalMsg);
        if (!originalMsg.equals(correctedMsg)) {
            log.debug("[QC] corrected '{}' -> '{}'", originalMsg, correctedMsg);
        }
        /* 0-2) 의미 확정(Ser8 ↔ S8 등) 적용 */
        Long sidNum = Optional.ofNullable(req.getSessionId())
                .map(Object::toString)
                .filter(s -> s.matches("\\d+"))   // ✔ 다자리 숫자 허용
                .map(Long::valueOf)
                .orElse(null);
        final String finalQuery = decideFinalQuery(correctedMsg, sidNum);
        /* A. RAG Stand-Alone */
// A. RAG Stand-Alone
        if (ragStandalone) {
            String sid = Optional.ofNullable(req.getSessionId())
                    .map(String::valueOf)
                    .map(s -> s.startsWith("chat-") ? s           // 이미 정규화
                            : (s.matches("\\d+") ? "chat-" + s   // 205 → chat-205
                            : s))                                  // UUID 등
                    .orElse(UUID.randomUUID().toString());

            // ✅ RAG Stand-alone에도 교정된 쿼리 사용
            String answer = ragSvc.getAnswer(correctedMsg, sid);

            // ▲ ASSISTANT 답변 저장 금지(정책)
            return ChatResult.of(
                    answer, "rag:" + chatModel.getClass().getSimpleName(), true);
        }

        /* B. Retrieval OFF */
        if (!useRetrieval) {
            String sessionId = Optional.ofNullable(req.getSessionId())
                    .map(String::valueOf)
                    .map(s -> s.startsWith("chat-") ? s : (s.matches("\\d+") ? "chat-" + s : s))
                    .orElse(UUID.randomUUID().toString());

            String memCtx = memorySvc.loadContext(sessionId);

            boolean useLc = Optional.ofNullable(req.getModel())
                    .map(m -> m.startsWith("lc:"))
                    .orElse(false);

            /* unifiedCtx = memCtx 만 전달 (OFF 경로는 각 메서드 내부에서 교정 재적용) */
            return useLc
                    ? invokeLangChain(req, memCtx)
                    : invokeOpenAiJava(req, memCtx);
        }

        /* C. Retrieval ON (Hybrid + Meta‑Strategy)
         *    ▶▶ 하나의 세션키(sessionKey)만 생성·전파 ◀◀
         */
        String sessionKey = Optional.ofNullable(req.getSessionId())
                .map(String::valueOf)
                .map(s -> s.startsWith("chat-") ? s : (s.matches("\\d+") ? "chat-" + s : s))
                .orElse(UUID.randomUUID().toString());

        /* ── 세션별 메모리 / RAG 컨텍스트 로드 ───────────────── */
        String memCtx = memorySvc.loadContext(sessionKey);   // ✅ 세션‑스코프
        String ragCtx = req.isUseRag()
                ? ragSvc.getAnswer(finalQuery, sessionKey)
                : null;

        /* ▶ 진단용 우회: HybridRetriever 비활성화 */
        if (bypassHybrid) {
            log.warn("[Bypass] debug.hybrid.bypass=true → HybridRetriever 미사용 (sid={})", sessionKey);
            boolean useLc = Optional.ofNullable(req.getModel())
                    .map(m -> m.startsWith("lc:"))
                    .orElse(false);
            return useLc
                    ? invokeLangChain(req, buildUnifiedContext(null, ragCtx, memCtx))
                    : invokeOpenAiJava(req, buildUnifiedContext(null, ragCtx, memCtx));
        }


        /* ❶ "정보 없음" 은 의미 없는 컨텍스트 → null 로 치환 */
        if ("정보 없음".equals((ragCtx != null ? ragCtx.trim() : ""))) {
            ragCtx = null;
        }

        // ❶ 메타‑전략 선택
        var chosen = strategySelector.selectForQuestion(finalQuery, req);
        strategyTracker.associate(sessionKey, chosen); // 피드백 집계용

        // ❷ 전략별 수집 경로
        List<Content> fused;
        switch (chosen) {
            case WEB_FIRST -> {
                // 웹 우선 → 스니펫을 Content로 래핑, 부족하면 벡터 보강
                List<String> sn = searchService.searchSnippets(finalQuery, hybridTopK);
                List<Content> webOnly = sn.stream().map(Content::from).toList();
                if (webOnly.size() < Math.max(3, hybridTopK / 2)) {
                    var pine = ragSvc.asContentRetriever(pineconeIndexName);
                    var vec = pine.retrieve(Query.from(finalQuery));
                    fused = new java.util.ArrayList<>(webOnly);
                    fused.addAll(vec);
                } else fused = webOnly;
            }
            case VECTOR_FIRST -> {
                var pine = ragSvc.asContentRetriever(pineconeIndexName);
                fused = pine.retrieve(Query.from(finalQuery));
                if (fused.size() < Math.max(3, hybridTopK / 2)) {
                    List<String> sn = searchService.searchSnippets(finalQuery, hybridTopK / 2);
                    fused = new java.util.ArrayList<>(fused);
                    fused.addAll(sn.stream().map(Content::from).toList());
                }
            }
            case DEEP_DIVE_SELF_ASK -> {
                fused = hybridRetriever.retrieveProgressive(finalQuery, sessionKey, hybridTopK);
            }
            case WEB_VECTOR_FUSION -> {
                List<String> augmented = augmentationSvc.augment(finalQuery);
                List<String> queries = QueryHygieneFilter.sanitize(augmented, 4, 0.80);
                fused = (queries != null && queries.size() > 1)
                        ? hybridRetriever.retrieveAll(queries, hybridTopK)
                        : hybridRetriever.retrieveProgressive(finalQuery, sessionKey, hybridTopK);
            }
            default -> {
                fused = hybridRetriever.retrieveProgressive(finalQuery, sessionKey, hybridTopK);
            }
        }


        // 🔸 3) 교차‑인코더 리랭킹(임베딩 기반 대체 구현) → 상위 N 문서
        List<Content> topDocs = reranker.rerank(finalQuery, fused, rerankTopN);
        if (log.isDebugEnabled())
            log.debug("[Hybrid] fused={}, topN={} (sid={})", (fused != null ? fused.size() : 0), (topDocs != null ? topDocs.size() : 0), sessionKey);
        /* 🔴 컨텍스트 부족 가드레일(하이브리드 이후로 이동)
         *   웹/벡터 문서(topDocs)와 RAG가 모두 비면 즉시 종료 */
        if ((topDocs == null || topDocs.isEmpty()) && !org.springframework.util.StringUtils.hasText(ragCtx)) {
            log.warn("[Guard] no web/vector docs & no ragCtx → stop LLM (sid={}, q='{}')", sessionKey, finalQuery);
            return ChatResult.of("정보 없음",
                    "lc:" + chatModel.getClass().getSimpleName(), true);
        }

        // 🔸 4) 최종 컨텍스트 생성(룰 기반) — 오케스트레이터로 이관
        String unifiedCtx = contextOrchestrator.orchestrate(finalQuery,
                ragSvc.asContentRetriever(pineconeIndexName).retrieve(Query.from(finalQuery)), // vector
                topDocs);

        // 🔸 5) 단일 LLM 호출로 답변 생성
        // 🔸 5) 모델/온도 준비 → 위험 질의면 온도 하향
        String cleanModel = chooseModel(req.getModel(), true);
        double llmTemp = Optional.ofNullable(req.getTemperature()).orElse(defaultTemp);
        if (FallbackHeuristics.detect(finalQuery) != null) {
            llmTemp = Math.min(llmTemp, 0.05); // 탐색 억제
        }
        // 준비 끝난 후 팩토리 호출
        ChatModel dynamic = chatModelFactory.lc(
                cleanModel,
                llmTemp,
                Optional.ofNullable(req.getTopP()).orElse(defaultTopP),
                req.getMaxTokens()
        );

        List<ChatMessage> msgs = buildLcMessages(req, unifiedCtx);
        String answer = dynamic.chat(msgs).aiMessage().text();
        String out = ruleEngine.apply(answer, "ko", RulePhase.POST);

        // 컨텍스트 스코어(사실성/품질/신규성) 산출 → 강화 점수 보정
        var scoreReport = contextualScorer.score(correctedMsg, unifiedCtx, out);


        reinforceAssistantAnswer(sessionKey, correctedMsg, out, scoreReport.overall(), chosen);
        return ChatResult.of(out, "lc:" + cleanModel, true);
    }   // ② 메서드 끝!  ←★★ 반드시 닫는 중괄호 확인
// ------------------------------------------------------------------------





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
            String verified = shouldVerify(joinedContext, req)
                    ? verifier.verify(correctedMsg, joinedContext, draft, "gpt-4o")
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
            try { memorySvc.reinforceWithSnippet(sessionKey, correctedMsg, out, srcTag, /*score*/ 0.5); } catch (Throwable ignore) {}
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

            String verified = shouldVerify(joinedContext, req)
                    ? verifier.verify(correctedMsg, joinedContext, draft, "gpt-4o") // gpt-4o 검증
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
            return ChatResult.of(out, "lc:" + cleanModel, req.isUseRag());

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


}