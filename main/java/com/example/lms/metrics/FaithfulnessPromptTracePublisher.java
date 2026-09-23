package com.example.lms.metrics;

import com.example.lms.moe.NormalizedRagMetrics;
import com.example.lms.search.TraceStore;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Publishes count-only faithfulness metrics after prompt evidence selection and
 * before the LLM call. It only fills missing keys, so richer evaluator output
 * keeps precedence when already present.
 */
public final class FaithfulnessPromptTracePublisher {

    private FaithfulnessPromptTracePublisher() {
    }

    public static void publishBeforeLlm(
            String query,
            boolean webRequested,
            boolean ragRequested,
            int webDocCount,
            int ragDocCount,
            int evidenceCount,
            int topK,
            boolean fallbackUsed) {
        try {
            int safeWebDocs = Math.max(0, webDocCount);
            int safeRagDocs = Math.max(0, ragDocCount);
            int safeEvidence = Math.max(0, evidenceCount);
            int safeTopK = Math.max(1, topK);
            int requestedLanes = (webRequested ? 1 : 0) + (ragRequested ? 1 : 0);
            int hitLanes = ((webRequested && safeWebDocs > 0) ? 1 : 0)
                    + ((ragRequested && safeRagDocs > 0) ? 1 : 0);
            int docCount = safeWebDocs + safeRagDocs;
            Map<String, Integer> sourceCounts = new LinkedHashMap<>();
            if (safeWebDocs > 0) {
                sourceCounts.put("web", safeWebDocs);
            }
            if (safeRagDocs > 0) {
                sourceCounts.put("rag", safeRagDocs);
            }

            NormalizedRagMetrics metrics = NormalizedRagMetrics.from(
                    requestedLanes,
                    hitLanes,
                    docCount,
                    safeEvidence,
                    sourceCounts,
                    requestedLanes <= 0 ? 0.0d : (docCount / (double) requestedLanes),
                    safeTopK,
                    0.0d,
                    fallbackUsed ? 1.0d : 0.0d);
            putMetricIfAbsent("rag.eval.normalized.retrievalHitRate", metrics.retrievalHitRate());
            putMetricIfAbsent("rag.eval.normalized.evidenceCoverage", metrics.evidenceCoverage());
            putMetricIfAbsent("rag.eval.normalized.sourceDiversity", metrics.sourceDiversity());
            putMetricIfAbsent("rag.eval.normalized.resultDepth", metrics.resultDepth());
            putMetricIfAbsent("rag.eval.normalized.latencyCost", metrics.latencyCost());
            putMetricIfAbsent("rag.eval.normalized.fallbackCost", metrics.fallbackCost());
            putMetricIfAbsent("rag.eval.normalized.balancedScore", metrics.balancedScore());
            putMetricIfAbsent("rag.eval.normalized.schemaVersion", NormalizedRagMetrics.SCHEMA_VERSION);

            QualityProxy quality = qualityProxy(query, docCount, safeEvidence, sourceCounts.size());
            putMetricIfAbsent("rag.answerQuality.decision", quality.decision);
            putMetricIfAbsent("rag.answerQuality.confidence", quality.confidence);
            putMetricIfAbsent("rag.answerQuality.faithfulnessScore", quality.faithfulnessScore);
            putMetricIfAbsent("rag.answerQuality.docCount", docCount);
            putMetricIfAbsent("rag.answerQuality.distinctSources", sourceCounts.size());
            putMetricIfAbsent("rag.answerQuality.reason", quality.reason);
        } catch (Exception ex) {
            TraceStore.put("rag.faithfulness.promptTrace.suppressed", true);
            TraceStore.put("rag.faithfulness.promptTrace.errorType",
                    ex.getClass().getSimpleName());
        }
    }

    private static QualityProxy qualityProxy(String query, int docCount, int evidenceCount, int distinctSources) {
        if (query == null || query.trim().length() < 2) {
            return new QualityProxy("REWRITE_QUERY", 0.2d, 0.08d, "query_too_short");
        }
        if (docCount <= 0) {
            return new QualityProxy("REPAIR_WITH_WEB", 0.1d, 0.06d, "empty_docs");
        }
        if (evidenceCount <= 0 || distinctSources <= 0) {
            return new QualityProxy("REPAIR_WITH_WEB", 0.4d, 0.24d, "weak_evidence");
        }
        double confidence = Math.min(0.85d, 0.4d + (0.45d * Math.min(1.0d, evidenceCount / (double) docCount)));
        return new QualityProxy("ACCEPT", confidence, confidence, "prompt_evidence_present");
    }

    private static void putMetricIfAbsent(String key, Object value) {
        if (TraceStore.get(key) != null) {
            return;
        }
        Object previous = TraceStore.putIfAbsent(key, value);
        if (previous == null) {
            FaithfulnessMetricSnapshotStore.put(key, value);
        }
    }

    private record QualityProxy(String decision, double confidence, double faithfulnessScore, String reason) {
    }
}
