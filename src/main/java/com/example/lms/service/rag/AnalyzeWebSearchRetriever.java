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

import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;              /* 🔴 NEW */
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 형태소 분석 → 키워드 기반 네이버 검색 Retriever.
 */
@Slf4j
@RequiredArgsConstructor
public class AnalyzeWebSearchRetriever implements ContentRetriever {

    private final Analyzer           analyzer;
    private final NaverSearchService searchSvc;
    private final int                topK;

    /** Executor for parallel token searches */
    private final ExecutorService searchExecutor = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors())
    );
    /* 🔴 메타 태그‧시간 태그 필터용 패턴 */
    private static final Pattern META_TAG = Pattern.compile("\\[[^\\]]+\\]");
    private static final Pattern TIME_TAG = Pattern.compile("\\b\\d{1,2}:\\d{2}\\b");

    /* 🔴 메타·개행 제거 유틸 */
    private static String normalize(String raw) {
        if (raw == null) return "";
        String s = META_TAG.matcher(raw).replaceAll("");
        s = TIME_TAG.matcher(s).replaceAll("");
        return s.replace("\n", " ").trim();
    }

    @Override
    public List<Content> retrieve(Query query) {

        /* 🔴 1) 원문 정규화(노이즈 제거) */
        String normalized = normalize(query.text());

        /* 2) 형태소 토큰화 */
        Set<String> tokens = analyze(normalized);
        if (tokens.isEmpty()) return List.of();

        /* 3) 토큰별 검색 후 합치기 */
        int eachTopK = Math.max(1, topK / tokens.size());
        List<Content> merged = new ArrayList<>();

        // perform searches for each token in parallel
        List<CompletableFuture<List<Content>>> futures = new ArrayList<>();
        for (String t : tokens) {
            futures.add(
                    CompletableFuture.supplyAsync(() -> {
                        List<String> lines = searchSvc.searchSnippets(t, eachTopK);
                        return lines.stream().map(Content::from).toList();
                    }, searchExecutor)
            );
        }
        for (CompletableFuture<List<Content>> future : futures) {
            try {
                merged.addAll(future.join());
            } catch (Exception e) {
                log.warn("[Analyze] async search failed", e);
            }
            if (merged.size() >= topK) {
                break;
            }
        }
        return merged.size() > topK ? merged.subList(0, topK) : merged;
    }

    /* ───────── helper ───────── */
    private Set<String> analyze(String text) {
        Set<String> terms = new LinkedHashSet<>();
        try (TokenStream ts = analyzer.tokenStream("f", text)) {
            CharTermAttribute attr = ts.addAttribute(CharTermAttribute.class);
            ts.reset();
            while (ts.incrementToken()) {
                String term = attr.toString().trim();
                if (term.length() > 2) terms.add(term);   /* 🔴 길이 ≤2 토큰 제외 */
            }
            ts.end();
        } catch (IOException e) {
            log.warn("[Analyze] Tokenizing failed", e);
        }
        return terms;
    }
}
