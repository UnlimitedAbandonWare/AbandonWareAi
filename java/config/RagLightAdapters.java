// src/main/java/config/RagLightAdapters.java
package config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;

import service.rag.planner.SelfAskPlanner;

import java.lang.reflect.Method;
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
            Class<?> c = o.getClass();
            try {
                // com.abandonware.ai.service.rag.model.ContextSlice
                Method gid = safeMethod(c, "getId");
                Method gtitle = safeMethod(c, "getTitle");
                Method gsnippet = safeMethod(c, "getSnippet");
                Method gsource = safeMethod(c, "getSource");
                Method gscore = safeMethod(c, "getScore");
                Method grank = safeMethod(c, "getRank");
                if (gtitle != null || gsnippet != null) {
                    Map<String,Object> m = new LinkedHashMap<>();
                    if (gid != null)    m.put("id",     gid.invoke(o));
                    if (gtitle != null)  m.put("title",  gtitle.invoke(o));
                    if (gsnippet != null)m.put("snippet",gsnippet.invoke(o));
                    if (gsource != null) m.put("source", gsource.invoke(o));
                    if (gscore != null)  m.put("score",  gscore.invoke(o));
                    if (grank != null)   m.put("rank",   grank.invoke(o));
                    out.add(m);
                    continue;
                }
                // dev.langchain4j.rag.content.Content
                Method text = safeMethod(c, "text");
                if (text != null) {
                    Map<String,Object> m = new LinkedHashMap<>();
                    m.put("id", null);
                    m.put("title", null);
                    m.put("snippet", String.valueOf(text.invoke(o)));
                    m.put("source", null);
                    m.put("score", 0.0);
                    m.put("rank", 0);
                    out.add(m);
                    continue;
                }
            } catch (Throwable ignore) {
                // best-effort
            }
        }
        return out;
    }

    private static Method safeMethod(Class<?> c, String name, Class<?>... params) {
        try { return c.getMethod(name, params); } catch (NoSuchMethodException e) { return null; }
    }

    // ========== Web Retriever Adapter ==========
    @Bean(name = "webRetriever")
    public SelfAskPlanner.Retriever webRetriever(ApplicationContext ctx) {
        final Object impl = resolveAny(ctx,
                "com.example.lms.service.rag.AnalyzeWebSearchRetriever",
                "service.rag.AnalyzeWebSearchRetriever",
                "com.abandonware.ai.service.rag.AnalyzeWebSearchRetriever",
                "com.abandonware.ai.agent.integrations.TavilyWebSearchRetriever"
        );
        return (query, topK) -> {
            if (impl == null || query == null) return List.of();
            try {
                // common happy-path: search(String,int)
                Method m = impl.getClass().getMethod("search", String.class, int.class);
                Object res = m.invoke(impl, query, topK);
                return toStdContextList((List<?>) res);
            } catch (NoSuchMethodException e) {
                try {
                    // langchain4j ContentRetriever: retrieve(Query)
                    Class<?> Query = Class.forName("dev.langchain4j.rag.query.Query");
                    Method of = Query.getMethod("from", String.class);
                    Object q = of.invoke(null, query);
                    Method retrieve = impl.getClass().getMethod("retrieve", Query);
                    Object res = retrieve.invoke(impl, q);
                    return toStdContextList((List<?>) res);
                } catch (Throwable t) {
                    return List.of();
                }
            } catch (Throwable t) {
                return List.of();
            }
        };
    }

    // ========== Vector/Hybrid Retriever Adapter (best-effort) ==========
    @Bean(name = "vectorRetriever")
    @ConditionalOnMissingBean(name = "vectorRetriever")
    public SelfAskPlanner.Retriever vectorRetriever(ApplicationContext ctx) {
        final Object impl = resolveAny(ctx,
                // Prefer a ready-made list<Map> retriever if exists
                "com.abandonware.ai.agent.integrations.HybridRetriever",
                "com.example.lms.service.rag.HybridRetriever",
                // Fallbacks (may not expose search(String,int) - will return empty)
                "com.example.lms.vector.FederatedEmbeddingStore",
                "com.abandonware.ai.vector.FederatedEmbeddingStore"
        );
        return (query, topK) -> {
            if (impl == null || query == null) return List.of();
            try {
                Method m = impl.getClass().getMethod("search", String.class, int.class);
                Object res = m.invoke(impl, query, topK);
                return toStdContextList((List<?>) res);
            } catch (Throwable t) {
                // No generic text search available on this impl; graceful degrade
                return List.of();
            }
        };
    }

    // ========== RRF Fuser Adapter ==========
    @Bean(name = "rrfFuser")
    @Primary
    public SelfAskPlanner.Fuser rrfFuser(ApplicationContext ctx) {
        // Try a bean instance first
        final Object bean = resolveAny(ctx,
                "service.rag.fusion.WeightedRRF",
                "com.example.lms.service.rag.fusion.RrfFusion",
                "com.abandonware.ai.service.rag.fusion.RrfFusion",
                "com.abandonware.ai.agent.integrations.RrfFusion"
        );
        if (bean != null) {
            try {
                Method fuse = bean.getClass().getMethod("fuse", List.class, int.class);
                return (lists, topK) -> {
                    try {
                        @SuppressWarnings("unchecked")
                        List<Map<String,Object>> res = (List<Map<String,Object>>) fuse.invoke(bean, lists, topK);
                        return res == null ? List.of() : res;
                    } catch (Throwable t) {
                        return simpleFuse(lists, topK);
                    }
                };
            } catch (NoSuchMethodException ignore) { /* fall through to static helper */ }
        }
        // Static helper: service.rag.fusion.WeightedRRF.fuse(/* ... */)
        try {
            Class<?> wr = Class.forName("service.rag.fusion.WeightedRRF");
            Method fuseStatic = wr.getMethod("fuse", List.class, int.class);
            return (lists, topK) -> {
                try { 
                    @SuppressWarnings("unchecked")
                    List<Map<String,Object>> res = (List<Map<String,Object>>) fuseStatic.invoke(null, lists, topK);
                    return res == null ? List.of() : res;
                } catch (Throwable t) { return simpleFuse(lists, topK); }
            };
        } catch (Throwable ignore) {
            // nothing
        }
        // Last resort
        return RagLightAdapters::simpleFuse;
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

    // -------- util --------
    private static Object resolveAny(ApplicationContext ctx, String... classNames) {
        for (String cn : classNames) {
            try {
                Class<?> c = Class.forName(cn);
                Object bean = ctx.getBean(c);
                if (bean != null) return bean;
            } catch (Throwable ignore) {
            }
        }
        return null;
    }
}