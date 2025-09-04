package com.example.lms.service.rag;
import com.example.risk.RiskScorer;
import com.example.lms.service.rag.whiten.QueryWhiteningService;
import com.example.lms.service.routing.RouteSignal;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;

import java.lang.reflect.Method;
import com.example.lms.service.MemoryReinforcementService;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;

import lombok.extern.slf4j.Slf4j;
import com.example.lms.service.rag.guard.EvidenceGate;
import lombok.RequiredArgsConstructor;
import com.example.lms.service.rag.pre.QueryContextPreprocessor;
import com.example.lms.prompt.PromptBuilder;
import com.example.lms.prompt.PromptContext;
import com.example.lms.guard.AnswerSanitizer;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

import java.util.Optional;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;

import java.util.stream.Collectors;
import java.util.stream.Stream;

import java.util.concurrent.atomic.AtomicInteger;
//검색
@Slf4j
@Service
@RequiredArgsConstructor
public class LangChainRAGService {
    @Value("${abandonware.rag.risk.min-rdi:0}")
    private int minRdi;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private RiskScorer riskScorer;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private QueryWhiteningService whitening;

    /** Unified metadata key – 모든 서비스가 동일 키 사용 */
    public static final String META_SID = "sid";   // ← ChatService & NaverSearchService 와 통일
    /** sid 필터: null 또는 "__PRIVATE__"는 공용으로 간주하여 통과; wildcard removed */ // [HARDENING]
    private boolean passesSid(Map<String, Object> md, String currentSid) {
        String sid = Optional.ofNullable(md.get(META_SID)).map(String::valueOf).orElse(null);
        // [HARDENING] treat null or __PRIVATE__ as public; do not allow '*' wildcard
        if (sid == null || "__PRIVATE__".equals(sid)) return true;
        return currentSid != null && currentSid.equals(sid);            // 동일 세션만 허용
    }


    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(Object meta) {
        try {
            Method m = meta.getClass().getMethod("asMap");
            return (Map<String, Object>) m.invoke(meta);
        } catch (NoSuchMethodException e) {
            try {
                Method m = meta.getClass().getMethod("map");
                return (Map<String, Object>) m.invoke(meta);
            } catch (Exception ex) {
                return Map.of();
            }
        } catch (Exception ex) {
            return Map.of();
        }
    }


    // 단일 진실원(Single Source of Truth)으로 통일: service.routing.ModelRouter 사용
    private final com.example.lms.service.routing.ModelRouter modelRouter; // 라우팅은 이걸로만
    private final EmbeddingModel              embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final MemoryReinforcementService  memorySvc;
    @Qualifier("compositeQueryContextPreprocessor")
    private final QueryContextPreprocessor    preprocessor;   //  의도/도메인/원소 제약 주입원
    private final PromptBuilder               promptBuilder;
    private final AnswerSanitizer             answerSanitizer;
    private final EvidenceGate                evidenceGate;   // ✅ 주입

    /**
     * 대화 기록을 제한된 크기로 유지하기 위해 Caffeine LRU 캐시를 사용한다.
     * 세션별 히스토리는 100개 항목까지만 보존하며, 마지막 사용 시점을 기준으로 만료된다.
     */
    private final Cache<String, String> conversationMemory =
            Caffeine.newBuilder()
                    .maximumSize(100)
                    .expireAfterAccess(Duration.ofHours(6))
                    .build();

    @Value("${rag.top-k:3}")
    private int topK;
    // Use a higher default threshold to suppress low‑quality matches
    @Value("${rag.min-score:0.8}")
    private double minScore;
    // (-) Old direct implementations removed.
    //     → Only the three facade methods that delegate to getAnswerInternal are retained.

    /** 벡터스토어에서 RAG 컨텍스트 검색 */
    private List<String> retrieveRagContext(String query, String sessionId) {
        Embedding queryEmbedding;
        try {
            queryEmbedding = embeddingModel.embed(query).content();
            // apply whitening fail-soft
            try {
                queryEmbedding = whitening.maybeWhiten(queryEmbedding);
            } catch (Exception ignore) {}
        } catch (Exception e) {
            log.warn("[RAG] embedding failed, degrade to lexical/web-only: {}", e.toString());
            // When embedding fails, return no vector matches to allow fallback behaviour.
            return java.util.Collections.emptyList();
        }
        EmbeddingSearchRequest req = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(topK)
                .minScore(minScore)
                .build();

        EmbeddingSearchResult<TextSegment> res = embeddingStore.search(req);
        return filterMatchesToString(res, sessionId);
    }



    /** 동일 로직을 ContentRetriever 형태로 노출 */
    public ContentRetriever asContentRetriever(String indexName) {
        return (Query q) -> {
            // ☑ META_SID 사용으로 세션 오염 차단
            String sid = Optional.ofNullable(q.metadata())
                    .map(meta -> toMap(meta).get(META_SID))
                    .map(Object::toString)
                    .orElse(null);

            Embedding queryEmbedding;
            try {
                queryEmbedding = embeddingModel.embed(q.text()).content();
                // apply whitening when available
                try {
                    if (whitening != null) {
                        queryEmbedding = whitening.maybeWhiten(queryEmbedding);
                    }
                } catch (Exception ignore) {
                    // fail-soft: ignore whitening exceptions
                }
            } catch (Exception e) {
                log.warn("[RAG] embedding failed, degrade to lexical/web-only: {}", e.toString());
                // When embedding fails return an empty list so web search may still run.
                return java.util.Collections.emptyList();
            }

            EmbeddingSearchRequest req = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(topK)
                    .minScore(minScore)
                    .build();

            EmbeddingSearchResult<TextSegment> res = embeddingStore.search(req);
            return filterMatchesToContent(res, sid);
        };
    }

    /** helper: session‑filtered matches to String with fallback */
    private List<String> filterMatchesToString(EmbeddingSearchResult<TextSegment> res, String sessionId) {
        var safeMatches = java.util.Optional.ofNullable(res.matches())
                .orElse(java.util.Collections.emptyList());
        int total = safeMatches.size();
        List<String> matches = safeMatches.stream()
                .filter(m -> passesSid(toMap(m.embedded().metadata()), sessionId))
                .map(match -> match.embedded().text())
                .collect(Collectors.toList());
        if (log.isDebugEnabled()) {
            log.debug("[RAG] vector matches: total={}, afterSid={}", total, matches.size());
        }
        return matches;
    }
// 기존 필드/생성자 유지 (utilityChatModel 주입)

    // + 퍼사드: 외부에서 모델 오버라이드
    public String getAnswerWithModel(String query, String sessionId, ChatModel override) {
        return getAnswerInternal(query, sessionId, null, override);
    }

    // 기존 오버로드 유지 (동작은 내부 공통으로 위임)
    public String getAnswer(String query, String sessionId) {
        return getAnswerInternal(query, sessionId, null, null);
    }

    public String getAnswer(String query, String sessionId, String externalContext) {
        return getAnswerInternal(query, sessionId, externalContext, null);
    }

    // + 공통 내부 구현: override가 있으면 그 모델 사용
    private String getAnswerInternal(String query, String sessionId, String externalContext,
                                     ChatModel override) {
        // ── 의도 추정
        //  의도·제약 계산 (없으면 빈 셋/GENERAL)
        // ── 모델 라우팅(override 우선)


        final String intent = (preprocessor != null) ? preprocessor.inferIntent(query) : "GENERAL";

// 간단 위험도/상세도/출력예산 기본값
        final String risk = null;          // 필요하면 간단 휴리스틱으로 "HIGH"/"LOW" 넣어도 됨
        final String verbosity = "standard";
        final int targetOutTokens = 1024;

        ChatModel use = (override != null)
                ? override
                : modelRouter.route(intent, risk, verbosity, targetOutTokens);
        log.debug("▶ RAG 시작 session={}, query={}", sessionId, query);

        // 1) 자료 수집
        List<String> ragSnippets = retrieveRagContext(query, sessionId);
        String history = conversationMemory.asMap().getOrDefault(sessionId, "No history yet.");

        // ── Evidence Gate: PAIRING/RECOMMENDATION에서 증거 부족 시 LLM 호출 차단
        int minEv = (org.springframework.util.StringUtils.hasText(externalContext)) ? 2 : 1;
        boolean ok = evidenceGate.hasSufficientCoverage(query, ragSnippets, externalContext, minEv);
        if (("PAIRING".equalsIgnoreCase(intent) || "RECOMMENDATION".equalsIgnoreCase(intent)) && !ok) {
            return "정보 없음";
        }

        // 2) 컨텍스트 조립
        var ragContent = ragSnippets.stream().map(dev.langchain4j.rag.content.Content::from).toList();
        if (minRdi > 0 && riskScorer != null) {
            ragContent = ragContent.stream().filter(c -> {
                int rdi = riskScorer.computeRdi(() -> java.util.List.of(c));
                return rdi >= minRdi;
            }).toList();
        }

        var webContent = org.springframework.util.StringUtils.hasText(externalContext)
                ? java.util.Arrays.stream(externalContext.split("\\r?\\n"))
                .map(String::trim).filter(s -> !s.isEmpty())
                .map(dev.langchain4j.rag.content.Content::from).toList()
                : java.util.List.<dev.langchain4j.rag.content.Content>of();
        // --- Confidence estimation for dynamic routing ---
        try {
            int evidenceCount = ragContent.size() + webContent.size();
            double confidence = Math.max(0.0, Math.min(1.0, evidenceCount / 12.0));
            if (confidence < 0.35 && override == null) {
                // Construct a routing signal favouring high quality when evidence is low
                RouteSignal sig = new RouteSignal(
                        0.5,
                        0.0,
                        1.0 - confidence,
                        0.0,
                        RouteSignal.Intent.GENERAL,
                        RouteSignal.Verbosity.DETAILED,
                        1536,
                        RouteSignal.Preference.QUALITY,
                        "Low evidence; elevate"
                );
                try {
                    use = modelRouter.escalate(sig);
                    log.debug("[Routing] Escalated model due to low evidence (conf={}) -> {}", String.format("%.2f", confidence), modelRouter.resolveModelName(use));
                } catch (Exception e) {
                    log.warn("[Routing] escalation failed: {}", e.toString());
                }
            }
        } catch (Exception ignore) {
            // fail-soft: ignore routing estimation errors
        }

        // 교체
        final String domain = (preprocessor != null) ? preprocessor.detectDomain(query) : "";
        final java.util.Map<String, java.util.Set<String>> rules =
                (preprocessor != null) ? preprocessor.getInteractionRules(query) : java.util.Map.of();

        PromptContext ctx = PromptContext.builder()
                .web(webContent)
                .rag(ragContent)
                .memory("")
                .history(history)
                .domain(domain)
                .intent(intent)
                .interactionRules(rules)
                .build();
        String instructions = promptBuilder.buildInstructions(ctx);
        // Use PromptBuilder to build the full prompt body without manual string concatenation.
        String body = promptBuilder.build(ctx);

        // 3) 모델 라우팅(override > 의도별 선택)
        String answer = use.chat(java.util.List.of(
                SystemMessage.from(instructions),
                UserMessage.from(body)
        )).aiMessage().text();


        // LangChain4j 1.0.1: chat(List<ChatMessage>) → AiMessage.text()

        // 4) 산출물 가드(금지 요소 컷/정정)
        answer = answerSanitizer.sanitize(answer, ctx);

        java.util.concurrent.atomic.AtomicInteger rank = new java.util.concurrent.atomic.AtomicInteger(1);
        for (String snippet : ragSnippets) {
            memorySvc.reinforceWithSnippet(sessionId, query, snippet, "RAG", 1.0 / rank.getAndIncrement());
        }

        conversationMemory.asMap().merge(
                sessionId,
                "User: " + query + "\nAssistant: " + answer,
                (oldV, newV) -> (oldV == null ? "" : oldV + "\n") + newV
        );

        log.debug("◀ RAG 완료 session={}, answer len={}", sessionId, answer.length());
        return answer;
    }

    private List<Content> filterMatchesToContent(EmbeddingSearchResult<TextSegment> res, String sessionId) {
        var safeMatches = java.util.Optional.ofNullable(res.matches())
                .orElse(java.util.Collections.emptyList());
        int total = safeMatches.size();
        List<Content> list = safeMatches.stream()
                .filter(m -> passesSid(toMap(m.embedded().metadata()), sessionId))
                .map(m -> Content.from(m.embedded().text()))
                .collect(Collectors.toList());
        if (log.isDebugEnabled()) {
            log.debug("[RAG] vector contents: total={}, afterSid={}", total, list.size());
        }
        return list;
    }
}
