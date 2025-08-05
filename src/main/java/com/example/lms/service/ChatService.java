package com.example.lms.service;
import org.springframework.beans.factory.annotation.Qualifier;
import com.example.lms.domain.enums.RulePhase;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.entity.CurrentModel;
import com.example.lms.repository.CurrentModelRepository;
import com.example.lms.service.NaverSearchService;
// src/main/java/com/example/lms/service/rag/SelfAskWebSearchRetriever.java
import java.util.LinkedHashSet;
import org.springframework.lang.Nullable;
import java.util.function.Consumer;
import dev.langchain4j.rag.content.Content;   // ✅ hybridRetriever 에서 사용
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
import dev.langchain4j.chain.ConversationalRetrievalChain;
import com.example.lms.service.FactVerifierService;  // 검증 서비스 주입

/* ---------- LangChain4j ---------- */
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
// import 블록
import java.util.stream.Stream;          // buildUnifiedContext 사용
import java.util.stream.Collectors;
import org.springframework.lang.Nullable;      // ③ searchSnippets 3-인자용
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;

/* ---------- RAG ---------- */
import com.example.lms.service.rag.LangChainRAGService;

import dev.langchain4j.memory.chat.ChatMemoryProvider;   // ← 이 한 줄만 추가

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

// ① import
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.Map;                              // ⬅️ 추가
import java.util.concurrent.ConcurrentHashMap;     // ⬅️ 추가
// (다른 import 들 모여 있는 곳에 아래 한 줄을 넣어 주세요)



// import 블록 맨 아래쯤
import dev.langchain4j.memory.ChatMemory;
import com.example.lms.transform.QueryTransformer;            // ⬅️ 추가
/**
 * 중앙 허브 – OpenAI-Java · LangChain4j · RAG 통합. (v7.2, RAG 우선 패치 적용)
 * <p>
 * - LangChain4j 1.0.1 API 대응
 * - "웹‑RAG 우선" 4‑Point 패치(프롬프트 강화 / 메시지 순서 / RAG 길이 제한 / 디버그 로그) 반영
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

    private final OpenAiService openAi;     // OpenAI-Java SDK
    private final ChatModel chatModel;  // 기본 LangChain4j ChatModel
    private final PromptService promptSvc;
    private final CurrentModelRepository modelRepo;
    private final RuleEngine ruleEngine;
    private final MemoryReinforcementService memorySvc;
    private final FactVerifierService verifier;     // ★ 신규 주입

    private final LangChainRAGService ragSvc;

    // 이미 있는 DI 필드 아래쪽에 추가
    private final NaverSearchService searchService;
    private final ChatMemoryProvider chatMemoryProvider; // 세션 메모리 Bean
    private final QueryTransformer queryTransformer;     // ⬅️ 추가: 힌트 기반 2차 검색

    // 하이브리드 검색기를 Bean 으로 주입
    private final HybridRetriever hybridRetriever;
    private final com.github.benmanes.caffeine.cache.LoadingCache<String, ConversationalRetrievalChain> chains =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                    .maximumSize(1024)
                    .expireAfterAccess(java.time.Duration.ofHours(6))
                    // CacheLoader는 K -> V 시그니처여야 하므로 세션키를 받아 체인을 생성하는 팩토리로 연결
                    .build(this::createChain);


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
            ### LIVE WEB RESULTS (highest priority)
            %s

            - Extract concrete dates (YYYY-MM-DD) if present.
            - Cite site titles in parentheses.
            """;


    /* 폴리싱용 시스템 프롬프트 (단일 정의) */
    private static final String POLISH_SYS_PROMPT =
            "다음 초안을 더 자연스럽고 전문적인 한국어로 다듬어 주세요. 새로운 정보는 추가하지 마세요.";
    /* ──────────────── RAG 패치: 프롬프트 강화 ──────────────── */
    private static final String RAG_PREFIX = """
            ### CONTEXT (ordered by priority)
            %s
            
            ### INSTRUCTIONS
            - **Earlier sections have higher authority.**
              ↳ Web-search snippets come first and OVERRIDE any conflicting vector-RAG or memory info.
            - Cite the source titles when you answer.
            - If the information is insufficient, reply "정보 없음".
            """;
    private static final String MEM_PREFIX = """
            ### LONG-TERM MEMORY
            %s
            """;


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

    // ------------------------------------------------------------------------
// ② 2-인자 실제 구현 (헤더·중괄호 반드시 포함!)
    public ChatResult continueChat(ChatRequestDto req,
                                   Function<String, List<String>> externalCtxProvider) {

        /* 0) 플래그 */
        boolean useRetrieval = req.isUseWebSearch() || req.isUseRag();
        boolean ragStandalone = req.isUseRag() && Boolean.TRUE.equals(req.getRagStandalone());
        boolean web = true, rag = true, mem = true;
        // 사람/의료진 질의 → 웹 우선, RAG 억제
        if (isPersonQuery(req.getMessage())) {
            rag = false;
        }
        /* A. RAG Stand-Alone */
// A. RAG Stand-Alone
        if (ragStandalone) {
            String sid = Optional.ofNullable(req.getSessionId())
                    .map(String::valueOf)
                    .map(s -> s.startsWith("chat-") ? s           // 이미 정규화
                            : (s.matches("\\d+")     ? "chat-"+s   // 205 → chat-205
                       : s))                                  // UUID 등
                .orElse(UUID.randomUUID().toString());

            String answer = ragSvc.getAnswer(req.getMessage(), sid);

            // ▲ ASSISTANT 답변 저장 금지(정책)
            return ChatResult.of(
                    answer, "rag:" + chatModel.getClass().getSimpleName(), true);
        }

        /* B. Retrieval OFF */
        if (!useRetrieval) {
            String sessionId = Optional.ofNullable(req.getSessionId())
                    .map(String::valueOf)
                    .map(s -> s.startsWith("chat-") ? s : (s.matches("\\d+") ? "chat-"+s : s))
                .orElse(UUID.randomUUID().toString());

            String memCtx = memorySvc.loadContext(sessionId);

            boolean useLc = Optional.ofNullable(req.getModel())
                    .map(m -> m.startsWith("lc:"))
                    .orElse(false);

            /* unifiedCtx = memCtx 만 전달 */
            return useLc
                    ? invokeLangChain(req, memCtx)
                    : invokeOpenAiJava(req, memCtx);
        }

        /* C. Retrieval ON (Hybrid)
         *    ▶▶ 하나의 세션키(sessionKey)만 생성·전파 ◀◀
         */
        String sessionKey = Optional.ofNullable(req.getSessionId())
                .map(String::valueOf)
                .map(s -> s.startsWith("chat-") ? s : (s.matches("\\d+") ? "chat-"+s : s))
                .orElse(UUID.randomUUID().toString());

        /* ── 세션별 메모리 / RAG 컨텍스트 로드 ───────────────── */
        String memCtx = memorySvc.loadContext(sessionKey);   // ✅ 세션‑스코프
        String ragCtx = req.isUseRag()
                ? ragSvc.getAnswer(req.getMessage(), sessionKey)
                : null;

        /* ❶ "정보 없음" 은 의미 없는 컨텍스트 → null 로 치환 */
        if ("정보 없음".equals((ragCtx != null ? ragCtx.trim() : ""))) {
            ragCtx = null;
        }

        // 1차 웹 검색(사용자 질의만)
        String webCtx = req.isUseWebSearch()
                ? String.join("\n", externalCtxProvider.apply(req.getMessage()))
                : null;
        /* ❷ ditto */
        if ("정보 없음".equals((webCtx != null ? webCtx.trim() : ""))) {
            webCtx = null;
        }

        // ▲ 컨텍스트 저장은 NaverSearchService가 스니펫별 점수로 수행.
        //    ChatService에서는 별도 강화하지 않음(ASSISTANT/HYBRID 오염 방지).
        String unifiedCtx = buildUnifiedContext(webCtx, ragCtx, memCtx);

// ❷ 체인 캐싱 역시 동일 키 사용
        ConversationalRetrievalChain chain = chains.get(sessionKey); // LoadingCache가 lazy-build
        // 📌 웹 검색이 비어 있어도 RAG·Memory 컨텍스트로 답변을 시도한다.
        //    (webCtx == null 가능성에 대비해 이후 로직은 null-safe 로 작성돼 있음)

        /* 🔴 공식 출처 게이트: 질의가 '라이브 패치/공지' 의도이면 OFFICIAL 출처 필수 */
        boolean officialRequired = isLivePatchNewsQuery(req.getMessage());
        if (officialRequired && !containsOfficialSource(webCtx)) {
            // ✋ 조기 종료: 저장 금지 & 더 찾아볼지 사용자에게 재질문
            return ChatResult.of("확인 불가: 공식 출처가 검색되지 않았습니다. 도메인 범위를 넓혀 더 찾아볼까요?",
                    "lc:" + chatModel.getClass().getSimpleName(), true);
        }

        // ───────────────────────────────────────────────
        // 초기 컨텍스트로 1차 초안 답변 생성
        String draft = chain.execute(req.getMessage());
        // (B) **LLM 힌트 기반 추가 검색 단계**: 초안 답변을 이용해 추가 웹 검색
        List<String> queries = queryTransformer.transformEnhanced(req.getMessage(), draft);
        if (queries.size() > 1) {
            List<String> extraSnippets = aggregateSearch(queries, 5);
            if (!extraSnippets.isEmpty()) {
                String mergedWeb = concatIfNew(webCtx, String.join("\n", extraSnippets));
                if (!mergedWeb.equals(webCtx)) {
                    // 웹 컨텍스트에 새 스니펫 추가된 경우 컨텍스트 재빌드 후 답변 재생성
                    String unifiedCtx2 = buildUnifiedContext(mergedWeb, ragCtx, memCtx);
                    String answer = chain.execute(req.getMessage());
                    return ChatResult.of(answer, "lc:" + chatModel.getClass().getSimpleName(), true);
                }
            }
        }
        // 새 스니펫이 없으면 초안 답변 그대로 반환
        return ChatResult.of(draft, "lc:" + chatModel.getClass().getSimpleName(), true);


    }   // ② 메서드 끝!  ←★★ 반드시 닫는 중괄호 확인
// ------------------------------------------------------------------------


    // 📌 ChatService 생성자 끝이나 @PostConstruct 블록에서 한 번만 초기화
    @PostConstruct
    private void initRetrievalChain() {
        // 체인은 필요할 때 lazy-build 하므로 여기서는 초기 작업이 없습니다.
    }

    private ConversationalRetrievalChain buildChain(ChatMemory mem) {
        return ConversationalRetrievalChain.builder()
                .chatModel(chatModel)
                .chatMemory(mem)
                .contentRetriever(hybridRetriever)   // ✅ 외부 Bean 사용
                .build();

    }


/* ───────────────────────── BACKWARD-COMPAT ───────────────────────── */
/** (호환용) 외부 컨텍스트 없이 사용하던 기존 시그니처 */



/* ---------- 편의 one‑shot ---------- */
public ChatResult ask(String userMsg) {
    return continueChat(ChatRequestDto.builder()
            .message(userMsg)
            .build());
}

    /* ═════════ OpenAI‑Java 파이프라인 (2‑Pass + 검증) ═════════ */

    /** OpenAI‑Java 파이프라인 – 단일 unifiedCtx 인자 사용 */
            private ChatResult invokeOpenAiJava(ChatRequestDto req, String unifiedCtx) {

        String modelId = chooseModel(req.getModel(), false);

        List<com.theokanning.openai.completion.chat.ChatMessage> msgs = new ArrayList<>();
        addSystemPrompt(msgs, req.getSystemPrompt());
                /* 병합된 컨텍스트 한 번만 주입 */
                addContextOai(msgs, "%s", unifiedCtx, defaultWebCtxMaxTokens + defaultRagCtxMaxTokens  +defaultMemCtxMaxTokens);
        appendHistoryOai(msgs, req.getHistory());
        appendUserOai(msgs, req.getMessage());

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
                    req.getMessage(),   // 원 질문
                    draft,              // 1차 초안
                    5);                 // top-k
            String hintWebCtx = hintSnippets.isEmpty()
                    ? null
                    : String.join("\n", hintSnippets);

            /* ─── ② 컨텍스트 병합 & 검증 ─── */
            String joinedContext = Stream.of(unifiedCtx, hintWebCtx)
                    .filter(StringUtils::hasText)
                    .collect(Collectors.joining("\n"));
            String verified = Boolean.TRUE.equals(req.isUseVerification())
                    ? verifier.verify(req.getMessage(), joinedContext, draft, "gpt-4o")
                    : draft;

            /* ─── ② (선택) 폴리싱 ─── */
            /* ─── ② 경고‑배너 & (선택) 폴리싱 ─── */
            boolean insufficientContext = !StringUtils.hasText(joinedContext);
            boolean fallbackHappened = Boolean.TRUE.equals(req.isUseVerification())
                    && StringUtils.hasText(joinedContext)
                    && verified.equals(draft);          // 검증이 변화 못 줌

            String warning = "\n\n⚠️ 본 답변은 검증된 정보가 부족하거나 부정확할 수 있습니다. 참고용으로 활용해 주세요.";
            String toPolish = (insufficientContext || fallbackHappened)
                    ? verified  +warning
            : verified;

            String finalText = req.isPolish()
                    ? polishAnswerOai(toPolish, modelId,
                    req.getMaxTokens(),
                    req.getTemperature(),
                    req.getTopP())
                    : toPolish;

            /* ─── ③ 후처리 & 메모리 ─── */
            String out = ruleEngine.apply(finalText, "ko", RulePhase.POST);
            reinforceMemoryWithText(out);
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

    /* ═════════ LangChain4j 파이프라인 (2‑Pass + 검증) ═════════ */

    /** LangChain4j 파이프라인 – unifiedCtx 사용 */
    private ChatResult invokeLangChain(ChatRequestDto req, String unifiedCtx) {

        String cleanModel = chooseModel(req.getModel(), true);
        List<ChatMessage> msgs = buildLcMessages(req, unifiedCtx);

        ChatModel dynamicChatModel = OpenAiChatModel.builder()
                .apiKey(openaiApiKey)
                .modelName(cleanModel)
                .temperature(Optional.ofNullable(req.getTemperature()).orElse(defaultTemp))
                .topP(Optional.ofNullable(req.getTopP()).orElse(defaultTopP))
                .maxTokens(req.getMaxTokens())
                .build();

        try {
            /* ① 초안 */
            String draft = dynamicChatModel.chat(msgs).aiMessage().text();

            /* ①-b LLM-힌트 기반 보강 검색 */
            List<String> hintSnippets = searchService.searchSnippets(
                    req.getMessage(), draft, 5);
            String hintWebCtx = hintSnippets.isEmpty()
                    ? null
                    : String.join("\n", hintSnippets);

            /* ② 컨텍스트 병합 & 검증 */
            String joinedContext = Stream.of(unifiedCtx, hintWebCtx)
                    .filter(StringUtils::hasText)
                    .collect(Collectors.joining("\n"));
            String verified = Boolean.TRUE.equals(req.isUseVerification())
                    ? verifier.verify(req.getMessage(), joinedContext, draft, "gpt-4o")
                    : draft;

            /* ③ 경고‑배너 & (선택) 폴리싱 */
            boolean insufficientContext = !StringUtils.hasText(joinedContext);
            boolean fallbackHappened = Boolean.TRUE.equals(req.isUseVerification())
                    && StringUtils.hasText(joinedContext)
                    && verified.equals(draft);

            String warning  = "\n\n⚠️ 본 답변은 검증된 정보가 부족하거나 부정확할 수 있습니다. 참고용으로 활용해 주세요.";
            String toPolish = (insufficientContext || fallbackHappened)
                    ? verified  +warning
            : verified;

            String finalText = req.isPolish()
                    ? polishAnswerLc(toPolish, dynamicChatModel)
                    : toPolish;

            /* ④ 후처리 & 메모리 */
            String out = ruleEngine.apply(finalText, "ko", RulePhase.POST);
            reinforceMemory(req);
            return ChatResult.of(out, "lc:" + cleanModel, req.isUseRag());

        } catch (Exception ex) {
            log.error("[LangChain4j] 호출 실패", ex);
            return ChatResult.of("LangChain 오류: " + ex.getMessage(), "lc:" + cleanModel, req.isUseRag());
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

private String chooseModel(String requested, boolean stripLcPrefix) {
    if (StringUtils.hasText(requested)) {
        return stripLcPrefix ? requested.replaceFirst("^lc:", "") : requested;
    }
    if (StringUtils.hasText(tunedModelId)) {
        return tunedModelId;
    }
    return modelRepo.findById(1L)
            .map(CurrentModel::getModelId)
            .orElse(defaultModel);
}

    /* ─────────────────────── 새 헬퍼 메서드 ─────────────────────── */

    /** 모든 컨텍스트(web → rag → mem)를 우선순위대로 합산한다. */
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

    /** 간단 휴리스틱: 사람/의료진 질의 여부 */
    private static boolean isPersonQuery(String s) {
        if (s == null) return false;
        return Pattern.compile("(교수|의사|의료진|전문의|님)").matcher(s).find();
    }

    /* ✅ 패치/공지/배너/버전 질의 분류(간단 규칙) */
    private boolean isLivePatchNewsQuery(String s) {
        if (!StringUtils.hasText(s)) return false;
        return Pattern.compile("(?i)(패치\\s*노트|업데이트|공지|배너|스케줄|일정|버전\\s*\\d+(?:\\.\\d+)*)")
                .matcher(s).find();
    }

    /* ✅ 웹 스니펫 묶음에 공식 도메인이 포함되어 있는지 검사 */
    private boolean containsOfficialSource(String webCtx) {
        if (!StringUtils.hasText(webCtx)) return false;
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
/** RAG 컨텍스트를 길이 제한(RAG_CTX_MAX_TOKENS)까지 잘라 준다. */
private static String truncate(String text, int max) {
    return text != null && text.length() > max ? text.substring(0, max) : text;
}
/** ② 히스토리(OAI 전용) – 최근 maxHistory 개만 전송 */
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
    private void reinforceMemoryWithText(String text) {
        if (org.springframework.util.StringUtils.hasText(text)) {
            memorySvc.reinforceMemoryWithText(text);
        }
    }
    /**
     * Caffeine LoadingCache용 체인 팩토리.
     * 세션키를 받아 해당 세션의 ChatMemory를 생성/획득한 뒤 체인을 구성한다.
     */
    private ConversationalRetrievalChain createChain(String sessionKey) {
        // 세션 스코프의 ChatMemory 확보
        ChatMemory mem = chatMemoryProvider.get(sessionKey);
        // 공통 빌더로 체인 생성
        return buildChain(mem);
    }
}
