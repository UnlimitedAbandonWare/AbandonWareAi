package com.example.lms.service.rag;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HybridRetrieverSupportTest {

    @Test
    void officialDomainMatchesExactHostOrSubdomainOnly() {
        List<String> officialDomains = List.of("openai.com");

        assertTrue(HybridRetrieverSupport.isOfficial("https://openai.com/docs", officialDomains));
        assertTrue(HybridRetrieverSupport.isOfficial("https://platform.openai.com/docs", officialDomains));
        assertFalse(HybridRetrieverSupport.isOfficial("https://notopenai.com/phish", officialDomains));
        assertFalse(HybridRetrieverSupport.isOfficial("https://example.com/path/openai.com", officialDomains));
    }

    @Test
    void invalidIntegerMetadataLeavesTraceBreadcrumb() {
        TraceStore.clear();

        List<Integer> values = HybridRetrieverSupport.toIntList(List.of("3", "raw-number-secret"));

        assertEquals(List.of(3), values);
        assertEquals(true, TraceStore.get("rag.hybridRetrieverSupport.suppressed.toIntegerOrNull"));
        assertEquals("invalid_number",
                TraceStore.get("rag.hybridRetrieverSupport.toIntegerOrNull.errorType"));
        assertFalse(TraceStore.getAll().toString().contains("raw-number-secret"));
    }
}
