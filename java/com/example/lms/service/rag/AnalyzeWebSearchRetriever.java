package com.example.lms.service.rag;

import com.example.lms.service.NaverSearchService;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Lucene 형태소 분석기를 사용하여 쿼리의 핵심 키워드를 추출하고,
 * 이를 바탕으로 확장된 검색어를 생성하여 웹을 검색하는 Retriever입니다.
 * <p>
 * 1. <b>형태소 분석:</b> 쿼리에서 명사 등 핵심 토큰을 정확히 분리합니다.
 * 2. <b>쿼리 확장:</b> 원본 쿼리 + 핵심 토큰 조합 쿼리("...정리", "...요약")를 생성합니다.
 * 3. <b>통합 검색:</b> 생성된 모든 쿼리로 검색을 수행하고 결과를 융합합니다.
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnalyzeWebSearchRetriever implements ContentRetriever {

    private final Analyzer analyzer; // 한국어 형태소 분석기 (e.g., Nori)
    private final NaverSearchService searchService;
    private final int topK;

    @Override
    public List<Content> retrieve(Query query) {
        String originalQuery = (query != null && query.text() != null) ? query.text().trim() : "";
        if (!StringUtils.hasText(originalQuery)) {
            return Collections.emptyList();
        }

        // 1. 형태소 분석 기반으로 검색할 쿼리 목록 생성
        List<String> queriesToSearch = createExpandedQueries(originalQuery);
        log.debug("[Analyze] Expanded queries: {}", queriesToSearch);

        // 2. 생성된 모든 쿼리로 순차 검색 후, LinkedHashSet으로 중복 제거 및 순서 보장
        LinkedHashSet<String> uniqueSnippets = new LinkedHashSet<>();
        for (String q : queriesToSearch) {
            try {
                // 각 쿼리별로 topK만큼 검색하여 결과의 다양성 확보
                List<String> snippets = searchService.searchSnippets(q, topK);
                uniqueSnippets.addAll(snippets);
            } catch (Exception e) {
                log.warn("[Analyze] Failed to search for query '{}': {}", q, e.getMessage());
            }
        }

        // 3. 최종 결과를 topK만큼 제한하여 반환
        return uniqueSnippets.stream()
                .limit(topK)
                .map(Content::from)
                .collect(Collectors.toList());
    }

    /**
     * 원본 쿼리를 형태소 분석하여 여러 검색용 쿼리를 생성합니다.
     *
     * @param originalQuery 원본 사용자 쿼리
     * @return 검색에 사용할 쿼리 리스트 (원본 쿼리 포함)
     */
    private List<String> createExpandedQueries(String originalQuery) {
        // Lucene 분석기로 핵심 토큰(주로 명사) 추출
        Set<String> coreTokens = analyzeToSet(originalQuery);

        List<String> expandedQueries = new ArrayList<>();
        // a) 가장 중요한 원본 쿼리를 제일 먼저 추가
        expandedQueries.add(originalQuery);

        if (!coreTokens.isEmpty()) {
            String joinedCoreTokens = String.join(" ", coreTokens);
            // b) 핵심 토큰만 조합한 쿼리 추가 (원본과 다를 경우)
            if (!joinedCoreTokens.equals(originalQuery)) {
                expandedQueries.add(joinedCoreTokens);
            }
            // c) 핵심 토큰에 보조 키워드를 붙여 확장
            expandedQueries.add(joinedCoreTokens + " 정리");
            expandedQueries.add(joinedCoreTokens + " 요약");
        }

        return expandedQueries;
    }

    /**
     * Lucene Analyzer를 사용해 텍스트에서 두 글자 이상의 핵심 토큰을 추출합니다.
     *
     * @param text 분석할 텍스트
     * @return 추출된 토큰 Set (순서 유지를 위해 LinkedHashSet 사용)
     */
    private Set<String> analyzeToSet(String text) {
        Set<String> terms = new LinkedHashSet<>();
        if (!StringUtils.hasText(text)) {
            return terms;
        }

        try (TokenStream tokenStream = analyzer.tokenStream("text", text)) {
            CharTermAttribute attr = tokenStream.addAttribute(CharTermAttribute.class);
            tokenStream.reset();
            while (tokenStream.incrementToken()) {
                String term = attr.toString();
                // 한 글자짜리 토큰(조사 등)은 보통 노이즈이므로 제외
                if (term.length() > 1) {
                    terms.add(term);
                }
            }
            tokenStream.end();
        } catch (IOException e) {
            log.warn("[Analyze] Text tokenizing failed for text: '{}'. Reason: {}", text, e.getMessage());
        }
        return terms;
    }
}