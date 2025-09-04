package com.example.lms.service.rag.handler;

import com.example.lms.service.rag.handler.WebSearchHandler;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * A retrieval handler that performs an additional web search when the current
 * accumulator does not contain a sufficient number of hits. This handler is
 * intended to be inserted between the primary web search stage and the vector
 * search stage to improve resiliency against zero-hit scenarios. When the
 * accumulator has fewer than {@code minHits} items it issues a second web
 * query. If {@code relax} is {@code true} the query text is expanded with
 * additional Korean language or site hints to broaden the search scope. The
 * handler delegates to the provided {@link WebSearchHandler} and does not
 * modify the overall chain flow; it always returns {@code true} to allow
 * subsequent handlers to execute.
 */
@Slf4j
@RequiredArgsConstructor
public class EmptyResultRetryHandler extends AbstractRetrievalHandler {

    /**
     * The web search handler used to execute a second search when the
     * accumulator is below the required hit count. The handler must not be
     * {@code null} and is expected to populate the provided accumulator when
     * invoked. The {@link WebSearchHandler} instance is reused from the
     * surrounding retrieval chain.
     */
    private final WebSearchHandler web;

    /**
     * The minimum number of hits expected after the primary web search. If
     * the accumulator contains at least this number of items the retry is
     * skipped.
     */
    private final int minHits;

    /**
     * When {@code true} the handler broadens the retry query by appending
     * Korean language and site hints. When {@code false} the original query
     * text is used verbatim.
     */
    private final boolean relax;

    @Override
    protected boolean doHandle(Query q, List<Content> acc) {
        int currentHits = (acc == null ? 0 : acc.size());
        if (currentHits >= minHits) {
            // Enough results; no retry needed
            return true;
        }
        try {
            log.info("[Retry] 빈 결과 감지 → 웹 재시도 (relax={})", relax);
            String text = (q != null ? q.text() : "");
            if (text == null) {
                text = "";
            }
            // Append Korean language hints when relax mode is enabled
            if (relax) {
                text = text + " (ko OR KR) OR site:kr";
            }
            // Create a new query preserving any existing metadata
            Query retryQuery = new Query(text, q != null ? q.metadata() : null);
            // Delegate to the web search handler; this call will populate the accumulator
            web.handle(retryQuery, acc);
        } catch (Exception e) {
            // Fail softly: log and allow the chain to continue
            log.warn("[Retry] 재시도 실패 – 패스", e);
        }
        return true;
    }
}