package com.example.lms.application.port.out;

import java.util.List;

/**
 * Port for indexing and searching uploaded files.
 *
 * <p>When file‑based retrieval is enabled the application core uses this
 * interface to extract text from uploaded documents and identify relevant
 * passages.  Implementations may use full‑text search, RRF/Borda rank
 * aggregation or simple keyword matching as deemed appropriate.  Any
 * exceptions thrown by the underlying implementation should be caught
 * and reported as warnings via the SSE trace channel.</p>
 */
public interface FileSearchPort {

    /**
     * Index the provided documents and return a list of relevant
     * snippets for the given query.  Implementations should be
     * fail‑soft: if processing a particular file fails it should log
     * a warning and continue with the remaining files.
     *
     * @param query        the natural language query
     * @param uploadedUrls a list of uploaded file URLs
     * @param topK         maximum number of snippets to return
     * @return relevant snippets extracted from the uploaded files
     */
    List<String> searchFiles(String query, List<String> uploadedUrls, int topK);
}