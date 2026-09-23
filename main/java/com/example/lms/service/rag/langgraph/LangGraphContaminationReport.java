package com.example.lms.service.rag.langgraph;

import java.util.List;
import java.util.Map;

public record LangGraphContaminationReport(
        String runId,
        String threadIdHash,
        String queryHash,
        String graphMode,
        List<NodeReport> nodes,
        ContaminationSummary contaminationSummary,
        Map<String, Object> trace
) {
    public record NodeReport(
            String node,
            Map<String, String> snapshotIds,
            Map<String, Object> delta,
            double contaminationScore,
            Map<String, Integer> sourceMix,
            boolean memoryLeakFlag,
            boolean promptInjectionFlag,
            boolean staleContextFlag,
            List<String> suspectFields,
            List<String> recommendedActions,
            Map<String, Object> contextSummary,
            Map<String, String> fieldHashes,
            List<String> riskMarkers
    ) {
    }

    public record ContaminationSummary(
            String highestRiskNode,
            String likelySourceCategory,
            double maxScore,
            Map<String, List<String>> fieldActions
    ) {
    }
}
