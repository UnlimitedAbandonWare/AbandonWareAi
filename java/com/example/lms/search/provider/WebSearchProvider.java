package com.example.lms.search.provider;

import com.example.lms.service.NaverSearchService.SearchResult;
import java.util.List;

/**
 * 웹 검색 공급자 추상화 인터페이스
 * (Naver, Brave 등 구체적인 구현체에 의존하지 않기 위함)
 */
public interface WebSearchProvider {

    /**
     * 단순 스니펫 검색
     */
    List<String> search(String query, int topK);

    /**
     * 추적 정보가 포함된 검색 (Trace 포함)
     */
    SearchResult searchWithTrace(String query, int topK);

    /**
     * 서비스 활성화 여부
     */
    boolean isEnabled();

    /**
     * 공급자 이름 (로깅/디버깅용)
     */
    String getName();

    /**
     * 추적 정보 객체(traceObj)와 스니펫 목록을 기반으로 HTML을 생성한다.
     * 기본 구현은 빈 문자열을 반환하며, 각 구현체에서 필요한 경우 오버라이드한다.
     */
    default String buildTraceHtml(Object traceObj, List<String> snippets) {
        return "";
    }

    /**
     * Whether this provider is available to serve queries right now (e.g. API key configured, not rate-limited).
     */
    default boolean isAvailable() {
        return isEnabled();
    }

    /**
     * Lower values are tried first when multiple providers are orchestrated.
     */
    default int getPriority() {
        return 100;
    }

    /**
     * Whether this provider is known to support boolean {@code OR} syntax inside the raw query string.
     *
     * <p>This is mainly used by the guard detour "cheap retry" site-hint logic. When a provider
     * doesn't support OR (or treats it as a literal token), combining site constraints can reduce
     * recall significantly.
     *
     * <p>Implementations should return {@code true} only if OR is reliably supported.
     */
    default boolean supportsSiteOrSyntax() {
        return false;
    }

}
