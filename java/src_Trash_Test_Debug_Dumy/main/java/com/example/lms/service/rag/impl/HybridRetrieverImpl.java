package com.example.lms.service.rag.impl;
import org.springframework.beans.factory.annotation.Qualifier;
import com.example.lms.service.rag.HybridRetriever;
import com.example.lms.service.rag.handler.PreconditionCheckHandler;
import com.example.lms.service.rag.handler.SelfAskHandler;
import com.example.lms.service.rag.handler.AnalyzeHandler;
import com.example.lms.service.rag.handler.QueryRouteHandler;
import com.example.lms.service.rag.handler.WebSearchHandler;
import com.example.lms.service.rag.handler.VectorDbHandler;
import com.example.lms.service.rag.handler.EmptyResultRetryHandler;
import com.example.lms.service.rag.handler.EvidenceRepairHandler;
import com.example.lms.service.rag.handler.LocationAnswerHandler;
import com.example.lms.service.rag.handler.LocationAnswerHandlerAdapter;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Fixed-order hybrid retriever:
 *   SelfAsk → Analyze → Web → VectorDb
 */
@Component
public class HybridRetrieverImpl implements HybridRetriever {

    // Individual retrieval handlers comprising the chain.  Each handler implements
    // fail‑soft semantics and contributes results to the accumulator.
    private final PreconditionCheckHandler precondition;

    private final SelfAskHandler selfAsk;
    private final AnalyzeHandler analyze;
    private final QueryRouteHandler route;
    private final WebSearchHandler web;
    // Adaptive web search handler that will apply search gating logic and run the fan‑out search when needed.
    private final com.example.lms.integration.handlers.AdaptiveWebSearchHandler adaptive;
    private final VectorDbHandler vector;
    private final EvidenceRepairHandler repair;
    private final LocationAnswerHandlerAdapter locationAdapter;
    private final EmptyResultRetryHandler retry;

    @Autowired
    public HybridRetrieverImpl(
            SelfAskHandler selfAsk,
            @Qualifier("preconditionCheckHandlerImpl") PreconditionCheckHandler precondition,

            AnalyzeHandler analyze,
            QueryRouteHandler route,
            WebSearchHandler web,
            VectorDbHandler vector,
            EvidenceRepairHandler repair,
            LocationAnswerHandler locationHandler,
            // Inject the adaptive web search handler so that we can perform conditional web retrieval based on RAG and heuristics.
            com.example.lms.integration.handlers.AdaptiveWebSearchHandler adaptive
    ) {
        this.precondition = Objects.requireNonNull(precondition);

        this.selfAsk = Objects.requireNonNull(selfAsk);
        this.analyze = Objects.requireNonNull(analyze);
        this.route = Objects.requireNonNull(route);
        this.web = Objects.requireNonNull(web);
        this.vector = Objects.requireNonNull(vector);
        this.repair = Objects.requireNonNull(repair);
        this.adaptive = Objects.requireNonNull(adaptive);
        this.locationAdapter = new LocationAnswerHandlerAdapter(locationHandler);
        // Initialise the retry handler with the web stage.  Expect at least 3 web results and relax the query for retries.
        this.retry = new EmptyResultRetryHandler(web, 3, true);
    }

    @Override
    public List<Content> retrieveAll(List<String> queries, int topK) {
        List<Content> acc = new ArrayList<>();
        if (queries == null || queries.isEmpty()) return acc;
        for (String q : queries) {
            acc.addAll(retrieve(new Query(q)));
        }
        return acc;
    }

    @Override
    public List<Content> retrieveProgressive(String query, String sessionId, int topK, Map<String, Object> meta) {
        return retrieve(new Query(query));
    }

    @Override
    public List<Content> retrieve(Query q) {
        // Initialise an empty accumulator.  Each handler appends results
        // and propagates any internal exceptions without aborting the chain.
        List<Content> acc = new ArrayList<>();
        // 1) Precondition: abort if both web and vector search are disabled
        precondition.handle(q, acc);
        // 1.5) Self-Ask stage
        selfAsk.handle(q, acc);
        // 2) Self‑Ask: generate hints or decomposed queries

        // 3) Analyze: classify and augment the query
        analyze.handle(q, acc);
        // 4) Route: decide retrieval strategy and write metadata hints
        route.handle(q, acc);
        // 5) Adaptive web search stage.  Use the adaptive handler to decide whether to perform web retrieval
        // based on metadata hints (rag.on, searchMode, precision etc.) and to invoke the fanout/quorum service.
        try {
            adaptive.handle(q, acc);
        } catch (Exception ignore) {
            // Adaptive handler should fail-soft; ignore any exceptions here to keep the chain robust.
        }
        // Explicitly invoke the legacy WebSearchHandler after the adaptive stage.  This maintains the
        // fixed chain order SelfAsk → Analyze → Web → VectorDb and satisfies the requirement to call the
        // base web handler even when adaptive has already produced some results.  Errors are swallowed.
        try {
            web.handle(q, acc);
        } catch (Exception ignore) {
            // ignore web retrieval failures
        }
        // Perform an automatic retry on empty accumulator before querying vector search.
        retry.handle(q, acc);
        // 6) Vector DB search stage
        vector.handle(q, acc);
        // 7) Evidence repair: fix low quality web snippets
        repair.handle(q, acc);
        // 8) Location‑aware answer
        locationAdapter.handle(q, acc);
        return acc;
    }

    private static void safeAdd(List<Content> acc, List<Content> add) {
        if (add != null) acc.addAll(add);
    }
}
