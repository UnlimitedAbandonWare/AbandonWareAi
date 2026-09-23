package com.example.lms.integration.handlers;

import com.example.lms.gptsearch.decision.SearchDecisionService;
import com.example.lms.gptsearch.web.ProviderId;
import com.example.lms.gptsearch.web.WebSearchProvider;
import com.example.lms.gptsearch.web.dto.WebDocument;
import com.example.lms.gptsearch.web.dto.WebSearchQuery;
import com.example.lms.gptsearch.web.dto.WebSearchResult;
import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.QueryUtils;
import com.example.lms.service.rag.RelevanceScoringService;
import com.example.lms.service.rag.auth.DomainProfileLoader;
import com.example.lms.service.rag.extract.PageContentScraper;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdaptiveWebSearchHandlerUnicodeBoundaryTest {

    private static final String URL = "https://example.test/unicode";
    private static final String TITLE = "Unicode source";
    private static final String ELLIPSIS = "/* ... *&#47;";

    @AfterEach
    void clearTraceStore() {
        TraceStore.clear();
    }

    @Test
    void precisionAggregationDoesNotSplitSupplementaryCharacterAtDynamicBudget() {
        String header = "[" + TITLE + " | " + URL + "]\n";
        String prefix = "x".repeat(199);

        String actual = handle(prefix + "\uD83D\uDE00" + "tail", true, header.length() + 200);

        assertAll(
                () -> assertEquals(header + prefix, actual),
                () -> assertTrue(isWellFormedUtf16(actual)));
    }

    @Test
    void precisionAggregationPreservesBmpAndCompletePairControls() {
        String header = "[" + TITLE + " | " + URL + "]\n";
        int limit = header.length() + 200;

        assertAll(
                () -> assertEquals(header + "y".repeat(200), handle("y".repeat(201), true, limit)),
                () -> assertEquals(header + "q".repeat(198) + "\uD83D\uDE00",
                        handle("q".repeat(198) + "\uD83D\uDE00" + "tail", true, limit)));
    }

    @Test
    void fallbackSnippetDoesNotSplitSupplementaryCharacterAtFixedLimit() {
        String header = "[" + TITLE + " | TAVILY | " + URL + "]\n  ";
        String prefix = "x".repeat(199);

        String actual = handle(prefix + "\uD83D\uDE00" + "tail", false, 60_000);

        assertAll(
                () -> assertEquals(header + prefix + ELLIPSIS, actual),
                () -> assertTrue(isWellFormedUtf16(actual)));
    }

    @Test
    void fallbackSnippetPreservesBmpAndCompletePairControls() {
        String header = "[" + TITLE + " | TAVILY | " + URL + "]\n  ";

        assertAll(
                () -> assertEquals(header + "y".repeat(200) + ELLIPSIS,
                        handle("y".repeat(201), false, 60_000)),
                () -> assertEquals(header + "q".repeat(198) + "\uD83D\uDE00" + ELLIPSIS,
                        handle("q".repeat(198) + "\uD83D\uDE00" + "tail", false, 60_000)));
    }

    private static String handle(String body, boolean precision, int maxAggregateChars) {
        PageContentScraper scraper = mock(PageContentScraper.class);
        when(scraper.fetchText(eq(URL), anyInt())).thenReturn(body);

        WebDocument document = new WebDocument(URL, TITLE, "", null, null);
        AdaptiveWebSearchHandler handler = new AdaptiveWebSearchHandler(
                new SearchDecisionService(),
                List.of(new OneDocumentProvider(document)),
                scraper,
                mock(RelevanceScoringService.class),
                mock(DomainProfileLoader.class));
        ReflectionTestUtils.setField(handler, "maxAggregateChars", maxAggregateChars);

        Query query = QueryUtils.buildQuery("unicode web evidence",
                Map.of(
                        "useWebSearch", true,
                        "searchMode", "FORCE_LIGHT",
                        "webProviders", "TAVILY",
                        "webTopK", 1,
                        "precision", precision));
        List<Content> output = new ArrayList<>();

        handler.handle(query, output);

        assertEquals(1, output.size());
        return output.get(0).textSegment().text();
    }

    private static boolean isWellFormedUtf16(String value) {
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (Character.isHighSurrogate(current)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) {
                    return false;
                }
                i++;
            } else if (Character.isLowSurrogate(current)) {
                return false;
            }
        }
        return true;
    }

    private record OneDocumentProvider(WebDocument document) implements WebSearchProvider {
        @Override
        public WebSearchResult search(WebSearchQuery query) {
            return new WebSearchResult(id().name(), List.of(document));
        }

        @Override
        public ProviderId id() {
            return ProviderId.TAVILY;
        }
    }
}
