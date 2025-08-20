package com.example.lms.infrastructure.file;

import com.example.lms.application.port.out.FileSearchPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * Naïve implementation of {@link FileSearchPort} that performs no actual
 * indexing or search.  This placeholder adapter satisfies the dependency
 * for file search without introducing heavy libraries.  It simply returns
 * an empty list of snippets.  Real implementations may leverage Lucene,
 * OpenSearch or embedding‑based similarity search.
 */
@Slf4j
@Component
public class LocalFileSearchAdapter implements FileSearchPort {

    @Override
    public List<String> searchFiles(String query, List<String> uploadedUrls, int topK) {
        // TODO: implement file indexing and keyword search
        log.debug("LocalFileSearchAdapter.searchFiles invoked with query={} urls={}", query, uploadedUrls);
        return Collections.emptyList();
    }
}