package com.example.lms.plan;

import java.util.List;
import java.util.Map;

public record PlanHints(
        String planId,
        Boolean officialSourcesOnly,
        String whitelistProfile,
        List<String> retrievalOrder,
        Integer webTopK,
        Integer vecTopK,
        Integer kgTopK,
        List<Integer> kSchedule,
        Long webBudgetMs,
        Long vecBudgetMs,
        Integer minCitations,
        Boolean allowWeb,
        Boolean allowRag,
        Boolean onnxEnabled,
        Boolean overdriveEnabled,
        Map<String, Object> raw
) {
    public static PlanHints empty(String planId) {
        return new PlanHints(
                planId,
                null,
                null,
                List.of(),
                null,
                null,
                null,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Map.of()
        );
    }

    public boolean isEmpty() {
        return officialSourcesOnly == null
                && (whitelistProfile == null || whitelistProfile.isBlank())
                && (retrievalOrder == null || retrievalOrder.isEmpty())
                && webTopK == null
                && vecTopK == null
                && kgTopK == null
                && (kSchedule == null || kSchedule.isEmpty())
                && webBudgetMs == null
                && vecBudgetMs == null
                && minCitations == null
                && allowWeb == null
                && allowRag == null
                && onnxEnabled == null
                && overdriveEnabled == null;
    }
}
