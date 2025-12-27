package com.example.lms.service.soak;

import java.util.List;

public interface SearchOrchestrator {
    /** 반환: relevance 점수 상위 k개의 (정답여부/근거여부 포함) */
    List<SearchResult> search(String query, int k);

    class SearchResult {
        public String id; public boolean supportedByEvidence; public double relScore;
    }
}