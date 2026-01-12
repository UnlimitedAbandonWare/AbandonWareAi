package com.example.lms.compare.common;

import java.util.List;
import java.util.Map;



/**
 * Result of a comparative calculation. The ranking list contains
 * scored representations of each candidate entity along with source
 * links that can be used for attribution. The breakdown map
 * provides optional component-wise scores or other metadata that
 * downstream explainers may wish to surface.
 */
public record CompareResult(
        List<ScoredEntity> ranking,
        Map<String, Object> breakdown
) {
    /**
     * Simple DTO representing an entity and its associated score. A list of
     * source links may be attached for provenance. The entity id may be
     * null or coincide with the entity name depending on the implementation.
     */
    public static record ScoredEntity(
            String id,
            String name,
            double score,
            List<String> sourceLinks
    ) {
    }
}