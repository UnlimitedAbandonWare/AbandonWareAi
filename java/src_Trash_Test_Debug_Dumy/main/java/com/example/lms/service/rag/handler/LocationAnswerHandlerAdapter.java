package com.example.lms.service.rag.handler;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import java.util.List;

/**
 * Adapter that wraps the {@link LocationAnswerHandler} so it can participate in the
 * standard retrieval responsibility chain.  The original LocationAnswerHandler
 * implements the simple {@link RetrievalHandler} interface rather than
 * extending {@link AbstractRetrievalHandler}, which prevents it from being
 * linked directly.  This adapter delegates to the underlying handler and
 * always returns {@code true} so that subsequent handlers execute.
 */
public class LocationAnswerHandlerAdapter extends AbstractRetrievalHandler {

    private final LocationAnswerHandler delegate;

    public LocationAnswerHandlerAdapter(LocationAnswerHandler delegate) {
        this.delegate = delegate;
    }

    @Override
    protected boolean doHandle(Query query, List<Content> accumulator) {
        if (delegate != null) {
            try {
                delegate.handle(query, accumulator);
            } catch (Exception ignore) {
                // fail‑soft: ignore any location service errors
            }
        }
        return true;
    }
}