package com.example.lms.service.rag;

import com.example.lms.service.NaverSearchService;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.regex.Pattern;             /* 🔴 NEW */

@RequiredArgsConstructor
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

    /* 🔴 NEW: 신뢰도 낮은 도메인 컷 – 허용 목록만 유지 */
    private static final List<String> ALLOWED = List.of(
            "namu.wiki", "game8.co", "gamerant.com",
            "sportskeeda.com", "fandom.com", "eulji.ac.kr"
    );

    private static List<String> filterAllowed(List<String> snippets) {
        return snippets.stream()
                .filter(s -> ALLOWED.stream().anyMatch(s::contains))
                .toList();
    }

    @Override
    public List<Content> retrieve(Query query) {
        String normalized = normalize(query.text());
        List<String> a = filterAllowed(
                searchSvc.searchSnippets(normalized, topK));          // 🔴 수정
        // 폴백 1: 공식 도메인 우선(site:)
        List<String> b = a.size() >= MIN_SNIPPETS ? List.of()
                : searchSvc.searchSnippets("site:eulji.ac.kr " + normalized, topK);
        // 폴백 2: 도메인 완화(사람명/조합 질의에서 효과)
        List<String> c = (a.size() + b.size()) >= MIN_SNIPPETS ? List.of()
                : searchSvc.searchSnippets(normalized.replace("교수님", "교수"), topK);
        return java.util.stream.Stream.of(a, b, c)
                .flatMap(java.util.Collection::stream)
                .distinct()
                .limit(topK)
                .map(Content::from)
                .toList();
    }
}
