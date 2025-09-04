package com.example.lms.api;

import com.example.lms.service.NaverSearchService;
import com.example.lms.service.NaverSearchService.SearchResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 간단한 웹 검색 JSON API. 쿼리와 topK 값을 받아 NaverSearchService를 통해 검색 결과를 반환한다.
 * SSE 스트림 구현은 이번 스코프에 포함되지 않는다.
 */
@RestController
@RequestMapping("/api/web-search")
@RequiredArgsConstructor
public class WebSearchController {

    private final NaverSearchService naverSearchService;

    /**
     * JSON 결과 검색 엔드포인트.
     * @param query 검색어
     * @param topK 결과 상위 K개 수
     * @return SearchResult 객체
     */
    @GetMapping(produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
    public SearchResult search(@RequestParam(name = "q") String query,
                               @RequestParam(name = "topK", defaultValue = "10") int topK) {
        return naverSearchService.searchWithTrace(query, topK);
    }
}