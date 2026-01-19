package com.example.lms.service.verbosity;

import java.util.List;



public record VerbosityProfile(
        String hint,                // brief|standard|deep|ultra
        int    minWordCount,
        int    targetTokenBudgetOut,
        String audience,            // dev|pm|enduser
        String citationStyle,       // e.g., "inline"
        List<String> sections
) {
    public boolean deepish() {
        return "deep".equalsIgnoreCase(hint) || "ultra".equalsIgnoreCase(hint);
    }
}