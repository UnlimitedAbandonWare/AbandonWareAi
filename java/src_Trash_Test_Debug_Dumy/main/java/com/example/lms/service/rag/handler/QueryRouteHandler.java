package com.example.lms.service.rag.handler;

import com.example.lms.service.rag.model.RouteDecision;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Handler that decides which retrieval routes to take based on a query.
 *
 * <p>This minimal implementation always chooses the {@link RouteDecision#HYBRID}
 * strategy and does not alter the query or accumulator.  It extends
 * {@link AbstractRetrievalHandler} to fit into the hybrid retriever chain
 * without throwing exceptions.  A more sophisticated implementation
 * could inspect the query contents or metadata to choose between web,
 * vector or hybrid retrieval and record the decision on the accumulator.
 */
@Component
public class QueryRouteHandler extends AbstractRetrievalHandler {

    private static final Logger log = LoggerFactory.getLogger(QueryRouteHandler.class);

    @Override
    protected boolean doHandle(Query query, List<Content> accumulator) {
        // Choose HYBRID route by default.  Production implementations
        // may examine the query and populate metadata.
        log.debug("[QueryRouteHandler] defaulting to HYBRID route for query: {}", query.text());
        // no state changes, simply continue chain
        return true;
    }
}