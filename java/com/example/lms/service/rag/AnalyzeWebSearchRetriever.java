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




import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
        java.util.List<String> flattened = new java.util.ArrayList<>();
        for (String raw : mergedSnippets) {
            java.util.Optional<java.util.List<String>> maybeJson = tryFlattenBraveJson(raw, topK);
            if (maybeJson.isPresent()) {
                flattened.addAll(maybeJson.get());
            } else {
                flattened.add(raw);
            }
        }

        java.util.Map<String, String> dedupMap = new java.util.LinkedHashMap<>();
        for (String snippet : flattened) {
            String url = extractUrl(snippet);
            String key = (url != null && !url.isBlank()) ? url : snippet;
            dedupMap.putIfAbsent(key, snippet);
        }

        if (dedupMap.isEmpty()) {
            try {
                log.warn("[AnalyzeWebSearchRetriever] planner-based web search empty; fallback to direct search for original query '{}'", originalQuery);
                java.util.List<String> fallback = searchService.searchSnippets(originalQuery, topK);
                if (fallback != null && !fallback.isEmpty()) {
                    for (String raw : fallback) {
                        if (looksLikeBraveJson(raw)) {
                            try {
                                java.util.List<String> extra = flattenBraveJson(raw, topK);
                                for (String s : extra) {
                                    String url = extractUrl(s);
                                    String key = (url != null && !url.isBlank()) ? url : s;
                                    dedupMap.putIfAbsent(key, s);
                                }
                            } catch (Exception e) {
                                log.warn("[Analyze] Failed to flatten JSON snippet from fallback, dropping it: {}", e.getMessage());
                            }
                        } else {
                            String url = extractUrl(raw);
                            String key = (url != null && !url.isBlank()) ? url : raw;
                            dedupMap.putIfAbsent(key, raw);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("[AnalyzeWebSearchRetriever] fallback direct web search failed for query '{}': {}", originalQuery, e.getMessage());
            }
        }

        java.util.List<String> unique = new java.util.ArrayList<>(dedupMap.values());
        return unique.stream()
                .limit(topK)
                .map(Content::from)
                .collect(java.util.stream.Collectors.toList());

    }
    private boolean looksLikeBraveJson(String s) {
        if (s == null) return false;
        String trimmed = s.trim();
        if (trimmed.length() < 100) {
            return false;
        }
        return trimmed.startsWith("{")
                && trimmed.contains("\"web\"")
                && trimmed.contains("\"results\"");
    }


    private java.util.List<String> flattenBraveJson(String json, int max) throws java.io.IOException {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(json);
        com.fasterxml.jackson.databind.JsonNode results = root.path("web").path("results");
        java.util.List<String> out = new java.util.ArrayList<>();
        if (results.isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode node : results) {
                String title = node.path("title").asText("");
                String desc = node.path("description").asText("");
                String url = node.path("url").asText("");

                StringBuilder sb = new StringBuilder();
                if (!title.isBlank()) {
                    sb.append(title.trim()).append("\n");
                }
                if (!desc.isBlank()) {
                    sb.append(desc.trim()).append("\n");
                }
                if (!url.isBlank()) {
                    sb.append("URL: ").append(url.trim());
                }

                String snippet = sb.toString().trim();
                if (!snippet.isBlank()) {
                    out.add(snippet);
                }
                if (out.size() >= max) {
                    break;
                }
            }
        }
        return out;
    }

    private String extractUrl(String snippet) {
        if (snippet == null) {
            return null;
        }
        int idx = snippet.indexOf("URL:");
        if (idx < 0) {
            return null;
        }
        String rest = snippet.substring(idx + 4).trim();
        int newline = rest.indexOf('\n');
        if (newline >= 0) {
            rest = rest.substring(0, newline);
        }
        return rest.trim();
    }

    // Local query expansion helpers removed to enforce planner-only generation.

private java.util.Optional<java.util.List<String>> tryFlattenBraveJson(String raw, int max) {
    if (raw == null || raw.isBlank()) {
        return java.util.Optional.empty();
    }
    String cleaned = stripCodeFence(raw);
    try {
        java.util.List<String> flattened = flattenBraveJson(cleaned, max);
        return flattened.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(flattened);
    } catch (Exception e) {
        log.warn("[AnalyzeWebSearchRetriever] Failed to flatten JSON snippet: {}", e.getMessage());
        return java.util.Optional.empty();
    }
}

private String stripCodeFence(String s) {
    String trimmed = s == null ? "" : s.trim();
    if (trimmed.startsWith("```")) {
        int firstNewline = trimmed.indexOf('\n');
        if (firstNewline > 0) {
            trimmed = trimmed.substring(firstNewline + 1);
        }
    }
    if (trimmed.endsWith("```")) {
        int lastFence = trimmed.lastIndexOf("```");
        if (lastFence >= 0) {
            trimmed = trimmed.substring(0, lastFence).trim();
        }
    }
    return trimmed;
}


    /**
     * Deep Search Mode: 특정 의료/교수 질의에 대해 확장 쿼리를 추가한다.
     */
    private java.util.List<String> maybeExpandQueriesForDeepSearch(String originalQuery, java.util.List<String> queries) {
        if (originalQuery == null || queries == null || queries.isEmpty()) {
            return queries;
        }
        String q = originalQuery.toLowerCase(java.util.Locale.ROOT);

        // 간단한 규칙: "을지" + "교수"가 함께 등장하면 소아청소년 정신건강의학과 프로필을 찾는 질의로 간주
        boolean isEuljiProfessorQuery = q.contains("을지") && q.contains("교수");
        if (!isEuljiProfessorQuery) {
            return queries;
        }

        String expanded = expandProfessorQuery(originalQuery);
        if (expanded == null || expanded.isBlank()) {
            return queries;
        }

        boolean alreadyPresent = queries.stream().anyMatch(expanded::equals);
        if (alreadyPresent) {
            return queries;
        }

        java.util.List<String> extended = new java.util.ArrayList<>(queries);
        extended.add(expanded);
        log.debug("[AnalyzeWebSearchRetriever] Deep search with expanded query: {}", expanded);
        return extended;
    }

    private String expandProfessorQuery(String query) {
        if (query == null) {
            return null;
        }
        if (query.contains("을지") && query.contains("교수")) {
            return query + " 정신건강의학과 소아청소년 ADHD 의료진";
        }
        return query + " 상세 프로필";
    }

}
