package com.abandonware.ai.agent.fallback;

import com.abandonware.ai.agent.orchestrator.recovery.FailureClass;
import com.abandonware.ai.agent.orchestrator.recovery.Verdict;
import com.abandonware.ai.agent.rag.model.Result;
import com.example.lms.trace.SafeRedactor;
import java.util.List;

public class NovaFallbackCoordinator {
    public String handle(String query, List<Result> results) {
        return "Information is insufficient; collecting more reliable evidence.";
    }

    public String handle(String query, List<Result> results, Verdict verdict) {
        if (verdict != null && verdict.failureClass() == FailureClass.DATA) {
            return "Evidence is insufficient. Ask the user for more context or increase web.search weight in the next round. (reason: "
                    + safeLabel(verdict.reason(), "unknown") + ")";
        }
        if (verdict != null && verdict.failureClass() == FailureClass.BUDGET) {
            return "Response budget is exhausted; summarizing in safe mode. (remaining ms: "
                    + safeLabel(verdict.signals().getOrDefault("budget_ms_left", "n/a"), "n/a") + ")";
        }
        return handle(query, results);
    }

    private static String safeLabel(Object value, String fallback) {
        return SafeRedactor.traceLabelOrFallback(value, fallback);
    }
}
