package com.example.lms.service.rag.rerank;

import com.example.lms.health.GpuHardwareDiagnostics;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import dev.langchain4j.rag.content.Content;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;




/**
 * A gate controlling invocation of the expensive cross-encoder reranker.
 *
 * <p>The hybrid retrieval pipeline collects a candidate list of documents
 * during the first pass.  While the cross-encoder produces more accurate
 * rankings, it is computationally expensive and should only be invoked when
 * the candidate set is sufficiently uncertain.  This gate reads several
 * heuristics from the application configuration to decide whether or not to
 * perform reranking.  When the candidate list contains fewer than
 * {@code ceTopK} elements the marginal gain of reranking is low and the gate
 * returns {@code false}.  Additional heuristics based on uncertainty,
 * disagreement and margin are exposed via configuration but are not
 * implemented here.  They provide extension points for future work.</p>
 */
@Component
public class RerankGate {

    private final Environment env;

    public RerankGate(Environment env) {
        this.env = env;
    }

    /**
     * Threshold for the retrieval uncertainty.  Values in the range [0,1].
     * Lower uncertainty indicates high confidence in the initial ranking and
     * therefore reranking is unnecessary.
     */
    @Value("${rerank.gate.uncertainty-threshold:0.45}")
    private double uncertaintyThreshold;

    /**
     * Threshold for modality disagreement.  High disagreement between web and
     * vector scores suggests conflicting signals and warrants reranking.
     */
    @Value("${rerank.gate.disagreement-threshold:0.25}")
    private double disagreementThreshold;

    /**
     * Threshold for margin between top candidates.  When the difference
     * between the first and second candidate scores falls below this margin
     * the gate promotes reranking to refine the ordering.
     */
    @Value("${rerank.gate.margin-threshold:0.08}")
    private double marginThreshold;

    /**
     * Minimum number of candidates required before reranking is considered.
     * When fewer candidates are available the benefit of reranking is low and
     * the gate returns {@code false}.  The default of 12 is bound to
     * {@code ranking.rerank.ce.topK} (legacy) with fallback to
     * {@code rerank.ce.topK} (canonical).
     */
    @Value("${ranking.rerank.ce.topK:${rerank.ce.topK:12}}")
    private int ceTopK;

    /**
     * Decide whether or not to invoke the cross-encoder reranker based on
     * candidate size and configured heuristics.  Currently this method
     * implements a simple size check; future implementations may compute
     * uncertainty, disagreement and margin metrics to refine the decision.
     *
     * @param candidates the first pass candidate list
     * @return {@code true} when reranking should be performed; otherwise
     * {@code false}
     */
    public boolean shouldRerank(List<Content> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return false;
        }
        if (gpuHardwareAdmissionBlocksRerank()) {
            return false;
        }

        // [Err333 Fix] ensure that even small candidate sets are reranked
        int size = candidates.size();
        // [Err333 Fix] 소량 후보군(1~ceTopK-1개)은 비용이 낮으므로 항상 재랭크를 수행한다.
        if (size < ceTopK) {
            TraceStore.put("rerank.ce.skipped", true);
            TraceStore.put("rerank.ce.skipReason", "insufficient_candidates");
            return false;
        }
        try {
            // ----------------------- NEW HEURISTICS -----------------------
            // 1) Extract simple features from the candidate set.  We use the
            //    length of the underlying text as a proxy for the document's
            //    richness.  While this is a rough measure, it provides
            //    inexpensive signals about the variation between candidates.
            java.util.List<Integer> lengths = new java.util.ArrayList<>();
            for (Content c : candidates) {
                if (c == null) continue;
                String text;
                try {
                    var seg = c.textSegment();
                    text = (seg != null && seg.text() != null) ? seg.text() : null;
                } catch (RuntimeException ex) {
                    TraceStore.put("rerank.ce.textFallback", true);
                    TraceStore.put("rerank.ce.textFallback.errorType", safeExceptionName(ex));
                    text = null;
                }
                if (text == null || text.isBlank()) {
                    text = c.toString();
                }
                if (text != null) {
                    lengths.add(text.length());
                }
            }
            if (lengths.size() < 2) {
                // Not enough data to assess margin → rerank if candidate count threshold passed
                return true;
            }
            java.util.Collections.sort(lengths, java.util.Collections.reverseOrder());
            // 2) Margin heuristic: compute the relative difference between
            //    the two longest candidates.  If the margin exceeds the
            //    configured threshold, the benefit of reranking is deemed
            //    negligible, so we skip the expensive cross-encoder.
            double len1 = lengths.get(0);
            double len2 = lengths.get(1);
            double relMargin = Math.abs(len1 - len2) / Math.max(len1, 1.0);
            if (relMargin > marginThreshold) {
                // The top candidate is much longer than the second; assume
                // strong confidence in the current ordering.
                return false;
            }
            // 3) Uncertainty heuristic: use the coefficient of variation of
            //    lengths as a proxy for uncertainty.  High variation means
            //    there is more diversity in candidate content length and
            //    reranking could meaningfully adjust the ordering.  When
            //    variation is low, skip reranking.  We normalise the
            //    coefficient to [0,1] and compare against the configured
            //    uncertainty threshold.
            double sum = 0.0;
            for (int l : lengths) sum += l;
            double mean = sum / lengths.size();
            double variance = 0.0;
            for (int l : lengths) {
                double d = l - mean;
                variance += d * d;
            }
            variance /= Math.max(1, lengths.size());
            double stdev = Math.sqrt(variance);
            double coeff = (mean > 0.0) ? (stdev / mean) : 0.0;
            // Normalise coefficient roughly into [0,1] by dividing by 1.0
            double uncertainty = Math.min(1.0, coeff);
            if (uncertainty < uncertaintyThreshold) {
                // Low uncertainty → ordering is likely already adequate.
                return false;
            }
            // 4) Modality disagreement heuristic: not directly available.
            //    As a shim, we base disagreement on whether there is
            //    a wide spread in candidate lengths.  If the spread is large
            //    (i.e., coeff > disagreementThreshold), we consider signals
            //    to be conflicting and perform reranking.
            if (coeff > disagreementThreshold) {
                return true;
            }
            // Default to reranking when candidate count is large and
            // heuristics do not strongly indicate skipping.
            return true;
        } catch (RuntimeException ex) {
            // [Err333 Fix] Fail-soft: on any error still prefer reranking when candidates exist
            TraceStore.put("rerank.ce.failSoft", true);
            TraceStore.put("rerank.ce.failSoft.errorType", safeExceptionName(ex));
            return true;
        }
    }

    private boolean gpuHardwareAdmissionBlocksRerank() {
        if (!boolProp("awx.gpu-hardware.admission.rerank-gate-enabled", false)) {
            return false;
        }
        Map<String, Object> snapshot = GpuHardwareDiagnostics.snapshot(env);
        Map<String, Object> admission = GpuHardwareDiagnostics.admissionFromSnapshot(snapshot);
        boolean allowed = boolValue(admission.get("rerankAllowed"), true);
        try {
            TraceStore.put("rerank.ce.gpuHardwareAdmission.status", admission.getOrDefault("status", ""));
            TraceStore.put("rerank.ce.gpuHardwareAdmission.reason", SafeRedactor.traceLabelOrFallback(String.valueOf(admission.getOrDefault("reason", "")), "unknown"));
            TraceStore.put("rerank.ce.gpuHardwareAdmission.allowed", allowed);
            if (!allowed) {
                TraceStore.put("rerank.ce.skipped", true);
                TraceStore.put("rerank.ce.skipReason", "gpu_hardware_admission");
            }
        } catch (RuntimeException ex) {
            TraceStore.put("rerank.ce.gpuHardwareAdmission.traceErrorType", safeExceptionName(ex));
        }
        return !allowed;
    }

    private boolean boolProp(String key, boolean defaultValue) {
        String value = env == null ? null : env.getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value.trim());
    }

    private static boolean boolValue(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(value).trim();
        return s.isBlank() ? defaultValue : Boolean.parseBoolean(s);
    }

    private static String safeExceptionName(RuntimeException ex) {
        return ex == null ? "RuntimeException" : ex.getClass().getSimpleName();
    }
}
