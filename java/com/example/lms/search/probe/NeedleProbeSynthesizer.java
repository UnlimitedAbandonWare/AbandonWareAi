package com.example.lms.search.probe;

import com.example.lms.rag.model.QueryDomain;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Synthesizes needle probe queries for 2-pass retrieval when evidence quality
 * is weak.
 */
@Component
public class NeedleProbeSynthesizer {

    /**
     * Synthesize high-precision probe queries based on evidence signals.
     *
     * @param userPrompt     the user's original query
     * @param domain         the classified query domain
     * @param signals        evidence quality signals from first-pass retrieval
     * @param alreadyPlanned queries already planned
     * @param locale         target locale
     * @return list of probe queries (max 1-2)
     */
    public List<String> synthesize(
            String userPrompt,
            QueryDomain domain,
            EvidenceSignals signals,
            List<String> alreadyPlanned,
            Locale locale) {
        if (userPrompt == null || userPrompt.isBlank()) {
            return List.of();
        }

        // Skip if signals indicate sufficient quality
        if (signals != null && signals.docCount() >= 3 && signals.authorityAvg() > 0.5) {
            return List.of();
        }

        // Generate a simple probe query based on the domain
        String domainHint = (domain != null) ? domain.name().toLowerCase(Locale.ROOT) : "";
        String probe = userPrompt.trim();

        // Add site hint for higher authority
        if (domainHint.contains("game") || domainHint.contains("subculture")) {
            probe = probe + " site:namu.wiki";
        } else if (domainHint.contains("tech") || domainHint.contains("study")) {
            probe = probe + " site:wikipedia.org";
        }

        // Avoid duplicates
        if (alreadyPlanned != null && alreadyPlanned.contains(probe)) {
            return List.of();
        }

        return List.of(probe);
    }
}
