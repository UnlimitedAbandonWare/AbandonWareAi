// src/main/java/com/example/lms/trace/SearchTraceAspect.java
package com.example.lms.trace;

import com.example.lms.gptsearch.decision.SearchDecision;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.stream.Collectors;




/**
 * Aspect that instruments search decisions and retrieval operations.
 * Records lightweight summary events after the decision engine runs
 * and after content retrieval executes.  The events include the search
 * depth, providers and topK along with the number of documents
 * retrieved, latency and a preview of the first few document identifiers.
 */
@Aspect
@Component
public class SearchTraceAspect {

    /**
     * Intercepts the search decision method and emits a {@code search_decision}
     * event.  Extracts the search depth (LIGHT/DEEP), provider names and
     * topK from the returned {@link SearchDecision} object.
     */
    @Around("execution(* com.example.lms.gptsearch.decision.SearchDecisionService.decide(..))")
    public Object aroundDecision(ProceedingJoinPoint pjp) throws Throwable {
        Object result = pjp.proceed();
        if (result instanceof SearchDecision sd) {
            String mode = sd.depth() != null ? sd.depth().name() : "LIGHT";
            List<?> providers = sd.providers();
            List<String> providerNames = providers == null ? Collections.emptyList() : providers.stream()
                    .map(Object::toString)
                    .collect(Collectors.toList());
            int topK = sd.topK();
            TraceLogger.emit("search_decision", "search", Map.of(
                    "mode", mode,
                    "providers", providerNames,
                    "topK", topK
            ));
        }
        return result;
    }

    /**
     * Intercepts calls to any {@link ContentRetriever#retrieve(Query)} implementation.
     * Measures latency, counts the returned documents and extracts up to three
     * stable identifiers from their metadata.  Emits a {@code retrieval}
     * event and forwards the count to the EBNA detector.
     */
    @Around("execution(* dev.langchain4j.rag.content.retriever.ContentRetriever+.retrieve(..)) && args(query)")
    @SuppressWarnings("unchecked")
    public Object aroundRetrieve(ProceedingJoinPoint pjp, Query query) throws Throwable {
        long start = System.currentTimeMillis();
        Object ret = pjp.proceed();
        long latency = System.currentTimeMillis() - start;

        List<Content> results;
        if (ret instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Content) {
            results = (List<Content>) ret;
        } else {
            results = Collections.emptyList();
        }
        int count = results.size();
        List<String> ids = results.stream()
                .limit(3)
                .map(SearchTraceAspect::extractStableId)
                .collect(Collectors.toList());
        TraceLogger.emit("retrieval", "search", Map.of(
                "count", count,
                "lat_ms", latency,
                "doc_ids", ids
        ));
        EbnaDetector.incRetrieved(count);
        return ret;
    }

    /**
     * LangChain4j 1.0.x: Content.metadata() 키는 문자열이 아닐 수 있다.
     * (예: ContentMetadata enum/타입). 안전하게 문자열로 정규화해서 id/url 계열을 우선 추출.
     */
    private static String extractStableId(Content c) {
        Map<?, ?> md = c.metadata();
        if (md != null && !md.isEmpty()) {
            for (Map.Entry<?, ?> e : md.entrySet()) {
                String k = String.valueOf(e.getKey()).toLowerCase(Locale.ROOT);
                if (k.equals("id") || k.equals("content_id") || k.equals("doc_id")
                        || k.equals("url") || k.equals("source_url") || k.equals("uri")) {
                    Object v = e.getValue();
                    if (v != null) return String.valueOf(v);
                }
            }
        }
        // 메타데이터에 식별자가 없으면 안정적 해시로 폴백
        return Integer.toString(Objects.hashCode(c));
    }
}