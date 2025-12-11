package com.abandonware.ai.service.rag;

import trace.TraceContext;
import com.abandonware.ai.service.NaverSearchService;
import com.abandonware.ai.service.rag.model.ContextSlice;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
@Service
public class AnalyzeWebSearchRetriever {

    private final ExecutorService searchExecutor = Executors.newCachedThreadPool();

    private final NaverSearchService naver;

    @Value("${naver.search.timeout-ms:2500}")
    private int timeoutMs;

    @Value("${naver.hedge.enabled:true}")
    private boolean hedgeEnabled;

    @Value("${naver.hedge.delay-ms:120}")
    private int hedgeDelayMs;

    public AnalyzeWebSearchRetriever(NaverSearchService naver) {
        this.naver = naver;
    }

    public List<ContextSlice> search(String query, int topK) {
        // Hedging/timeout placeholders are non-functional; rely on configured values for logging/metrics in real impl.
        return java.util.List.of();
    }
}