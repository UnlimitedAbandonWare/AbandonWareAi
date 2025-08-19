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
        if (queryText == null) {
            return List.of(Source.WEB, Source.VECTOR, Source.KG);
        }
        String q = queryText.toLowerCase(java.util.Locale.ROOT);
        // very simple interrogative detection
        boolean hasWh = q.matches(".*\\b(who|what|where|when|why|how)\\b.*");
        int len = q.length();
        if (hasWh) {
            return List.of(Source.KG, Source.WEB, Source.VECTOR);
        }
        if (len > 100) {
            return List.of(Source.VECTOR, Source.WEB, Source.KG);
        }
        return List.of(Source.WEB, Source.VECTOR, Source.KG);
    }
}