package com.example.lms.gptsearch.decision;

import com.example.lms.gptsearch.dto.SearchMode;
import com.example.lms.gptsearch.web.ProviderId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Simple decision engine for determining if and how to execute a web search.
 * A more sophisticated implementation would incorporate cognitive state
 * analysis, LLM self‑reflection and risk estimation.  For the purposes
 * of this upgrade, we implement a deterministic policy based on the
 * requested mode and basic heuristics on the query string.
 */
public class SearchDecisionService {

    /**
     * Determine whether a web search should be executed.  When the mode is
     * OFF, searching is skipped entirely.  FORCE_LIGHT and FORCE_DEEP
     * unconditionally return a decision to search with the corresponding
     * depth.  In AUTO mode, a naive heuristic is used: questions ending
     * with a question mark or containing Korean question particles
     * (“무엇”, “어떻게”, etc.) trigger a LIGHT search; comparative or
     * factual keywords ("vs", "비교", "뭐가 더") trigger a DEEP search.  All
     * other queries default to no search.  This heuristic is intentionally
     * conservative; real deployments should integrate a proper cognitive
     * state analyser and LLM tool call inspection.
     *
     * @param query The user’s natural language request (may be null)
     * @param mode The requested search mode
     * @param providerIds String identifiers of preferred providers
     * @param topK Desired number of results per provider (fallback to 5 when null)
     * @return A search decision record
     */
    public SearchDecision decide(String query, SearchMode mode, List<String> providerIds, Integer topK) {
        if (mode == null) mode = SearchMode.AUTO;
        int k = (topK == null || topK <= 0) ? 5 : topK;
        // Resolve providers
        List<ProviderId> providers = new ArrayList<>();
        if (providerIds != null && !providerIds.isEmpty()) {
            for (String id : providerIds) {
                try {
                    providers.add(ProviderId.valueOf(id.trim().toUpperCase(Locale.ROOT)));
                } catch (Exception ignore) {
                    // skip unknown providers
                }
            }
        }
        if (providers.isEmpty()) {
            // Default provider order when none specified
            providers.add(ProviderId.BING);
            providers.add(ProviderId.TAVILY);
        }
        switch (mode) {
            case OFF:
                return new SearchDecision(false, SearchDecision.Depth.LIGHT, providers, k, "Search disabled by user");
            case FORCE_LIGHT:
                return new SearchDecision(true, SearchDecision.Depth.LIGHT, providers, k, "Forced light search");
            case FORCE_DEEP:
                return new SearchDecision(true, SearchDecision.Depth.DEEP, providers, k, "Forced deep search");
            case AUTO:
            default:
                String q = (query == null) ? "" : query.toLowerCase(Locale.ROOT);
                // Simple heuristics for demonstration
                boolean endsWithQuestion = q.trim().endsWith("?");
                boolean containsComparative = q.contains(" vs ") || q.contains("비교") || q.contains("뭐가 더");
                if (containsComparative) {
                    return new SearchDecision(true, SearchDecision.Depth.DEEP, providers, k, "Comparative query triggers deep search");
                } else if (endsWithQuestion || q.contains("무엇") || q.contains("어떻게") || q.contains("왜")) {
                    return new SearchDecision(true, SearchDecision.Depth.LIGHT, providers, k, "Question detected triggers light search");
                } else {
                    return new SearchDecision(false, SearchDecision.Depth.LIGHT, providers, k, "No search needed (heuristic)");
                }
        }
    }
}