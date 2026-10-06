package com.example.lms.search;

import ai.abandonware.nova.config.NovaWebFailSoftProperties;
import ai.abandonware.nova.orch.aop.WebFailSoftSearchAspect;
import ai.abandonware.nova.orch.web.RuleBasedQueryAugmenter;
import com.example.lms.infra.resilience.IrregularityProfiler;
import com.example.lms.prompt.QueryKeywordPromptBuilder;
import com.example.lms.search.terms.SelectedTerms;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.anyList;

class KeywordFailureRiskIsolationTest {
    @AfterEach void clear() { TraceStore.clear(); GuardContextHolder.clear(); }

    @org.junit.jupiter.api.Test
    void uncertainSpellingsRemainAlternativesThroughMinimumMustRepair() {
        for (String query : List.of("루미단인가 루미딘인가", "별숲에서 루미단인가 루미딘인가 그게 뭐야?",
                "원신에 보디냐챠인가 보댜냐챠인가 그게 뭐야?")) {
            SelectedTerms terms = ReflectionTestUtils.invokeMethod(
                    KeywordSelectionService.class, "fallbackTerms", query, "game", 2);
            assertNotNull(terms);
            assertTrue(terms.getExact().isEmpty(), "uncertain spellings cannot become one exact phrase");
            assertTrue(terms.getMust().stream().noneMatch(t -> t.contains("인가")));
            assertEquals(1, terms.getShould().size());
            assertTrue(terms.getShould().get(0).contains(" OR "));
            SelectedTerms repaired = ReflectionTestUtils.invokeMethod(KeywordSelectionService.class,
                    "ensureMinMust", terms, query, "game", 2, "fixture");
            assertNotNull(repaired);
            assertEquals(terms.getMust(), repaired.getMust());
            assertEquals(terms.getShould(), repaired.getShould());
            if (query.startsWith("별숲")) assertEquals(List.of("별숲"), repaired.getMust());
            if (query.startsWith("원신")) assertEquals(List.of("원신"), repaired.getMust());
        }
    }

    @org.junit.jupiter.api.Test
    void explicitConstraintsAreNotRemovedAsSpellingUncertainty() {
        for (String query : List.of("\"루미단인가 루미딘인가\"", "루미단인가 루미딘인가 site:example.org -제외")) {
            SelectedTerms terms = ReflectionTestUtils.invokeMethod(
                    KeywordSelectionService.class, "fallbackTerms", query, "game", 2);
            assertNotNull(terms);
            assertFalse(terms.getMust().isEmpty());
            assertFalse(terms.getShould().stream().anyMatch(t -> t.contains(" OR ")));
        }
    }

    @org.junit.jupiter.api.Test
    void completeQueryConstraintsSurviveTruncatedAlternativeSeeds() {
        String shortQuery = "별숲에서 루미단인가 루미딘인가";
        String fullQuery = shortQuery + " " + "내용 ".repeat(60) + "site:example.org -포럼";
        GuardContext context = GuardContext.defaultContext();
        context.setUserQuery(fullQuery); GuardContextHolder.set(context);
        for (String conversation : List.of(fullQuery, shortQuery)) {
            SelectedTerms existing = new SelectedTerms();
            existing.setMust(List.of("별숲", "기존조건"));
            existing.setNegative(List.of("포럼"));
            SelectedTerms repaired = ReflectionTestUtils.invokeMethod(KeywordSelectionService.class,
                    "ensureMinMust", existing, conversation, "game", 2, "fixture");
            assertSame(existing, repaired, "complete original constraints cannot be replaced by a truncated seed");
            assertEquals(List.of("포럼"), repaired.getNegative());
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void auxiliaryFailurePreservesRiskEntityAndStrictSearchBoundary(boolean highRisk) {
        TraceStore.clear(); GuardContextHolder.clear();
        String query="Starforest Lumidan build guide";
        GuardContext context=GuardContext.defaultContext();
        context.setUserQuery(query); context.setOfficialOnly(true);
        context.setMinCitations(2); context.setHighRiskQuery(highRisk);
        GuardContextHolder.set(context);
        ChatModel model=mock(ChatModel.class);
        when(model.chat(anyList())).thenThrow(new IllegalStateException("synthetic auxiliary failure"));
        KeywordSelectionService service=new KeywordSelectionService(model,new QueryKeywordPromptBuilder());
        ReflectionTestUtils.setField(service,"irregularityProfiler",new IrregularityProfiler());
        var terms=service.select("USER: "+query,"game",3).orElseThrow();
        verify(model,times(1)).chat(anyList());
        assertEquals("fallback_exception",TraceStore.get("keywordSelection.mode"));
        assertFalse(terms.getMust().isEmpty());
        assertTrue(terms.getExact().contains("Starforest Lumidan"));
        assertEquals(highRisk,context.isHighRiskQuery(),"auxiliary transport failure cannot raise semantic query risk");
        assertTrue(context.isOfficialOnly()); assertEquals(2,context.getMinCitations());
        assertEquals(0.25,context.getIrregularityScore(),1e-9);
        assertEquals("keyword_failed",TraceStore.get("irregularity.last"));
        NovaWebFailSoftProperties props=new NovaWebFailSoftProperties();
        var aspect=new WebFailSoftSearchAspect(props,new RuleBasedQueryAugmenter(props),
                null,null,null,null,null,null,null,mock(ObjectProvider.class));
        String snippet="[WEB:NOFILTER_SAFE|CRED:UNVERIFIED] Starforest Lumidan story https://community.example/story";
        List<String> out=ReflectionTestUtils.invokeMethod(aspect,"applyStages",List.of(snippet),context,
                new RuleBasedQueryAugmenter.Augment(query,List.of(query),Set.of(),RuleBasedQueryAugmenter.Intent.GENERAL),2,query);
        assertNotNull(out);
        Boolean rescue=ReflectionTestUtils.invokeMethod(aspect,"shouldStarvationFallback",true,
                context.isHighRiskQuery(),new RuleBasedQueryAugmenter.Augment(query,List.of(query),Set.of(),
                        RuleBasedQueryAugmenter.Intent.GENERAL),List.of(),2,2,List.of(snippet),0);
        assertEquals(!highRisk,rescue,"starvation rescue eligibility must preserve semantic risk");
        if(highRisk) assertEquals("high_risk",TraceStore.get("web.failsoft.starvationFallback.skipReason"));
        else assertTrue(out.stream().anyMatch(s -> s.contains("Starforest Lumidan")
                && s.contains("https://community.example/story")),"fallback must preserve body and locator");
    }
}
