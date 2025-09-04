package com.example.lms.service.rag.merge;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A utility to merge two ranked lists of {@link MergeItem} objects using
 * a weighted round‑robin strategy.  The algorithm removes duplicate
 * identifiers across lists, then interleaves items from the web and
 * vector sources based upon the supplied weights.  When one source
 * becomes exhausted the remainder of the other source is appended until
 * the requested number of results is reached.  Weights are clamped
 * between 0 and 1 and normalised internally; if both weights sum to zero
 * the merger falls back to equal weighting.  This class is stateless
 * and thread‑safe.
 */
public class WeightedInterleaveMerger {

    /**
     * Merge two input lists using a weighted round‑robin algorithm.  The
     * caller may specify arbitrary non‑negative weights; negative values
     * are treated as zero.  If both lists are empty an empty list is
     * returned.
     *
     * @param webHits   ranked hits from the web search (may be null)
     * @param vectorHits ranked hits from the vector search (may be null)
     * @param wWeb      weight for the web list (0.0–1.0 suggested)
     * @param wVec      weight for the vector list (0.0–1.0 suggested)
     * @param topK      maximum number of results to return
     * @return a merged list of up to {@code topK} items
     */
    public List<MergeItem> merge(List<MergeItem> webHits, List<MergeItem> vectorHits,
                                 double wWeb, double wVec, int topK) {
        List<MergeItem> result = new ArrayList<>();
        if (topK <= 0) {
            return result;
        }
        // Normalise null lists to empty
        List<MergeItem> web = (webHits == null) ? new ArrayList<>() : new ArrayList<>(webHits);
        List<MergeItem> vec = (vectorHits == null) ? new ArrayList<>() : new ArrayList<>(vectorHits);

        // Deduplicate across both lists by identifier.  Items appearing in
        // the web list take precedence over those in the vector list.
        Set<String> seen = new HashSet<>();
        List<MergeItem> dedupWeb = new ArrayList<>();
        for (MergeItem item : web) {
            String id = (item != null ? item.getId() : null);
            if (id == null || !seen.contains(id)) {
                dedupWeb.add(item);
                if (id != null) {
                    seen.add(id);
                }
            }
        }
        List<MergeItem> dedupVec = new ArrayList<>();
        for (MergeItem item : vec) {
            String id = (item != null ? item.getId() : null);
            // Skip duplicates already seen in web list
            if (id == null || !seen.contains(id)) {
                dedupVec.add(item);
                if (id != null) {
                    seen.add(id);
                }
            }
        }

        // Clamp weights and normalise.  If the sum is zero fall back to equal
        // distribution to avoid division by zero.
        double webWeight = Math.max(0.0, Math.min(1.0, wWeb));
        double vecWeight = Math.max(0.0, Math.min(1.0, wVec));
        double sum = webWeight + vecWeight;
        if (sum <= 0.0) {
            webWeight = 0.5;
            vecWeight = 0.5;
            sum = 1.0;
        }
        double expectedWeb = (webWeight / sum) * topK;
        double expectedVec = (vecWeight / sum) * topK;

        int webCount = 0;
        int vecCount = 0;
        int webIndex = 0;
        int vecIndex = 0;

        // Merge until we reach topK or both lists are exhausted
        while (result.size() < topK && (webIndex < dedupWeb.size() || vecIndex < dedupVec.size())) {
            // Determine which list to pull from next by comparing the
            // proportion of items already taken relative to the expected
            // number.  The list with the lower ratio is behind its quota
            // and thus selected next.
            double ratioWeb = (expectedWeb > 0.0) ? (webCount / expectedWeb) : Double.POSITIVE_INFINITY;
            double ratioVec = (expectedVec > 0.0) ? (vecCount / expectedVec) : Double.POSITIVE_INFINITY;
            boolean pickWeb;
            if (ratioWeb < ratioVec) {
                pickWeb = true;
            } else if (ratioVec < ratioWeb) {
                pickWeb = false;
            } else {
                // Tie – prefer the list with remaining elements or fall back to web
                pickWeb = (webIndex < dedupWeb.size());
            }
            if (pickWeb && webIndex < dedupWeb.size()) {
                result.add(dedupWeb.get(webIndex++));
                webCount++;
            } else if (!pickWeb && vecIndex < dedupVec.size()) {
                result.add(dedupVec.get(vecIndex++));
                vecCount++;
            } else if (webIndex < dedupWeb.size()) {
                // Only web has remaining items
                result.add(dedupWeb.get(webIndex++));
                webCount++;
            } else if (vecIndex < dedupVec.size()) {
                // Only vector has remaining items
                result.add(dedupVec.get(vecIndex++));
                vecCount++;
            } else {
                break;
            }
        }
        return result;
    }
}