package com.example.lms.service.rag;

import trace.TraceContext;
import com.example.lms.service.NaverSearchService;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.util.StringUtils;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;




/**
 * Lucene 형태소 분석기를 사용하여 쿼리의 핵심 키워드를 추출하고,
 * 이를 바탕으로 확장된 검색어를 생성하여 웹을 검색하는 Retriever입니다.
 * <p>
 * 1. <b>형태소 분석:</b> 쿼리에서 명사 등 핵심 토큰을 정확히 분리합니다.
 * 2. <b>쿼리 확장:</b> 원본 쿼리 + 핵심 토큰 조합 쿼리("/* ... *&#47;정리", "/* ... *&#47;요약")를 생성합니다.
 * 3. <b>통합 검색:</b> 생성된 모든 쿼리로 검색을 수행하고 결과를 융합합니다.
 * </p>
 */
@Component
@RequiredArgsConstructor
public class AnalyzeWebSearchRetriever implements ContentRetriever {

    private int timeoutMs = 1800;
    private int webTopK = 10;
    public void setTimeoutMs(int ms) { this.timeoutMs = ms; }
    public void setWebTopK(int k) { this.webTopK = k; }
    
    private static final Logger log = LoggerFactory.getLogger(AnalyzeWebSearchRetriever.class);

    private final Analyzer analyzer; // 한국어 형태소 분석기 (e.g., Nori)
    private final NaverSearchService searchService;
    private final int topK;
    @Qualifier("guardrailQueryPreprocessor")
    private final com.example.lms.service.rag.pre.QueryContextPreprocessor preprocessor;

    // SmartQueryPlanner for centralised query generation.  Handlers must
    // not perform local expansion; the planner encapsulates noise clipping,
    // domain inference, keyword extraction and sanitisation.
    

    
/* Removed manual constructor; Lombok @RequiredArgsConstructor will generate it. */

private final com.example.lms.search.SmartQueryPlanner smartQueryPlanner;

    @Override
    public List<Content> retrieve(Query query) {
        String originalQuery = (query != null && query.text() != null) ? query.text().trim() : "";
        originalQuery = preprocessor.enrich(originalQuery);
        if (!StringUtils.hasText(originalQuery)) {
            return Collections.emptyList();
        }

        // Use SmartQueryPlanner to generate all search queries.  The planner
        // performs domain inference, noise clipping and keyword extraction.
        // Null-safe: if the planner is unavailable, fall back to using the original query as-is.
        java.util.List<String> queries;
        if (smartQueryPlanner == null) {
            log.warn("[AnalyzeWebSearchRetriever] smartQueryPlanner is null; using passthrough plan.");
            queries = java.util.List.of(originalQuery);
        } else {
            queries = smartQueryPlanner.plan(
                    originalQuery,
                    /* assistantDraft */ null,
                    /* max */ 8
            );
        }

        // Execute searches in parallel for each query and collect the snippets.
        java.util.List<String> mergedSnippets = queries.parallelStream()
                .flatMap(q -> {
                    try {
                        return searchService.searchSnippets(q, topK).stream();
                    } catch (Exception e) {
                        log.warn("[Analyze] Failed to search for query '{}': {}", q, e.getMessage());
                        return Stream.empty();
                    }
                })
                .toList();

        // Deduplicate and convert to Content objects, preserving order.
        return new java.util.LinkedHashSet<>(mergedSnippets).stream()
                .limit(topK)
                .map(Content::from)
                .collect(java.util.stream.Collectors.toList());
    }
    // Local query expansion helpers removed to enforce planner-only generation.
}