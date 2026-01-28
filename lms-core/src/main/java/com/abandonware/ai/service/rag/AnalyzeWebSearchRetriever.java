package com.abandonware.ai.service.rag;

import com.abandonware.ai.service.NaverSearchService;
import com.abandonware.ai.service.rag.model.ContextSlice;
import com.example.lms.util.HtmlTextUtil;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Web(Search) -> ContextSlice 변환기.
 *
 * - com.abandonware.* 네임스페이스에서는 NaverSearchService가 com.example 구현을 delegate 하며,
 *   이 retriever는 그 결과(List<String> snippets)를 ContextSlice 로 가공합니다.
 * - 네트워크/외부 API 실패시에도 호출자가 죽지 않도록 fail-soft + timeout/hedge를 사용합니다.
 */
@Service
public class AnalyzeWebSearchRetriever {
    private static final Logger log = LoggerFactory.getLogger(AnalyzeWebSearchRetriever.class);

    private final NaverSearchService naver;

    private final ExecutorService exec = Executors.newFixedThreadPool(2);

    @Value("${naver.search.timeout-ms:3000}")
    private int timeoutMs;

    @Value("${naver.hedge.enabled:true}")
    private boolean hedgeEnabled;

    @Value("${naver.hedge.delay-ms:200}")
    private int hedgeDelayMs;

    public AnalyzeWebSearchRetriever(NaverSearchService naver) {
        this.naver = naver;
    }

    /**
     * SearchProbeController가 호출하는 엔트리 포인트.
     */
    public List<ContextSlice> search(String query, int topK) {
        return retrieve(query, topK);
    }

    /**
     * (DynamicRetrievalHandlerChain 등) 내부에서 사용하는 엔트리 포인트.
     */
    public List<ContextSlice> retrieve(String query, int topK) {
        if (query == null || query.isBlank()) return Collections.emptyList();

        int k = topK > 0 ? topK : 6;

        CompletableFuture<List<String>> primary = CompletableFuture.supplyAsync(() -> naver.searchSnippets(query, k), exec);
        CompletableFuture<List<String>> hedge = null;

        if (hedgeEnabled) {
            hedge = CompletableFuture.supplyAsync(
                    () -> naver.searchSnippets(query, k),
                    CompletableFuture.delayedExecutor(hedgeDelayMs, TimeUnit.MILLISECONDS, exec)
            );
        }

        CompletableFuture<?> any = (hedge != null) ? CompletableFuture.anyOf(primary, hedge) : primary;

        try {
            @SuppressWarnings("unchecked")
            List<String> snippets = (List<String>) any.get(timeoutMs, TimeUnit.MILLISECONDS);
            // cancel loser
            primary.cancel(false);
            if (hedge != null) hedge.cancel(false);
            return toSlices(snippets, k);
        } catch (Exception e) {
            primary.cancel(false);
            if (hedge != null) hedge.cancel(false);
            log.debug("web search failed (query='{}'): {}", query, e.toString());
            return Collections.emptyList();
        }
    }

    private List<ContextSlice> toSlices(List<String> snippets, int topK) {
        if (snippets == null || snippets.isEmpty()) return Collections.emptyList();

        List<ContextSlice> out = new ArrayList<>();
        int rank = 0;

        for (String raw : snippets) {
            if (raw == null || raw.isBlank()) continue;
            rank++;
            if (rank > topK) break;

            String href = HtmlTextUtil.extractFirstHref(raw);
            String title = HtmlTextUtil.stripHtml(HtmlTextUtil.extractFirstAnchorText(raw));
            String after = HtmlTextUtil.stripHtml(HtmlTextUtil.afterFirstAnchor(raw));
            String snippet = (after == null || after.isBlank()) ? HtmlTextUtil.stripHtml(raw) : after.trim();

            ContextSlice cs = new ContextSlice();
            cs.setId((href != null && !href.isBlank()) ? href : "web:" + rank);
            cs.setTitle((title == null || title.isBlank()) ? "Web result #" + rank : title.trim());
            cs.setSnippet(snippet);
            cs.setSource("web");
            cs.setScore(Math.max(0.1f, 1.0f - 0.05f * (rank - 1)));
            cs.setRank(rank);
            out.add(cs);
        }

        return out;
    }

    @PreDestroy
    public void shutdown() {
        exec.shutdownNow();
    }
}
