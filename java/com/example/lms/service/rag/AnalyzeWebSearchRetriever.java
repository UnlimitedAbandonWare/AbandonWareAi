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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
    @Qualifier("guardrailQueryPreprocessor")
    private final com.example.lms.service.rag.pre.QueryContextPreprocessor preprocessor;

    @Override
    public List<Content> retrieve(Query query) {
        String originalQuery = (query != null && query.text() != null) ? query.text().trim() : "";
        originalQuery = preprocessor.enrich(originalQuery);
        if (!StringUtils.hasText(originalQuery)) {
            return Collections.emptyList();
        }

        // ...
        // 1) 확장 검색어 생성
        List<String> queriesToSearch = com.example.lms.search.QueryHygieneFilter
                .sanitize(createExpandedQueries(originalQuery), /*max*/4, /*minSim*/0.80);

// 2. 병렬 스트림으로 모든 검색어를 동시에 실행
        List<String> mergedSnippets = queriesToSearch.parallelStream()
                .flatMap(q -> {
                    try {
                        return searchService.searchSnippets(q, topK).stream();
                    } catch (Exception e) {
                        log.warn("[Analyze] Failed to search for query '{}': {}", q, e.getMessage());
                        return Stream.empty();
                    }
                })
                .toList();

        // 3) 중복 제거 후 최종 반환
        return new LinkedHashSet<>(mergedSnippets).stream()
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
            // 인물 질의면 "정리/요약" 대신 프로필/경력에 집중
            boolean person = joinedCoreTokens.matches(".*(교수|의사|의료진|전문의|박사|님|씨).*");
            if (person) {
                expandedQueries.add(joinedCoreTokens + " 프로필");
                expandedQueries.add(joinedCoreTokens  +" 경력");
            } else {
                expandedQueries.add(joinedCoreTokens  +" 정리");
                expandedQueries.add(joinedCoreTokens + " 요약");
            }
        }
        // 형태소 변형 추가: 한글 연속 블록을 분리하고 하이픈을 변환하여 검색 다양성을 높인다.
        try {
            java.util.List<String> morphVariants = generateMorphVariants(originalQuery);
            for (String mv : morphVariants) {
                if (!expandedQueries.contains(mv)) {
                    expandedQueries.add(mv);
                }
            }
        } catch (Exception ignore) {
            // 변형 생성 실패 시 무시
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

    /**
     * 한국어 형태소/띄어쓰기 변형을 생성한다. 원본 문자열에서 연속된 한글 블록을 찾아 다양한 분리 변형을 만든다.
     * 예: "국비학원" → ["국비 학원", "국 비학원", "국 비 학원"]
     * 또한 하이픈을 공백 또는 제거하여 변형을 생성한다. '국비'와 '학원'이 모두 포함되면 '국비지원 학원' 변형도 추가한다.
     *
     * @param query 원본 쿼리
     * @return 생성된 변형 목록
     */
    private java.util.List<String> generateMorphVariants(String query) {
        if (query == null || query.isBlank()) return java.util.Collections.emptyList();
        java.util.List<String> vars = new java.util.ArrayList<>();
        // 하이픈 변형: 하이픈을 공백으로, 하이픈 제거
        if (query.contains("-")) {
            vars.add(query.replace('-', ' '));
            vars.add(query.replace("-", ""));
        }
        // 연속된 한글 블록을 찾아 분리 변형 생성
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("([\\p{IsHangul}]{3,})").matcher(query);
        while (matcher.find()) {
            String block = matcher.group(1);
            int len = block.length();
            for (int i = 1; i < len; i++) {
                String left = block.substring(0, i);
                String right = block.substring(i);
                vars.add(query.replace(block, left + " " + right));
            }
        }
        // 국비와 학원이 모두 포함되면 국비지원 변형 추가
        String lower = query.toLowerCase(java.util.Locale.ROOT);
        if ((lower.contains("국비") || lower.contains("국 비")) && lower.contains("학원")) {
            vars.add(query.replaceAll("국 ?비[- ]?학원", "국비지원 학원"));
            vars.add(query.replaceAll("국비", "국비지원"));
        }
        return vars;
    }
}