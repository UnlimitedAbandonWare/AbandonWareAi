package com.example.lms.service.impl;

import com.acme.aicore.adapters.search.CachedWebSearch;
import com.acme.aicore.domain.model.SearchBundle;
import com.acme.aicore.domain.model.SearchBundle.Doc;
import com.acme.aicore.domain.model.WebSearchQuery;
import com.example.lms.service.NaverSearchService;
import com.example.lms.service.NaverSearchService.SearchResult;
import com.example.lms.service.NaverSearchService.SearchTrace;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Facade over {@link CachedWebSearch} that adapts the low‑level bundle API into the
 * {@link NaverSearchService} contract.  Invokes the multi search and extracts
 * titles/snippets/URLs from the returned documents.  A simple {@link SearchTrace}
 * is assembled summarising the number of providers, number of documents and total
 * duration.  When the underlying call fails or returns no documents the
 * returned lists are empty.
 */
@Service
@RequiredArgsConstructor
public class NaverSearchServiceFacade implements NaverSearchService {
    private final CachedWebSearch multi;

    /**
     * Perform a web search using the configured multi search and return the top K
     * documents along with a basic trace.  Preferred/avoid domains are
     * currently ignored but retained for API compatibility.
     */
    public SearchResult search(String query, int topK, List<String> preferredDomains, List<String> avoidDomains) {
        long start = System.currentTimeMillis();
        WebSearchQuery q = new WebSearchQuery(query);
        // Fan‑out to up to 2 providers and wait up to 8 seconds for completion
        Mono<SearchBundle> mono = multi.searchMulti(q, 2);
        SearchBundle bundle;
        try {
            bundle = mono.toFuture().get(8, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ex) {
            bundle = SearchBundle.empty();
        }
        if (bundle == null) {
            bundle = SearchBundle.empty();
        }
        // Extract document list and build snippet/URL lists
        List<Doc> docs = bundle.docs();
        List<String> snippets = new ArrayList<>();
        List<String> urls = new ArrayList<>();
        int limit = Math.min(topK, docs.size());
        for (int i = 0; i < limit; i++) {
            Doc d = docs.get(i);
            // Compose snippet: title and snippet joined with newline
            String s = (d.title() == null ? "" : d.title()) + "\n" + (d.snippet() == null ? "" : d.snippet());
            snippets.add(s.trim());
            urls.add(d.url());
        }
        long took = System.currentTimeMillis() - start;
        SearchTrace trace = new SearchTrace(
                query,
                // Provide the list of providers that participated
                java.util.List.of("multi"),
                urls,
                took,
                preferredDomains != null && !preferredDomains.isEmpty(),
                false,
                null,
                java.util.List.of());
        return new SearchResult(snippets, trace);
    }

    @Override
    public SearchResult searchWithTrace(String query, int topK) {
        return search(query, topK, null, null);
    }

    @Override
    public String buildTraceHtml(SearchTrace trace, List<String> curatedSnippets) {
        // Suppress trace output when the search was effectively offline or produced
        // no curated snippets.  We leverage TraceCompat.offline to detect both
        // offline traces (no providers and under 50ms) and empty snippet lists.
        // When the search is offline or produced no snippets, return a simple diagnostic string
        // instead of suppressing the trace entirely.  We do not attempt to introspect
        // a reason here due to limited reflection support; users can consult logs for details.
        if (com.example.lms.service.trace.TraceCompat.offline(trace, curatedSnippets)) {
            return "WEB-SEARCH OFFLINE: offline or empty results";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<div class='trace'><h4>Web Trace</h4>");
        if (trace != null) {
            sb.append("<div><b>Query:</b> ").append(escape(trace.query())).append("</div>");
            // Use TraceCompat to extract the elapsed duration in a resilient manner
            sb.append("<div><b>Elapsed:</b> ")
              .append(com.example.lms.service.trace.TraceCompat.totalMs(trace))
              .append(" ms</div>");
        }
        if (curatedSnippets != null && !curatedSnippets.isEmpty()) {
            sb.append("<ul>");
            for (String s : curatedSnippets) sb.append("<li>").append(escape(s)).append("</li>");
            sb.append("</ul>");
        }
        sb.append("</div>");
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
