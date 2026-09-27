package com.example.lms.dto.learning;

import java.util.List;
import java.util.Objects;

public record ClaimVerdict(String claim, String verdict, List<String> supportsUrls) {
    public ClaimVerdict {
        Objects.requireNonNull(claim, "claim must not be null");
        Objects.requireNonNull(verdict, "verdict must not be null");
        supportsUrls = supportsUrls == null ? List.of() : List.copyOf(supportsUrls);
    }
}
