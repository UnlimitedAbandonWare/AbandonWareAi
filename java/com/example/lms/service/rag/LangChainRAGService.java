package com.example.lms.service.rag;

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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
    /** Unified metadata key – 모든 서비스가 동일 키 사용 */
    public static final String META_SID = "sid";   // ← ChatService & NaverSearchService 와 통일
    /** sid 필터: null 또는 "*"는 공용으로 간주하여 통과 */
    private boolean passesSid(Map<String, Object> md, String currentSid) {
        String sid = Optional.ofNullable(md.get(META_SID)).map(String::valueOf).orElse(null);
        if (sid == null || "*".equals(sid)) return true;                // 공용 허용
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

    private final ChatModel                   chatModel;
    private final EmbeddingModel              embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final MemoryReinforcementService  memorySvc;

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
    // (-) 구(舊) 직접 구현 삭제
    //     → 아래 getAnswerInternal(...)  퍼사드 3종만 유지

    /** 벡터스토어에서 RAG 컨텍스트 검색 */
    private List<String> retrieveRagContext(String query, String sessionId) {
        Embedding queryEmbedding = embeddingModel.embed(query).content();
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

            EmbeddingSearchRequest req = EmbeddingSearchRequest.builder()
                    .queryEmbedding(embeddingModel.embed(q.text()).content())
                    .maxResults(topK)
                    .minScore(minScore)
                    .build();

            EmbeddingSearchResult<TextSegment> res = embeddingStore.search(req);
            return filterMatchesToContent(res, sid);
        };
    }

    /** helper: session‑filtered matches to String with fallback */
    private List<String> filterMatchesToString(EmbeddingSearchResult<TextSegment> res, String sessionId) {
        int total = res.matches() != null ? res.matches().size() : 0;
        List<String> matches = res.matches().stream()
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
        log.debug("▶ RAG 시작 session={}, query={}", sessionId, query);

        List<String> ragSnippets = retrieveRagContext(query, sessionId);
        String history = conversationMemory.asMap().getOrDefault(sessionId, "No history yet.");

        String prompt = """
            ### WEB SEARCH
            %s

            ### VECTOR RAG
            %s

            ### HISTORY
            %s

            ### QUESTION
            %s
            """.formatted(
                org.springframework.util.StringUtils.hasText(externalContext) ? externalContext : "N/A",
                String.join("\n\n---\n\n", ragSnippets),
                history,
                query
        );

        ChatModel use = (override != null ? override : this.chatModel);   // ★ 핵심
        String answer = use.chat(prompt);

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
        int total = res.matches() != null ? res.matches().size() : 0;
        List<Content> list = res.matches().stream()
                .filter(m -> passesSid(toMap(m.embedded().metadata()), sessionId))
                .map(m -> Content.from(m.embedded().text()))
                .collect(Collectors.toList());
        if (log.isDebugEnabled()) {
            log.debug("[RAG] vector contents: total={}, afterSid={}", total, list.size());
        }
        return list;
    }
}
