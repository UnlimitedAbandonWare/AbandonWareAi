package com.example.lms.search.org;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;




/**
 * Builds organisation-aware search queries using the canonical name and official sites.
 *
 * <p>This augmenter generates additional search queries when an organisation has been
 * resolved.  It prepends {@code site:} prefixes for each official domain, includes
 * generic queries on the canonical name and, when the original query suggests a
 * recruitment or employment context, appends variations for 채용/연봉/면접.</p>
 */
@Component("searchOrgAwareQueryAugmenter")
@Slf4j
public class OrgAwareQueryAugmenter {
    /**
     * Construct organisation-specific queries based on the resolved organisation and
     * the original query.
     *
     * @param original the original (possibly normalised) user query
     * @param org the resolved organisation entry
     * @return a list of expanded queries tailored to the organisation
     */
    public List<String> build(String original, OrganizationResolver.Org org) {
        List<String> out = new ArrayList<>();
        if (org == null) return out;
        String canonical = org.canonical;
        if (canonical == null || canonical.isBlank()) return out;
        // Add site-scoped queries for each official site
        if (org.sites != null) {
            for (String site : org.sites) {
                if (site != null && !site.isBlank()) {
                    out.add("site:" + site + " " + canonical);
                }
            }
        }
        // Include the canonical name alone
        out.add(canonical);
        // Add recruitment-specific queries if the original contains hints
        if (original != null && original.matches(".*(취업|채용|연봉|면접).*")) {
            out.add(canonical + " 채용");
            out.add(canonical + " 연봉");
            out.add(canonical + " 면접");
        }
        // Always boost Naver Place and Blog for local company info
        out.add("site:place.naver.com " + canonical);
        out.add("site:blog.naver.com " + canonical);
        return out;
    }
}