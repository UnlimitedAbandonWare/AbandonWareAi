package com.example.lms.service.rag.rerank;

import lombok.extern.slf4j.Slf4j;
import com.example.lms.search.org.OrganizationResolver;
import org.springframework.stereotype.Component;
import java.util.Optional;





/**
 * Scores web documents with geographic and organisation-specific boosts.
 */
@Component("rerankGeoOrgBoostScorer")
@Slf4j
public class GeoOrgBoostScorer {

    /**
     * @param url    the document URL
     * @param orgOpt optional organisation context
     * @return the boost score (positive values increase rank)
     */
    public double boost(String url, Optional<OrganizationResolver.Org> orgOpt) {
        double score = 0.0;
        if (url == null || url.isBlank()) return score;

        String lower = url.toLowerCase();

        if (orgOpt != null && orgOpt.isPresent()) {
            for (String site : orgOpt.get().sites()) {
                if (site != null && !site.isBlank() && lower.contains(site.toLowerCase())) {
                    score += 1.0;
                    break;
                }
            }
        }
        // Korean TLD
        if (lower.endsWith(".kr")) score += 0.3;

        // gov/edu/trusted naver
        if (lower.contains(".go.kr") || lower.contains(".ac.kr") || lower.contains(".or.kr")
                || lower.contains(".co.kr") || lower.contains("place.naver.com") || lower.contains("blog.naver.com")) {
            score += 0.4;
        }
        return score;
    }
}