package com.example.lms.service.rag.fusion;

import com.example.lms.service.rag.catalog.OrganizationResolver;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;




/**
 * Additive boosts for web snippets based on org mention and local domains.
 */
@Component("fusionGeoOrgBoostScorer")
public class GeoOrgBoostScorer {
    private static final Logger log = LoggerFactory.getLogger(GeoOrgBoostScorer.class);

    /**
     * @param snippet     raw web snippet (may contain markup)
     * @param resolvedOrg resolved organisation, may be null
     * @return non-negative additive boost
     */
    public double boost(String snippet, OrganizationResolver.ResolvedOrg resolvedOrg) {
        if (snippet == null || snippet.isBlank()) return 0.0;

        double score = 0.0;
        String lower = snippet.toLowerCase();

        if (resolvedOrg != null && resolvedOrg.canonical() != null) {
            String canon = resolvedOrg.canonical().toLowerCase();
            if (!canon.isBlank() && lower.contains(canon)) score += 1.0;
        }
        if (lower.contains(".kr") || lower.contains("place.naver.com")) score += 0.5;

        return score;
    }
}