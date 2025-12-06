package com.example.lms.service.rag.fusion;

import lombok.extern.slf4j.Slf4j;
import com.example.lms.service.rag.catalog.OrganizationResolver;
import org.springframework.stereotype.Component;




/**
 * Additive boosts for web snippets based on org mention and local domains.
 */
@Component("fusionGeoOrgBoostScorer")
@Slf4j
public class GeoOrgBoostScorer {

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