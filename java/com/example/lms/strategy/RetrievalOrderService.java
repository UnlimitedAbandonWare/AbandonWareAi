package com.example.lms.strategy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Determines the order in which retrieval sources (Web, Vector, Knowledge Graph)
 * should be queried.  This simple heuristic implementation inspects the query
 * length and the presence of basic interrogative words to choose an order.
 *
 * <p>
 * - If the query contains question words (who/what/where/when/why/how), prefer KG first.
 * - If the query is long (>100 characters), vector search is prioritized.
 * - Otherwise, use the default Web→Vector→KG order.
 */
@Service
@Slf4j
public class RetrievalOrderService {

    public enum Source { WEB, VECTOR, KG }

    /**
     * Decide the retrieval order for a given query text.
     *
     * @param queryText the raw query string
     * @return a list of sources in the order they should be invoked
     */
    public List<Source> decideOrder(String queryText) {
        // Enforce a fixed retrieval order of Web → Vector → Knowledge Graph.  The heuristics
        // previously used to reorder based on interrogative words or query length
        // have been removed to ensure deterministic execution.  Additional stages
        // such as Self‑Ask and Analyze are executed prior to this method.
        return List.of(Source.WEB, Source.VECTOR, Source.KG);
    }
}