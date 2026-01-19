package com.example.lms.service.rag.fusion;

import com.example.lms.gptsearch.web.dto.WebDocument;
import com.example.lms.gptsearch.web.dto.WebSearchResult;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Map;




import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Simple unit test for the {@link RrfFusion} utility verifying that
 * documents returned by multiple providers are promoted in the fused
 * ranking.  When the same URL appears in more than one provider
 * result list it should accumulate a higher score and therefore
 * appear earlier in the output.
 */
public class RrfFusionTest {

    @Test
    void combineWeighted_should_promote_consensus() {
        // Create some dummy documents with distinct URLs
        WebDocument docA = new WebDocument("https://a.com", "A", "snipA", null, Instant.now());
        WebDocument docB = new WebDocument("https://b.com", "B", "snipB", null, Instant.now());
        WebDocument docC = new WebDocument("https://c.com", "C", "snipC", null, Instant.now());
        // Provider 1 returns A then B
        WebSearchResult r1 = new WebSearchResult("Naver", List.of(docA, docB));
        // Provider 2 returns A then C
        WebSearchResult r2 = new WebSearchResult("Bing", List.of(docA, docC));
        List<WebDocument> fused = RrfFusion.combineWeighted(
                List.of(r1, r2),
                Map.of("Naver", 1.0, "Bing", 1.0),
                60,
                2
        );
        // The document present in both providers (A) should be ranked first
        assertEquals("https://a.com", fused.get(0).getUrl());
    }
}