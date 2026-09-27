package com.example.lms.gptsearch.web.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;



/**
 * Container for a list of web documents returned from a search.  The
 * result also records the provider ID that produced the documents.
 */
public class WebSearchResult {
    private final List<WebDocument> documents;
    private final String providerId;

    public WebSearchResult(String providerId, List<WebDocument> documents) {
        this.providerId = providerId;
        this.documents = (documents == null) ? new ArrayList<>() : new ArrayList<>(documents);
    }

    /**
     * Get the list of documents.  The returned list is mutable but its
     * references are not shared with the underlying storage.
     */
    public List<WebDocument> getDocuments() {
        return Collections.unmodifiableList(documents);
    }

    /**
     * Identifier for the provider that produced these results.
     */
    public String getProviderId() {
        return providerId;
    }
}