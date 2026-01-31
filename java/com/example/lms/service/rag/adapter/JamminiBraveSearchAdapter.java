package com.example.lms.service.rag.adapter;

import com.example.lms.service.rag.port.WebSearchPort;
import com.example.lms.service.web.BraveSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * Simple Brave-based WebSearchPort adapter.
 *
 * This intentionally keeps logic minimal and delegates
 * to existing BraveSearchService. If you later introduce
 * extra caching / rate limiting (e.g. Upstash), this is
 * the natural place to plug it in.
 */
@Component("jamminiBraveSearchAdapter")
public class JamminiBraveSearchAdapter implements WebSearchPort {

    private static final Logger log = LoggerFactory.getLogger(JamminiBraveSearchAdapter.class);

    private final BraveSearchService braveSearchService;

    public JamminiBraveSearchAdapter(BraveSearchService braveSearchService) {
        this.braveSearchService = braveSearchService;
    }

    @Override
    @Cacheable(
            value = "webSearchCache",
            key = "#query + '-' + #topK",
            unless = "#result == null || #result.isEmpty()"
    )
    public List<SearchSnippet> searchSnippets(String query, int topK) {
        try {
            List<String> raw = braveSearchService.searchSnippets(query, topK);
            return raw.stream()
                    .map(s -> new SearchSnippet(null, null, s, 0.0d))
                    .toList();
        } catch (Exception ex) {
            log.warn("[Brave] searchSnippets failed: {}", ex.toString());
            return Collections.emptyList();
        }
    }

    @Override
    public String adapterId() {
        return "jammini-brave";
    }
}
