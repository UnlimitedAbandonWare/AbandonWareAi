package com.example.lms.service.rag;
import java.util.regex.Pattern;              /* 🔴 NEW */
import com.example.lms.service.NaverSearchService;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
// lombok.RequiredArgsConstructor;      // ← import 제거
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import java.util.concurrent.ExecutorService;   // ✅ 추가
import java.util.concurrent.Executors;        // ✅ 추가

import java.io.IOException;
import java.util.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 형태소 분석 → 키워드 기반 네이버 검색 Retriever.
 */
@Slf4j
public class AnalyzeWebSearchRetriever implements ContentRetriever {

    private final Analyzer           analyzer;
    private final NaverSearchService searchSvc;
    private final int                maxTokens;  // 토큰화 길이 제한
    private final int                topK;       // 검색 결과 상한 (maxTokens와 동일 값 사용)
    /** Executor for parallel token searches */
    private final ExecutorService searchExecutor =
            Executors.newFixedThreadPool(
                    Math.max(2, Runtime.getRuntime().availableProcessors()));


    public AnalyzeWebSearchRetriever(
            Analyzer analyzer,
            NaverSearchService svc,
            int maxTokens) {
        this.analyzer = analyzer;
        this.searchSvc = svc;
        this.maxTokens = maxTokens;
        this.topK = maxTokens;   // 동일 값으로 초기화
    }

    /* 🔴 메타 태그‧시간 태그 필터용 패턴 */
    private static final Pattern META_TAG = Pattern.compile("\\[[^\\]]+\\]");
    private static final Pattern TIME_TAG = Pattern.compile("\\b\\d{1,2}:\\d{2}\\b");

    /* 🔴 영문한글/고유명사 결합 보존 규칙 */
    private static final Pattern PROPER_COMPOUND = Pattern.compile(
            "(?i)^[A-Z]{1,6}[\\s-]?[가-힣].*|[A-Za-z]아카데미$|[가-힣]아카데미$");

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
        /* 2) 고유명사 패턴이면 분해 우회(원문 그대로 검색) */
        if (PROPER_COMPOUND.matcher(normalized).find()) {
            List<String> lines = searchSvc.searchSnippets(normalized, topK);
            return lines.stream().distinct().limit(topK).map(Content::from).toList();
        }


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
                if (term.length() > 1) {
                    terms.add(term);   // 한 글자 토큰만 제외, 두 글자 이상은 포함
                }
            }
            ts.end();
        } catch (IOException e) {
            log.warn("[Analyze] Tokenizing failed: {}", e.getMessage(), e);
        } catch (Exception e) {
            log.error("[Analyze] Unexpected error", e);
        }
        return terms;
    }
}