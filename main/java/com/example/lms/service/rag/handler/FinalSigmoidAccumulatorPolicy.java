package com.example.lms.service.rag.handler;

import com.example.lms.guard.FinalSigmoidGate;
import com.example.lms.search.TraceStore;
import com.example.lms.search.probe.EvidenceSignals;
import com.example.lms.service.rag.auth.AuthorityScorer;
import dev.langchain4j.rag.content.Content;

import java.util.Comparator;
import java.util.List;

final class FinalSigmoidAccumulatorPolicy {

    private FinalSigmoidAccumulatorPolicy() {
    }

    static void apply(
            FinalSigmoidGate gate,
            EvidenceSignals sig,
            List<Content> accumulator,
            AuthorityScorer authorityScorer) {
        if (gate == null || sig == null || accumulator == null) {
            return;
        }
        double policyRisk = clamp(sig.duplicateRatio()), hallRisk = 1.0d - clamp(sig.coverageScore());
        boolean strong = sig.docCount() > 0 && (sig.coverageScore() >= 0.80d || sig.authorityAvg() >= 0.50d);
        FinalSigmoidGate.GateResult result = gate.check(gate.score(hallRisk, policyRisk, strong ? 0.0d : 1.0d), policyRisk, strong);
        TraceStore.put("retrieval.finalSigmoidGate.result", result.name());
        if (accumulator.size() <= 3) {
            return;
        }
        if (result == FinalSigmoidGate.GateResult.BLOCK) {
            trim(accumulator, Math.min(accumulator.size(), Math.max(3, accumulator.size() / 3)), authorityScorer);
            TraceStore.put("retrieval.finalSigmoidGate.blockTrim.count", accumulator.size());
        } else if (result == FinalSigmoidGate.GateResult.DEGRADE) {
            int limit = Math.min(accumulator.size(), Math.max(3, (int) (accumulator.size() * 0.6d)));
            accumulator.subList(limit, accumulator.size()).clear();
            TraceStore.put("retrieval.finalSigmoidGate.degradeTrim.count", accumulator.size());
        }
    }

    private static void trim(List<Content> accumulator, int limit, AuthorityScorer authorityScorer) {
        if (authorityScorer != null) {
            accumulator.sort(Comparator.comparingDouble(c -> -authorityScorer.weightFor(source(c))));
        }
        accumulator.subList(limit, accumulator.size()).clear();
    }

    private static String source(Content content) {
        var meta = content.textSegment().metadata();
        String url = meta.getString("url");
        return url == null || url.isBlank() ? meta.getString("source") : url;
    }

    private static double clamp(double value) {
        return Math.max(0.0d, Math.min(1.0d, value));
    }
}
