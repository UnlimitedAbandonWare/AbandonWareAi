package com.example.lms.service.rag.adapter;

import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.port.WebSearchPort;
import com.example.lms.service.web.BraveSearchService;
import com.example.lms.trace.SafeRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * Simple Brave-based WebSearchPort adapter.
 *
 * This intentionally keeps logic minimal and delegates
 * to existing BraveSearchService. If you later introduce
 * extra caching / rate limiting (e.g. Upstash), this is
 * the natural place to plug it in.
 */
@Component("jamminiBraveSearchAdapter")
public class JamminiBraveSearchAdapter implements WebSearchPort {

    private static final Logger log = LoggerFactory.getLogger(JamminiBraveSearchAdapter.class);

    private final BraveSearchService braveSearchService;

    public JamminiBraveSearchAdapter(BraveSearchService braveSearchService) {
        this.braveSearchService = braveSearchService;
    }

    @Override
    @Cacheable(
            value = "webSearchCache",
            key = "#query + '-' + #topK",
            unless = "#result == null || #result.isEmpty()"
    )
    public List<SearchSnippet> searchSnippets(String query, int topK) {
        try {
            List<String> raw = braveSearchService.searchSnippets(query, topK);
            return raw.stream()
                    .map(s -> new SearchSnippet(null, null, s, 0.0d))
                    .toList();
        } catch (Exception ex) {
            traceFailure(ex, topK);
            log.warn("[AWX][search][brave] jammini searchSnippets failed failureReason={} errorType={} queryHash12={} queryLength={} requestedCount={}",
                    "jammini-brave-search-error",
                    SafeRedactor.traceLabelOrFallback(ex.getClass().getSimpleName(), "unknown"),
                    SafeRedactor.hash12(query),
                    query == null ? 0 : query.length(),
                    topK);
            return Collections.emptyList();
        }
    }

    private static void traceFailure(Exception ex, int topK) {
        TraceStore.put("web.brave.jammini.searchSnippets.failed", true);
        TraceStore.put("web.brave.jammini.searchSnippets.failureReason", "jammini-brave-search-error");
        TraceStore.put("web.brave.jammini.searchSnippets.errorType",
                SafeRedactor.traceLabelOrFallback(ex == null ? null : ex.getClass().getSimpleName(), "unknown"));
        TraceStore.put("web.brave.jammini.searchSnippets.requestedCount", Math.max(0, topK));
    }

    @Override
    public String adapterId() {
        return "jammini-brave";
    }
}
