package com.example.lms.service.rag.handler;

import com.example.lms.integration.handlers.AdaptiveWebSearchHandler;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;

import java.util.List;

/**
 * Chain-safe adapter for the shared adaptive web-search handler.
 *
 * <p>The adaptive handler is a singleton Spring bean and should not be wired
 * into the fixed chain with {@link #linkWith(RetrievalHandler)} directly; doing
 * so would mutate its next handler and affect other call paths. This wrapper
 * makes the fixed chain invoke the existing adaptive stage without changing the
 * delegate's own chain state.</p>
 */
public final class AdaptiveWebStageHandler extends AbstractRetrievalHandler {

    private final AdaptiveWebSearchHandler delegate;

    public AdaptiveWebStageHandler(AdaptiveWebSearchHandler delegate) {
        this.delegate = delegate;
    }

    @Override
    protected boolean doHandle(Query query, List<Content> accumulator) {
        if (delegate != null) {
            delegate.handle(query, accumulator);
        }
        return true;
    }
}
