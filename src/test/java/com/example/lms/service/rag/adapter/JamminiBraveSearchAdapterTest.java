package com.example.lms.service.rag.adapter;

import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.port.WebSearchPort;
import com.example.lms.service.web.BraveSearchService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JamminiBraveSearchAdapterTest {

    @Test
    void braveFailureReturnsEmptyAndLeavesRedactedTraceBreadcrumb() {
        BraveSearchService braveSearchService = mock(BraveSearchService.class);
        when(braveSearchService.searchSnippets(anyString(), anyInt()))
                .thenThrow(new IllegalStateException("raw brave token"));
        JamminiBraveSearchAdapter adapter = new JamminiBraveSearchAdapter(braveSearchService);

        TraceStore.clear();
        List<WebSearchPort.SearchSnippet> result = adapter.searchSnippets("secret user query", 3);

        assertTrue(result.isEmpty());
        assertEquals(true, TraceStore.get("web.brave.jammini.searchSnippets.failed"));
        assertEquals("jammini-brave-search-error",
                TraceStore.get("web.brave.jammini.searchSnippets.failureReason"));
        assertEquals("IllegalStateException", TraceStore.get("web.brave.jammini.searchSnippets.errorType"));
        assertEquals(3, TraceStore.get("web.brave.jammini.searchSnippets.requestedCount"));
        assertFalse(TraceStore.getAll().toString().contains("raw brave token"));
        assertFalse(TraceStore.getAll().toString().contains("secret user query"));
    }
}
