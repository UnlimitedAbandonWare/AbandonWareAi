package com.example.lms.service.rag.handler;

import com.example.lms.service.rag.AnalyzeWebSearchRetriever;
import com.example.lms.service.rag.LangChainRAGService;
import com.example.lms.service.rag.SelfAskWebSearchRetriever;
import com.example.lms.service.rag.WebSearchRetriever;
import com.example.lms.service.rag.QueryComplexityGate;
import com.example.lms.integration.handlers.AdaptiveWebSearchHandler;
import com.example.lms.location.LocationService;
import com.example.lms.location.intent.LocationIntent;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import java.util.List;



/**
 * 기본 검색 체인 구현. 순서는 명시적으로 고정되어 있으며,
 * 필요에 따라 단계가 건너뛰어질 수 있다.
 *
 * 실행 순서: Memory → Self-Ask → Analyze → Web → Vector → Repair
 */
@RequiredArgsConstructor
public class DefaultRetrievalHandlerChain implements RetrievalHandler {

    private final com.example.lms.service.rag.handler.MemoryHandler memoryHandler;
    private final SelfAskWebSearchRetriever selfAsk;
    private final AnalyzeWebSearchRetriever analyze;
    // 新規: 어댑티브 웹 검색 핸들러 (웹 이전 단계에서 검색 필요 여부를 판단)
    private final AdaptiveWebSearchHandler adaptiveWeb;
    private final WebSearchRetriever web;
    private final LangChainRAGService rag;
    private final com.example.lms.service.rag.handler.EvidenceRepairHandler repair;
    private final QueryComplexityGate gate;

    /**
     * Service used to detect location-related intents.  When the user's query
     * is determined to be about their current location (e.g. "나 지금 어디야?"),
     * the retrieval chain will short-circuit and avoid performing any
     * expensive web or vector lookups.  A specialized chat handler can then
     * take over to generate the appropriate response.
     */
    private final LocationService locationService;

    // 미설정 시 안전하게 비우고 핸들러 내부에서 가드
    @Value("${pinecone.index.name:}")
    private String pineconeIndexName;

    @Value("${rag.search.top-k:5}")
    private int topK;

    @Override
    public void handle(Query query, List<Content> accumulator) {
        if (accumulator == null) {
            return;
        }
        // 1. 세션 메모리 로드
        Long sessionId = null;
        try {
            if (query != null && query.metadata() != null) {
                java.util.Map<String,Object> md = toMap(query.metadata());
                Object sidObj = md.get(LangChainRAGService.META_SID);
                if (sidObj != null) {
                    sessionId = Long.parseLong(String.valueOf(sidObj));
                }
            }
        } catch (Exception ignore) {
            // ignore
        }
        if (sessionId != null) {
            try {
                String hist = memoryHandler.loadForSession(sessionId);
                if (hist != null && !hist.isBlank()) {
                    accumulator.add(Content.from(hist));
                    // Early-cut removed: do not terminate when reaching topK here
                }
            } catch (Exception ignore) {
                // ignore
            }
        }
        String q = (query != null && query.text() != null) ? query.text().trim() : "";
        // 2. Self-Ask: 복잡한 경우만
        boolean needSelf = false;
        try {
            needSelf = gate != null && gate.needsSelfAsk(q);
        } catch (Exception ignore) {}
        if (needSelf) {
            add(accumulator, selfAsk.retrieve(query));
            // Early-cut removed: continue gathering evidence instead of returning
        }
        // 3. Analyze: 모호 또는 복잡한 경우만
        boolean needAnalyze = false;
        try {
            needAnalyze = gate != null && gate.needsAnalyze(q);
        } catch (Exception ignore) {}
        if (needAnalyze) {
            add(accumulator, analyze.retrieve(query));
            // Early-cut removed: continue gathering evidence instead of returning
        }
        // 3-b. Location: detect and short-circuit when the query is location-related
        try {
            // When the location service is available and consent has been granted
            // it will classify the user query into a location intent.  If the
            // intent is not NONE, then retrieval is skipped so that a dedicated
            // location handler can generate a reply without any web or vector
            // evidence.  Do not add any content to the accumulator in this case.
            if (locationService != null) {
                LocationIntent li = locationService.detectIntent(q);
                if (li != null && li != LocationIntent.NONE) {
                    // Skip retrieval entirely.  Downstream chat logic should
                    // handle location queries by consulting the location service.
                    return;
                }
            }
        } catch (Exception ignore) {
            // fail-soft: ignore any errors during location detection
        }
        // 4. Adaptive Web Search (조건 실행)
        // Determine whether to perform web retrieval based on metadata.
        // The 'useWebSearch' flag must be true and 'searchMode' must not be OFF.
        boolean allowWeb = mdBool(query.metadata(), "useWebSearch", false);
        String modeStr   = mdString(query.metadata(), "searchMode", "AUTO");
        if ("OFF".equalsIgnoreCase(String.valueOf(modeStr))) {
            allowWeb = false;
        }
        try {
            if (allowWeb && adaptiveWeb != null) {
                adaptiveWeb.handle(query, accumulator);
                // Early-cut removed: do not return here; allow subsequent stages
            }
        } catch (Exception ignore) {
            // Swallow exceptions to maintain chain robustness
        }
        // 5. Web search - explicitly invoke the legacy WebSearchRetriever after the adaptive stage
        // to make the chain order clear (Self-Ask → Analyze → Web → Vector → Repair).  The
        // evidence returned by the adaptiveWeb stage may overlap with this call, but
        // invoking web.retrieve() here satisfies the MOE requirement to explicitly
        // sequence retrieval stages.  Failures are swallowed to maintain chain
        // robustness.
        try {
            add(accumulator, web.retrieve(query));
        } catch (Exception ignore) {
            // ignore web retrieval failures
        }
        // Early-cut removed: continue to vector and repair stages regardless of accumulator size
        // 6. Vector (조건 실행)
        // Only perform vector (RAG) retrieval when the 'useRag' metadata flag is true.
        boolean allowRag = mdBool(query.metadata(), "useRag", false);
        if (allowRag) {
            ContentRetriever vector = rag.asContentRetriever(pineconeIndexName);
            add(accumulator, vector.retrieve(query));
        }
        // Early-cut removed: continue to repair stage
        // 7. Repair
        try {
            if (repair != null) {
                add(accumulator, repair.retrieve(query));
            }
        } catch (Exception ignore) {
            // ignore
        }
    }

    private static void add(List<Content> target, List<Content> source) {
        if (source != null && !source.isEmpty()) {
            target.addAll(source);
        }
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String,Object> toMap(Object meta) {
        if (meta == null) return java.util.Map.of();
        try {
            java.lang.reflect.Method m = meta.getClass().getMethod("asMap");
            return (java.util.Map<String,Object>) m.invoke(meta);
        } catch (NoSuchMethodException e) {
            try {
                java.lang.reflect.Method m2 = meta.getClass().getMethod("map");
                return (java.util.Map<String,Object>) m2.invoke(meta);
            } catch (Exception ex) {
                return java.util.Map.of();
            }
        } catch (Exception ex) {
            return java.util.Map.of();
        }
    }

    // [HARDENING] ensure SID metadata is present on every query
    private dev.langchain4j.rag.query.Query ensureSidMetadata(dev.langchain4j.rag.query.Query original, String sessionKey) {
        var md = original.metadata() != null
            ? original.metadata()
            : dev.langchain4j.data.document.Metadata.from(
                java.util.Map.of(com.example.lms.service.rag.LangChainRAGService.META_SID, sessionKey));
        // Directly construct a new Query with the updated metadata.  LangChain4j 1.0.x
        // provides a public constructor for Query that accepts text and metadata,
        // eliminating the need for the deprecated builder API and reflection.
        return new dev.langchain4j.rag.query.Query(original.text(), md);
    }

    /**
     * Safely read a boolean value from metadata.  When the key is absent
     * or the value cannot be parsed, the provided default is returned.
     *
     * @param md     metadata object, may be null
     * @param k      key name
     * @param defVal default value when parsing fails
     * @return the parsed boolean or defVal when missing/invalid
     */
    private static boolean mdBool(Object meta, String k, boolean defVal) {
        try {
            var map = toMap(meta);
            Object v = map.get(k);
            if (v instanceof Boolean b) return b;
            if (v != null)          return Boolean.parseBoolean(String.valueOf(v));
            return defVal;
        } catch (Exception e) { return defVal; }
    }

    /**
     * Safely read a string value from metadata.  When the key is absent
     * or the value is null, the provided default is returned.
     *
     * @param md     metadata object, may be null
     * @param k      key name
     * @param defVal default value when missing
     * @return the string representation of the value or defVal when missing/invalid
     */
    private static String mdString(Object meta, String k, String defVal) {
        try {
            var map = toMap(meta);
            Object v = map.get(k);
            return (v != null) ? String.valueOf(v) : defVal;
        } catch (Exception e) { return defVal; }
    }

}