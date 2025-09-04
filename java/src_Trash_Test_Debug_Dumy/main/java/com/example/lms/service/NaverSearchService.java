package com.example.lms.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Naver 기반 웹검색 서비스 계약(필수 중첩타입 포함).
 * <p>※ 본 인터페이스는 호출부 호환을 위해 <b>단일 정의</b> 원칙을 따른다.</p>
 */
public interface NaverSearchService {

    /** 개별 검색 스텝 메타 */
    class SearchStep {
        private final String query;
        private final int returned;
        private final int afterFilter;
        private final long tookMs;

        public SearchStep(String query, int returned, int afterFilter, long tookMs) {
            this.query = query;
            this.returned = returned;
            this.afterFilter = afterFilter;
            this.tookMs = tookMs;
        }
        public String query() { return query; }
        public int returned() { return returned; }
        public int afterFilter() { return afterFilter; }
        public long tookMs() { return tookMs; }
    }

    /** 검색 실행 트레이스(간략형) */
    class SearchTrace {
        private final String query;
        private final List<String> providers;
        private final List<String> urls;
        private final long tookMs;
        private final boolean usedPreferredDomains;
        private final boolean usedAvoidDomains;
        private final String note;
        private final List<SearchStep> steps;

        public SearchTrace(String query,
                           List<String> providers,
                           List<String> urls,
                           long tookMs,
                           boolean usedPreferredDomains,
                           boolean usedAvoidDomains,
                           String note,
                           List<SearchStep> steps) {
            this.query = query;
            this.providers = providers != null ? List.copyOf(providers) : List.of();
            this.urls = urls != null ? List.copyOf(urls) : List.of();
            this.tookMs = tookMs;
            this.usedPreferredDomains = usedPreferredDomains;
            this.usedAvoidDomains = usedAvoidDomains;
            this.note = note;
            this.steps = steps != null ? List.copyOf(steps) : List.of();
        }

        public String query() { return query; }
        public List<String> providers() { return providers; }
        public List<String> urls() { return urls; }
        public long tookMs() { return tookMs; }
        public boolean usedPreferredDomains() { return usedPreferredDomains; }
        public boolean usedAvoidDomains() { return usedAvoidDomains; }
        public String note() { return note; }
        public List<SearchStep> steps() { return steps; }
    }

    /** 검색 결과(요약+트레이스) */
    class SearchResult {
        private final List<String> snippets;
        private final SearchTrace trace;

        public SearchResult(List<String> snippets, SearchTrace trace) {
            this.snippets = snippets != null ? List.copyOf(snippets) : List.of();
            this.trace = trace;
        }
        public List<String> snippets() { return snippets; }
        public SearchTrace trace() { return trace; }
    }

    // ---- 호환 상수/헬퍼 ------------------------------------------------------

    /**
     * 공공/의학/정부 등 신뢰도 높은 출처 감지 패턴.
     * (필요 시 구현체에서 추가 패턴을 and/or로 조합)
     */
    Pattern MEDICAL_OR_OFFICIAL_PATTERN = Pattern.compile(
            "(식약처|식품의약품|질병청|보건복지부|정부|공식|WHO|CDC|FDA|NIH|모범사례|가이드라인)",
            Pattern.CASE_INSENSITIVE
    );

    static boolean containsJoongna(String s) {
        if (s == null) return false;
        String t = s.toLowerCase();
        return t.contains("중고나라") || t.contains("중고 나라") || t.contains("joonggonara");
    }
    static boolean containsBunjang(String s) {
        if (s == null) return false;
        String t = s.toLowerCase();
        return t.contains("번개장터") || t.contains("번장") || t.contains("bunjang");
    }

    // ---- 핵심 계약 -----------------------------------------------------------

    /**
     * 선호/제외 도메인을 고려한 검색.
     */
    SearchResult search(String query, int topK, List<String> preferredDomains, List<String> avoidDomains);

    /**
     * 간편 검색(트레이스 포함).
     */
    default SearchResult searchWithTrace(String query, int topK) {
        return search(query, topK, null, null);
    }

    /**
     * 스니펫만 필요한 경우의 편의 API.
     */
    default List<String> searchSnippets(String query, int topK) {
        SearchResult r = search(query, topK, null, null);
        return r != null ? r.snippets() : List.of();
    }

    /** 트레이스 HTML 렌더(구현측에서 escape 주의) */
    String buildTraceHtml(SearchTrace trace, List<String> curatedSnippets);
}
