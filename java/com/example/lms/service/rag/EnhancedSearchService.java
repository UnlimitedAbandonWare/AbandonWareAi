package com.example.lms.service;

import com.example.lms.search.provider.WebSearchProvider;

import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class EnhancedSearchService {
    private static final Logger log = LoggerFactory.getLogger(EnhancedSearchService.class);

    private final WebSearchProvider webSearchProvider;

    public EnhancedSearchService(WebSearchProvider webSearchProvider) {
        this.webSearchProvider = webSearchProvider;
    }

    public List<String> safeSearch(String query, int topK) {
        try {
            List<String> snippets = webSearchProvider.search(query, topK);
            return snippets.stream()
                    .filter(s -> !s.contains("[검색 결과 없음]"))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Search failed for query: {}", query, e);
            return List.of(); // 실패 시 빈 리스트 반환
        }
    }
}