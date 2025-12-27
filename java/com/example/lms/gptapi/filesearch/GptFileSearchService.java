package com.example.lms.gptapi.filesearch;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.stream.Collectors;




/**
 * Service layer facade for GPT API file search.
 *
 * <p>This service provides higher level semantics around the low-level
 * client.  It performs language normalization and concatenates
 * snippet strings into a single plain-text context.  If no results
 * are returned from the client, an empty string will be propagated.</p>
 */
@Service
@RequiredArgsConstructor
public class GptFileSearchService {

    private final GptFileSearchClient client;

    @Value("${abandonware.gptapi.filesearch.topK:6}")
    private int topK;

    /**
     * Retrieve file snippets for the given query.  The snippets are
     * concatenated using newline separators.  If the client returns an
     * empty list the result will be {@code null}.
     *
     * @param query the user query
     * @return a single string containing concatenated file snippets or
     *         {@code null} if none were found
     */
    public String searchFileContext(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        List<String> snippets = client.search(query, topK);
        if (snippets == null || snippets.isEmpty()) {
            return null;
        }
        return snippets.stream()
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.joining("\n"));
    }
}