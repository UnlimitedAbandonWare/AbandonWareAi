package com.example.lms.service.rag;

import com.abandonware.ai.addons.budget.TimeBudget;
import com.abandonware.ai.addons.budget.TimeBudgetContext;
import com.example.lms.search.TraceStore;
import com.example.lms.search.provider.WebSearchProvider;
import com.example.lms.service.rag.auth.AuthorityScorer;
import com.example.lms.service.rag.detector.GameDomainDetector;
import com.example.lms.service.rag.extract.PageContentScraper;
import com.example.lms.service.rag.filter.EducationDocClassifier;
import com.example.lms.service.rag.filter.GenericDocClassifier;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WebSearchRetrieverRelationEvidenceTest {
    private static final String URL = "https://docs.example/fixture";
    private static final String QUERY = "카렐로바 전용 무기 이름이 뭐냐";
    private static final String RELATION = "카렐로바의 전용 무기는 은빛 서약의 활";
    private static final String INTRO = "카렐로바는 먼 지역을 여행하며 여러 기록과 풍경을 소개하는 인물 ";

    @AfterEach
    void clearRequestState() {
        TimeBudgetContext.clear();
        TraceStore.clear();
    }

    @Test
    void retrieve_preservesExplicitRelationNearEndOfFlatBody() {
        Content result = retrieve(QUERY, "카렐로바 소개 자료", INTRO.repeat(35) + RELATION, false);
        assertTrue(result.textSegment().text().contains(RELATION), "complete subject-relation-value must survive");
        assertMetadata(result);
    }

    @Test
    void retrieve_preservesQualifiedRelationFromMiddleAndEndOfHtmlTable() throws Exception {
        String target = "http://93.184.216.34/fixture";
        for (boolean atEnd : new boolean[]{false, true}) {
            String html = "<body><main><nav>" + "카렐로바 무기 메뉴 ".repeat(100) + "</nav><article><p>"
                    + INTRO.repeat(35) + "</p><table><tr><td>"
                    + RELATION + " (비공식 추정)</td></tr></table>"
                    + (atEnd ? "" : "<p>" + "다른 내용 ".repeat(150) + "</p>") + "</article></main></body>";
            Connection connection = mock(Connection.class, RETURNS_SELF);
            Connection.Response response = mock(Connection.Response.class);
            when(connection.execute()).thenReturn(response);
            when(response.statusCode()).thenReturn(200);
            when(response.parse()).thenReturn(Jsoup.parse(html));
            try (MockedStatic<Jsoup> jsoup = mockStatic(Jsoup.class)) {
                jsoup.when(() -> Jsoup.connect(target)).thenReturn(connection);
                String body = new PageContentScraper().fetchText(target, 1000);
                Content result = retrieve(QUERY, "카렐로바 소개 자료", body, false);
                assertTrue(result.textSegment().text().contains(RELATION + " (비공식 추정)"),
                        "scraper to retriever must preserve the complete qualified relation");
                assertFalse(result.textSegment().text().contains("메뉴"));
                assertMetadata(result);
            }
        }
    }

    @Test
    void retrieve_keepsTableCaptionQualifierWithMiddleRelation() throws Exception {
        var extract = PageContentScraper.class.getDeclaredMethod("extractBodyText", org.jsoup.nodes.Document.class);
        extract.setAccessible(true);
        String html = "<body><main><p>" + INTRO.repeat(35) + "</p><table>"
                + "<caption>비공식 추정 2026-10-01</caption><tr><td>" + RELATION + "</td></tr></table>"
                + "<p>" + "후속 안내 ".repeat(150) + "</p></main></body>";
        String body = (String) extract.invoke(null, Jsoup.parse(html));
        String text = retrieve(QUERY, "카렐로바 소개", body, false).textSegment().text();
        assertTrue(text.contains(RELATION) && text.contains("비공식 추정 2026-10-01"),
                "a table-local qualifier must travel with the relation, including downstream window selection");
    }

    @Test
    void retrieve_doesNotReplaceSupportedSerpWithWeakerBody() {
        Content result = retrieve(QUERY, RELATION, INTRO.repeat(35), false);
        assertTrue(result.textSegment().text().contains(RELATION), "supported SERP must survive a weaker body");
        assertMetadata(result);
    }

    @Test
    void retrieve_doesNotEmitCommentJunkSuffix() {
        String text = retrieve(QUERY, "카렐로바 소개 자료", INTRO.repeat(35), false).textSegment().text();
        assertFalse(text.contains("/* ... *"), "evidence must not contain a code-comment truncation marker");
        assertFalse(text.contains("&#47;"));
    }

    @Test
    void retrieve_doesNotPromoteOtherSubjectRelation() {
        String serp = "카렐로바 전용 무기 관련 자료를 안내";
        Content result = retrieve(QUERY, serp, "밀로슈의 전용 무기는 청동 나침의 창", false);
        assertEquals(URL + " - " + serp, result.textSegment().text());
        assertMetadata(result);
    }

    @Test
    void retrieve_keepsFallbackAndBoundsForGeneralQuery() {
        String serp = "광합성 정의 자료";
        for (boolean fail : new boolean[]{false, true}) {
            Content fallback = retrieve("광합성 정의", serp, null, fail);
            assertEquals(URL + " - " + serp, fallback.textSegment().text());
            assertMetadata(fallback);
        }
        String definition = "광합성 정의는 빛을 이용하여 양분을 만드는 과정이다. ";
        Content bounded = retrieve("광합성 정의", serp, definition.repeat(40), false);
        assertTrue(bounded.textSegment().text().contains("광합성 정의는"));
        assertTrue(bounded.textSegment().text().length() <= 480 + ("\n\n[출처] " + URL).length());
        assertMetadata(bounded);
    }

    @Test
    void retrieve_keepsSerpWhenRelationUnitExceedsBound() {
        String oversized = "카렐로바의 전용 무기는 " + "아주 긴 고유 명칭 ".repeat(60) + "은빛 서약의 활";
        Content result = retrieve(QUERY, RELATION, oversized, false);
        assertEquals(URL + " - " + RELATION, result.textSegment().text());
    }

    @Test
    void retrieve_preservesNegativeRelationWithoutRewriting() {
        String negative = "카렐로바의 전용 무기는 은빛 서약의 활이 아니다";
        Content result = retrieve(QUERY, "카렐로바 소개 자료", INTRO.repeat(35) + negative, false);
        assertTrue(result.textSegment().text().contains(negative));
        assertFalse(result.textSegment().text().contains(RELATION + "\n"));
    }

    @Test
    void retrieve_keepsSupportedSerpWhenBodyOnlyRepeatsQueryTerms() {
        String body = "카렐로바 전용 무기 관련 자료를 안내합니다.";
        Content result = retrieve(QUERY, RELATION, body, false);
        assertTrue(result.textSegment().text().contains(RELATION));
        assertTrue(result.textSegment().text().length() <= 480 + ("\n\n[출처] " + URL).length());
        assertMetadata(result);
    }

    @Test
    void retrieve_keepsSerpWhenEqualOverlapWouldExceedCombinedBound() {
        String body = "카렐로바 전용 무기 관련 자료를 안내 ".repeat(40);
        Content result = retrieve(QUERY, RELATION, body, false);
        assertEquals(URL + " - " + RELATION, result.textSegment().text());
        assertMetadata(result);
    }

    @Test
    void retrieve_doesNotReplaceExactSubjectWithShorterPrefixMatch() {
        String body = "카렐리아의 전용 무기 이름이 청동 나침의 창이다";
        Content result = retrieve(QUERY, RELATION, body, false);
        assertEquals(URL + " - " + RELATION, result.textSegment().text());
        assertMetadata(result);
    }

    @Test
    void retrieve_matchesInflectedQueryWithoutSubjectDictionary() {
        String relation = "카렐로바 전용무기는 은빛 서약의 활";
        Content result = retrieve("카렐로바의 전용무기가 무엇이냐", "카렐로바 소개 자료",
                INTRO.repeat(35) + relation, false);
        assertTrue(result.textSegment().text().contains(relation));
        assertMetadata(result);
    }

    private Content retrieve(String query, String snippet, String body, boolean failFetch) {
        TraceStore.clear();
        TimeBudgetContext.set(new TimeBudget(30_000));
        WebSearchProvider provider = mock(WebSearchProvider.class);
        PageContentScraper scraper = mock(PageContentScraper.class);
        GameDomainDetector detector = mock(GameDomainDetector.class);
        when(detector.detect(anyString())).thenReturn("GENERAL");
        when(provider.getName()).thenReturn("fixture-provider");
        when(provider.search(anyString(), anyInt())).thenReturn(List.of(URL + " - " + snippet));
        if (failFetch) {
            when(scraper.fetchText(eq(URL), anyInt())).thenThrow(new IllegalStateException("fixture fetch failure"));
        } else {
            when(scraper.fetchText(eq(URL), anyInt())).thenReturn(body);
        }
        WebSearchRetriever retriever = new WebSearchRetriever(provider, null, scraper,
                mock(AuthorityScorer.class), mock(GenericDocClassifier.class), detector,
                mock(EducationDocClassifier.class));
        List<Content> result = retriever.retrieve(QueryUtils.buildQuery(query, Map.of("webTopK", 1)));
        assertEquals(1, result.size());
        verify(provider, times(1)).search(anyString(), anyInt());
        verify(scraper, times(1)).fetchText(eq(URL), anyInt());
        return result.get(0);
    }

    private void assertMetadata(Content result) {
        assertEquals(URL, result.textSegment().metadata().getString("url"));
        assertEquals(URL, result.textSegment().metadata().getString("source"));
        assertEquals("fixture-provider", result.textSegment().metadata().getString("provider"));
    }
}
