package com.example.lms.integration.handlers;

import com.example.lms.gptsearch.decision.SearchDecision;
import com.example.lms.gptsearch.decision.SearchDecisionService;
import com.example.lms.gptsearch.dto.SearchMode;
import com.example.lms.gptsearch.web.WebSearchProvider;
import com.example.lms.gptsearch.web.dto.WebDocument;
import com.example.lms.gptsearch.web.dto.WebSearchQuery;
import com.example.lms.gptsearch.web.dto.WebSearchResult;
import com.example.lms.service.rag.handler.AbstractRetrievalHandler;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.util.ArrayList;
import java.util.List;

/**
 * Adaptive web search handler that decides whether to execute a web search
 * based on the user query and preferences.  When the decision service
 * indicates that a search is warranted, the handler invokes the configured
 * providers in parallel (sequentially in this stub implementation) and
 * converts the returned snippets into {@link Content} objects for the
 * retrieval chain.  Errors are logged but do not propagate.
 */
@Slf4j
@RequiredArgsConstructor
public class AdaptiveWebSearchHandler extends AbstractRetrievalHandler {

    private final SearchDecisionService decisionService;
    private final List<WebSearchProvider> providers;

    /**
     * Execute adaptive web search.  The query metadata may contain the
     * ChatRequestDto with user preferences, but if unavailable the handler
     * falls back to AUTO mode and default providers.
     */
    @Override
    protected boolean doHandle(Query q, List<Content> acc) {
        if (q == null || acc == null) {
            return true;
        }
        String text = (q.text() == null ? "" : q.text().trim());
        // For now we ignore provider hints from metadata; a real implementation
        // would extract searchMode and provider preferences from query.metadata().
        SearchDecision decision = decisionService.decide(text, SearchMode.AUTO, null, null);
        if (!decision.shouldSearch()) {
            return true;
        }
        try {
            // Build search query.  Freshness not modelled in this stub.
            com.example.lms.gptsearch.web.dto.WebSearchQuery wq = new WebSearchQuery(text, decision.topK(), decision.providers(), null);
            List<WebDocument> docs = new ArrayList<>();
            for (WebSearchProvider p : providers) {
                try {
                    WebSearchResult r = p.search(wq);
                    if (r != null && r.getDocuments() != null) {
                        docs.addAll(r.getDocuments());
                    }
                } catch (Exception e) {
                    log.debug("Web provider {} failed: {}", p.id(), e.toString());
                }
            }
            // Convert to RAG content objects.  Each snippet becomes one Content.
            for (WebDocument d : docs) {
                if (d == null || d.getSnippet() == null) continue;
                acc.add(Content.from(d.getSnippet()));
            }
        } catch (Exception ex) {
            log.warn("[AdaptiveWeb] search failed", ex);
        }
        return true;
    }
}