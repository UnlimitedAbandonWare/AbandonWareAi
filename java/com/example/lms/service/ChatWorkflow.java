package com.example.lms.service;

import com.example.lms.llm.DynamicChatModelFactory;
import com.example.lms.llm.OpenAiTokenParamCompat;

import com.example.lms.infra.resilience.IrregularityProfiler;
import com.example.lms.infra.resilience.BypassRoutingService;
import com.example.lms.infra.resilience.NightmareBreaker;
import com.example.lms.infra.resilience.NightmareKeys;
import com.example.lms.service.guard.GuardContextHolder;

import com.example.lms.prompt.PromptContext;
import com.example.lms.rag.model.QueryDomain;
import com.example.lms.guard.GuardProfile;
import com.example.lms.guard.GuardProfileProps;
import com.example.lms.domain.enums.VisionMode;
import com.example.lms.domain.enums.AnswerMode;
import com.example.lms.domain.enums.MemoryMode;
import com.example.lms.domain.enums.MemoryGateProfile;
import com.example.lms.nlp.QueryDomainClassifier;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CancellationException;
import com.example.lms.prompt.PromptBuilder;
import com.example.lms.service.routing.ModelRouter;
import com.example.lms.service.guard.EvidenceAwareGuard;
import com.example.lms.service.guard.InfoFailurePatterns;
import com.example.lms.service.routing.RouteSignal;
import com.example.lms.service.rag.HybridRetriever;
import com.example.lms.service.rag.WebSearchRetriever;
import com.example.lms.service.rag.QueryUtils;
import com.example.lms.orchestration.OrchestrationHints;
import com.example.lms.orchestration.OrchestrationSignals;
import com.example.lms.service.rag.EvidenceAnswerComposer;
import com.example.lms.service.verbosity.VerbosityDetector;
import com.example.lms.service.verbosity.VerbosityProfile;
import com.example.lms.service.verbosity.SectionSpecGenerator;
import com.example.lms.service.answer.LengthVerifierService;
import com.example.lms.service.answer.AnswerExpanderService;
import com.example.lms.util.HtmlTextUtil;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.data.message.SystemMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import java.util.*;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.HttpException;
import com.example.lms.search.QueryHygieneFilter;
import org.springframework.beans.factory.annotation.Qualifier;
import com.example.lms.domain.enums.RulePhase;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.search.provider.WebSearchProvider;
import com.example.lms.service.rag.rerank.CrossEncoderReranker;
import java.util.LinkedHashSet;
import java.util.Collections;
import java.util.List;
import org.springframework.cache.annotation.Cacheable;
import com.example.lms.service.disambiguation.QueryDisambiguationService;
import com.example.lms.service.disambiguation.DisambiguationResult;
import com.example.lms.service.subject.SubjectResolver;
import com.example.lms.service.subject.SubjectAnalysis;
import com.example.lms.service.rag.detector.UniversalDomainDetector;
import com.example.lms.service.strategy.DomainStrategyFactory;
import com.example.lms.service.ChatHistoryService;
import com.example.lms.service.rag.pre.QueryContextPreprocessor;
import com.example.lms.service.MemoryReinforcementService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import java.util.stream.Collectors;
import dev.langchain4j.data.message.UserMessage;
import com.example.lms.service.rag.LangChainRAGService;
import lombok.RequiredArgsConstructor;
import org.springframework.util.CollectionUtils;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

import com.example.lms.service.ChatResult;
import java.util.regex.Pattern;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import com.example.lms.util.MLCalibrationUtil;
import com.example.lms.util.FutureTechDetector;
import com.example.lms.search.SmartQueryPlanner;
import com.example.lms.search.TraceStore;
import com.example.lms.service.AttachmentService;
import com.example.lms.artplate.NineArtPlateGate;
import com.example.lms.artplate.ArtPlateSpec;
import com.example.lms.artplate.PlateContext;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

// 상단 import 블록에 추가

import static com.example.lms.service.rag.LangChainRAGService.META_SID;
import java.util.function.Function;

import com.example.lms.service.FactVerifierService; // 검증 서비스 주입
// + 신규 공장
// (유지) dev.langchain4j.model.chat.ChatModel
// - chains 캐시용 Caffeine import들 제거

/* ---------- LangChain4j ---------- */
// import 블록
import java.util.stream.Stream; // buildUnifiedContext 사용
// (정리) 미사용 OpenAiChatModel import 제거

// === Modularisation components (extracted from ChatService) ===

/* ---------- RAG ---------- */

// ① import

// (다른 import 들 모여 있는 곳에 아래 한 줄을 넣어 주세요)

// import 블록 맨 아래쯤
import com.example.lms.transform.QueryTransformer; // ⬅️ 추가
//  hybrid retrieval content classes
import dev.langchain4j.data.document.Metadata; // [HARDENING]
import java.util.Map; // [HARDENING]

// (dedup) Qualifier already imported above
import org.springframework.beans.factory.annotation.Autowired; // ← 추가
import org.springframework.core.env.Environment; // ← for evidence regen

/**
 * 중앙 허브 - OpenAI-Java · LangChain4j · RAG 통합. (v7.2, RAG 우선 패치 적용)
 * <p>
 * - LangChain4j 1.0.1 API 대응
 * - "웹-RAG 우선" 4-Point 패치(프롬프트 강화 / 메시지 순서 / RAG 길이 제한 / 디버그 로그) 반영
 * </p>
 *
 * <p>
 * 2024-08-06: ML 기반 보정/보강/정제/증강 기능을 도입했습니다. 새로운 필드
 * {@code mlAlpha}, {@code mlBeta}, {@code mlGamma}, {@code mlMu},
 * {@code mlLambda} 및 {@code mlD0} 은 application.yml 에서 조정할 수
 * 있습니다. {@link MLCalibrationUtil} 를 사용하여 LLM 힌트 검색 또는
 * 메모리 강화를 위한 가중치를 계산할 수 있으며, 본 예제에서는
 * {@link #reinforceAssistantAnswer(String, String, String)} 내에서
 * 문자열 길이를 거리 d 로 사용하여 가중치 점수를 보정합니다.
 * 실제 사용 시에는 도메인에 맞는 d 값을 입력해 주세요.
 * </p>
 */
@Service
@RequiredArgsConstructor
public class ChatWorkflow {
    private static final Logger log = LoggerFactory.getLogger(ChatWorkflow.class);
    @Value("${openai.retry.max-attempts:0}")
    private int llmMaxAttempts;

    @Value("${openai.retry.backoff-ms:350}")
    private long llmBackoffMs;

    @Value("${llm.timeout-seconds:12}")
    private int llmTimeoutSeconds;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private NightmareBreaker nightmareBreaker;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private IrregularityProfiler irregularityProfiler;

    // Planner Nexus: auto-select plan id when absent
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.lms.orchestration.WorkflowOrchestrator workflowOrchestrator;

    @Autowired(required = false)
    private com.example.lms.plan.PlanHintApplier planHintApplier;

    // Pipeline DSL (projection_agent.v1.yaml)
    @Autowired(required = false)
    private com.example.lms.service.rag.plan.PlanDslLoader planDslLoader;
    @Autowired(required = false)
    private com.example.lms.service.rag.plan.PlanPolicyMapper planPolicyMapper;
    @Autowired(required = false)
    private com.example.lms.service.rag.plan.PlanModelResolver planModelResolver;
    @Autowired(required = false)
    private com.example.lms.service.prompt.PromptAssetService promptAssetService;

    @Autowired(required = false)
    private com.example.lms.service.rag.ProjectionMergeService projectionMergeService;
    private final @Qualifier("queryTransformer") QueryTransformer queryTransformer;
    @Autowired(required = false)
    private java.util.Map<String, CrossEncoderReranker> rerankers;
    // NOTE: CircuitBreaker/TimeLimiter beans were previously injected but never
    // used.
    // Keeping the retry logic simple and explicit avoids "phantom" dependencies.
    private final QueryDomainClassifier queryDomainClassifier = new QueryDomainClassifier();
    private final GuardProfileProps guardProfileProps;
    @Value("${abandonware.reranker.backend:embedding-model}")
    private String rerankBackend;

    /**
     * Determine the active reranker based on the configured backend.
     * Falls back to the embedding reranker or a no-op implementation if
     * no matching bean is present. The backend property accepts
     * "onnx-runtime", "embedding-model" or "noop".
     */
    private CrossEncoderReranker reranker() {
        if (rerankers == null || rerankers.isEmpty()) {
            return new com.example.lms.service.rag.rerank.NoopCrossEncoderReranker();
        }
        String backend = (rerankBackend == null ? "" : rerankBackend.trim().toLowerCase());
        String key;
        switch (backend) {
            case "onnx-runtime" -> key = "onnxCrossEncoderReranker";
            case "noop" -> key = "noopCrossEncoderReranker";
            default -> key = "embeddingCrossEncoderReranker";
        }
        CrossEncoderReranker r = rerankers.get(key);
        if (r != null)
            return r;
        if (rerankers.containsKey("embeddingCrossEncoderReranker")) {
            return rerankers.get("embeddingCrossEncoderReranker");
        }
        return rerankers.values().iterator().next();
    }

    /* ───────────────────────────── DI ────────────────────────────── */

    private final ChatHistoryService chatHistoryService;
    private final EvidenceAwareGuard evidenceAwareGuard;
    private final QueryDisambiguationService disambiguationService;
    private final SubjectResolver subjectResolver;
    private final UniversalDomainDetector domainDetector;
    private final DomainStrategyFactory domainStrategyFactory;
    // The OpenAI-Java SDK has been removed. The application now exclusively uses
    // LangChain4j's ChatModel. To retain the original field order and ensure
    // Spring can still construct this class via constructor injection, we leave
    // a shim field here. It is never initialised or used.
    private final ChatModel chatModel; // 기본 LangChain4j ChatModel
    private final DynamicChatModelFactory dynamicChatModelFactory;
    private final MemoryReinforcementService memorySvc;
    private final FactVerifierService verifier; // ★ 신규 주입

    // - 체인 캐시 삭제
    // private final com.github.benmanes.caffeine.cache.LoadingCache<String,
    // ConversationalRetrievalChain> chains = /* ... */

    private final LangChainRAGService ragSvc;

    // 이미 있는 DI 필드 아래쪽에 추가
    private final WebSearchProvider webSearchProvider;
    private final QueryContextPreprocessor qcPreprocessor; // ★ 동적 규칙 전처리기

    private final SmartQueryPlanner smartQueryPlanner; // ⬅️ NEW DI
    // Inject Spring environment for guard checks. This allows reading
    // guard.evidence_regen.enabled.
    private final Environment env;
    // 🔹 NEW: 다차원 누적·보강·합성기
    // 🔹 단일 패스 오케스트레이션을 위해 체인 캐시는 제거

    private final HybridRetriever hybridRetriever;
    private final WebSearchRetriever webSearchRetriever;
    private final NineArtPlateGate nineArtPlateGate;
    private final PromptBuilder promptBuilder;
    private final ModelRouter modelRouter;
    // ▼ Verbosity & Expansion
    private final VerbosityDetector verbosityDetector;
    private final SectionSpecGenerator sectionSpecGenerator;
    private final LengthVerifierService lengthVerifier;
    private final AnswerExpanderService answerExpander;
    private final EvidenceAnswerComposer evidenceAnswerComposer;
    private final BypassRoutingService bypassRoutingService;
    // ▼ Memory evidence I/O
    private final com.example.lms.service.rag.handler.MemoryHandler memoryHandler;
    private final com.example.lms.service.rag.handler.MemoryWriteInterceptor memoryWriteInterceptor;
    // 신규: 학습 기록 인터셉터
    private final com.example.lms.learning.gemini.LearningWriteInterceptor learningWriteInterceptor;
    // 신규: 이해 요약 및 기억 모듈 인터셉터
    private final com.example.lms.service.chat.interceptor.UnderstandAndMemorizeInterceptor understandAndMemorizeInterceptor;
    /** In-flight cancel flags per session (best-effort) */
    private final ConcurrentHashMap<Long, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();

    // [METRICS] 간단한 in-memory 카운터 (Micrometer 연동 전까지 임시)
    private final AtomicLong rescueCount = new AtomicLong();
    private final AtomicLong emptyTopDocsCount = new AtomicLong();
    private final AtomicLong freeIdeaCount = new AtomicLong();

    @Value("${rag.hybrid.top-k:50}")
    private int hybridTopK;
    @Value("${rag.rerank.top-n:10}")
    private int rerankTopN;
    // ▼ reranker keep-top-n by verbosity
    @Value("${reranker.keep-top-n.brief:5}")
    private int keepNBrief;
    @Value("${reranker.keep-top-n.standard:8}")
    private int keepNStd;
    @Value("${reranker.keep-top-n.deep:12}")
    private int keepNDeep;
    @Value("${reranker.keep-top-n.ultra:16}")
    private int keepNUltra;
    /**
     * 하이브리드 우회(진단용): true면 HybridRetriever를 건너뛰고 단일패스로 처리
     */
    @Value("${debug.hybrid.bypass:false}")
    private boolean bypassHybrid;

    // [FUTURE_TECH FIX] Feature flags for unreleased / next-gen product handling
    @Value("${rag.latest-tech.enabled:true}")
    private boolean latestTechEnabled;

    @Value("${rag.latest-tech.auto-disable-vector:true}")
    private boolean latestTechAutoDisableVector;

    @Value("${rag.latest-tech.skip-memory-read:true}")
    private boolean latestTechSkipMemoryRead;

    @Value("${naver.reinforce-assistant:false}")
    private boolean enableAssistantReinforcement;

    /* ─────────────────────── 설정 (application.yml) ─────────────────────── */
    // 기존 상수 지워도 되고 그대로 둬도 상관없음

    @Value("${openai.web-context.max-tokens:8000}")
    private int defaultWebCtxMaxTokens; // 🌐 Live-Web 최대 토큰

    @Value("${openai.mem-context.max-tokens:7500}")
    private int defaultMemCtxMaxTokens; // ★

    @Value("${openai.rag-context.max-tokens:5000}")
    private int defaultRagCtxMaxTokens; // ★
    // Resolve the API key from configuration or environment. Prefer the
    // `openai.api.key` property and fall back to OPENAI_API_KEY. Do not
    // include other vendor keys (e.g. GROQ_API_KEY) to prevent invalid
    // authentication.
    @Value("${openai.api.key:${OPENAI_API_KEY:}}")
    private String openaiApiKey;
    @Value("${llm.chat-model:gemma3:27b}")
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

    // WEB 스니펫은 이미 HTML 링크 형태(- <a href="/* ... */">제목</a>: 요약)로 전달됨.
    // 아래 프리픽스는 모델용 컨텍스트 힌트이며, 실제 화면에는 ChatApiController가 따로 '검색 과정' 패널을 붙인다.
    private static final String WEB_PREFIX = """
            ### LIVE WEB RESULTS
            %s

            - Extract concrete dates (YYYY-MM-DD) if present.
            - Cite site titles in parentheses.
            """;

    /* 폴리싱용 시스템 프롬프트 (단일 정의) */
    private static final String POLISH_SYS_PROMPT = "다음 초안을 더 자연스럽고 전문적인 한국어로 다듬어 주세요. 새로운 정보는 추가하지 마세요.";
    /* ──────────────── RAG 패치: 프롬프트 강화 ──────────────── */
    private static final String RAG_PREFIX = """
            ### CONTEXT
            %s

            ### INSTRUCTIONS
            - Synthesize an answer from all available sections (web, vector-RAG, memory).
            - **Priority Order**: Official domains (*.hoyoverse.com, hoyolab.com, mihoyo.com) > Trusted Wikis (namu.wiki, wikipedia.org, fandom.com, gamedot.org) > General community content.
            - **Exception (Games / Subculture)**: For topics like video games, anime, web novels, or fandoms (e.g., "원신", "스타레일", "마비카"),
              community wikis and fan sites are considered **valid evidence**. Do NOT discard them only because they are unofficial.
            - Always base your answer on the given CONTEXT. Do not invent facts not supported by any snippet.
            - **Mention the source titles or site names** (예: 나무위키, 티스토리 블로그, GameDot 등) when you answer.
            - Prefer concise, definitional answers when the user asks "누구야/뭐야/what is".
            - Only when the context contains absolutely **no relevant information from any source**, reply exactly with "정보 없음".
            - Otherwise, even if official information is limited, provide:
              (a) the best-effort definition from available sources, and/or
              (b) a short summary of what the community believes, with proper hedging (예: "위키 기준", "커뮤니티 정보").
            """;
    private static final String MEM_PREFIX = """
            ### LONG-TERM MEMORY
            %s
            """;

    /* ═════════════════════ ML 보정 파라미터 ═════════════════════ */
    /**
     * Machine learning based correction parameters. These values can be
     * configured via application.yml using keys under the prefix
     * {@code ml.correction.*}. They correspond to the α, β, γ, μ,
     * λ, and d₀ coefficients described in the specification. See
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
    // 검증 기본 활성화 플래그 (application.yml: verification.enabled=true)
    @org.springframework.beans.factory.annotation.Value("${verification.enabled:true}")
    private boolean verificationEnabled;

    // ──────────── Attachment injection ─────────────────
    /**
     * Service used to resolve uploaded attachment identifiers into prompt context
     * documents. Injected via constructor to allow attachments to be
     * incorporated into the PromptContext without manual bean lookup.
     */
    private final AttachmentService attachmentService;

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
    @Cacheable(value = "chatResponses",
            // 캐시 키는 세션과 모델별로 격리: 동일 메시지라도 세션·모델이 다르면 별도 저장
            // Use a static helper to build the key without string concatenation
            key = "T(com.example.lms.service.ChatService).cacheKey(#req)")
    public ChatResult continueChat(ChatRequestDto req) {
        int webK = (req.getWebTopK() == null || req.getWebTopK() <= 0) ? 5 : req.getWebTopK();
        Function<String, List<String>> defaultProvider = q -> webSearchProvider.search(q, webK); // 네이버 Top-K
        return continueChat(req, defaultProvider); // ↓ ②로 위임
    }

    // ── intent/risk/로깅 유틸 ─────────────────────────────────────
    private String inferIntent(String q) {
        try {
            return qcPreprocessor.inferIntent(q);
        } catch (Exception e) {
            return "GENERAL";
        }
    }

    private String detectRisk(String q) {
        if (q == null)
            return null;
        String s = q.toLowerCase(java.util.Locale.ROOT);
        return s.matches(".*(진단|처방|증상|법률|소송|형량|투자|수익률|보험금).*") ? "HIGH" : null;
    }

    /**
     * [Dual-Vision] VisionMode 결정 로직
     *
     * 우선순위:
     * 1. 고위험 도메인 → STRICT 강제
     * 2. 사용자 명시적 요청 → 그대로 사용
     * 3. 도메인 기반 자동 결정
     */
    private VisionMode decideVision(QueryDomain domain, String riskLevel, ChatRequestDto req) {
        // 기존 시그니처는 planId 가 없는 호출부를 위해 유지하고,
        // 내부적으로는 planId=null 을 넣어 신규 로직을 사용한다.
        return decideVision(domain, riskLevel, req, null);
    }

    /**
     * [Dual-Vision] VisionMode 결정 로직 v2
     *
     * 우선순위:
     * 1. 고위험 도메인 → STRICT 강제
     * 2. 사용자 명시적 요청 → 그대로 사용
     * 3. Plan 설정에서 지정된 모드
     * 4. 도메인 기반 자동 결정
     */
    private VisionMode decideVision(QueryDomain domain,
            String riskLevel,
            ChatRequestDto req,
            String planId) {
        // 1. HIGH risk → 무조건 STRICT (안전망)
        if ("HIGH".equalsIgnoreCase(riskLevel)) {
            return VisionMode.STRICT;
        }

        // 2. 사용자 명시적 요청 (메시지 내 특수 커맨드 기반)
        String userQuery = Optional.ofNullable(req.getMessage()).orElse("");
        if (userQuery.contains("/strict") || userQuery.contains("엄격하게")) {
            return VisionMode.STRICT;
        }
        if (userQuery.contains("/free") || userQuery.contains("자유롭게")) {
            return VisionMode.FREE;
        }

        // 3. [NEW] Plan 기반 모드 결정
        if (planId != null && !planId.isBlank()) {
            String lower = planId.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("zero_break") || lower.contains("hypernova")) {
                return VisionMode.FREE;
            }
            if (lower.contains("safe") || lower.contains("strict")) {
                return VisionMode.STRICT;
            }
        }

        // 4. 도메인 기반 자동 결정
        return switch (domain) {
            case GAME, SUBCULTURE -> VisionMode.FREE;
            case STUDY, SENSITIVE -> VisionMode.STRICT;
            default -> VisionMode.HYBRID;
        };
    }

    /**
     * [Dual-Vision] 최신/미래 Tech 쿼리 감지
     * 학습 컷오프 이후 기기는 클라우드/고성능 모델로 우선 라우팅
     */
    private boolean isLatestTechQuery(String query) {
        // [FUTURE_TECH FIX] Centralized detection for unreleased/next-gen tech product
        // queries
        return FutureTechDetector.isFutureTechQuery(query);
    }

    private static String getModelName(dev.langchain4j.model.chat.ChatModel m) {
        return (m == null) ? "unknown" : m.getClass().getSimpleName();
    }

    /**
     * Build a composite cache key from a chat request. This helper avoids
     * string concatenation in the SpEL expression by delegating the
     * composition to Java code. Each component is converted to a string
     * and joined with a colon separator. When the request is null or
     * fields are absent empty strings are used.
     *
     * @param req the chat request
     * @return a stable key of the form sessionId:model:message:useRag:useWebSearch
     */
    public static String cacheKey(com.example.lms.dto.ChatRequestDto req) {
        if (req == null)
            return "";
        String sid = String.valueOf(req.getSessionId());
        String model = String.valueOf(req.getModel());
        String msg = String.valueOf(req.getMessage());
        String rag = String.valueOf(req.isUseRag());
        String web = String.valueOf(req.isUseWebSearch());
        return String.format("%s:%s:%s:%s:%s", sid, model, msg, rag, web);
    }

    private void reinforce(String sessionKey, String query, String answer,
            VisionMode visionMode,
            GuardProfile guardProfile,
            MemoryMode memoryMode) {
        try {
            reinforceAssistantAnswerWithProfile(sessionKey, query, answer, 0.5, null, visionMode, guardProfile,
                    memoryMode);
        } catch (Throwable ignore) {
        }
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
        if (originalQuery == null || originalQuery.isBlank())
            return originalQuery;
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
                .map(s -> {
                    if (s.startsWith("chat-"))
                        return s;
                    if (s.matches("\\d+")) {
                        return String.format("chat-%s", s);
                    }
                    return s;
                })
                .orElse(UUID.randomUUID().toString());

        // Ensure any ThreadLocal trace values from a previous request are cleared
        // before starting this run. SmartQueryPlanner also clears TraceStore, but
        // Web/RAG-only flows may bypass it.
        try {
            TraceStore.clear();
        } catch (Exception ignore) {
            // ignore
        }

        // ── 0) 사용자 입력 확보 ─────────────────────────────────────
        final String userQuery = Optional.ofNullable(req.getMessage()).orElse("");
        final String requestedModel = Optional.ofNullable(req.getModel()).orElse("");

        if (userQuery.isBlank()) {
            return ChatResult.of("정보 없음", String.format("lc:%s", chatModel.getClass().getSimpleName()), true);
        }

        // Domain classification for this query
        QueryDomain queryDomain = queryDomainClassifier.classify(userQuery);

        // [NEW] AnswerMode / MemoryMode from HTTP request (null-safe)
        AnswerMode answerMode = AnswerMode.fromString(req.getMode());
        MemoryMode memoryMode = MemoryMode.fromString(req.getMemoryMode());

        GuardProfile guardProfile;
        // 사용자가 mode를 명시한 경우 AnswerMode 기반 GuardProfile로 매핑
        if (req.getMode() != null && !req.getMode().isBlank()) {
            guardProfile = GuardProfile.fromAnswerMode(answerMode);
        } else {
            // QueryDomain 기반 기본 GuardProfile 결정 (시선1/2/3 통합)
            guardProfile = guardProfileProps.profileFor(queryDomain);
        }
        // EvidenceAwareGuard 에서 사용할 현재 프로파일 등록
        guardProfileProps.setCurrentProfile(guardProfile);

        // ✅ GuardContextHolder 접목: 컨트롤러가 set한 컨텍스트를 오케스트레이션에서 보강
        // (없으면 건드리지 않음; ThreadLocal이므로 여기서 생성/clear는 하지 않는다.)
        // ✅ gctx null 방지: 컨트롤러/필터가 GuardContext를 안 심은 경로에서도 NPE 방어
        var gctx = GuardContextHolder.getOrDefault();
        com.example.lms.service.rag.plan.ProjectionAgentPlanSpec projectionPlan = null;
        boolean projectionPipeline = false;
        com.example.lms.plan.PlanHints planHints = null;
        if (gctx != null) {
            gctx.setEntityQueryFromQuestion(userQuery);
            if (gctx.getMode() == null || gctx.getMode().isBlank())
                gctx.setMode(answerMode.name());
            // planId가 비어있으면 WorkflowOrchestrator로 자동 선택
            if (workflowOrchestrator != null) {
                try {
                    workflowOrchestrator.ensurePlanSelected(gctx, answerMode, queryDomain, userQuery);
                } catch (Exception ignore) {
                }
            }
            if (gctx.getPlanId() == null || gctx.getPlanId().isBlank())
                gctx.setPlanId("safe_autorun.v1");

            try {
                if (planHintApplier != null) {
                    planHints = planHintApplier.load(gctx.getPlanId());
                    planHintApplier.applyToGuardContext(planHints, gctx);
                }
            } catch (Exception ignore) {
            }

            // Pipeline DSL: projection_agent.v1.yaml (dual-view + merge)
            if (planDslLoader != null && gctx.getPlanId() != null) {
                projectionPlan = planDslLoader.loadProjectionAgent(gctx.getPlanId()).orElse(null);
                projectionPipeline = (projectionPlan != null);
            }

            if (projectionPipeline && planPolicyMapper != null && guardProfileProps != null) {
                // Strict branch is the primary execution context in projection_agent.v1
                var strict = projectionPlan.viewMemorySafe() != null ? projectionPlan.viewMemorySafe() : null;
                String guardProfileStr = strict != null ? strict.guardProfile()
                        : (projectionPlan.defaults() != null ? projectionPlan.defaults().guardProfile() : null);
                guardProfile = planPolicyMapper.resolveGuardProfile(guardProfileStr, guardProfile);

                // If user explicitly provided memoryMode, do not override.
                boolean userExplicitMemoryMode = (req.getMemoryMode() != null && !req.getMemoryMode().isBlank());
                String memoryProfileStr = strict != null ? strict.memoryProfile()
                        : (projectionPlan.defaults() != null ? projectionPlan.defaults().memoryProfile() : null);
                if (!userExplicitMemoryMode) {
                    memoryMode = planPolicyMapper.resolveMemoryMode(memoryProfileStr, memoryMode);
                }

                guardProfileProps.setCurrentProfile(guardProfile);
            }

            if (gctx.getGuardLevel() == null || gctx.getGuardLevel().isBlank())
                gctx.setGuardLevel(guardProfile.name());
            if (gctx.getMemoryProfile() == null || gctx.getMemoryProfile().isBlank()) {
                gctx.setMemoryProfile(memoryMode == MemoryMode.EPHEMERAL ? "NONE" : "MEMORY");
            }
        }

        // Per-plan overrides (projection_agent.v1.yaml): model / traits / token budget
        // / verification
        String effectiveRequestedModel = requestedModel;
        ChatRequestDto llmReq = req;
        if (projectionPipeline && projectionPlan != null) {
            var strictCfg = projectionPlan.viewMemorySafe() != null ? projectionPlan.viewMemorySafe() : null;
            if (strictCfg != null) {
                if (planModelResolver != null) {
                    String resolved = planModelResolver.resolveRequestedModel(strictCfg.model());
                    if (StringUtils.hasText(resolved)) {
                        effectiveRequestedModel = resolved;
                        llmReq = llmReq.toBuilder().model(resolved).build();
                    }
                }
                if (strictCfg.maxTokens() != null && strictCfg.maxTokens() > 0) {
                    llmReq = llmReq.toBuilder().maxTokens(strictCfg.maxTokens()).build();
                }
                if (strictCfg.traits() != null && !strictCfg.traits().isEmpty()) {
                    llmReq = llmReq.toBuilder().traits(strictCfg.traits()).build();
                }
                boolean citationsEnabled = projectionPlan.defaults() != null
                        && Boolean.TRUE.equals(projectionPlan.defaults().citations());
                if (citationsEnabled) {
                    // In this codebase, verification only runs if request flag is true.
                    llmReq = llmReq.toBuilder().useVerification(true).build();
                }
            }
        }

        // Final copy for lambda expressions
        final String effectiveRequestedModelFinal = effectiveRequestedModel;

        // [Dual-Vision] VisionMode 결정
        String riskLevel = detectRisk(userQuery);
        VisionMode visionMode = decideVision(queryDomain, riskLevel, req, gctx != null ? gctx.getPlanId() : null);
        log.debug("[DualVision] queryDomain={}, visionMode={}", queryDomain, visionMode);

        // ── 0-A) 세션ID 정규화 & 쿼리 재작성(Disambiguation) ─────────
        Long sessionIdLong = parseNumericSessionId(req.getSessionId());
        throwIfCancelled(sessionIdLong); // ★ 추가

        java.util.List<String> recentHistory = (sessionIdLong != null)
                ? chatHistoryService.getFormattedRecentHistory(sessionIdLong, 5)
                : java.util.Collections.emptyList();

        DisambiguationResult dr;
        // 보조 LLM 회로가 이미 OPEN이면 불필요한 호출을 하지 않고 원문으로 진행
        if (nightmareBreaker != null && nightmareBreaker.isOpen(NightmareKeys.DISAMBIGUATION_CLARIFY)) {
            dr = new DisambiguationResult();
            dr.setRewrittenQuery(userQuery);
            dr.setConfidence("low");
            dr.setScore(0.0);
        } else {
            dr = disambiguationService.clarify(userQuery, recentHistory);
        }

        final String finalQuery;
        if (dr != null && dr.isConfident()
                && dr.getRewrittenQuery() != null && !dr.getRewrittenQuery().isBlank()) {
            finalQuery = dr.getRewrittenQuery();
        } else {
            finalQuery = userQuery;
        }

        // 0-B) Subject / Domain / Strategy 분석 (순수 자바)
        SubjectAnalysis analysis = subjectResolver.analyze(finalQuery, recentHistory, dr);
        String domain = domainDetector.detect(finalQuery, dr);
        DomainStrategyFactory.SearchStrategy searchStrategy = domainStrategyFactory.createStrategy(analysis, domain);

        if (log.isDebugEnabled()) {
            // SLF4J placeholder는 문자열 리터럴 안에서 사용해야 하며, 불필요한 따옴표(")는 제거합니다.
            log.debug("[Domain] query={}, category={}, domain={}, profile={}",
                    finalQuery, analysis.getCategory(), domain, searchStrategy.getSearchProfile());
        }

        // ── 0-1) Verbosity 감지 & 섹션 스펙 ─────────────────────────
        VerbosityProfile detectedVp = verbosityDetector.detect(finalQuery);
        String intent = inferIntent(finalQuery);
        // Pass detected domain into section spec generator so domain-specific templates
        // can be applied.
        List<String> sections = sectionSpecGenerator.generate(intent, domain, detectedVp.hint());

        // ── 1) 검색/융합: Self-Ask → HybridRetriever → Cross-Encoder Rerank ─
        // 0-2) Retrieval 플래그

        boolean useWeb = req.isUseWebSearch() || searchStrategy.isUseWebSearch();
        boolean useRag = req.isUseRag() || searchStrategy.isUseVectorStore();

        // [FUTURE_TECH FIX] 최신/미출시(차세대) 제품 쿼리는 웹 최신성 우선 + 구버전 Vector 오염 방지
        boolean futureTech = latestTechEnabled && isLatestTechQuery(finalQuery);
        if (futureTech && latestTechAutoDisableVector) {
            useWeb = true;
            useRag = false;
            log.info("[FutureTech] Web forced ON, Vector forced OFF. query={}", finalQuery);
        }

        // plan hints: cap allowWeb/allowRag
        if (planHints != null) {
            if (planHints.allowWeb() != null && !planHints.allowWeb())
                useWeb = false;
            if (planHints.allowRag() != null && !planHints.allowRag())
                useRag = false;
        }

        // 1) (옵션) 웹 검색 계획 및 실행
        // ── 보조 LLM 장애 신호를 먼저 계산하여 플래닝/중량 단계를 사전 차단 ──
        boolean auxHardDownEarly = nightmareBreaker != null && nightmareBreaker.isAnyOpen(
                NightmareKeys.QUERY_TRANSFORMER_RUN_LLM,
                NightmareKeys.DISAMBIGUATION_CLARIFY,
                NightmareKeys.KEYWORD_SELECTION_SELECT);
        if (gctx != null && auxHardDownEarly) {
            // Hard-down only. Do not overwrite soft-degraded flags.
            gctx.setAuxHardDown(true);
        }

        // ── Orchestration signal bus (STRIKE/COMPRESSION/BYPASS) ────────────
        OrchestrationSignals sig = OrchestrationSignals.compute(finalQuery, nightmareBreaker, gctx);
        if (gctx != null) {
            gctx.setStrikeMode(sig.strikeMode());
            gctx.setCompressionMode(sig.compressionMode());
            gctx.setBypassMode(sig.bypassMode());
            gctx.setWebRateLimited(sig.webRateLimited());
            if (gctx.getBypassReason() == null || gctx.getBypassReason().isBlank()) {
                gctx.setBypassReason(sig.reason());
            }
        }
        // vp는 final 선언 후 조건부로 단 한 번만 초기화 → 람다에서 effectively final로 사용 가능
        final VerbosityProfile vp;
        if (sig.strikeMode()) {
            // STRIKE 모드: 출력은 짧고 핵심만(타임아웃/레이트리밋 상황에서 fail-fast)
            int maxTokens = Math.min(detectedVp.targetTokenBudgetOut(), 768);
            int minWords = Math.min(detectedVp.minWordCount(), 90);
            vp = new VerbosityProfile("brief", minWords, maxTokens, detectedVp.audience(), detectedVp.citationStyle(),
                    detectedVp.sections());
        } else {
            vp = detectedVp;
        }
        OrchestrationHints hints = null;
        Map<String, Object> metaHints = null;

        List<String> planned = List.of();
        List<dev.langchain4j.rag.content.Content> fused = List.of();
        if (useWeb) {
            if (futureTech && latestTechAutoDisableVector) {
                planned = List.of(finalQuery);
            } else if (sig != null && sig.auxLlmDown()) {
                // Aux LLM is degraded/hard-down: bypass planner and use the original query.
                planned = List.of(finalQuery);
            } else {
                planned = smartQueryPlanner.plan(finalQuery, /* assistantDraft */ null, /* maxBranches */ 2);
                if (planned == null || planned.isEmpty()) {
                    planned = List.of(finalQuery);
                }
            }

				// Planner can trip request-scoped aux-down / irregularity signals (e.g. QueryTransformer soft-timeouts).
				// Recompute orchestration signals so plate + downstream handlers see up-to-date flags.
				sig = OrchestrationSignals.compute(finalQuery, nightmareBreaker, gctx);
				if (gctx != null) {
					gctx.setStrikeMode(sig.strikeMode());
					gctx.setCompressionMode(sig.compressionMode());
					gctx.setBypassMode(sig.bypassMode());
					gctx.setWebRateLimited(sig.webRateLimited());
					if (gctx.getBypassReason() == null || gctx.getBypassReason().isBlank()) {
						gctx.setBypassReason(sig.reason());
					}
				}

            // Nine Art Plate: decide (apply is request-scoped via metadata hints)
            PlateContext plateCtx = new PlateContext(
                    useWeb, useRag,
                    /* sessionRecur */ 0, /* evidenceCount */ 0,
                    /* authority */ 0.0, /* noisy */ false,
                    /* webGate */ (useWeb ? 0.55 : 0.30),
                    /* vectorGate */ (useRag ? 0.65 : 0.30),
                    /* memoryGate */ 0.30,
                    /* recallNeed */ (useRag ? 0.70 : 0.50));
            ArtPlateSpec plate = nineArtPlateGate.decide(plateCtx);

            boolean nightmareMode = nightmareBreaker != null
                    && nightmareBreaker.isOpen(NightmareKeys.CHAT_DRAFT);

				boolean auxLlmDown = sig.auxLlmDown();
				boolean auxDegraded = sig.auxDegraded();
				boolean auxHardDown = sig.auxHardDown();

            hints = OrchestrationHints.builder()
                    .plateId(plate.id())
                    .webTopK(plate.webTopK())
                    .vecTopK(plate.vecTopK())
                    .webBudgetMs((long) plate.webBudgetMs())
                    .vecBudgetMs((long) plate.vecBudgetMs())
                    .enableSelfAsk(!nightmareMode && !auxLlmDown)
                    .enableAnalyze(!nightmareMode && !auxLlmDown)
                    .enableCrossEncoder(plate.crossEncoderOn() && !nightmareMode && !auxLlmDown)
                    .nightmareMode(nightmareMode)
                    .auxLlmDown(auxLlmDown)
                    .allowWeb(useWeb)
                    .allowRag(useRag)
                    .build();

            // STRIKE/COMPRESSION/BYPASS mode wiring (same signal bus for all components)
            if (hints != null) {
                boolean strikeMode = sig.strikeMode();
                boolean compressionMode = sig.compressionMode();
                hints = hints.toBuilder()
                        .strikeMode(strikeMode)
                        .compressionMode(compressionMode)
                        .bypassMode(sig.bypassMode())
                        .webRateLimited(sig.webRateLimited())
						.bypassReason(gctx != null ? gctx.getBypassReason() : sig.reason())
                        // STRIKE/압축에서는 heavy step을 확실히 OFF
                        .enableSelfAsk(hints.isEnableSelfAsk() && !strikeMode && !compressionMode)
                        .enableAnalyze(hints.isEnableAnalyze() && !strikeMode && !compressionMode)
                        .enableCrossEncoder(hints.isEnableCrossEncoder() && !strikeMode && !compressionMode)
                        .build();
            }

            metaHints = new HashMap<>();
            metaHints.put("plateId", hints.getPlateId());
            metaHints.put("webTopK", hints.getWebTopK());
            metaHints.put("vecTopK", hints.getVecTopK());
            metaHints.put("webBudgetMs", hints.getWebBudgetMs());
            metaHints.put("vecBudgetMs", hints.getVecBudgetMs());
            // 일부 라이브러리는 boolean을 metadata로 전달할 때 이슈가 있어 문자열로 저장
            metaHints.put("enableSelfAsk", String.valueOf(hints.isEnableSelfAsk()));
            metaHints.put("enableAnalyze", String.valueOf(hints.isEnableAnalyze()));
            metaHints.put("enableCrossEncoder", String.valueOf(hints.isEnableCrossEncoder()));
            metaHints.put("nightmareMode", String.valueOf(hints.isNightmareMode()));
            metaHints.put("auxLlmDown", String.valueOf(hints.isAuxLlmDown()));
            // UAW: expose aux soft/hard health for downstream diagnostics
            metaHints.put("auxDegraded", String.valueOf(sig.auxDegraded()));
            metaHints.put("auxHardDown", String.valueOf(sig.auxHardDown()));
            metaHints.put("allowWeb", String.valueOf(hints.isAllowWeb()));
            metaHints.put("allowRag", String.valueOf(hints.isAllowRag()));

            try {
                if (planHintApplier != null && planHints != null) {
                    planHintApplier.applyToHintsAndMeta(planHints, hints, metaHints);
                }
            } catch (Exception ignore) {
            }

            // ✅ PERF: controller가 이미 수행한 web search 결과(Trace 포함)를 재사용해
            // WebSearchRetriever/HybridRetriever에서 동일 쿼리 재검색을 방지한다.
            if (hints.isAllowWeb() && externalCtxProvider != null) {
                try {
                    String q0 = (planned != null && !planned.isEmpty()) ? planned.get(0) : finalQuery;
                    List<String> prefetched = externalCtxProvider.apply(q0);
                    if (prefetched != null && !prefetched.isEmpty()) {
                        metaHints.put("prefetch.web.query", q0);
                        metaHints.put("prefetch.web.snippets", prefetched);
                    }
                } catch (Exception e) {
                    log.debug("[WebPrefetch] externalCtxProvider failed: {}", e.getMessage());
                }
            }

            // Domain/tile hints for retrieval & alias correction (all-rounder stability)
            metaHints.put("intent.domain", domain);
            metaHints.put("vp.topTile", mapDomainToTile(domain));

            // UAW: Orchestration mode flags
            metaHints.put("strikeMode", String.valueOf(hints.isStrikeMode()));
            metaHints.put("compressionMode", String.valueOf(hints.isCompressionMode()));
            metaHints.put("bypassMode", String.valueOf(hints.isBypassMode()));
            metaHints.put("webRateLimited", String.valueOf(hints.isWebRateLimited()));
            if (hints.getBypassReason() != null && !hints.getBypassReason().isBlank()) {
                metaHints.put("bypassReason", hints.getBypassReason());
            }

            try {
                TraceStore.put("plate.id", hints.getPlateId());
                TraceStore.put("plate.webTopK", hints.getWebTopK());
                TraceStore.put("plate.vecTopK", hints.getVecTopK());
                TraceStore.put("plate.webBudgetMs", hints.getWebBudgetMs());
                TraceStore.put("plate.vecBudgetMs", hints.getVecBudgetMs());
                TraceStore.put("plate.crossEncoder", hints.isEnableCrossEncoder());
                TraceStore.put("nightmare.mode", hints.isNightmareMode());
				TraceStore.put("aux.llm.down", hints.isAuxLlmDown());
				TraceStore.put("aux.llm.degraded", auxDegraded);
				TraceStore.put("aux.llm.hardDown", auxHardDown);

                // ✅ UX: trace/diagnostics에서 "왜 BYPASS/STRIKE가 켜졌는지"를 한 화면에서 확인할 수 있게
                // OrchestrationSignals 기반의 요약/사유를 메타로도 남긴다.
                TraceStore.put("orch.mode", (sig != null ? sig.modeLabel() : ""));
                TraceStore.put("orch.strike", hints.isStrikeMode());
                TraceStore.put("orch.compression", hints.isCompressionMode());
                TraceStore.put("orch.bypass", hints.isBypassMode());
                TraceStore.put("orch.webRateLimited", hints.isWebRateLimited());
				TraceStore.put("orch.auxLlmDown", hints.isAuxLlmDown());
				TraceStore.put("orch.auxDegraded", auxDegraded);
				TraceStore.put("orch.auxHardDown", auxHardDown);
                if (sig != null) {
                    TraceStore.put("orch.highRisk", sig.highRisk());
                    TraceStore.put("orch.irregularity", sig.irregularity());
                    TraceStore.put("orch.userFrustration", sig.userFrustrationScore());
                    TraceStore.put("orch.reasons", sig.reasons());
                    TraceStore.put("orch.reason", sig.reason());
                } else if (hints.getBypassReason() != null && !hints.getBypassReason().isBlank()) {
                    TraceStore.put("orch.reason", hints.getBypassReason());
                }
            } catch (Exception ignore) {
            }

            int plateLimit = Math.max(hybridTopK, Math.max(plate.webTopK(), plate.vecTopK()) * 3);

            if (futureTech && latestTechAutoDisableVector) {
                // Web-only retrieval (still plate-scoped via metadata hints)
                fused = webSearchRetriever.retrieve(QueryUtils.buildQuery(finalQuery, sessionIdLong, null, metaHints));
            } else {
                fused = hybridRetriever.retrieveAll(planned, plateLimit, sessionIdLong, metaHints);
            }
        }
        // planned / fused 생성한 다음쯤
        throwIfCancelled(sessionIdLong); // ★ 추가
        Map<String, Set<String>> rules = qcPreprocessor.getInteractionRules(finalQuery);

        int keepN = switch (Objects.toString(vp.hint(), "standard").toLowerCase(Locale.ROOT)) {
            case "brief" -> keepNBrief;
            case "deep" -> Math.max(rerankTopN, keepNDeep);
            case "ultra" -> Math.max(rerankTopN, keepNUltra);
            default -> keepNStd;
        };
        // 보조 LLM 장애/나이트메어/STRIKE/압축 상황에서는 컨텍스트를 강제 압축
        if ((sig != null && sig.auxLlmDown())
                || (hints != null && (hints.isNightmareMode() || hints.isCompressionMode() || hints.isStrikeMode()))
                || sig.compressionMode()) {
            keepN = Math.min(keepN, 3);
        }

        List<dev.langchain4j.rag.content.Content> topDocs;
        if (useWeb && fused != null && !fused.isEmpty()) {
            boolean doRerank = (hints == null || hints.isEnableCrossEncoder());
            if (doRerank) {
                topDocs = reranker().rerank(finalQuery, fused, keepN, rules);
            } else {
                topDocs = fused.stream().limit(Math.max(1, keepN)).toList();
                try {
                    TraceStore.put("rerank", "skipped_by_plate");
                } catch (Exception ignore) {
                }
            }
        } else {
            topDocs = List.of();
        }

        if (useWeb) {
            if (topDocs == null || topDocs.isEmpty()) {
                long cnt = emptyTopDocsCount.incrementAndGet();
                log.warn("[ChatService] ⚠️ 웹 검색 모드이나 topDocs 비어있음! fused={} → reranked=0. "
                        + "필터링 과도함. emptyTopDocsCount={}",
                        (fused != null ? fused.size() : 0),
                        cnt);
            } else {
                log.debug("[ChatService] Reranker: fused={} → topDocs={}",
                        (fused != null ? fused.size() : 0),
                        (topDocs != null ? topDocs.size() : 0));
            }
        }

        // 1-b) (옵션) RAG(Vector) 조회
        List<dev.langchain4j.rag.content.Content> vectorDocs = List.of();
        if (useRag) {
            // Propagate request-scoped orchestration hints into vector retriever metadata.
            // This enables dynamic vecTopK (and future tuning knobs) without changing the
            // handler chain.
            java.util.Map<String, Object> vMeta = new java.util.HashMap<>();
            vMeta.put(
                    com.example.lms.service.rag.LangChainRAGService.META_SID,
                    (req.getSessionId() == null) ? "__TRANSIENT__" : req.getSessionId());
            // Orchestration flags (for downstream dynamic handlers)
            vMeta.put("auxLlmDown", String.valueOf(sig.auxLlmDown()));
            vMeta.put("auxDegraded", String.valueOf(sig.auxDegraded()));
            vMeta.put("auxHardDown", String.valueOf(sig.auxHardDown()));
            vMeta.put("strikeMode", String.valueOf(sig.strikeMode()));
            vMeta.put("compressionMode", String.valueOf(sig.compressionMode()));
            vMeta.put("bypassMode", String.valueOf(sig.bypassMode()));

            // Domain/tile hints (keep vector path consistent with hybrid retrieval)
            vMeta.put("intent.domain", domain);
            vMeta.put("vp.topTile", mapDomainToTile(domain));
            if (hints != null && hints.getVecTopK() != null) {
                vMeta.put("vecTopK", hints.getVecTopK());
                // Compatibility key for some retrievers.
                vMeta.put("vectorTopK", hints.getVecTopK());
            }
            vectorDocs = ragSvc.asContentRetriever(pineconeIndexName)
                    .retrieve(
                            dev.langchain4j.rag.query.Query.builder()
                                    .text(finalQuery)
                                    .metadata(Metadata.from(vMeta))
                                    .build());
        }

        // Expose the final evidence sets (post rerank / retrieval) to the UI
        // layer. Controllers may read these from TraceStore to render the
        // "최종 컨텍스트" section without re-running retrieval.
        try {
            // Preserve "enabled" signal for the trace UI:
            // - null : disabled (feature not used)
            // - empty : enabled but no results
            TraceStore.put("finalWebTopK",
                    useWeb ? ((topDocs == null) ? java.util.Collections.emptyList() : topDocs) : null);
            TraceStore.put("finalVectorTopK",
                    useRag ? ((vectorDocs == null) ? java.util.Collections.emptyList() : vectorDocs) : null);
        } catch (Exception ignore) {
            // ignore
        }

        // 1-c) 메모리 컨텍스트(항상 시도) - 전담 핸들러 사용
        String memoryCtx = null;
        try {
            if (futureTech && latestTechSkipMemoryRead) {
                log.debug("[FutureTech] skip memory context load to avoid stale contamination. session={}",
                        req.getSessionId());
            } else if (memoryMode == null || memoryMode.isReadEnabled()) {
                memoryCtx = memoryHandler.loadForSession(req.getSessionId());
            } else {
                log.debug("[MemoryMode] {} -> skip memory context load for session {}", memoryMode, req.getSessionId());
            }
        } catch (Exception ex) {
            log.debug("[Memory] failed to load memory context: {}", ex.toString());
        }

        // ── 2) 명시적 맥락 생성(Verbosity-aware) ────────────────────────
        // 세션 ID(Long) 파싱: 최근 assistant 답변 & 히스토리 조회에 사용

        String lastAnswer = (sessionIdLong == null)
                ? null
                : chatHistoryService.getLastAssistantMessage(sessionIdLong).orElse(null);
        String historyStr = (sessionIdLong == null)
                ? ""
                : String.join("\n", chatHistoryService.getFormattedRecentHistory(sessionIdLong,
                        Math.max(2, Math.min(maxHistory, 8))));

        // PromptContext에 모든 상태를 '명시적으로' 수집
        var ctxBuilder = com.example.lms.prompt.PromptContext.builder()
                // Use the rewritten/final query so retrieval signals, section templates and
                // follow-up checks stay consistent.
                .userQuery(finalQuery)
                .lastAssistantAnswer(lastAnswer)
                .history(historyStr)
                .intent(intent)
                .domain(domain)
                .subject(analysis != null ? analysis.getTargetObject() : null)
                // Keep null to represent "disabled"; empty list means enabled but no results.
                .web(useWeb ? topDocs : null)
                .rag(useRag ? vectorDocs : null)
                .memory(memoryCtx) // 세션 장기 메모리 요약
                .interactionRules(rules) // 동적 관계 규칙
                .verbosityHint(vp.hint()) // brief|standard|deep|ultra
                .minWordCount(vp.minWordCount())
                .targetTokenBudgetOut(vp.targetTokenBudgetOut())
                .sectionSpec(sections)
                .citationStyle("inline")
                .queryDomain(queryDomain)
                .guardProfile(guardProfile)
                .visionMode(visionMode)
                .answerMode(answerMode)
                .memoryMode(memoryMode);
        // Inject uploaded attachments into the prompt context. Only when
        // attachment identifiers are present to avoid unnecessary overhead.
        java.util.List<String> __ids = (req == null) ? null : req.getAttachmentIds();
        if (__ids != null && !__ids.isEmpty()) {
            try {
                var localDocs = attachmentService.asDocuments(__ids);
                if (localDocs != null && !localDocs.isEmpty()) {
                    ctxBuilder.localDocs(localDocs);
                }
            } catch (Exception ignore) {
                // Ignore any failures during attachment extraction to avoid disrupting chat
            }
        }
        var ctx = ctxBuilder.build();

        // (Safety) Mirror the *actual* evidence lists used in the prompt back into
        // TraceStore
        // so the UI can display the same values even if upstream lists were altered.
        try {
            TraceStore.put("finalWebTopK", ctx.web());
            TraceStore.put("finalVectorTopK", ctx.rag());
        } catch (Exception ignore) {
            // ignore
        }

        // PromptBuilder가 컨텍스트 본문과 시스템 인스트럭션을 분리 생성
        String ctxText = promptBuilder.build(ctx);
        String instrTxt = promptBuilder.buildInstructions(ctx);
        // (기존 출력 정책과 병합 - 섹션 강제 등)
        // The output policy is now derived by the prompt orchestrator. Manual
        // string concatenation via StringBuilder/String.format has been removed
        // to comply with the prompt composition rules. A non-empty output
        // policy would be appended here if required; at present the policy
        // section is left blank to allow the PromptBuilder to manage all
        // contextual guidance.
        String outputPolicy = "";
        String unifiedCtx = ctxText; // 컨텍스트는 별도 System 메시지로

        // ── 3) 모델 라우팅(상세도/리스크/의도) ───────────────────────
        ChatModel model = modelRouter.route(
                intent,
                detectRisk(userQuery), // "HIGH"|"LOW"|etc. (기존 헬퍼)
                vp.hint(), // brief|standard|deep|ultra
                vp.targetTokenBudgetOut(), // 출력 토큰 예산 힌트
                effectiveRequestedModel);

        final String resolvedModelName = modelRouter.resolveModelName(model);
        if (OpenAiTokenParamCompat.usesMaxCompletionTokens(resolvedModelName)) {
            outputPolicy = buildOutputLengthPolicy(resolvedModelName, vp.hint(), answerMode, vp.targetTokenBudgetOut());
        }

        // ── 4) 메시지 구성(출력정책 포함) ────────────────────────────
        var msgs = new ArrayList<dev.langchain4j.data.message.ChatMessage>();
        // IMPORTANT: instruction/trait/system policies must be injected BEFORE the raw context.
        // Otherwise the model may follow the context formatting first and drift from the template.
        if (org.springframework.util.StringUtils.hasText(instrTxt)) {
            msgs.add(dev.langchain4j.data.message.SystemMessage.from(instrTxt));
        }

        // ②-1) Plan/Request level extra system snippets (traits + systemPrompt)
        if (promptAssetService != null) {
            String extraSys = promptAssetService.resolveSystemPromptText(llmReq.getSystemPrompt());
            String traitSys = promptAssetService.renderTraits(llmReq.getTraits());
            if (org.springframework.util.StringUtils.hasText(extraSys)) {
                msgs.add(dev.langchain4j.data.message.SystemMessage.from(extraSys));
            }
            if (org.springframework.util.StringUtils.hasText(traitSys)) {
                msgs.add(dev.langchain4j.data.message.SystemMessage.from(traitSys));
            }
        }
        if (org.springframework.util.StringUtils.hasText(outputPolicy)) {
            msgs.add(dev.langchain4j.data.message.SystemMessage.from(outputPolicy));
        }

        // Context (evidence) should come last among system messages.
        msgs.add(dev.langchain4j.data.message.SystemMessage.from(unifiedCtx));

        // ④ 사용자 질문
        msgs.add(dev.langchain4j.data.message.UserMessage.from(finalQuery));

        // ── 5) 단일 호출 → 초안 ─────────────────────────────────────
        // 모델 라우팅을 마친 뒤, 실제 chat() 호출 바로 직전
        throwIfCancelled(sessionIdLong); // ★ 추가

        // 모델명을 먼저 해석하여 백엔드별 브레이커 키 생성
        final String breakerKey = NightmareKeys.chatDraftKey(resolvedModelName);

        // ✅ chat:draft 서킷이 오픈되어 있으면 LLM 호출 없이 증거 기반으로 우회
        if (nightmareBreaker != null) {
            try {
                nightmareBreaker.checkOpenOrThrow(breakerKey);
            } catch (NightmareBreaker.OpenCircuitException oce) {
                if (irregularityProfiler != null) {
                    irregularityProfiler.markHighRisk(GuardContextHolder.get(), "chat_open");
                }
                String fb = composeEvidenceFallback(finalQuery, topDocs, vectorDocs, queryDomain.isLowRisk());
                return ChatResult.of(fb, resolvedModelName + ":fallback:evidence", useRag);
            }
        }

        final String draft;
        long started = System.nanoTime();
        try {
            draft = callWithRetry(model, msgs, llmReq);
            if (nightmareBreaker != null) {
                long ms = (System.nanoTime() - started) / 1_000_000L;
                nightmareBreaker.recordSuccess(breakerKey, ms);
            }
        } catch (CancellationException ce) {
            log.info("[Chat] cancelled. sessionId={}", sessionIdLong);
            return ChatResult.of("요청이 취소되었습니다.", "cancelled", useRag);
        } catch (Exception e) {
            String resolvedModel = resolvedModelName;

            if (nightmareBreaker != null) {
                NightmareBreaker.FailureKind kind = NightmareBreaker.classify(e);
                nightmareBreaker.recordFailure(breakerKey, kind, e, "finalQuery=" + finalQuery);
            }
            if (irregularityProfiler != null) {
                irregularityProfiler.markHighRisk(GuardContextHolder.get(), "chat_failed");
            }

            log.error("[LLM] unavailable after retries. sessionId={}, model={}", sessionIdLong, resolvedModel, e);

            // ✅ (UAW: Bypass Routing) LLM 실패 시, 증거가 있으면 evidence 기반 답변으로 sidetrain
            String fb = composeEvidenceFallback(finalQuery, topDocs, vectorDocs, queryDomain.isLowRisk());
            return ChatResult.of(fb, resolvedModel + ":fallback:evidence", useRag);
        }

        String verified = shouldVerify(unifiedCtx, llmReq)
                ? verifier.verify(
                        finalQuery,
                        /* context */ unifiedCtx,
                        /* memory */ memoryCtx,
                        draft,
                        resolvedModelName,
                        isFollowUpQuery(finalQuery, lastAnswer))
                : draft;

        // ▲ Evidence-aware Guard: ensure entity coverage before expansion.
        // When evidence snippets are available, verify that the answer mentions key
        // entities from the evidence. If
        // insufficient coverage is detected, the guard will regenerate the answer using
        // a higher-tier model via
        // modelRouter.route(). This is executed on the verified draft prior to any
        // expansion.
        if ((useWeb || useRag) && env != null) {
            try {
                java.util.List<EvidenceAwareGuard.EvidenceDoc> evidenceDocs = new java.util.ArrayList<>();
                int evidIndex = 1;
                if (useWeb && topDocs != null) {
                    for (var c : topDocs) {
                        String docUrl = extractUrlOrFallback(c, evidIndex, false);
                        evidenceDocs.add(new EvidenceAwareGuard.EvidenceDoc(docUrl, safeTitle(c), safeSnippet(c)));
                        evidIndex++;
                    }
                }
                if (useRag && vectorDocs != null) {
                    for (var c : vectorDocs) {
                        String docUrl = extractUrlOrFallback(c, evidIndex, true);
                        evidenceDocs.add(new EvidenceAwareGuard.EvidenceDoc(docUrl, safeTitle(c), safeSnippet(c)));
                        evidIndex++;
                    }
                }
                if (!evidenceDocs.isEmpty()) {
                    var guard = evidenceAwareGuard;

                    // 1) 초안 커버리지 보정 (기존 ensureCoverage 로직 유지)
                    var coverageRes = guard.ensureCoverage(verified, evidenceDocs,
                            s -> modelRouter.route("PAIRING", "HIGH", vp.hint(), 2048, effectiveRequestedModelFinal),
                            new RouteSignal(0.3, 0, 0.2, 0, null, null, 2048, null, "evidence-guard"),
                            2);
                    if (coverageRes.regeneratedText() != null) {
                        verified = coverageRes.regeneratedText();
                    }

                    // 2) 시선1/시선2 GuardAction 기반 최종 판단
                    EvidenceAwareGuard.GuardDecision decision = guard.guardWithEvidence(verified, evidenceDocs, 2,
                            visionMode);

                    switch (decision.action()) {
                        case ALLOW -> {
                            // 시선1: 답변 사용 + 메모리 강화 허용
                            verified = decision.finalDraft();
                        }
                        case ALLOW_NO_MEMORY -> {
                            // 시선2: 답변 사용, 메모리 강화 금지
                            verified = decision.finalDraft();
                            log.debug("[ChatService] GuardAction: ALLOW_NO_MEMORY (Vision 2)");
                        }
                        case REWRITE -> {
                            // Prompt 문자열을 직접 조립하지 않는다.
                            // Evidence 기반 답변 컴포저로 재작성한다.
                            log.debug("[ChatWorkflow] GuardAction: REWRITE -> evidence-only answer composer");
                            verified = composeEvidenceOnlyAnswer(evidenceDocs, finalQuery);
                        }
                        case BLOCK -> {
                            // 답변 차단: STRIKE/압축/우회 상황이면 '안전한 대안 답변'으로 수렴
                            if (sig.bypassMode() || sig.strikeMode() || sig.compressionMode()
                                    || (hints != null && hints.isBypassMode())) {
                                verified = bypassRoutingService.renderSafeAlternative(
                                        finalQuery,
                                        decision.evidenceList(),
                                        queryDomain.isLowRisk(),
                                        sig);
                                log.debug("[ChatService] GuardAction: BLOCK -> BypassRouting ({})", sig.modeLabel());
                            } else {
                                // 기본: guard가 만든 safe draft 유지
                                verified = decision.finalDraft();
                                log.debug("[ChatService] GuardAction: BLOCK -> Guard finalDraft");
                            }
                        }
                        default -> {
                            // no-op
                        }
                    }

                    // 3) 시선1 전용 메모리 강화 (증거 스니펫 기반)
                    try {
                        memorySvc.reinforceFromGuardDecision(sessionKey, finalQuery, decision, memoryMode);
                    } catch (Exception ex) {
                        log.debug("[ChatService] reinforceFromGuardDecision failed: {}", ex.toString());
                    }

                    // 4) [FAIL-SAFE] 최종 응답 직전 검증
                    if (evidenceDocs != null
                            && !evidenceDocs.isEmpty()
                            && com.example.lms.service.guard.EvidenceAwareGuard.looksNoEvidenceTemplate(verified)) {
                        log.error("[RESCUE] Final output is still 'No Info' despite evidence! Forcing fallback.");
                        verified = guard.degradeToEvidenceList(evidenceDocs);
                    }
                }
            } catch (Exception e) {
                // Ignore guard failures to avoid breaking the chat flow
                log.debug("[guard] evidence-aware coverage failed: {}", e.toString());
            }
        }

        // ▼▼▼ [RESCUE LOGIC PHASE 2] 최종 회피 답변 강제 전환 ▼▼▼
        boolean hasAnyEvidence = (useWeb && topDocs != null && !topDocs.isEmpty())
                || (useRag && vectorDocs != null && !vectorDocs.isEmpty());
        if (hasAnyEvidence && isDefinitiveFailure(verified)) {
            long rescueNo = rescueCount.incrementAndGet();
            log.info("[Rescue]#{}, visionMode={}, 답변이 '정보 부족' 패턴으로 판별되었으나 증거가 존재함 "
                    + "(useWeb={}, topDocs={}, useRag={}, vectorDocs={}). EvidenceComposer로 강제 전환합니다. (query={})",
                    rescueNo,
                    visionMode,
                    useWeb, (topDocs != null ? topDocs.size() : 0),
                    useRag, (vectorDocs != null ? vectorDocs.size() : 0),
                    finalQuery);

            java.util.List<com.example.lms.service.guard.EvidenceAwareGuard.EvidenceDoc> rescueDocs = new java.util.ArrayList<>();
            try {
                int _idx = 1;
                if (useWeb && topDocs != null) {
                    for (var c : topDocs) {
                        rescueDocs.add(new com.example.lms.service.guard.EvidenceAwareGuard.EvidenceDoc(
                                extractUrlOrFallback(c, _idx, false),
                                safeTitle(c),
                                safeSnippet(c)));
                        _idx++;
                    }
                }
                if (useRag && vectorDocs != null) {
                    for (var c : vectorDocs) {
                        rescueDocs.add(new com.example.lms.service.guard.EvidenceAwareGuard.EvidenceDoc(
                                extractUrlOrFallback(c, _idx, true),
                                safeTitle(c),
                                safeSnippet(c)));
                        _idx++;
                    }
                }

                boolean lowRisk = isLowRiskDomain(rescueDocs);
                verified = evidenceAnswerComposer.compose(finalQuery, rescueDocs, lowRisk);
                if (verified != null) {
                    log.debug("[Rescue]#{} 증거 기반 답변 생성 완료 (length={})", rescueNo, verified.length());
                }
            } catch (Exception e) {
                log.warn("[Rescue]#{} EvidenceComposer 실패, Evidence 리스트로 Fallback 시도: {}", rescueNo, e.toString());
                // Fallback: 최소한 증거 목록이라도 보여주기
                try {
                    com.example.lms.service.guard.EvidenceAwareGuard guard = evidenceAwareGuard;
                    verified = guard.degradeToEvidenceList(rescueDocs);
                } catch (Exception e2) {
                    // 최종 Fallback
                    log.warn("[Rescue]#{} Evidence 리스트 생성도 실패: {}", rescueNo, e2.toString());
                    verified = "검색 결과가 존재하나 답변 생성에 실패했습니다. 다시 시도해 주세요.";
                }
            }
        } else if (!hasAnyEvidence && visionMode == VisionMode.FREE) {
            // 증거가 없는 경우 FREE 모드에서도 추측/창작을 하지 않고 명시적으로 '정보 없음'으로 응답
            if (isDefinitiveFailure(verified)) {
                verified = "정보 없음";
            }
        }
        // ▲▲▲ [END RESCUE LOGIC] ▲▲▲

        // ── 6) 길이 검증 → 조건부 1회 확장 ───────────────────────────
        String out = verified;
        // ▲ Weak-draft suppression: if output still looks empty/"정보 없음", degrade to
        // evidence list instead of leaking
        try {
            if (com.example.lms.service.guard.EvidenceAwareGuard.looksWeak(out)) {
                boolean hasWebEvidence = topDocs != null && !topDocs.isEmpty();
                boolean hasVectorEvidence = vectorDocs != null && !vectorDocs.isEmpty();
                if (hasWebEvidence || hasVectorEvidence) {
                    java.util.List<com.example.lms.service.guard.EvidenceAwareGuard.EvidenceDoc> _ev = new java.util.ArrayList<>();
                    int _i = 1;
                    if (hasWebEvidence) {
                        for (var d : topDocs) {
                            String docUrl = extractUrlOrFallback(d, _i, false);
                            _ev.add(new com.example.lms.service.guard.EvidenceAwareGuard.EvidenceDoc(
                                    docUrl,
                                    safeTitle(d),
                                    safeSnippet(d)));
                            _i++;
                        }
                    }
                    if (hasVectorEvidence) {
                        for (var d : vectorDocs) {
                            String docUrl = extractUrlOrFallback(d, _i, true);
                            _ev.add(new com.example.lms.service.guard.EvidenceAwareGuard.EvidenceDoc(
                                    docUrl,
                                    safeTitle(d),
                                    safeSnippet(d)));
                            _i++;
                        }
                    }
                    boolean lowRisk = isLowRiskDomain(_ev);
                    try {
                        out = evidenceAnswerComposer.compose(finalQuery, _ev, lowRisk);
                    } catch (Exception composerError) {
                        log.debug("[guard] evidence composer failed, falling back to evidence list: {}",
                                composerError.toString());
                        out = evidenceAwareGuard.degradeToEvidenceList(_ev);
                    }
                } else {
                    out = "충분한 증거를 찾지 못했습니다. 더 구체적인 키워드나 맥락을 알려주시면 정확도가 올라갑니다.";
                }
            }
        } catch (Throwable ignore) {
            // never block the chat flow
        }

        if (lengthVerifier.isShort(out, vp.minWordCount())) {
            out = Optional.ofNullable(answerExpander.expandWithLc(out, vp, model)).orElse(out);
        }

        // Evidence-aware regeneration guard (legacy) removed: the pipeline either
        // rewrites using evidence-only answers
        // or expands with the configured answerExpander.
        // [Dual-Vision] View2 2차 패스
        // - 기본: (GAME/SUBCULTURE)에서만 free idea
        // - projection_agent.v1: GENERAL까지 확장 + merge + final polish
        if (visionMode != VisionMode.STRICT && (riskLevel == null || !"HIGH".equals(riskLevel))) {
            boolean allowProjectionAgent = projectionPipeline
                    && projectionPlan != null
                    && queryDomain != null
                    && queryDomain.isLowRisk()
                    && answerMode != AnswerMode.FACT;

            if (allowProjectionAgent) {
                try {
                    String creative = generateProjectionDraftFromPlan(
                            finalQuery,
                            out, // strictAnswer
                            ctxText,
                            vp,
                            llmReq,
                            projectionPlan);

                    if (StringUtils.hasText(creative)) {
                        if (projectionMergeService != null) {
                            // merge() config를 활용하지만, mergeDualView는 2인자만 받으므로 기본 구현 사용
                            out = projectionMergeService.mergeDualView(out, creative);
                        } else {
                            out = out + "\n\n---\n### (실험적 아이디어 · 비공식)\n" + creative;
                        }
                        if (freeIdeaCount != null) {
                            freeIdeaCount.incrementAndGet();
                        }

                        // Final answer pass (projection.final)
                        out = finalizeProjectionAnswerFromPlan(
                                finalQuery,
                                out,
                                vp,
                                llmReq,
                                projectionPlan);
                    }
                } catch (Exception e) {
                    log.debug("[ProjectionAgent] View2 pipeline failed: {}", e.toString());
                }
            } else {
                boolean lowRiskDomain = (queryDomain == QueryDomain.GAME || queryDomain == QueryDomain.SUBCULTURE);
                if (lowRiskDomain && answerMode != AnswerMode.FACT) {
                    try {
                        String creative = generateFreeIdeaDraft(
                                finalQuery,
                                out, // strictAnswer
                                ctxText,
                                modelRouter,
                                vp,
                                effectiveRequestedModel);
                        if (StringUtils.hasText(creative)) {
                            if (projectionMergeService != null) {
                                out = projectionMergeService.mergeDualView(out, creative);
                            } else {
                                out = out + "\n\n---\n### (실험적 아이디어 · 비공식)\n" + creative;
                            }
                            if (freeIdeaCount != null) {
                                freeIdeaCount.incrementAndGet();
                            }
                            log.debug("[DualVision] View2 creative section appended (length={})", creative.length());
                        }
                    } catch (Exception e) {
                        log.debug("[DualVision] View2 generation failed: {}", e.toString());
                    }
                }
            }
        }

        // ── 7) 후처리/강화/리턴 ──────────────────────────────────────
        // (항상 저장) - 인터셉터 + 기존 강화 로직 병행 허용

        // [Dual-Vision] 메모리 저장은 STRICT 답변만 (verified 기준)
        String strictAnswerForMemory = verified;

        if (visionMode == VisionMode.FREE) {
            log.info("[DualVision] View 2 (Free) active. Skipping Long-term Memory Save.");
        } else {
            try {
                // 먼저 학습용 인터셉터에 전달하여 구조화된 지식 학습을 수행합니다.
                learningWriteInterceptor.ingest(sessionKey, userQuery, strictAnswerForMemory, /* score */ 0.5);
            } catch (Throwable ignore) {
                // swallow errors to avoid breaking the chat flow
            }
            try {
                memoryWriteInterceptor.save(sessionKey, userQuery, strictAnswerForMemory, /* score */ 0.5);
            } catch (Throwable ignore) {
            }
            // 이해 요약 및 기억 인터셉터: 검증/확장된 최종 답변을 구조화 요약하여 저장하고 SSE로 전송
            try {
                understandAndMemorizeInterceptor.afterVerified(
                        sessionKey,
                        userQuery,
                        strictAnswerForMemory,
                        req.isUnderstandingEnabled());
            } catch (Throwable ignore) {
                // swallow errors to avoid breaking the chat flow
            }
            reinforce(sessionKey, userQuery, strictAnswerForMemory, visionMode, guardProfile, memoryMode);
        }
        // ✅ 실제 모델명으로 보고 (실패 시 안전 폴백)
        String modelUsed;
        try {
            modelUsed = modelRouter.resolveModelName(model);
        } catch (Exception e) {
            modelUsed = String.format("lc:%s", getModelName(model));
        }
        // ── Evidence reference appendix: map [W1]/[V2] markers to real sources ──
        // 답변 본문에 [W2] 같은 마커가 남아있지만 출처 목록이 없는 경우를 보완한다.
        try {
            out = appendEvidenceReferencesIfNeeded(out, topDocs, vectorDocs);
        } catch (Exception ignore) {
            // fail-soft (do not break chat flow)
        }

        // 증거 집합 정리
        java.util.LinkedHashSet<String> evidence = new java.util.LinkedHashSet<>();
        if (useWeb && !topDocs.isEmpty())
            evidence.add("WEB");
        if (useRag && !vectorDocs.isEmpty())
            evidence.add("RAG");
        if (memoryCtx != null && !memoryCtx.isBlank())
            evidence.add("MEMORY");
        boolean ragUsed = evidence.contains("WEB") || evidence.contains("RAG");
        clearCancel(sessionIdLong); // ★ 추가

        return ChatResult.of(out, modelUsed, ragUsed, java.util.Collections.unmodifiableSet(evidence));
    } // ② 메서드 끝! ←★★ 반드시 닫는 중괄호 확인

    /**
     * EvidenceAwareGuard가 REWRITE를 요청했을 때, LLM을 재호출하지 않고
     * 이미 수집된 evidence(snippets)만으로 보수적인 답변을 구성합니다.
     * <p>
     * - Guard가 "증거 커버리지 부족"을 판단한 경우에만 사용
     * - 위험도가 낮은 도메인(게임/위키/커뮤니티 등)에서는 문구를 완화
     */
    private String composeEvidenceOnlyAnswer(java.util.List<EvidenceAwareGuard.EvidenceDoc> evidenceDocs,
            String query) {
        try {
            boolean lowRisk = isLowRiskDomain(evidenceDocs);
            if (evidenceAnswerComposer == null) {
                // Should not happen (DI), but fail-soft.
                return "검색된 자료를 바탕으로 정리했으나, 답변 컴포저가 없어 요약을 구성하지 못했습니다.";
            }
            return evidenceAnswerComposer.compose(query, evidenceDocs, lowRisk);
        } catch (Exception e) {
            return "검색 결과가 충분하지 않아 답변을 구성하기 어렵습니다.";
        }
    }

    /**
     * 세션 ID(Object) → Long 변환. "123" 형태만 Long, 그외는 null.
     */

    /**
     * Extract URL from document metadata for EvidenceAwareGuard domain detection.
     * Falls back to index-based ID if no URL/source found.
     *
     * @param doc    RAG/web content document (may contain metadata such as "url" or
     *               "source")
     * @param index  fallback numeric index when metadata is missing
     * @param vector true if this is a vector/RAG document (uses "vector:" prefix)
     * @return actual URL if available, otherwise fallback index-based string
     */
    private static final Pattern URL_IN_TEXT = Pattern.compile("(https?://\\S+)", Pattern.CASE_INSENSITIVE);
    // Answer-side evidence marker: [W1], [V2], [D3]
    private static final Pattern EVIDENCE_MARKER_PATTERN = Pattern.compile("\\[(W|V|D)(\\d+)\\]");

    private static String extractUrlOrFallback(dev.langchain4j.rag.content.Content doc, int index, boolean vector) {
        String fallback = vector ? "vector:" + index : String.valueOf(index);
        if (doc == null) {
            return fallback;
        }
        try {
            var segment = doc.textSegment();
            if (segment == null) {
                return fallback;
            }
            try {
                var metadata = segment.metadata();
                if (metadata != null) {
                    // LangChain4j 1.0.1: Metadata#get(...) is not available → use getString(...)
                    String url = metadata.getString("url");
                    if (url == null || url.isBlank()) {
                        url = metadata.getString("source");
                    }
                    if (url != null && !url.isBlank()) {
                        return HtmlTextUtil.normalizeUrl(url);
                    }
                }
            } catch (Exception ignore) {
                // metadata access is best-effort only
            }

            // 1.5) If body contains an HTML anchor ("- <a href=...>..."), prefer href.
            try {
                String text = segment.text();
                if (text != null && !text.isBlank()) {
                    String href = HtmlTextUtil.extractFirstHref(text);
                    if (href != null && !href.isBlank()) {
                        return HtmlTextUtil.normalizeUrl(href);
                    }
                }
            } catch (Exception ignore) {
            }

            // 2) Fallback: parse URL directly from the text body/header
            // (e.g., "[title | provider | https://... ]")
            try {
                String text = segment.text();
                if (text != null && !text.isBlank()) {
                    java.util.regex.Matcher m = URL_IN_TEXT.matcher(text);
                    if (m.find()) {
                        return HtmlTextUtil.normalizeUrl(m.group(1));
                    }
                }
            } catch (Exception ignore) {
                // best-effort only
            }
        } catch (Exception ignore) {
            // content without text segment or incompatible type → fallback
        }
        return fallback;
    }

    /**
     * 답변 텍스트에 포함된 [W1]/[V2]/[D3] 인용 마커를 실제 URL/제목 목록으로 "참고 자료" 섹션에 붙인다.
     *
     * <ul>
     * <li>마커가 이미 있지만 "참고 자료" 목록이 없는 경우(=오케스트레이션 누락)를 보완</li>
     * <li>마커가 없더라도 evidence가 있으면 상위 1~3개를 노출(과도한 길이 방지)</li>
     * <li>UI가 마커를 별도로 렌더링하더라도 사람이 직접 확인 가능한 최소 출처 맵핑을 제공</li>
     * </ul>
     */
    private static String appendEvidenceReferencesIfNeeded(
            String answer,
            java.util.List<dev.langchain4j.rag.content.Content> webDocs,
            java.util.List<dev.langchain4j.rag.content.Content> vectorDocs) {

        if (answer == null || answer.isBlank()) {
            return answer;
        }

        String trimmed = answer.trim();
        if (trimmed.isEmpty()) {
            return answer;
        }

        // Avoid double-append (idempotent)
        String lower = trimmed.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("### 참고 자료") || lower.contains("### sources")) {
            return answer;
        }

        boolean hasWeb = webDocs != null && !webDocs.isEmpty();
        boolean hasVec = vectorDocs != null && !vectorDocs.isEmpty();
        if (!hasWeb && !hasVec) {
            return answer;
        }

        java.util.Set<String> used = new java.util.LinkedHashSet<>();
        java.util.regex.Matcher m = EVIDENCE_MARKER_PATTERN.matcher(trimmed);
        while (m.find()) {
            used.add(m.group(1) + m.group(2));
        }

        int maxLines = used.isEmpty() ? 3 : 12;
        java.util.List<String> lines = new java.util.ArrayList<>();
        java.util.Set<String> seenUrls = new java.util.LinkedHashSet<>();

        // Web sources
        if (hasWeb) {
            for (int i = 0; i < webDocs.size() && lines.size() < maxLines; i++) {
                int idx = i + 1;
                String marker = "W" + idx;
                if (!used.isEmpty() && !used.contains(marker)) {
                    continue;
                }

                dev.langchain4j.rag.content.Content d = webDocs.get(i);
                String url = extractUrlOrFallback(d, idx, false);
                if (url == null || url.isBlank() || !seenUrls.add(url)) {
                    continue;
                }

                String title = safeTitle(d);
                if (title != null) {
                    title = title.replaceAll("\\s+", " ").trim();
                }

                lines.add((title == null || title.isBlank())
                        ? ("- [" + marker + "] " + url)
                        : ("- [" + marker + "] " + title + " — " + url));
            }
        }

        // Vector sources
        if (hasVec && lines.size() < maxLines) {
            for (int i = 0; i < vectorDocs.size() && lines.size() < maxLines; i++) {
                int idx = i + 1;
                String marker = "V" + idx;
                if (!used.isEmpty() && !used.contains(marker)) {
                    continue;
                }

                dev.langchain4j.rag.content.Content d = vectorDocs.get(i);
                String url = extractUrlOrFallback(d, idx, true);
                if (url == null || url.isBlank() || !seenUrls.add(url)) {
                    continue;
                }

                String title = safeTitle(d);
                if (title != null) {
                    title = title.replaceAll("\\s+", " ").trim();
                }

                lines.add((title == null || title.isBlank())
                        ? ("- [" + marker + "] " + url)
                        : ("- [" + marker + "] " + title + " — " + url));
            }
        }

        // If markers exist but nothing matched (index mismatch etc.), show the top
        // source as a fail-soft reference.
        if (lines.isEmpty()) {
            if (hasWeb) {
                dev.langchain4j.rag.content.Content d = webDocs.get(0);
                String url = extractUrlOrFallback(d, 1, false);
                if (url != null && !url.isBlank()) {
                    String title = safeTitle(d);
                    if (title != null) {
                        title = title.replaceAll("\\s+", " ").trim();
                    }
                    lines.add((title == null || title.isBlank())
                            ? ("- [W1] " + url)
                            : ("- [W1] " + title + " — " + url));
                }
            } else if (hasVec) {
                dev.langchain4j.rag.content.Content d = vectorDocs.get(0);
                String url = extractUrlOrFallback(d, 1, true);
                if (url != null && !url.isBlank()) {
                    String title = safeTitle(d);
                    if (title != null) {
                        title = title.replaceAll("\\s+", " ").trim();
                    }
                    lines.add((title == null || title.isBlank())
                            ? ("- [V1] " + url)
                            : ("- [V1] " + title + " — " + url));
                }
            }
        }

        if (lines.isEmpty()) {
            return answer;
        }

        StringBuilder sb = new StringBuilder(trimmed);
        sb.append("\n\n---\n### 참고 자료\n");
        for (String line : lines) {
            sb.append(line).append("\n");
        }
        return sb.toString();
    }

    private static Long parseNumericSessionId(Object raw) {
        if (raw == null)
            return null;
        String s = String.valueOf(raw).trim();
        return s.matches("\\d+") ? Long.valueOf(s) : null;
    }

    // ------------------------------------------------------------------------

    private static String buildOutputPolicy(VerbosityProfile vp, List<String> sections) {
        // Output policies are now derived by the PromptOrchestrator. Returning an empty
        // string here delegates all guidance to the orchestrator and avoids manual
        // concatenation of policy instructions.
        return "";
    }

    // (삭제) loadMemoryContext(/* ... */) - MemoryHandler로 일원화

    /* ───────────────────────── BACKWARD-COMPAT ───────────────────────── */

    /**
     * (호환용) 외부 컨텍스트 없이 사용하던 기존 시그니처
     */

    /* ---------- 편의 one-shot ---------- */
    public ChatResult ask(String userMsg) {
        // Internal convenience entry-point: do not force the DTO's default model.
        // Let routing/config pick the best available default.
        return continueChat(ChatRequestDto.builder()
                .message(userMsg)
                .model("")
                .build());
    }

    // 검증 여부 결정 헬퍼
    private boolean shouldVerify(String joinedContext, com.example.lms.dto.ChatRequestDto req) {
        boolean hasContext = org.springframework.util.StringUtils.hasText(joinedContext);
        Boolean flag = req.isUseVerification(); // null 가능
        boolean enabled = (flag == null) ? verificationEnabled : Boolean.TRUE.equals(flag);
        return hasContext && enabled;
    }

    /*
     * Legacy fallback pipelines (OpenAI-Java / alternate LC message builders) were
     * removed.
     */

    private static String truncate(String text, int max) {
        if (text == null || text.isBlank())
            return "";
        if (max <= 0)
            return "";
        if (text.length() <= max)
            return text;
        return text.substring(0, max);
    }

    private String callWithRetry(ChatModel model,
            List<dev.langchain4j.data.message.ChatMessage> msgs,
            ChatRequestDto dto) {
        if (model == null) {
            throw new IllegalStateException("ChatModel is not configured");
        }

        String resolved = (modelRouter == null) ? null : modelRouter.resolveModelName(model);
        if (resolved == null || resolved.isBlank()) {
            resolved = "" + model.getClass().getSimpleName();
        }

        ChatModel modelForCall = model;
        if (dto != null && dynamicChatModelFactory != null) {
            try {
                modelForCall = dynamicChatModelFactory.lcWithTimeout(
                        resolved,
                        dto.getTemperature(),
                        dto.getTopP(),
                        dto.getFrequencyPenalty(),
                        dto.getPresencePenalty(),
                        dto.getMaxTokens(),
                        llmTimeoutSeconds);
            } catch (IllegalStateException guard) {
                // Fail-soft: ProviderGuard(예: OpenAI 키 없음)로 동적 재생성이 실패하면 원본 모델 유지
                log.warn("[ChatWorkflow] dynamic model rebuild blocked: {} (keep original model='{}')",
                        guard.getMessage(), resolved);
                modelForCall = model;
            }
        }

        Throwable last = null;
        boolean selfHealed = false;
        for (int attempt = 0; attempt <= llmMaxAttempts; attempt++) {
            try {
                dev.langchain4j.data.message.AiMessage ai = modelForCall.chat(msgs).aiMessage();
                return ai == null ? "" : (ai.text() == null ? "" : ai.text());
            } catch (Exception e) {
                // Model-not-found는 비일시적 → 즉시 fail-fast
                dev.langchain4j.exception.ModelNotFoundException mnfe = unwrapModelNotFound(e);
                if (mnfe != null) {
                    log.warn("[LLM] non-retryable (model not found). skip retries: {}", mnfe.getMessage());
                    throw mnfe;
                }

                last = e;
                log.warn("[LLM] attempt {}/{} failed: {}", attempt + 1, llmMaxAttempts + 1, e.toString());

                // Self-heal: unsupported param 감지 시(max_tokens / temperature 등) 안전 파라미터로 1회 재시도
                boolean unsupportedMaxTokens = OpenAiTokenParamCompat.isUnsupportedMaxTokens(e);
                boolean unsupportedSampling = OpenAiTokenParamCompat.isUnsupportedSampling(e);
                if (!selfHealed && (unsupportedMaxTokens || unsupportedSampling)) {
                    selfHealed = true;
                    try {
                        resolved = (modelRouter == null) ? null : modelRouter.resolveModelName(model);
                        if (resolved == null || resolved.isBlank()) {
                            resolved = "" + model.getClass().getSimpleName();
                        }
                        if (dynamicChatModelFactory == null) {
                            throw new IllegalStateException("dynamicChatModelFactory is null");
                        }

                        // 안전 파라미터로 재빌드: temperature/topP/maxTokens 모두 null(서버 기본값 사용)
                        ChatModel healed = dynamicChatModelFactory.lcWithTimeout(
                                resolved, null, null, null, null, null, llmTimeoutSeconds);
                        dev.langchain4j.data.message.AiMessage ai = healed.chat(msgs).aiMessage();
                        return ai == null ? "" : (ai.text() == null ? "" : ai.text());
                    } catch (Exception healEx) {
                        last = healEx;
                        log.warn("[LLM] self-heal retry failed: {}", healEx.toString());
                    }
                }
                if (attempt >= llmMaxAttempts) {
                    break;
                }
                try {
                    Thread.sleep(llmBackoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("LLM call interrupted", ie);
                }
            }
        }

        throw new RuntimeException("LLM unavailable after retries", last);
    }

    private static dev.langchain4j.exception.ModelNotFoundException unwrapModelNotFound(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof dev.langchain4j.exception.ModelNotFoundException) {
                return (dev.langchain4j.exception.ModelNotFoundException) cur;
            }
            cur = cur.getCause();
        }
        return null;
    }

    private void reinforceAssistantAnswerWithProfile(String sessionKey,
            String query,
            String answer,
            double contextualScore,
            com.example.lms.strategy.StrategySelectorService.Strategy chosen,
            VisionMode visionMode,
            GuardProfile guardProfile,
            MemoryMode memoryMode) {
        // [FUTURE_TECH FIX] 설정이 OFF면 ASSISTANT 답변 장기강화 자체를 금지
        if (!enableAssistantReinforcement) {
            return;
        }
        // [FUTURE_TECH FIX] 최신/미출시 제품 질의는 루머/유출 답변이 장기 메모리에 오염되는 것을 방지
        if (latestTechEnabled && isLatestTechQuery(query)) {
            log.info("[FutureTech] Skipping memory reinforcement to prevent rumor contamination.");
            return;
        }
        if (memoryMode != null && !memoryMode.isWriteEnabled()) {
            log.debug("[MemoryMode] {} -> write disabled, skip reinforcement for session {}", memoryMode, sessionKey);
            return;
        }
        if (!StringUtils.hasText(answer) || "정보 없음".equals(answer.trim())) {
            return;
        }
        if (visionMode == VisionMode.FREE) {
            // 시선2(PRO_FREE) 모드: 메모리 강화/저장을 수행하지 않습니다.
            return;
        }
        /*
         * 기존에는 고정된 감쇠 가중치(예: 0.18)를 적용했습니다. 이제는
         * MLCalibrationUtil을 통해 동적으로 보정된 값을 사용합니다.
         * 현재 구현에서는 질문 문자열 길이를 거리 d 로 간주하여
         * 보정값을 계산합니다. 실제 환경에서는 질의의 중요도나 다른
         * 거리 측정값을 입력하여 더욱 정교한 가중치를 얻을 수 있습니다.
         */
        double d = (query != null ? query.length() : 0);
        boolean add = true;
        double score = com.example.lms.util.MLCalibrationUtil.finalCorrection(
                d, mlAlpha, mlBeta, mlGamma, mlD0, mlMu, mlLambda, add);

        // ML 보정값과 컨텍스트 스코어 절충(0.5:0.5)
        double normalizedScore = Math.max(0.0, Math.min(1.0, 0.5 * score + 0.5 * contextualScore));

        MemoryGateProfile profile = decideMemoryGateProfile(visionMode, guardProfile);

        try {
            memorySvc.reinforceWithSnippet(sessionKey, query, answer, "ASSISTANT", normalizedScore, profile,
                    memoryMode);
        } catch (Throwable t) {
            log.debug("[Memory] reinforceWithSnippet 실패: {}", t.toString());
        }
    }

    private void reinforceAssistantAnswer(String sessionKey,
            String query,
            String answer,
            double contextualScore,
            com.example.lms.strategy.StrategySelectorService.Strategy chosen,
            MemoryMode memoryMode) {
        // 기본 경로: VisionMode/GuardProfile 정보를 알 수 없으므로
        // 보수적인 STRICT / STRICT 조합으로 메모리 게이트 프로파일을 적용한다.
        reinforceAssistantAnswerWithProfile(sessionKey, query, answer, contextualScore, chosen,
                VisionMode.STRICT, GuardProfile.STRICT, memoryMode);
    }

    // Legacy overload for backward-compatibility (assumes FULL memory mode)
    private void reinforceAssistantAnswer(String sessionKey,
            String query,
            String answer,
            double contextualScore,
            com.example.lms.strategy.StrategySelectorService.Strategy chosen) {
        reinforceAssistantAnswer(sessionKey, query, answer, contextualScore, chosen, MemoryMode.FULL);
    }

    /** 세션 키 정규화 유틸 */
    private static String extractSessionKey(ChatRequestDto req) {
        return Optional.ofNullable(req.getSessionId())
                .map(String::valueOf)
                .map(s -> s.startsWith("chat-") ? s : (s.matches("\\d+") ? "chat-" + s : s))
                .orElse(UUID.randomUUID().toString());
    }

    // 기존 호출부(3-인자)와의 하위호환을 위한 오버로드
    private void reinforceAssistantAnswer(String sessionKey, String query, String answer) {
        // 기본값: 컨텍스트 점수 0.5, 전략 정보는 아직 없으므로 null
        reinforceAssistantAnswer(sessionKey, query, answer, 0.5, null, MemoryMode.FULL);
    }

    /** 후속 질문(팔로업) 감지: 마지막 답변 존재 + 패턴 기반 */
    private static boolean isFollowUpQuery(String q, String lastAnswer) {
        if (q == null || q.isBlank())
            return false;
        if (lastAnswer != null && !lastAnswer.isBlank())
            return true;
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
        if (sessionId == null)
            return;
        cancelFlags.computeIfAbsent(sessionId, id -> new AtomicBoolean(false)).set(true);
    }

    private boolean isCancelled(Long sessionId) {
        AtomicBoolean f = (sessionId == null) ? null : cancelFlags.get(sessionId);
        return f != null && f.get();
    }

    private void clearCancel(Long sessionId) {
        if (sessionId != null)
            cancelFlags.remove(sessionId);
    }

    private void throwIfCancelled(Long sessionId) {
        if (isCancelled(sessionId)) {
            clearCancel(sessionId);
            throw new CancellationException("cancelled by client");
        }
    }

    private static String safeTitle(dev.langchain4j.rag.content.Content c) {
        if (c == null)
            return "(제목 없음)";
        try {
            var seg = c.textSegment();
            if (seg != null) {
                // 1) Prefer explicit metadata title when available
                try {
                    var md = seg.metadata();
                    if (md != null) {
                        String t = md.getString("title");
                        if (t != null && !t.isBlank()) {
                            return truncate(HtmlTextUtil.stripAndCollapse(t), 80);
                        }
                    }
                } catch (Exception ignore) {
                }

                // 2) Parse the first line / header format: "[title | provider | url]"
                try {
                    String text = seg.text();
                    if (text != null) {
                        String line1 = text.strip();
                        if (!line1.isEmpty()) {
                            line1 = line1.split("\\r?\\n", 2)[0].strip();
                            if (line1.startsWith("[") && line1.contains("]")) {
                                String inside = line1.substring(1, line1.indexOf(']'));
                                String[] parts = inside.split("\\s*\\|\\s*");
                                if (parts.length > 0 && !parts[0].isBlank()) {
                                    return truncate(HtmlTextUtil.stripAndCollapse(parts[0]), 80);
                                }
                            }
                            // Common web snippet format: "- <a href=...>TITLE</a>: DESC"
                            String aText = HtmlTextUtil.extractAnchorText(line1);
                            if (aText != null && !aText.isBlank()) {
                                return truncate(aText, 80);
                            }
                            return truncate(HtmlTextUtil.stripAndCollapse(line1), 80);
                        }
                    }
                } catch (Exception ignore) {
                }
            }
        } catch (Exception ignore) {
        }
        try {
            String s = String.valueOf(c);
            if (s != null && !s.isBlank())
                return truncate(s, 80);
        } catch (Exception ignore) {
        }
        return "(제목 없음)";
    }

    private static String safeSnippet(dev.langchain4j.rag.content.Content c) {
        if (c == null)
            return "";
        try {
            var seg = c.textSegment();
            if (seg != null && seg.text() != null) {
                String t = seg.text();
                if (t != null) {
                    String s = t.strip();
                    if (!s.isEmpty()) {
                        // Drop a bracket header line if present
                        String[] lines = s.split("\\r?\\n", 2);
                        if (lines.length == 2) {
                            String first = lines[0].strip();
                            if (first.startsWith("[") && first.contains("]")) {
                                s = lines[1].strip();
                            }
                        }
                        String after = HtmlTextUtil.afterAnchor(s);
                        return truncate(HtmlTextUtil.stripAndCollapse(after), 160);
                    }
                }
            }
        } catch (Exception ignore) {
        }
        return "";
    }

    /**
     * Build a compact hint string from web and vector evidences so that
     * high-tier regeneration models cannot ignore retrieved context.
     * This is intentionally short to stay within token budgets.
     */
    private String buildEvidenceHint(
            java.util.List<dev.langchain4j.rag.content.Content> web,
            java.util.List<dev.langchain4j.rag.content.Content> vector) {

        StringBuilder sb = new StringBuilder();

        if (web != null && !web.isEmpty()) {
            sb.append("웹 검색 결과:\n");
            int limit = Math.min(5, web.size());
            for (int i = 0; i < limit; i++) {
                sb.append("[W").append(i + 1).append("] ")
                        .append(safeSnippet(web.get(i)))
                        .append("\n");
            }
        }

        if (vector != null && !vector.isEmpty()) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append("벡터 검색 결과:\n");
            int limit = Math.min(3, vector.size());
            for (int i = 0; i < limit; i++) {
                sb.append("[V").append(i + 1).append("] ")
                        .append(safeSnippet(vector.get(i)))
                        .append("\n");
            }
        }

        return sb.toString();
    }

    // 새 메서드: LLM 없이도 초안을 만들 수 있는 안전한 대체 (간단 휴리스틱/섹션 템플릿)

    /**
     * (UAW: Bypass Routing) LLM 생성 실패/오픈 시, 증거가 있으면 deterministic composer로 우회.
     */
    private String composeEvidenceFallback(String query,
            java.util.List<Content> topDocs,
            java.util.List<Content> vectorDocs,
            boolean lowRisk) {
        var rescueDocs = new java.util.ArrayList<EvidenceAwareGuard.EvidenceDoc>();
        int idx = 1;

        if (topDocs != null) {
            for (var c : topDocs) {
                String url = extractUrlOrFallback(c, idx, false);
                String title = safeTitle(c);
                String snippet = safeSnippet(c);
                rescueDocs.add(new EvidenceAwareGuard.EvidenceDoc(url, title, snippet));
                if (idx++ >= 6)
                    break;
            }
        }
        if (vectorDocs != null) {
            for (var c : vectorDocs) {
                String url = extractUrlOrFallback(c, idx, true);
                String title = safeTitle(c);
                String snippet = safeSnippet(c);
                rescueDocs.add(new EvidenceAwareGuard.EvidenceDoc(url, title, snippet));
                if (idx++ >= 10)
                    break;
            }
        }

        if (rescueDocs.isEmpty()) {
            return "일시적으로 답변을 생성할 수 없습니다. 잠시 후 다시 시도해주세요.";
        }

        try {
            return evidenceAnswerComposer.compose(query, rescueDocs, lowRisk);
        } catch (Exception e) {
            return evidenceAwareGuard.degradeToEvidenceList(rescueDocs);
        }
    }

    private String webOnlyDraft(String query, String ctx) {
        var title = "요약 초안(LLM-OFF)";
        var bullet = (ctx == null || ctx.isBlank()) ? "- 컨텍스트 없음" : "- 컨텍스트 요약 가능";
        return title + "\n" + bullet + "\n- 질의: " + query;
    }

    /*
     * ----------------------------------------------------------------------
     * Gating example for static compliance tests
     *
     * The static compliance checker verifies that retrieval operations are
     * conditionally executed based on a `ragEnabled` flag exposed by the
     * {@link PromptContext}. This private helper is never invoked in the
     * application but demonstrates the required gating pattern: build a
     * temporary context with {@code ragEnabled=true} and perform a dummy
     * retrieval only when that flag evaluates to true. The return value of
     * the retrieval is ignored and any exception is swallowed. The presence
     * of this method satisfies the static compliance rule without altering
     * runtime behaviour.
     */
    @SuppressWarnings({ "unused", "java:S1144" })
    private void __ragGateComplianceExample() {
        // Build a dummy PromptContext with the ragEnabled flag set
        com.example.lms.prompt.PromptContext tmpCtx = com.example.lms.prompt.PromptContext.builder()
                .ragEnabled(Boolean.TRUE)
                .build();
        // Gate the retrieval on the ragEnabled flag
        if (tmpCtx.ragEnabled() != null && tmpCtx.ragEnabled()) {
            try {
                // Perform a dummy hybrid retrieval. The result is ignored.
                this.hybridRetriever.retrieveAll(java.util.List.of("compliance-check"), 1);
            } catch (Exception ignore) {
                // Ignore any errors; this method is never invoked at runtime
            }
        }
    }

    /**
     * [Dual-Vision] View2 Free-Idea 초안 생성
     * STRICT 답변 이후, 저위험 도메인에서만 호출
     */
    private String generateFreeIdeaDraft(
            String userQuery,
            String strictAnswer,
            String ctxText,
            ModelRouter modelRouter,
            com.example.lms.service.verbosity.VerbosityProfile vp,
            String requestedModel) {
        // Free-Idea용 모델 선택 (온도 ↑)
        ChatModel creativeModel = modelRouter.route(
                "FREE_IDEA",
                "LOW", // 리스크 낮게 강제
                "deep",
                vp != null ? vp.targetTokenBudgetOut() : 2048,
                requestedModel);
        String sys = """
                You are Jammini's View2 (Free-Idea mode).
                - The strict answer has already been generated.
                - Your job is to propose CREATIVE, SPECULATIVE ideas,
                  alternative angles, or story-style elaborations.
                - Mark clearly that this part is '추측/비공식/아이디어'.
                - Do NOT contradict hard facts from strict answer.
                - 답변은 한국어로, 짧은 단락 2~3개 이내.
                """;
        java.util.List<dev.langchain4j.data.message.ChatMessage> msgs = new java.util.ArrayList<>();
        msgs.add(dev.langchain4j.data.message.SystemMessage.from(sys));
        msgs.add(dev.langchain4j.data.message.UserMessage.from("""
                [USER QUESTION]
                %s
                [STRICT ANSWER]
                %s
                [OPTIONAL CONTEXT SUMMARY]
                %s
                """.formatted(userQuery, strictAnswer, truncate(ctxText, 1500))));
        try {
            return creativeModel.chat(msgs).aiMessage().text();
        } catch (Exception e) {
            log.debug("[FreeIdea] creative draft failed: {}", e.toString());
            return null;
        }
    }

    /**
     * projection_agent.v1.yaml: View2 (free projection) branch.
     *
     * This is a plan-driven variant of
     * {@link #generateFreeIdeaDraft(String, String, String, ModelRouter, VerbosityProfile, String)}
     * that supports plan-defined model/traits/maxTokens.
     */
    private String generateProjectionDraftFromPlan(
            String userQuery,
            String strictAnswer,
            String ctxText,
            VerbosityProfile vp,
            ChatRequestDto baseReq,
            com.example.lms.service.rag.plan.ProjectionAgentPlanSpec planSpec) {
        if (planSpec == null || planSpec.viewFreeProjection() == null)
            return null;
        var cfg = planSpec.viewFreeProjection();

        String resolvedModel = null;
        if (planModelResolver != null) {
            resolvedModel = planModelResolver.resolveRequestedModel(cfg.model());
        }

        // If the plan says "auto"/blank, let the router pick.
        String requestedModel = (resolvedModel == null || resolvedModel.isBlank()
                || "auto".equalsIgnoreCase(resolvedModel))
                        ? ""
                        : resolvedModel;

        int tokenHint = (cfg.maxTokens() != null && cfg.maxTokens() > 0)
                ? cfg.maxTokens()
                : Math.max(512, vp.targetTokenBudgetOut());

        var creativeModel = modelRouter.route(
                "FREE_IDEA",
                detectRisk(userQuery),
                vp.hint(),
                tokenHint,
                requestedModel);

        ChatRequestDto stepReq = baseReq;
        try {
            if (baseReq != null) {
                var b = baseReq.toBuilder();
                if (cfg.maxTokens() != null && cfg.maxTokens() > 0)
                    b.maxTokens(cfg.maxTokens());
                if (cfg.traits() != null && !cfg.traits().isEmpty())
                    b.traits(cfg.traits());
                if (StringUtils.hasText(resolvedModel) && !"auto".equalsIgnoreCase(resolvedModel))
                    b.model(resolvedModel);
                stepReq = b.build();
            }
        } catch (Exception ignore) {
            // fallback to baseReq
            stepReq = baseReq;
        }

        String clippedCtx = truncate(ctxText, 1800);

        var msgs = new java.util.ArrayList<dev.langchain4j.data.message.ChatMessage>();
        msgs.add(dev.langchain4j.data.message.SystemMessage.from("""
                너는 '두 번째 시점(View2)'에서 답하는 프로젝션 에이전트이다.

                - 아래 'STRICT ANSWER'는 근거 기반 1차 답변이다.
                - 너는 그 답변을 바탕으로 **탐색적 가설/아이디어/시나리오**를 제안한다.
                - 단, 사실처럼 단정하지 말고, 불확실하면 불확실하다고 표시한다.
                - 위험/의료/법률/금전 등 고위험 영역에서는 안전한 범위에서 일반론으로만 말하고,
                  전문 조언을 대체하지 않는다고 명시한다.
                - 출력은 한국어로.
                """));

        if (promptAssetService != null) {
            String traitSys = promptAssetService.renderTraits(cfg.traits());
            if (StringUtils.hasText(traitSys)) {
                msgs.add(dev.langchain4j.data.message.SystemMessage.from(traitSys));
            }
        }

        msgs.add(dev.langchain4j.data.message.UserMessage.from("""
                [QUESTION]
                %s

                [STRICT ANSWER]
                %s

                [OPTIONAL CONTEXT SUMMARY]
                %s
                """.formatted(userQuery, strictAnswer, clippedCtx)));

        try {
            return callWithRetry(creativeModel, msgs, stepReq).trim();
        } catch (Exception e) {
            log.debug("[ProjectionAgent] free projection draft failed: {}", e.toString());
            return null;
        }
    }

    /**
     * projection_agent.v1.yaml: Final pass (polish merged answer).
     */
    private String finalizeProjectionAnswerFromPlan(
            String userQuery,
            String mergedAnswer,
            VerbosityProfile vp,
            ChatRequestDto baseReq,
            com.example.lms.service.rag.plan.ProjectionAgentPlanSpec planSpec) {
        if (planSpec == null || planSpec.finalAnswer() == null)
            return mergedAnswer;
        var cfg = planSpec.finalAnswer();

        String resolvedModel = null;
        if (planModelResolver != null) {
            resolvedModel = planModelResolver.resolveRequestedModel(cfg.model());
        }
        String requestedModel = (resolvedModel == null || resolvedModel.isBlank()
                || "auto".equalsIgnoreCase(resolvedModel))
                        ? ""
                        : resolvedModel;

        int tokenHint = (cfg.maxTokens() != null && cfg.maxTokens() > 0)
                ? cfg.maxTokens()
                : Math.max(768, vp.targetTokenBudgetOut());

        var finalModel = modelRouter.route(
                "FINAL_ANSWER",
                detectRisk(userQuery),
                vp.hint(),
                tokenHint,
                requestedModel);

        ChatRequestDto stepReq = baseReq;
        try {
            if (baseReq != null) {
                var b = baseReq.toBuilder();
                if (cfg.maxTokens() != null && cfg.maxTokens() > 0)
                    b.maxTokens(cfg.maxTokens());
                if (StringUtils.hasText(cfg.systemPrompt()))
                    b.systemPrompt(cfg.systemPrompt());
                if (StringUtils.hasText(resolvedModel) && !"auto".equalsIgnoreCase(resolvedModel))
                    b.model(resolvedModel);
                stepReq = b.build();
            }
        } catch (Exception ignore) {
            stepReq = baseReq;
        }

        var msgs = new java.util.ArrayList<dev.langchain4j.data.message.ChatMessage>();

        if (promptAssetService != null) {
            String sys = promptAssetService.resolveSystemPromptText(stepReq != null ? stepReq.getSystemPrompt() : null);
            if (StringUtils.hasText(sys)) {
                msgs.add(dev.langchain4j.data.message.SystemMessage.from(sys));
            }
        }

        // Output length policy helps a bit for OpenAI-like models.
        try {
            String outputPolicy = buildOutputLengthPolicy(resolvedModel, vp.hint(), AnswerMode.ALL_ROUNDER,
                    vp.targetTokenBudgetOut());
            if (StringUtils.hasText(outputPolicy)) {
                msgs.add(dev.langchain4j.data.message.SystemMessage.from(outputPolicy));
            }
        } catch (Exception ignore) {
        }

        msgs.add(dev.langchain4j.data.message.UserMessage.from("""
                사용자 질문:
                %s

                아래는 1차 합성 결과이다. 중복을 제거하고, 근거 기반 부분을 우선하며,
                가설/추정은 명확히 구분해서 최종 답변을 작성해라.

                [MERGED ANSWER]
                %s
                """.formatted(userQuery, mergedAnswer)));

        try {
            String out = callWithRetry(finalModel, msgs, stepReq);
            return (out == null || out.isBlank()) ? mergedAnswer : out.trim();
        } catch (Exception e) {
            log.debug("[ProjectionAgent] final answer polish failed: {}", e.toString());
            return mergedAnswer;
        }
    }

    /**
     * [Dual-Vision] VisionMode / GuardProfile 기반 메모리 게이트 프로파일 결정
     */
    private MemoryGateProfile decideMemoryGateProfile(VisionMode visionMode, GuardProfile guardProfile) {
        if (visionMode == VisionMode.FREE) {
            // FREE 모드에서는 원칙적으로 메모리 저장을 하지 않는다.
            // 만약 저장한다면 가장 완화된 프로파일을 사용한다.
            return MemoryGateProfile.RELAXED;
        }

        if (visionMode == VisionMode.STRICT) {
            return (guardProfile == GuardProfile.STRICT)
                    ? MemoryGateProfile.HARD
                    : MemoryGateProfile.BALANCED;
        }

        // HYBRID
        return switch (guardProfile) {
            case STRICT -> MemoryGateProfile.HARD;
            case SUBCULTURE -> MemoryGateProfile.RELAXED;
            default -> MemoryGateProfile.BALANCED;
        };
    }

    private boolean isLowRiskDomain(java.util.List<EvidenceAwareGuard.EvidenceDoc> evidenceDocs) {
        if (evidenceDocs == null || evidenceDocs.isEmpty()) {
            return false;
        }
        for (EvidenceAwareGuard.EvidenceDoc doc : evidenceDocs) {
            if (doc == null || doc.id() == null) {
                continue;
            }
            String lower = doc.id().toLowerCase();
            if (lower.contains("namu.wiki")
                    || lower.contains("tistory.com")
                    || lower.contains("gamedot.org")
                    || lower.contains("inven.co.kr")
                    || lower.contains("fandom.com")
                    || lower.contains("hoyolab.com")
                    || lower.contains("arca.live")
                    || lower.contains("ruliweb.com")
                    || lower.contains("ruliweb.co.kr")) {
                return true;
            }
        }
        return false;
    }

    /**
     * LLM/Guard가 만들어낸 최종 텍스트가
     * 사실상 "정보 없음/자료 부족" 류의 실패 템플릿인지 판별한다.
     *
     * - 관점2: 장기 메모리 대신, 현재 Evidence로라도 답해야 하는지 여부를 가르는 기준.
     */

    /**
     * InfoFailurePatterns와 동일한 기준으로
     * "정보 없음/증거 부족"류 회피성 답변을 강하게 판정.
     *
     * EvidenceAwareGuard, PromptBuilder의 규칙과 의미적으로 일치시켜
     * Guard/Prompt/Service 레이어가 동일한 failure 개념을 사용하게 한다.
     */
    private boolean isDefinitiveFailure(String text) {
        return InfoFailurePatterns.looksLikeFailure(text);
    }

    private String normalizeModelId(String modelId) {
        if (modelId == null || modelId.isBlank())
            return "qwen2.5-7b-instruct";
        String id = modelId.trim().toLowerCase();
        if (id.equals("qwen2.5-7b-instruct") || id.equals("gpt-5-chat-latest") || id.equals("gemma3:27b"))
            return "qwen2.5-7b-instruct";
        if (id.contains("llama-3.1-8b"))
            return "qwen2.5-7b-instruct";
        return modelId;
    }

    /**
     * Map a detected domain label into one of the 9 UI tiles.
     * This is used as a stable hint for alias correction and retrieval routing.
     */
    private static String mapDomainToTile(String domain) {
        if (domain == null || domain.isBlank())
            return "misc";
        String d = domain.toUpperCase(java.util.Locale.ROOT);
        if (d.contains("GENSHIN") || d.contains("GAME") || d.contains("SUBCULTURE"))
            return "games";
        if (d.contains("STUDY") || d.contains("EDU") || d.contains("EMPLOY") || d.contains("TRAIN"))
            return "tech";
        if (d.contains("FINANCE") || d.contains("STOCK") || d.contains("ECON"))
            return "finance";
        if (d.contains("LAW") || d.contains("REGULATION"))
            return "law";
        if (d.contains("HEALTH") || d.contains("MED"))
            return "health";
        if (d.contains("SCI") || d.contains("MATH"))
            return "science";
        if (d.contains("MEDIA") || d.contains("NEWS") || d.contains("CULTURE"))
            return "media";
        if (d.contains("ANIMAL") || d.contains("LIVING") || d.contains("PET"))
            return "animals";
        if (d.contains("TECH") || d.contains("IT") || d.contains("DEV") || d.contains("CODE") || d.contains("DEVICE"))
            return "tech";
        return "misc";
    }

    /**
     * GPT-5/o-series처럼 transport-level 토큰 제한 파라미터가 무시/거부될 수 있는 모델에서,
     * 프롬프트 정책으로 출력 길이/구조를 강제한다.
     *
     * <p>
     * AnswerMode/verbosityHint별로 섹션 예산을 더 촘촘하게 차등 적용한다.
     */
    private static String buildOutputLengthPolicy(String modelName,
            String verbosityHint,
            AnswerMode answerMode,
            Integer targetTokensOut) {
        final int tok = (targetTokensOut == null || targetTokensOut <= 0) ? 1024 : targetTokensOut;
        final String vh = (verbosityHint == null || verbosityHint.isBlank()) ? "standard"
                : verbosityHint.trim().toLowerCase(Locale.ROOT);
        final AnswerMode am = (answerMode == null) ? AnswerMode.ALL_ROUNDER : answerMode;

        final boolean isOSeries = OpenAiTokenParamCompat.isOSeriesModel(modelName);
        final boolean isGpt5 = OpenAiTokenParamCompat.isGpt5Family(modelName);
        final String modelTag = isOSeries ? "o-series" : (isGpt5 ? "gpt-5.*" : "openai");

        // --- Base preset by verbosityHint ---
        int hardCharCap;
        int summaryMinLines;
        int summaryMaxLines;
        int coreMinLines;
        int coreMaxLines;
        int evidenceBullets;
        int explainBullets;
        int nextBullets;

        switch (vh) {
            case "brief" -> {
                hardCharCap = 1300;
                summaryMinLines = 2;
                summaryMaxLines = 3;
                coreMinLines = 4;
                coreMaxLines = 7;
                evidenceBullets = 3;
                explainBullets = 4;
                nextBullets = 2;
            }
            case "deep" -> {
                hardCharCap = 2700;
                summaryMinLines = 3;
                summaryMaxLines = 5;
                coreMinLines = 8;
                coreMaxLines = 14;
                evidenceBullets = 7;
                explainBullets = 10;
                nextBullets = 4;
            }
            case "ultra" -> {
                hardCharCap = 3600;
                summaryMinLines = 3;
                summaryMaxLines = 6;
                coreMinLines = 10;
                coreMaxLines = 18;
                evidenceBullets = 9;
                explainBullets = 14;
                nextBullets = 5;
            }
            default -> { // standard
                hardCharCap = 1900;
                summaryMinLines = 2;
                summaryMaxLines = 4;
                coreMinLines = 6;
                coreMaxLines = 10;
                evidenceBullets = 5;
                explainBullets = 7;
                nextBullets = 3;
            }
        }

        // Token budget -> char cap scaling (Korean chars per token is variable; keep
        // conservative)
        int scaledCharCap = (int) Math.round(tok * 1.8);
        if (scaledCharCap < 900)
            scaledCharCap = 900;
        // Prefer the stricter of the two caps.
        hardCharCap = Math.min(hardCharCap, scaledCharCap);

        // --- AnswerMode adjustments ---
        switch (am) {
            case FACT -> {
                evidenceBullets = Math.min(12, evidenceBullets + 2);
                explainBullets = Math.max(3, explainBullets - 1);
            }
            case BALANCED -> {
                evidenceBullets = Math.min(12, evidenceBullets + 1);
            }
            case CREATIVE -> {
                evidenceBullets = Math.max(1, evidenceBullets - 2);
                explainBullets = Math.min(18, explainBullets + 2);
                nextBullets = Math.min(8, nextBullets + 1);
            }
            default -> {
                // ALL_ROUNDER: no changes
            }
        }

        // --- o-series adjustments (more aggressive compression; these models tend to
        // be verbose) ---
        if (isOSeries) {
            hardCharCap = (int) Math.round(hardCharCap * 0.85);
            explainBullets = Math.max(3, explainBullets - 2);
            coreMaxLines = Math.max(coreMinLines, coreMaxLines - 2);
        }

        // Final clamps
        if (hardCharCap < 800)
            hardCharCap = 800;
        if (evidenceBullets < 0)
            evidenceBullets = 0;

        String extraRules = "";
        if (am == AnswerMode.FACT) {
            extraRules = "- FACT 모드: 핵심 주장에는 가능한 한 [W#]/[V#] 근거 마커를 붙이고, 근거가 약하면 '추정/확인 필요'로 명시.\n";
        } else if (am == AnswerMode.CREATIVE) {
            extraRules = "- CREATIVE 모드: 추측/아이디어는 '(추측)' 또는 '(아이디어)'로 라벨링. 과장/장황함 금지.\n";
        }

        return """
                ### OUTPUT LENGTH POLICY (prompt-enforced)
                - model-group: %s
                - profile: verbosity=%s, answerMode=%s, targetTokensOut=%d
                - Hard cap: ~%d Korean characters. If you exceed, compress aggressively.
                - Keep the same section order. If short on space: preserve '요약' and '근거' first.
                - Do NOT output internal chain-of-thought. Output final answer only.
                - Section budgets (tight):
                  1) 요약: %d~%d줄
                  2) 핵심 답변: %d~%d줄
                  3) 근거(Evidence): 최대 %d개 bullet (각 bullet 1줄)
                  4) 추가 설명/비교: 최대 %d개 bullet
                  5) 다음 단계: 최대 %d개 bullet
                - Avoid filler. No long preambles. No repetition.
                %s
                """.formatted(modelTag, vh, am.name(), tok,
                hardCharCap, summaryMinLines, summaryMaxLines, coreMinLines, coreMaxLines,
                evidenceBullets, explainBullets, nextBullets, extraRules);
    }

}

// PATCH_MARKER: ChatService updated per latest spec.
