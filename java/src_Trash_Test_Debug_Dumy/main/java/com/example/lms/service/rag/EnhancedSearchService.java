package com.example.lms.service;

import java.util.List;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j; // 추가
@Slf4j  // 로그 기능을 제공하기 위해 추가
public class EnhancedSearchService {

    private final NaverSearchService searchService;

    public EnhancedSearchService(NaverSearchService searchService) {
        this.searchService = searchService;
    }

    public List<String> safeSearch(String query, int topK) {
        try {
            List<String> snippets = searchService.searchSnippets(query, topK);
            return snippets.stream()
                    .filter(s -> !s.contains("[검색 결과 없음]"))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Search failed for query: {}", query, e);
            return List.of(); // 실패 시 빈 리스트 반환
        }
    }
}
