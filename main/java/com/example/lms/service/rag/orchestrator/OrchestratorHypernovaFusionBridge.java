package com.example.lms.service.rag.orchestrator;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.nova.protocol.fusion.NovaNextFusionService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class OrchestratorHypernovaFusionBridge {

    private OrchestratorHypernovaFusionBridge() {
    }

    static List<UnifiedRagOrchestrator.Doc> apply(
            List<UnifiedRagOrchestrator.Doc> rankedDocs,
            Map<UnifiedRagOrchestrator.Doc, Double> rrfScores,
            NovaNextFusionService service) {
        if (rankedDocs == null || rankedDocs.isEmpty()) {
            return rankedDocs == null ? List.of() : rankedDocs;
        }
        if (service == null) {
            TraceStore.put("rag.orchestrator.hypernova.skipped", true);
            TraceStore.put("rag.orchestrator.hypernova.skipReason", "missing_nova_next_fusion_service");
            return rankedDocs;
        }
        try {
            return applyInternal(rankedDocs, rrfScores, service);
        } catch (Exception error) {
            TraceStore.put("rag.orchestrator.hypernova.skipped", true);
            TraceStore.put("rag.orchestrator.hypernova.skipReason", "nova_next_fusion_failed");
            TraceStore.put("rag.orchestrator.hypernova.errorType",
                    error == null ? "unknown" : error.getClass().getSimpleName());
            return rankedDocs;
        }
    }

    private static List<UnifiedRagOrchestrator.Doc> applyInternal(
            List<UnifiedRagOrchestrator.Doc> rankedDocs,
            Map<UnifiedRagOrchestrator.Doc, Double> rrfScores,
            NovaNextFusionService service) {
        List<NovaNextFusionService.ScoredResult> request = new ArrayList<>(rankedDocs.size());
        Map<String, UnifiedRagOrchestrator.Doc> bySyntheticId = new LinkedHashMap<>();
        for (int i = 0; i < rankedDocs.size(); i++) {
            UnifiedRagOrchestrator.Doc doc = rankedDocs.get(i);
            if (doc == null) {
                continue;
            }
            String syntheticId = "orch-fusion-" + i;
            Double rrfScore = rrfScores == null ? null : rrfScores.get(doc);
            double base = fusionBaseScore(doc, rrfScore);
            List<Double> sourceScores = fusionSourceScores(doc, rrfScore, base);
            NovaNextFusionService.ScoredResult scored = new NovaNextFusionService.ScoredResult();
            scored.setId(syntheticId);
            scored.setScore(base);
            scored.setBaseScore(base);
            scored.setRank(doc.rank > 0 ? doc.rank : i + 1);
            scored.setSource(doc.source == null ? "" : doc.source);
            scored.setSourceScores(sourceScores);
            scored.setSourceCount(sourceScores.size());
            copyNovaSignalMetadata(scored, doc.meta);
            request.add(scored);
            bySyntheticId.put(syntheticId, doc);
        }
        if (request.isEmpty()) {
            TraceStore.put("rag.orchestrator.hypernova.skipped", true);
            TraceStore.put("rag.orchestrator.hypernova.skipReason", "empty_scored_request");
            return rankedDocs;
        }
        List<NovaNextFusionService.ScoredResult> fused = service.fuse(request);
        if (fused == null || fused.isEmpty()) {
            TraceStore.put("rag.orchestrator.hypernova.skipped", true);
            TraceStore.put("rag.orchestrator.hypernova.skipReason", "empty_fused_response");
            return rankedDocs;
        }

        Map<String, NovaNextFusionService.ScoredResult> byId = new LinkedHashMap<>();
        for (NovaNextFusionService.ScoredResult scored : fused) {
            if (scored != null && scored.getId() != null) {
                byId.put(scored.getId(), scored);
            }
        }

        List<UnifiedRagOrchestrator.Doc> out = new ArrayList<>(rankedDocs.size());
        for (int i = 0; i < rankedDocs.size(); i++) {
            String syntheticId = "orch-fusion-" + i;
            UnifiedRagOrchestrator.Doc doc = bySyntheticId.get(syntheticId);
            if (doc == null) {
                continue;
            }
            NovaNextFusionService.ScoredResult scored = byId.get(syntheticId);
            if (scored != null) {
                doc.score = scored.getAdjustedScore();
                if (doc.meta == null) {
                    doc.meta = new LinkedHashMap<>();
                }
                doc.meta.put("grandas_base_score", scored.getBaseScore());
                doc.meta.put("grandas_adjusted_score", scored.getAdjustedScore());
                doc.meta.put("grandas_tail_signal", scored.getTailSignal());
                doc.meta.put("grandas_reason", safeReason(scored.getReason()));
                doc.meta.put("riskKAllocation", scored.getRiskKAllocation());
            }
            out.add(doc);
        }
        out.sort(Comparator.comparingDouble(OrchestratorHypernovaFusionBridge::finiteDocScore).reversed());
        TraceStore.put("rag.orchestrator.hypernova.applied", true);
        TraceStore.put("rag.orchestrator.hypernova.candidateCount", out.size());
        return out;
    }

    private static void copyNovaSignalMetadata(NovaNextFusionService.ScoredResult scored, Map<String, Object> meta) {
        if (scored == null || meta == null || meta.isEmpty()) {
            return;
        }
        scored.setAuthorityAvg(metaDouble(meta, "authorityAvg", "authority_avg", "selfask_lane_gate_authority_avg"));
        scored.setStrongCitationRate(metaDouble(meta, "strongCitationRate", "strong_citation_rate",
                "selfask_lane_gate_strong_citation_rate"));
        scored.setDuplicateRate(metaDouble(meta, "duplicateRate", "duplicate_rate",
                "selfask_lane_gate_duplicate_rate"));
        scored.setContradictionRate(metaDouble(meta, "contradictionRate", "contradiction_rate",
                "selfask_lane_gate_contradiction_rate"));
        scored.setCrossLaneSupportRate(metaDouble(meta, "crossLaneSupportRate", "cross_lane_support_rate",
                "selfask_lane_gate_cross_lane_support_rate"));
        scored.setGrandasReadiness(metaDouble(meta, "grandasReadiness", "grandas_readiness",
                "selfask_lane_gate_grandas_readiness"));
        scored.setTailSignal(metaDouble(meta, "tailSignal", "tail_signal", "selfask_lane_gate_tail_signal",
                "grandas_tail_signal"));
    }

    private static double metaDouble(Map<String, Object> meta, String... keys) {
        if (meta == null || keys == null) {
            return 0.0d;
        }
        for (String key : keys) {
            Double parsed = parseFiniteDouble(key == null ? null : meta.get(key));
            if (parsed != null) {
                return parsed;
            }
        }
        return 0.0d;
    }

    private static Double parseFiniteDouble(Object value) {
        if (value instanceof Number number) {
            double parsed = number.doubleValue();
            return Double.isFinite(parsed) ? parsed : null;
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                double parsed = Double.parseDouble(text.trim());
                return Double.isFinite(parsed) ? parsed : null;
            } catch (NumberFormatException ignored) {
                TraceStore.put("rag.orchestrator.suppressed.hypernovaMetaDouble", true);
                TraceStore.put("rag.orchestrator.suppressed.hypernovaMetaDouble.errorType", "invalid_number");
                return null;
            }
        }
        return null;
    }

    private static double fusionBaseScore(UnifiedRagOrchestrator.Doc doc, Double rrfScore) {
        if (rrfScore != null && Double.isFinite(rrfScore) && rrfScore > 0.0d) {
            return rrfScore;
        }
        return finiteDocScore(doc);
    }

    private static List<Double> fusionSourceScores(UnifiedRagOrchestrator.Doc doc, Double rrfScore, double fallback) {
        List<Double> out = new ArrayList<>(2);
        addScaleSignal(out, doc == null ? null : doc.score);
        addScaleSignal(out, rrfScore);
        if (out.isEmpty()) {
            addScaleSignal(out, fallback);
        }
        return List.copyOf(out);
    }

    private static void addScaleSignal(List<Double> out, Double value) {
        if (out == null || value == null || !Double.isFinite(value) || value < 0.0d) {
            return;
        }
        for (Double existing : out) {
            if (Math.abs(existing - value) <= 1.0e-12d) {
                return;
            }
        }
        out.add(value);
    }

    private static double finiteDocScore(UnifiedRagOrchestrator.Doc doc) {
        if (doc == null || !Double.isFinite(doc.score)) {
            return 0.0d;
        }
        return Math.max(0.0d, doc.score);
    }

    private static String safeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "";
        }
        return SafeRedactor.traceLabelOrFallback(reason, "");
    }
}
