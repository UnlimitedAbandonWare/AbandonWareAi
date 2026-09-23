package com.example.lms.service.rag.handler;

import com.example.lms.gptsearch.decision.SearchDecision;
import com.example.lms.gptsearch.decision.SearchDecisionService;
import com.example.lms.gptsearch.dto.SearchMode;
import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.QueryUtils;
import com.example.lms.trace.SafeRedactor;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.HashMap;
import java.util.List;
import java.util.Map;




/**
 * Handler responsible for dynamically routing a query based on a lightweight
 * search decision.  It inspects the query text and uses the injected
 * {@link SearchDecisionService} to determine an appropriate route (e.g.
 * web, RAG, mixed) and associated parameters such as depth and top-K.  The
 * resulting decision is written into the query's metadata, when possible,
 * so that downstream components may adjust their behaviour accordingly.
 * This handler does not perform any retrieval by itself; it simply
 * augments the query and lets the rest of the chain continue.
 *
 * <p>Beans of this type are only created when the property
 * {@code abandonware.chain.enabled} is {@code true} or not set.  When the
 * property is explicitly set to {@code false} the old retrieval pipeline
 * remains active.</p>
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "abandonware.chain.enabled", havingValue = "true", matchIfMissing = true)
public class QueryRouteHandler implements RetrievalHandler {

    private final SearchDecisionService decisionService;

    @Override
    public void handle(Query query, List<Content> accumulator) {
        if (query == null) {
            return;
        }
        // Defensive: ensure a text value exists
        String text = query.text() != null ? query.text() : "";
        try {
            // If routing hints already exist in the metadata, respect them and do not override.
            boolean hasHints = false;
            try {
                Map<String, Object> mm = QueryUtils.metadata(query);
                hasHints = mm.containsKey("precision") || mm.containsKey("depth") || mm.containsKey("route");
            } catch (Exception metadataReadEx) {
                TraceStore.put("retrieval.handler.suppressed.route.metadataRead", true);
                RetrievalHandlerTraceSuppressions.trace("route.metadataRead", metadataReadEx);
                traceRouteFailure("metadata_read", text, metadataReadEx);
            }
            if (hasHints) {
                // Do not override existing routing hints
                return;
            }
            // Invoke the decision engine with default AUTO mode and no provider hints
            SearchDecision decision = decisionService.decide(text, SearchMode.AUTO, null, null);
            // Build a metadata map capturing useful routing hints
            Map<String, Object> meta = new HashMap<>();
            // route: which high level retrieval strategy to use
            meta.put("route", decision.shouldSearch() ? "WEB" : "NONE");
            // depth: whether the search should be light or deep
            meta.put("depth", decision.depth() != null ? decision.depth().name() : "LIGHT");
            // topK: how many documents to request from downstream components
            meta.put("topK", decision.topK());
            // Provide sensible defaults for additional knobs expected by the hybrid retriever.
            meta.put("minRelatedness", 0.4d);
            meta.put("fusionMode", "rrf");
            // Store routing metadata in the repository-owned sidecar. LangChain4j
            // query metadata is immutable and does not carry arbitrary route hints.
            try {
                QueryUtils.mergeMetadata(query, meta);
            } catch (Exception metadataWriteEx) {
                TraceStore.put("retrieval.handler.suppressed.route.metadataWrite", true);
                RetrievalHandlerTraceSuppressions.trace("route.metadataWrite", metadataWriteEx);
                traceRouteFailure("metadata_write", text, metadataWriteEx);
            }
        } catch (Exception decisionEx) {
            TraceStore.put("retrieval.handler.suppressed.route.decision", true);
            RetrievalHandlerTraceSuppressions.trace("route.decision", decisionEx);
            traceRouteFailure("decision", text, decisionEx);
        }
        // This handler does not add any content; downstream handlers will
        // execute automatically via the RetrievalHandler.linkWith mechanism.
    }

    private static void traceRouteFailure(String stage, String text, Throwable error) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String safeReason = error == null
                ? "unknown"
                : "route-failure";
        TraceStore.inc("retrieval.route." + safeStage + ".failed");
        TraceStore.putIfAbsent("retrieval.route.failed", true);
        TraceStore.putIfAbsent("retrieval.route.failureStage", safeStage);
        TraceStore.putIfAbsent("retrieval.route.failureReason", safeReason);
        TraceStore.putIfAbsent("retrieval.route.queryHash", SafeRedactor.hash12(text));
        TraceStore.putIfAbsent("retrieval.route.queryLength", text == null ? 0 : text.length());
    }
}
