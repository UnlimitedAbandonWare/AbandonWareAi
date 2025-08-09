package com.example.lms.service.rag;

import com.example.lms.service.NaverSearchService;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.regex.Pattern;             /* 🔴 NEW */
@Slf4j
@RequiredArgsConstructor
//@Component // ← 빈 자동 등록이 필요할 때 주석 해제
public class WebSearchRetriever implements ContentRetriever {

    private final NaverSearchService searchSvc;
    private final int                topK;
    private static final int MIN_SNIPPETS = 2;

    /* 🔴 노이즈 제거 패턴 */
    private static final Pattern META_TAG = Pattern.compile("\\[[^\\]]+\\]");
    private static final Pattern TIME_TAG = Pattern.compile("\\b\\d{1,2}:\\d{2}\\b");

    private static String normalize(String raw) {        /* 🔴 NEW */
        if (raw == null) return "";

        String s = META_TAG.matcher(raw).replaceAll("");
        s = TIME_TAG.matcher(s).replaceAll("");
        return s.replace("\n", " ").trim();
    }

    /* ✅ 선호 도메인: 제거가 아닌 '우선 정렬'만 수행 */
    private static final List<String> PREFERRED = List.of(
            "namu.wiki", "wikipedia.org", "blog.naver.com",
            "eulji.ac.kr", "ac.kr", "go.kr"
    );
    private static boolean containsPreferred(String s) {
        return PREFERRED.stream().anyMatch(s::contains);
    }

    @Override
    public List<Content> retrieve(Query query) {
        String normalized = normalize(query.text());
        // 1) 1차 수집: topK*2 → 중복/정렬 후 topK
        List<String> first = searchSvc.searchSnippets(normalized, Math.max(topK, 1) * 2);
        if (log.isDebugEnabled()) {
            log.debug("[WebSearchRetriever] first raw={} (q='{}')", first.size(), normalized);
        }
        // 선호 도메인 우선 정렬(삭제 아님)
        List<String> ranked = first.stream()
                .sorted((a,b) -> Boolean.compare(containsPreferred(b), containsPreferred(a)))
                .distinct()
                .limit(topK)
                .toList();

        // 2) 폴백: 지나친 공손어/호칭 정리
        List<String> fallback = ranked.size() >= MIN_SNIPPETS ? List.of()
                : searchSvc.searchSnippets(normalized.replace("교수님", "교수").replace("님",""), topK);

        List<String> finalSnippets = java.util.stream.Stream.of(ranked, fallback)
                .flatMap(java.util.Collection::stream)
                .distinct()
                .limit(topK)
                .toList();

        if (log.isDebugEnabled()) {
            log.debug("[WebSearchRetriever] selected={} (topK={})", finalSnippets.size(), topK);
        }
        return finalSnippets.stream().map(Content::from).toList();
    }
}
