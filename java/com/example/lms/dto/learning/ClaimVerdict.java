package com.example.lms.dto.learning;

import java.util.List;
import java.util.Objects;



/**
 * Represents the verdict of a single claim extracted from an answer. The verdict
 * indicates whether the claim is SUPPORTED, UNSUPPORTED or CONTRADICTED by the evidence.
 */
public record ClaimVerdict(
        String claim,
        String verdict,
        List<String> supportsUrls
) {
    public ClaimVerdict {
        Objects.requireNonNull(claim, "claim must not be null");
        Objects.requireNonNull(verdict, "verdict must not be null");
        supportsUrls = supportsUrls == null ? List.of() : List.copyOf(supportsUrls);
    }
}