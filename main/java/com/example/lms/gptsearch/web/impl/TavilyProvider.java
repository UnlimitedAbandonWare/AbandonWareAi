package com.example.lms.gptsearch.web.impl;

import com.example.lms.gptsearch.web.AbstractWebSearchProvider;
import com.example.lms.gptsearch.web.ProviderId;
import com.example.lms.gptsearch.web.dto.WebDocument;
import com.example.lms.gptsearch.web.dto.WebSearchQuery;
import com.example.lms.gptsearch.web.dto.WebSearchResult;
import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.TavilyWebSearchRetriever;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.util.MetadataUtils;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;



/**
 * Provider adapter for the existing Tavily retriever.
 */
@Component
public class TavilyProvider extends AbstractWebSearchProvider {

    @Autowired(required = false)
    private TavilyWebSearchRetriever retriever;

    @Override
    public ProviderId id() {
        return ProviderId.TAVILY;
    }

    @Override
    protected WebSearchResult doSearch(WebSearchQuery query) {
        String rawQuery = query == null ? "" : query.getQuery();
        int requested = query == null ? 0 : query.getTopK();
        if (requested <= 0) {
            traceNonPositiveTopK(rawQuery);
            return new WebSearchResult(id().name(), List.of());
        }
        if (retriever == null) {
            traceDisabled(rawQuery, requested);
            return new WebSearchResult(id().name(), List.of());
        }

        TraceStore.put("web.tavily.failureReason", null);
        List<Content> contents = retriever.retrieve(new Query(rawQuery));
        List<WebDocument> docs = new ArrayList<>();
        for (Content content : contents) {
            WebDocument doc = toDocument(content);
            if (doc != null) {
                docs.add(doc);
            }
        }
        TraceStore.put("web.tavily.providerBridge", "TavilyWebSearchRetriever");
        TraceStore.put("web.tavily.bridgeDocumentCount", docs.size());
        if (!docs.isEmpty()) {
            traceBridgeProviderSuccess(rawQuery, requested, docs.size());
        }
        if (docs.isEmpty() && TraceStore.get("web.tavily.failureReason") == null) {
            traceBridgeProviderEmpty(rawQuery, requested);
        }
        return new WebSearchResult(id().name(), docs);
    }

    private static void traceNonPositiveTopK(String query) {
        String reason = "non_positive_top_k";
        TraceStore.put("web.tavily.providerDisabled", false);
        TraceStore.put("web.tavily.disabledReason", null);
        TraceStore.put("web.tavily.disabledReasonCanonical", null);
        TraceStore.put("web.tavily.skipped", true);
        TraceStore.put("web.tavily.skipped.reason", reason);
        TraceStore.put("web.tavily.failureReason", reason);
        TraceStore.put("web.tavily.requestedCount", 0);
        TraceStore.put("web.tavily.returnedCount", 0);
        TraceStore.put("web.tavily.afterFilterCount", 0);
        TraceStore.put("web.tavily.zeroResults", true);
        TraceStore.put("web.tavily.providerEmpty", false);
        TraceStore.put("web.tavily.afterFilterStarved", false);
        TraceStore.put("web.tavily.providerBridge", null);
        TraceStore.put("web.tavily.bridgeDocumentCount", 0);
        TraceStore.put("web.tavily.httpStatus", null);
        TraceStore.put("web.tavily.429", false);
        TraceStore.put("web.tavily.rateLimited", false);
        TraceStore.put("web.tavily.timeout", false);
        TraceStore.put("web.tavily.cancelled", false);
        TraceStore.put("web.tavily.queryHash", SafeRedactor.hashValue(query));
        TraceStore.put("web.tavily.queryLength", query == null ? 0 : query.length());
        TraceStore.put("web.tavily.exceptionType", null);
        TraceStore.put("web.tavily.errorType", null);
        TraceStore.put("web.tavily.tookMs", null);
        TraceStore.put("web.tavily.errorBodyHash", null);
        TraceStore.put("web.tavily.errorBodyLength", null);
        TraceStore.put("web.tavily.queryTokenBucket", null);
        TraceStore.put("web.tavily.timeoutMs", null);
        TraceStore.put("web.tavily.endpointHost", null);
        TraceStore.put("web.tavily.cooldown.reason", null);
        TraceStore.put("web.tavily.cooldown.hintMs", null);
        TraceStore.put("web.tavily.retryAfterMs", null);
        TraceStore.put("web.provider.name", "tavily");
        TraceStore.put("web.provider.enabled", true);
        TraceStore.put("web.provider.resultCount", 0);
        TraceStore.put("web.provider.disabledReason", null);
        TraceStore.put("web.query.hash", query == null || query.isBlank()
                ? null
                : SafeRedactor.hashValue(query));
        TraceStore.put("web.query.length", query == null ? 0 : query.length());
        TraceStore.putIfAbsent("web.query.variantCount", 0);
        TraceStore.put("web.failsoft.reason", reason);
        TraceStore.put("web.filter.starvationReason", null);
    }

    private static WebDocument toDocument(Content content) {
        if (content == null || content.textSegment() == null) {
            return null;
        }
        String text = content.textSegment().text();
        if (text == null || text.isBlank()) {
            return null;
        }
        Map<String, Object> metadata = MetadataUtils.toMap(content.textSegment().metadata());
        return new WebDocument(
                str(metadata.get("url")),
                str(metadata.get("title")),
                text,
                null,
                null
        );
    }

    private static void traceDisabled(String query, int requested) {
        String reason = "tavily.enabled=false";
        TraceStore.put("web.tavily.providerDisabled", true);
        TraceStore.put("web.tavily.disabledReason", reason);
        TraceStore.put("web.tavily.disabledReasonCanonical", reason);
        TraceStore.put("web.tavily.skipped", true);
        TraceStore.put("web.tavily.skipped.reason", reason);
        TraceStore.put("web.tavily.failureReason", "provider-disabled");
        TraceStore.put("web.tavily.requestedCount", Math.max(0, requested));
        TraceStore.put("web.tavily.returnedCount", 0);
        TraceStore.put("web.tavily.afterFilterCount", 0);
        TraceStore.put("web.tavily.zeroResults", true);
        TraceStore.put("web.tavily.providerEmpty", false);
        TraceStore.put("web.tavily.afterFilterStarved", false);
        TraceStore.put("web.tavily.httpStatus", null);
        TraceStore.put("web.tavily.429", false);
        TraceStore.put("web.tavily.rateLimited", false);
        TraceStore.put("web.tavily.timeout", false);
        TraceStore.put("web.tavily.cancelled", false);
        TraceStore.put("web.tavily.queryHash", SafeRedactor.hashValue(query));
    }

    private static void traceBridgeProviderSuccess(String query, int requested, int count) {
        TraceStore.put("web.tavily.providerDisabled", false);
        TraceStore.put("web.tavily.zeroResults", false);
        TraceStore.put("web.tavily.providerEmpty", false);
        TraceStore.put("web.tavily.afterFilterStarved", false);
        TraceStore.put("web.tavily.httpStatus", null);
        TraceStore.put("web.tavily.429", false);
        TraceStore.put("web.tavily.rateLimited", false);
        TraceStore.put("web.tavily.timeout", false);
        TraceStore.put("web.tavily.cancelled", false);
        TraceStore.put("web.tavily.failureReason", "");
        TraceStore.putIfAbsent("web.tavily.requestedCount", Math.max(0, requested));
        TraceStore.putIfAbsent("web.tavily.returnedCount", Math.max(0, count));
        TraceStore.putIfAbsent("web.tavily.afterFilterCount", Math.max(0, count));
        TraceStore.putIfAbsent("web.tavily.queryHash", SafeRedactor.hashValue(query));
        TraceStore.putIfAbsent("web.tavily.queryLength", query == null ? 0 : query.length());
    }

    private static void traceBridgeProviderEmpty(String query, int requested) {
        TraceStore.put("web.tavily.providerDisabled", false);
        TraceStore.put("web.tavily.zeroResults", true);
        TraceStore.put("web.tavily.providerEmpty", true);
        TraceStore.put("web.tavily.afterFilterStarved", false);
        TraceStore.put("web.tavily.httpStatus", null);
        TraceStore.put("web.tavily.429", false);
        TraceStore.put("web.tavily.rateLimited", false);
        TraceStore.put("web.tavily.timeout", false);
        TraceStore.put("web.tavily.cancelled", false);
        TraceStore.put("web.tavily.failureReason", "provider-empty");
        TraceStore.put("web.tavily.requestedCount", Math.max(0, requested));
        TraceStore.put("web.tavily.returnedCount", 0);
        TraceStore.put("web.tavily.afterFilterCount", 0);
        TraceStore.put("web.tavily.queryHash", SafeRedactor.hashValue(query));
        TraceStore.put("web.tavily.queryLength", query == null ? 0 : query.length());
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
