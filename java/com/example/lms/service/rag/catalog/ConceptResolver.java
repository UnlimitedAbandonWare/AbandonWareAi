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
 * Attempts to normalise user queries referencing general concepts to a
 * canonical form.  The resolver uses a simple token-based similarity
 * measure against known aliases and canonical names loaded from the
 * concept catalogue.
 */
@Component
@ConditionalOnProperty(name = "rag.concept-catalog.enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class ConceptResolver {

    private final ConceptCatalogLoader loader;
    private final double minScore;

    public ConceptResolver(ConceptCatalogLoader loader,
                           @Value("${rag.concept-catalog.min-score:0.5}") double minScore) {
        this.loader = loader;
        this.minScore = minScore;
    }

    /**
     * Encapsulates a resolved concept.  Contains the canonical name and
     * associated sites along with the confidence of the match.
     */
    public record ResolvedConcept(String canonical, List<String> sites, double confidence) {}

    /**
     * Resolve the given query to a concept, if possible.
     *
     * @param query the user query
     * @return an optional resolved concept when a confident match is found
     */
    public Optional<ResolvedConcept> resolve(String query) {
        if (query == null || query.isBlank() || loader.getEntries().isEmpty()) {
            return Optional.empty();
        }
        String q = query.toLowerCase(Locale.ROOT);
        Set<String> qTokens = tokenize(q);
        ResolvedConcept best = null;
        double bestSim = 0.0;
        for (ConceptCatalogLoader.ConceptEntry e : loader.getEntries()) {
            if (e.canonical == null) continue;
            List<String> names = new ArrayList<>();
            names.add(e.canonical);
            if (e.aliases != null) names.addAll(e.aliases);
            for (String name : names) {
                if (!StringUtils.hasText(name)) continue;
                double sim = similarity(qTokens, tokenize(name.toLowerCase(Locale.ROOT)), q, name.toLowerCase(Locale.ROOT));
                if (sim > bestSim) {
                    bestSim = sim;
                    best = new ResolvedConcept(e.canonical,
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