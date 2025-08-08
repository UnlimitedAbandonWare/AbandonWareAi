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
    /** HybridRetriever → 허용 도메인 전달용 */
    public static final String META_ALLOWED_DOMAINS = "allowedDomains";
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

    public String getAnswer(String query, String sessionId) {
        return getAnswer(query, sessionId, null);
    }

    public String getAnswer(String query,
                            String sessionId,
                            String externalContext) {

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
                StringUtils.hasText(externalContext) ? externalContext : "N/A",
                String.join("\n\n---\n\n", ragSnippets),
                history,
                query
        );

        String answer = chatModel.chat(prompt);

        AtomicInteger rank = new AtomicInteger(1);
        for (String snippet : ragSnippets) {
            memorySvc.reinforceWithSnippet(sessionId, query, snippet, "RAG", 1.0 / rank.getAndIncrement());
        }

        conversationMemory.asMap().merge(
                sessionId,
                "User: " + query + "\nAssistant: "+  answer,
                (oldV, newV) -> (oldV == null ? "" : oldV + "\n") + newV
        );

        log.debug("◀ RAG 완료 session={}, answer len={}", sessionId, answer.length());
        return answer;
    }

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
        List<String> matches = res.matches().stream()
                .filter(m -> {
                    Object sidObj = toMap(m.embedded().metadata()).get(META_SID);
                    String sidMeta = (sidObj == null ? null : String.valueOf(sidObj));
                    return sessionId == null
                            || sidMeta == null
                            || "*".equals(sidMeta)
                            || sessionId.equals(sidMeta);
                })
                .map(match -> match.embedded().text())
                .collect(Collectors.toList());
        return matches;

    }

    /** helper: session‑filtered matches to Content with fallback */
    private List<Content> filterMatchesToContent(EmbeddingSearchResult<TextSegment> res, String sessionId) {
        List<Content> list = res.matches().stream()
                .filter(m -> {
                    Object sidObj = toMap(m.embedded().metadata()).get(META_SID);
                    String sidMeta = (sidObj == null ? null : String.valueOf(sidObj));
                    return sessionId == null
                            || sidMeta == null
                            || "*".equals(sidMeta)
                            || sessionId.equals(sidMeta);
                })
                .map(m -> Content.from(m.embedded().text()))
                .collect(Collectors.toList());
        return list; // 세션 결과가 없으면 비어있는 리스트를 그대로 반환

    }
}
