package com.example.lms.service.rag.merge;

import com.example.lms.service.rag.PartialRetrievalResult;
import dev.langchain4j.rag.content.Content;

import java.util.ArrayList;
import java.util.List;

/**
 * Adapter interface used to convert between the system's internal
 * retrieval result representation and a generic list of {@link MergeItem}
 * objects used by the {@link WeightedInterleaveMerger}.  Implementations
 * should provide bidirectional conversions to allow the merger to
 * operate on arbitrary result types.  A simple default implementation
 * is provided for {@link PartialRetrievalResult} and may be used when
 * fine‑grained conversions are not required.
 *
 * @param <R> the retrieval result type
 */
public interface MergerAdapter<R> {

    /**
     * Extract a list of {@link MergeItem} entries from the provided
     * retrieval result.  Ordering of the returned list should mirror the
     * ranking of the underlying result.  Implementations may choose to
     * synthesise fields such as title or snippet from the result's
     * content.
     *
     * @param result the retrieval result to adapt (may be null)
     * @return a list of merge items, never null
     */
    List<MergeItem> itemsOf(R result);

    /**
     * Convert a list of {@link MergeItem} objects back into the
     * retrieval result type.  The returned result should encapsulate
     * contents, evidences and any other metadata as appropriate.  When
     * implementing this method it is acceptable to ignore fields of
     * {@link MergeItem} that cannot be represented in the target type.
     *
     * @param items the list of merge items (may be null)
     * @return a new retrieval result instance, never null
     */
    R fromItems(List<MergeItem> items);

    /**
     * Simple adapter for {@link PartialRetrievalResult} that flattens
     * content segments into merge items and reconstructs a partial result
     * containing only those segments.  This implementation treats all
     * items as originating from an unspecified source and does not use
     * the id or score fields.
     */
    class PartialResultAdapter implements MergerAdapter<PartialRetrievalResult> {
        @Override
        public List<MergeItem> itemsOf(PartialRetrievalResult result) {
            List<MergeItem> items = new ArrayList<>();
            if (result == null || result.getContents() == null) {
                return items;
            }
            for (Content c : result.getContents()) {
                // The id and score are unknown; we leave them null / zero
                MergeItem mi = new MergeItem();
                mi.setId(null);
                mi.setTitle(null);
                // Safely extract the snippet from content metadata
                mi.setSnippet(com.example.lms.service.rag.support.ContentCompat.textOf(c));
                mi.setScore(0.0);
                mi.setSource("unknown");
                items.add(mi);
            }
            return items;
        }

        @Override
        public PartialRetrievalResult fromItems(List<MergeItem> items) {
            PartialRetrievalResult result = new PartialRetrievalResult();
            if (items == null) {
                return result;
            }
            List<Content> contents = new ArrayList<>();
            for (MergeItem item : items) {
                if (item != null && item.getSnippet() != null) {
                    contents.add(com.example.lms.service.rag.support.ContentCompat.fromText(item.getSnippet()));
                }
            }
            // Set the contents on the result via reflection; there is no setter
            for (Content c : contents) {
                result.getContents().add(c);
            }
            return result;
        }
    }
}