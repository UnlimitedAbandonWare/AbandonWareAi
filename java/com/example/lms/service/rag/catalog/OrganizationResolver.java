package com.example.lms.service.rag.catalog;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;





/**
 * Attempts to normalise user queries referencing organisations to a
 * canonical form.  The resolver uses a simple token-based similarity
 * measure against known aliases and canonical names loaded from the
 * organisation catalogue.
 */
@Component("catalogOrganizationResolver")
@ConditionalOnProperty(name = "rag.org-catalog.enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class OrganizationResolver {

    private final OrgCatalogLoader loader;
    private final double minScore;

    /**
     * Construct a resolver using the supplied loader.  The minimum
     * similarity score required to emit a match can be configured via
     * the {@code rag.org-catalog.min-score} property; defaults to 0.5.
     */
    public OrganizationResolver(OrgCatalogLoader loader,
                                @Value("${rag.org-catalog.min-score:0.5}") double minScore) {
        this.loader = loader;
        this.minScore = minScore;
    }

    /**
     * Encapsulates a resolved organisation.  Contains the canonical name,
     * associated regions and sites along with the confidence of the match.
     */
    public record ResolvedOrg(String canonical, List<String> regions, List<String> sites, double confidence) {}

    /**
     * Resolve the given query to an organisation, if possible.
     *
     * @param query the user query
     * @return an optional resolved organisation when a confident match is found
     */
    public Optional<ResolvedOrg> resolve(String query) {
        if (query == null || query.isBlank() || loader.getEntries().isEmpty()) {
            return Optional.empty();
        }
        String q = query.toLowerCase(Locale.ROOT);
        // Tokenise the query on whitespace and punctuation
        Set<String> qTokens = tokenize(q);
        ResolvedOrg best = null;
        double bestSim = 0.0;
        for (OrgCatalogLoader.OrgEntry e : loader.getEntries()) {
            if (e.canonical == null) continue;
            // Compare against the canonical name and all aliases
            List<String> names = new ArrayList<>();
            names.add(e.canonical);
            if (e.aliases != null) names.addAll(e.aliases);
            for (String name : names) {
                if (!StringUtils.hasText(name)) continue;
                double sim = similarity(qTokens, tokenize(name.toLowerCase(Locale.ROOT)), q, name.toLowerCase(Locale.ROOT));
                if (sim > bestSim) {
                    bestSim = sim;
                    best = new ResolvedOrg(e.canonical,
                            (e.regions != null) ? List.copyOf(e.regions) : Collections.emptyList(),
                            (e.sites != null) ? List.copyOf(e.sites) : Collections.emptyList(),
                            sim);
                }
            }
        }
        if (best != null && bestSim >= minScore) {
            return Optional.of(best);
        }
        return Optional.empty();
    }

    /**
     * Compute a similarity score between the query and an alias.  This
     * implementation uses a hybrid of containment and Jaccard similarity.
     * If one string contains the other the score is promoted to 1.0.
     */
    private double similarity(Set<String> qTokens, Set<String> aTokens,
                              String qLower, String aLower) {
        if (qLower.contains(aLower) || aLower.contains(qLower)) {
            return 1.0;
        }
        if (qTokens.isEmpty() || aTokens.isEmpty()) {
            return 0.0;
        }
        Set<String> intersection = qTokens.stream().filter(aTokens::contains).collect(Collectors.toSet());
        Set<String> union = new java.util.HashSet<>(qTokens);
        union.addAll(aTokens);
        return union.isEmpty() ? 0.0 : ((double) intersection.size()) / union.size();
    }

    private Set<String> tokenize(String s) {
        if (s == null) return Collections.emptySet();
        String cleaned = s.replaceAll("[^\\p{IsHangul}\\p{L}\\p{Nd}]+", " ").trim();
        if (cleaned.isBlank()) return Collections.emptySet();
        return java.util.Arrays.stream(cleaned.split("\\s+"))
                .filter(w -> !w.isBlank())
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }
}