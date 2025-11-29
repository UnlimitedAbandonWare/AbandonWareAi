package com.example.lms.service.rag.handler;

import com.example.lms.service.rag.WebSearchRetriever;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import java.util.List;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;



/**
 * Simple web retrieval handler used in the fixed retrieval chain.
 * <p>
 * This handler wraps a {@link WebSearchRetriever} and delegates the
 * retrieval of web snippets.  Failures are swallowed to allow the
 * chain to continue.  Returning {@code true} always propagates
 * execution to the next handler in the chain.
 */
@RequiredArgsConstructor
public class WebHandler extends AbstractRetrievalHandler {
    private static final Logger log = LoggerFactory.getLogger(WebHandler.class);

    /**
     * The underlying web search retriever.  Must not be null.
     */
    private final WebSearchRetriever retriever;

    @Override
    protected boolean doHandle(Query q, List<Content> acc) {
        try {
            acc.addAll(retriever.retrieve(q));
        } catch (Exception e) {
            // fail-soft: log the error and continue to the next handler
            log.warn("[Web] 실패 - 패스", e);
        }
        return true;
    }
}