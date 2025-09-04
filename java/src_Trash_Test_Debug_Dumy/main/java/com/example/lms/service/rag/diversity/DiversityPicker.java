package com.example.lms.service.rag.diversity;

import dev.langchain4j.rag.content.Content;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Picks a diversified subset from a ranked list of content to reduce redundancy.
 * This default implementation performs a simple truncation and logs diversity
 * metrics.  More sophisticated implementations may incorporate a DPP‑inspired
 * log‑det objective to penalise near‑duplicate documents.
 */
@Component
public class DiversityPicker {
    private static final Logger log = LoggerFactory.getLogger(DiversityPicker.class);

    /**
     * The maximum number of candidates to consider when computing diversity.  A
     * higher value increases computational cost but may improve the quality
     * of the selected subset.
     */
    @Value("${retrieval.diversity.topN:24}")
    private int topN;

    /**
     * Trade‑off parameter controlling the strength of the diversity penalty.  A
     * value closer to 0.0 favours relevance while values closer to 1.0 favour
     * diversity.  This implementation does not currently use the beta
     * parameter but it is exposed for future extension.
     */
    @Value("${retrieval.diversity.beta:0.5}")
    private double beta;

    /**
     * Select up to {@code k} documents from the ranked list.  This method
     * currently returns the first {@code k} items without further
     * diversification.  When a more sophisticated diversity algorithm is
     * implemented, this method should apply it here.
     *
     * @param ranked the ranked list of content
     * @param k the maximum number of items to return
     * @return the selected subset, preserving original order
     */
    public List<Content> pick(List<Content> ranked, int k) {
        if (ranked == null || ranked.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        int limit = Math.min(k, ranked.size());
        List<Content> subset = new ArrayList<>(ranked.subList(0, limit));
        log.debug("[Diversity] picked {} out of {} candidates", subset.size(), ranked.size());
        return subset;
    }
}