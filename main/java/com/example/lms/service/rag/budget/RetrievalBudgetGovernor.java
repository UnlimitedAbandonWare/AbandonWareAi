package com.example.lms.service.rag.budget;

import ai.abandonware.nova.orch.anchor.AnchorNarrowingResult;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RetrievalBudgetGovernor {
    private static final Logger log = LoggerFactory.getLogger(RetrievalBudgetGovernor.class);
    private final RetrievalBudgetProperties properties;

    public RetrievalBudgetGovernor(RetrievalBudgetProperties properties) {
        this.properties = properties == null ? new RetrievalBudgetProperties() : properties;
    }

    public RetrievalBudgetDecision decide(AnchorNarrowingResult anchor,
                                          double offlineTextureHit,
                                          boolean freshnessQuery,
                                          int requestedWebTopK,
                                          int requestedVectorTopK,
                                          int requestedKgTopK,
                                          int requestedQueryBurstCount) {
        int maxBurst = clamp(properties.getMaxQueryBurst(), 1, 32);
        int defaultBurst = clamp(properties.getDefaultQueryBurst(), 1, maxBurst);
        int requestedBurst = requestedQueryBurstCount > 0 ? requestedQueryBurstCount : defaultBurst;
        int burst = clamp(requestedBurst, 1, maxBurst);

        int webTopK = Math.max(0, requestedWebTopK);
        int vectorTopK = Math.max(0, requestedVectorTopK);
        int kgTopK = Math.max(0, requestedKgTopK);
        double confidence = anchor == null ? 0.0d : clamp01(anchor.anchorConfidence());
        double drift = anchor == null ? 0.0d : clamp01(anchor.driftScore());
        double textureHit = clamp01(offlineTextureHit);
        boolean kgFinalDrop = traceHasSignal("kg_final_drop");

        boolean enableHypernova = confidence >= clamp01(properties.getHypernovaRequiresAnchorConfidence())
                && drift < 0.65d;
        boolean enableMassiveExpansion = enableHypernova && textureHit < 0.60d && !freshnessQuery;
        String reason = "default";

        if (confidence < 0.40d || drift >= 0.65d) {
            burst = Math.min(burst, 2);
            enableHypernova = false;
            enableMassiveExpansion = false;
            reason = "anchor_drift_high";
        } else if (textureHit >= 0.60d && !freshnessQuery) {
            burst = Math.min(burst, 3);
            kgTopK = Math.max(kgTopK, requestedKgTopK + 2);
            enableMassiveExpansion = false;
            reason = "offline_texture_hit";
        } else if (freshnessQuery) {
            burst = Math.min(burst, Math.max(2, defaultBurst));
            enableMassiveExpansion = false;
            reason = "freshness_online_required";
        }

        if (kgFinalDrop) {
            kgTopK = Math.max(0, requestedKgTopK);
            reason = reason.equals("default") ? "kg_final_drop_preserve" : reason + "+kg_final_drop_preserve";
        }

        RetrievalBudgetDecision decision = new RetrievalBudgetDecision(
                webTopK,
                vectorTopK,
                kgTopK,
                burst,
                enableHypernova,
                enableMassiveExpansion,
                reason);
        traceDecision(decision, requestedBurst, textureHit, freshnessQuery, anchor);
        return decision;
    }

    private void traceDecision(RetrievalBudgetDecision decision,
                               int requestedBurst,
                               double textureHit,
                               boolean freshnessQuery,
                               AnchorNarrowingResult anchor) {
        try {
            TraceStore.put("rag.budget.suggested.webTopK", decision.webTopK());
            TraceStore.put("rag.budget.suggested.vectorTopK", decision.vectorTopK());
            TraceStore.put("rag.budget.suggested.kgTopK", decision.kgTopK());
            TraceStore.put("rag.budget.applied.queryBurstCount", decision.queryBurstCount());
            TraceStore.put("rag.budget.suggested.enableHypernova", decision.enableHypernova());
            TraceStore.put("rag.budget.suggested.enableMassiveExpansion", decision.enableMassiveExpansion());
            String safeReason = SafeRedactor.traceLabelOrFallback(decision.reason(), "unknown");
            TraceStore.put("rag.budget.reason", safeReason);
            TraceStore.put("rag.metrics.textureHitRate", round4(textureHit));
            TraceStore.put("rag.metrics.offlinePredictionUsed", textureHit > 0.0d && !freshnessQuery);
            int accepted = anchor == null ? 0 : Math.max(0, anchor.acceptedCandidateCount());
            int total = anchor == null ? 0 : anchor.totalCandidateCount();
            double precision = total == 0 ? 0.0d : accepted / (double) total;
            TraceStore.put("rag.metrics.anchorPrecisionProxy", round4(precision));
            double reduction = requestedBurst <= 0 ? 0.0d
                    : 1.0d - (decision.queryBurstCount() / (double) Math.max(1, requestedBurst));
            TraceStore.put("rag.metrics.burstReductionRate", round4(Math.max(0.0d, reduction)));
            TraceStore.append("orch.events.v1", java.util.Map.of(
                    "type", "BUDGET_GOVERNED",
                    "reason", safeReason,
                    "queryBurstCount", decision.queryBurstCount()));
        } catch (Throwable failure) {
            // Budget diagnostics must never affect retrieval.
            String errorType = SafeRedactor.traceLabelOrFallback(
                    failure.getClass().getSimpleName(), "unknown");
            try {
                TraceStore.put("rag.budget.suppressed.traceDecision", true);
                TraceStore.put("rag.budget.suppressed.traceDecision.errorType", errorType);
            } catch (RuntimeException traceFailure) {
                log.debug("[RetrievalBudgetGovernor] fail-soft stage={} errorType={}",
                        "traceFallback",
                        SafeRedactor.traceLabelOrFallback(
                                traceFailure.getClass().getSimpleName(), "unknown"));
            }
            log.debug("[RetrievalBudgetGovernor] fail-soft stage={} errorType={}", "traceDecision", errorType);
        }
    }

    private static boolean traceHasSignal(String signal) {
        Object kgAxis = TraceStore.get("rag.eval.kgAxis.signals");
        if (containsSignal(kgAxis, signal)) {
            return true;
        }
        Object breaks = TraceStore.get("rag.eval.thresholdBreaks");
        return containsSignal(breaks, signal);
    }

    private static boolean containsSignal(Object raw, String signal) {
        if (raw == null || signal == null || signal.isBlank()) {
            return false;
        }
        if (raw instanceof CharSequence s) {
            return s.toString().contains(signal);
        }
        if (raw instanceof Iterable<?> rows) {
            for (Object row : rows) {
                if (containsSignal(row, signal)) {
                    return true;
                }
            }
            return false;
        }
        if (raw instanceof java.util.Map<?, ?> map) {
            for (Object value : map.values()) {
                if (containsSignal(value, signal)) {
                    return true;
                }
            }
            return false;
        }
        if (raw instanceof Object[] array) {
            return containsSignal(List.of(array), signal);
        }
        return false;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private static double round4(double value) {
        return Math.round(clamp01(value) * 10000.0d) / 10000.0d;
    }
}
