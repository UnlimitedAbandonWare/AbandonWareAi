package com.example.lms.service.rag.rerank;

import dev.langchain4j.rag.content.Content;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.List;




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
     * {@code ranking.rerank.ce.topK}.
     */
    @Value("${ranking.rerank.ce.topK:12}")
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
        // 기본 게이트: 최소 후보 수가 충족되지 않으면 재랭킹할 필요가 없다.
        if (candidates.size() < ceTopK) {
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
                } catch (Exception ignore) {
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
        } catch (Exception e) {
            // Fail-soft: on any error fall back to original size check
            return candidates.size() >= ceTopK;
        }
    }
}