
package com.example.lms.service.rag;

import com.example.lms.service.NaverSearchService;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import lombok.extern.slf4j.Slf4j;   // ✅ 로그 어노테이션 import
// lombok.RequiredArgsConstructor;      // ← import 제거

import java.util.List;
import java.util.regex.Pattern;             /* 🔴 NEW */
import java.util.concurrent.ExecutorService;   // ✅ 추가
import java.util.concurrent.Executors;        // ✅ 추가

@Slf4j
public class WebSearchRetriever implements ContentRetriever {

    private final NaverSearchService searchSvc;
    private final int                topK;
    /** Executor for parallel token searches */
    private final ExecutorService searchExecutor = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors())
    );

    /* LangChainConfig 직접 호출용 명시적 생성자 */
    public WebSearchRetriever(NaverSearchService svc, int k) {
        this.searchSvc = svc;
        this.topK      = k;
    }
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



    @Override
    public List<Content> retrieve(Query query) {
        String normalized = normalize(query.text());
        // 1) 원본 쿼리 그대로 검색
        List<String> a = searchSvc.searchSnippets(normalized, topK);
        // 2) 폴백 1: 공식 도메인 우선(site:)
        List<String> b = a.size() >= MIN_SNIPPETS ? List.of()
                : searchSvc.searchSnippets("site:eulji.ac.kr " + normalized, topK);
        // 3) 폴백 2: 표현 완화(사람명/조합 질의에서 효과)
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
