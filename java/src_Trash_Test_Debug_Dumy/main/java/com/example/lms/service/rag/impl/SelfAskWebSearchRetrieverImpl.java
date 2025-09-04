package com.example.lms.service.rag.impl;

import com.example.lms.service.rag.SelfAskWebSearchRetriever;
import com.example.lms.service.rag.WebSearchRetriever;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Production implementation of {@link SelfAskWebSearchRetriever} that delegates to
 * {@link WebSearchRetriever}.  A minimal rewrite of the query is performed to
 * remove trailing punctuation before passing it to the underlying web retriever.
 * Additional metadata is ignored.
 */
@Component
@Primary
public class SelfAskWebSearchRetrieverImpl implements SelfAskWebSearchRetriever {

    private final WebSearchRetriever web;

    @Autowired
    public SelfAskWebSearchRetrieverImpl(WebSearchRetriever web) {
        this.web = Objects.requireNonNull(web, "web");
    }

    @Override
    public List<Content> askWeb(String question, int topK, Map<String, Object> meta) {
        String q = (question == null) ? "" : question.trim();
        // Remove trailing '?' or '!' characters to mimic self‑ask rewriting
        while (!q.isEmpty() && (q.endsWith("?") || q.endsWith("!"))) {
            q = q.substring(0, q.length() - 1).trim();
        }
        try {
            return web.retrieve(new Query(q));
        } catch (Exception e) {
            // On failure, return an empty list
            return Collections.emptyList();
        }
    }
}
