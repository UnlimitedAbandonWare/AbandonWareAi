package com.example.lms.search.probe;

import com.example.lms.service.rag.auth.AuthorityScorer;
import dev.langchain4j.rag.content.Content;

import java.util.*;

/**
 * Evidence quality signals for retrieval evaluation and needle probe
 * triggering.
 * Computes metrics like authority average, coverage, duplicate ratio, and
 * document count.
 */
public record EvidenceSignals(
        int docCount,
        double authorityAvg,
        double coverageScore,
        double duplicateRatio) {
    /**
     * Creates an empty EvidenceSignals with default values.
     */
    public static EvidenceSignals empty() {
        return new EvidenceSignals(0, 0.0, 0.0, 1.0);
    }

    /**
     * Compute evidence signals from query and retrieved content.
     *
     * @param query           the user query
     * @param contents        retrieved contents
     * @param authorityScorer optional authority scorer for URL weighting
     * @return computed evidence signals
     */
    public static EvidenceSignals compute(String query, List<Content> contents, AuthorityScorer authorityScorer) {
        if (contents == null || contents.isEmpty()) {
            return empty();
        }

        int docCount = contents.size();
        Set<String> domains = new HashSet<>();
        double authoritySum = 0.0;
        int authorityCount = 0;

        for (Content c : contents) {
            if (c == null || c.textSegment() == null)
                continue;
            try {
                var meta = c.textSegment().metadata();
                if (meta != null) {
                    String url = meta.getString("url");
                    if (url == null || url.isBlank()) {
                        url = meta.getString("source");
                    }
                    if (url != null && !url.isBlank()) {
                        String domain = extractDomain(url);
                        if (domain != null) {
                            domains.add(domain);
                        }
                        if (authorityScorer != null) {
                            try {
                                authoritySum += authorityScorer.weightFor(url);
                                authorityCount++;
                            } catch (Exception ignore) {
                            }
                        }
                    }
                }
            } catch (Exception ignore) {
            }
        }

        double authorityAvg = (authorityCount > 0) ? (authoritySum / authorityCount) : 0.0;
        double duplicateRatio = domains.isEmpty() ? 1.0 : (1.0 - ((double) domains.size() / docCount));

        // Coverage: simple heuristic - ratio of non-empty docs
        int nonEmpty = 0;
        for (Content c : contents) {
            if (c != null && c.textSegment() != null) {
                String text = c.textSegment().text();
                if (text != null && !text.isBlank()) {
                    nonEmpty++;
                }
            }
        }
        double coverageScore = (docCount > 0) ? ((double) nonEmpty / docCount) : 0.0;

        return new EvidenceSignals(docCount, authorityAvg, coverageScore, duplicateRatio);
    }

    private static String extractDomain(String url) {
        if (url == null || url.isBlank())
            return null;
        try {
            String host = java.net.URI.create(url).getHost();
            if (host != null) {
                host = host.toLowerCase(Locale.ROOT);
                if (host.startsWith("www.")) {
                    host = host.substring(4);
                }
                return host;
            }
        } catch (Exception ignore) {
        }
        return null;
    }
}
