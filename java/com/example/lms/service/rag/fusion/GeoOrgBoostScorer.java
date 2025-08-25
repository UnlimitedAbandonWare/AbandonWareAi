package com.example.lms.service.rag.fusion;

import com.example.lms.service.rag.catalog.OrganizationResolver;
import org.springframework.stereotype.Component;

/**
 * A simple scorer that assigns additive boosts to web snippets when they
 * originate from domestic (.kr) or Naver Place domains or when they
 * explicitly mention the canonical name of a resolved organisation.
 *
 * <p>During retrieval the {@link com.example.lms.service.rag.AnalyzeWebSearchRetriever}
 * invokes this scorer to bias results towards official or locally relevant
 * sources.  The design intentionally avoids coupling to the broader
 * scoring pipeline by exposing only a static boost value which can be
 * combined with other ranking signals downstream.</p>
 */
@Component
public class GeoOrgBoostScorer {

    /**
     * Compute an additive boost for a given snippet.  Currently the boost is
     * calculated as follows:
     *
     * <ul>
     *     <li>Add {@code 1.0} if the snippet text contains the canonical
     *     organisation name (case‑insensitive).</li>
     *     <li>Add {@code 0.5} if the snippet contains a Korean country
     *     domain (e.g. {@code .kr}) or originates from Naver Place.</li>
     * </ul>
     *
     * @param snippet     the raw web snippet (may contain markup)
     * @param resolvedOrg the resolved organisation, may be {@code null}
     * @return an additive boost value (non‑negative)
     */
    public double boost(String snippet, OrganizationResolver.ResolvedOrg resolvedOrg) {
        if (snippet == null || snippet.isBlank()) {
            return 0.0;
        }
        double score = 0.0;
        String lower = snippet.toLowerCase();
        // boost when snippet mentions the canonical organisation name
        if (resolvedOrg != null && resolvedOrg.canonical() != null) {
            String canon = resolvedOrg.canonical().toLowerCase();
            if (!canon.isBlank() && lower.contains(canon)) {
                score += 1.0;
            }
        }
        // boost when snippet originates from local domains (.kr or place.naver.com)
        if (lower.contains(".kr") || lower.contains("place.naver.com")) {
            score += 0.5;
        }
        return score;
    }
}