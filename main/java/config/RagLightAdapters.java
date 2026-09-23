// src/main/java/config/RagLightAdapters.java
package config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.lms.service.rag.AnalyzeWebSearchRetriever;
import com.example.lms.service.rag.HybridRetriever;
import com.example.lms.service.rag.QueryUtils;
import com.example.lms.service.rag.fusion.RrfFusion;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import service.rag.planner.SelfAskPlanner;

import java.util.*;
import java.util.stream.Collectors;

/**
 * RagLightAdapters
 *
 * 목적: 서로 다른 패키지/구현이 공존하는 레포에서 컴파일 타임 의존성을 추가하지 않고
 *      SelfAskPlanner가 기대하는 (Retriever/Fuser) 인터페이스로 어댑팅한다.
 * 설계: 런타임 리플렉션 + Optional 주입 + Fail-Soft(FallbackRetrieveTool 개념)로 ‘조화로운’ 통합.
 *
 * 주입되는 빈 이름 (Qualifier):
 *  - "webRetriever"    : 웹 검색기 어댑터
 *  - "vectorRetriever" : 벡터/하이브리드 검색기 어댑터(없으면 Optional.empty)
 *  - "rrfFuser"        : RRF 퓨저(없으면 단순 점수 정렬 폴백)
 */
@Configuration
public class RagLightAdapters {
    private static final Logger log = LoggerFactory.getLogger(RagLightAdapters.class);

    private static List<Map<String,Object>> toStdContextList(List<?> lst) {
        if (lst == null) return List.of();
        List<Map<String,Object>> out = new ArrayList<>();
        for (Object o : lst) {
            if (o == null) continue;
            if (o instanceof Map) {
                // already standard
                @SuppressWarnings("unchecked")
                Map<String,Object> m = (Map<String,Object>) o;
                out.add(m);
                continue;
            }
            if (o instanceof Content content) {
                TextSegment segment = content.textSegment();
                Map<String,Object> m = new LinkedHashMap<>();
                m.put("id", null);
                m.put("title", null);
                m.put("snippet", segment == null ? "" : segment.text());
                m.put("source", null);
                m.put("score", 0.0);
                m.put("rank", 0);
                out.add(m);
            }
        }
        return out;
    }

    // ========== Web Retriever Adapter ==========
    @Bean(name = "webRetriever")
    public SelfAskPlanner.Retriever webRetriever(ObjectProvider<AnalyzeWebSearchRetriever> retrieverProvider) {
        return (query, topK) -> {
            AnalyzeWebSearchRetriever impl = retrieverProvider == null ? null : retrieverProvider.getIfAvailable();
            if (impl == null || query == null) return List.of();
            try {
                Map<String, Object> meta = Map.of("webTopK", Math.max(1, topK));
                return toStdContextList(impl.retrieve(QueryUtils.buildQuery(query, meta)));
            } catch (Throwable error) {
                logFailSoft("web.retrieve", error);
                return List.of();
            }
        };
    }

    // ========== Vector/Hybrid Retriever Adapter (best-effort) ==========
    @Bean(name = "vectorRetriever")
    @ConditionalOnMissingBean(name = "vectorRetriever")
    public SelfAskPlanner.Retriever vectorRetriever(ObjectProvider<HybridRetriever> retrieverProvider) {
        return (query, topK) -> {
            HybridRetriever impl = retrieverProvider == null ? null : retrieverProvider.getIfAvailable();
            if (impl == null || query == null) return List.of();
            try {
                Map<String, Object> meta = Map.of("vectorTopK", Math.max(1, topK));
                return toStdContextList(impl.retrieve(QueryUtils.buildQuery(query, meta)));
            } catch (Throwable error) {
                logFailSoft("vector.retrieve", error);
                return List.of();
            }
        };
    }

    // ========== RRF Fuser Adapter ==========
    @Bean(name = "rrfFuser")
    @Primary
    public SelfAskPlanner.Fuser rrfFuser() {
        return (lists, topK) -> {
            try {
                return RrfFusion.fuse(lists, topK);
            } catch (Throwable error) {
                logFailSoft("rrf.fuse", error);
                return simpleFuse(lists, topK);
            }
        };
    }

    private static List<Map<String,Object>> simpleFuse(List<List<Map<String,Object>>> lists, int topK) {
        if (lists == null) return List.of();
        return lists.stream().filter(Objects::nonNull).flatMap(List::stream)
            .sorted((a,b) -> Double.compare(score(b), score(a)))
            .limit(Math.max(1, topK))
            .collect(Collectors.toList());
    }

    private static double score(Map<String,Object> m) {
        Object s = m == null ? null : m.get("score");
        return (s instanceof Number) ? ((Number) s).doubleValue() : 0.0;
    }

    private static void logFailSoft(String stage, Throwable error) {
        if (log.isDebugEnabled()) {
            log.debug("[RagLightAdapters] fail-soft stage={} errorType={}", stage, errorType(error));
        }
    }

    private static String errorType(Throwable error) {
        return error == null ? "unknown" : error.getClass().getSimpleName();
    }
}
