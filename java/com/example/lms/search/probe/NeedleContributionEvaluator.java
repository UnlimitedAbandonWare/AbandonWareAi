package com.example.lms.search.probe;

import dev.langchain4j.rag.content.Content;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Evaluates how much the needle probe contributed to the final retrieval
 * results.
 * Used for learning and reward signal computation.
 */
@Component
public class NeedleContributionEvaluator {

    /**
     * Evaluate needle contribution by comparing needle docs with final top-N.
     *
     * @param needleDocs    documents retrieved by needle probe
     * @param needleUrls    URLs from needle probe
     * @param topNDocs      final top-N documents after reranking
     * @param beforeSignals evidence signals before needle
     * @param afterSignals  evidence signals after needle merge
     * @return contribution metrics
     */
    public NeedleContribution evaluate(
            List<Content> needleDocs,
            Set<String> needleUrls,
            List<Content> topNDocs,
            EvidenceSignals beforeSignals,
            EvidenceSignals afterSignals) {
        if (needleDocs == null || needleDocs.isEmpty()) {
            return NeedleContribution.empty();
        }

        int docsAdded = needleDocs.size();
        int docsUsedInTopN = countNeedleDocsInTopN(needleUrls, topNDocs);

        double qualityDelta = 0.0;
        if (beforeSignals != null && afterSignals != null) {
            qualityDelta = afterSignals.authorityAvg() - beforeSignals.authorityAvg();
        }

        return NeedleContribution.of(docsAdded, docsUsedInTopN, qualityDelta);
    }

    private int countNeedleDocsInTopN(Set<String> needleUrls, List<Content> topNDocs) {
        if (needleUrls == null || needleUrls.isEmpty() || topNDocs == null) {
            return 0;
        }

        int count = 0;
        for (Content c : topNDocs) {
            if (c == null || c.textSegment() == null)
                continue;
            try {
                var meta = c.textSegment().metadata();
                if (meta != null) {
                    String url = meta.getString("url");
                    if (url == null)
                        url = meta.getString("source");
                    if (url != null && needleUrls.contains(url)) {
                        count++;
                    }
                }
            } catch (Exception ignore) {
            }
        }
        return count;
    }
}
