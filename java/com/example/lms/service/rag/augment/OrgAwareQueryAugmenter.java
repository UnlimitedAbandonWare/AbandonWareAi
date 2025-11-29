package com.example.lms.service.rag.augment;

import lombok.extern.slf4j.Slf4j;
import com.example.lms.service.rag.catalog.OrganizationResolver;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;





/**
 * Constructs additional search queries based on the presence of an organisation
 * in the user query.  When a resolved organisation is available the
 * augmenter produces combinations of the canonical name, original query and
 * region hints to improve recall on local search engines.
 */
@Component
@Slf4j
public class OrgAwareQueryAugmenter {

    /**
     * Generate a list of augmented queries based on the resolved organisation
     * and optional session region.
     *
     * @param originalQuery the original user query
     * @param sessionRegion a region hint from the user session (may be {@code null})
     * @param resolvedOrg   the resolved organisation (may be {@code null})
     * @param strongBias    if {@code true}, favour queries anchored on the
     *                      canonical organisation name first
     * @return a list of additional queries (duplicates removed), not including
     *         morphological variants which are handled separately
     */
    public List<String> augment(String originalQuery,
                                String sessionRegion,
                                OrganizationResolver.ResolvedOrg resolvedOrg,
                                boolean strongBias) {
        Set<String> out = new LinkedHashSet<>();
        if (originalQuery != null && !originalQuery.isBlank()) {
            out.add(originalQuery.trim());
        }
        if (resolvedOrg == null) {
            if (sessionRegion != null && !sessionRegion.isBlank()) {
                // Only region hint available
                out.add(originalQuery + " " + sessionRegion);
            }
            return new ArrayList<>(out);
        }
        String canonical = resolvedOrg.canonical();
        List<String> regions = resolvedOrg.regions();
        // Basic expansions: canonical on its own and appended to the original
        if (StringUtils.hasText(canonical)) {
            if (strongBias) {
                out.add(canonical);
                out.add(canonical + " " + originalQuery);
            } else {
                out.add(originalQuery + " " + canonical);
                out.add(canonical);
            }
        }
        // Region-specific expansions
        if (regions != null) {
            for (String r : regions) {
                if (!StringUtils.hasText(r)) continue;
                if (StringUtils.hasText(canonical)) {
                    out.add(canonical + " " + r);
                }
                if (StringUtils.hasText(originalQuery)) {
                    out.add(originalQuery + " " + r);
                }
            }
        }
        // Session region hint
        if (StringUtils.hasText(sessionRegion)) {
            if (StringUtils.hasText(canonical)) {
                out.add(canonical + " " + sessionRegion);
            }
            if (StringUtils.hasText(originalQuery)) {
                out.add(originalQuery + " " + sessionRegion);
            }
        }
        return new ArrayList<>(out);
    }
}