package com.example.risk;

import dev.langchain4j.rag.content.Content;
import java.util.List;

/**
 * Minimal interface representing a context with signals.  This interface
 * allows the risk scoring components to extract text and other signal
 * information from arbitrary domain objects without depending on
 * specific implementation details.  Only the list of signals is exposed.
 */
public interface ListingContext {
    /**
     * Return the list of content signals associated with this context.
     *
     * @return list of Content objects; may be empty but never null
     */
    List<Content> signals();
}