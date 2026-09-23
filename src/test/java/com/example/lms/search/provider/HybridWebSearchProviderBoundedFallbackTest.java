package com.example.lms.search.provider;

import com.example.lms.learning.gemini.GeminiGateway;
import com.example.lms.search.TraceStore;
import com.example.lms.service.NaverSearchService;
import com.example.lms.service.web.BraveSearchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HybridWebSearchProviderBoundedFallbackTest {

    @Test void conversateNaverEnoughSkipsBraveAndAllExpansion() {
        TraceStore.put("conversate.web.singleCycle",true);
        var naver=usableNaver();var brave=usableBrave();var gateway=mock(GeminiGateway.class);
        var hits=List.of("서울 지하철 이용 방법 안내", "서울 지하철 노선 이용 안내", "서울 지하철 승차권 안내");
        when(naver.searchSnippetsSync(anyString(),eq(3))).thenReturn(hits);
        assertEquals(hits,boundedProvider(naver,brave,gateway).search("서울 지하철 이용 방법",3));
        assertEquals("provider_search_method",((java.util.Map<?,?>)TraceStore.get("conversate.search.NAVER")).get("requestCountScope"));
        verify(brave,never()).searchWithMeta(anyString(),eq(3));
        verify(gateway,never()).expandSearchQueryOnce(anyString());
    }

    @Test void conversateNaverFailureDoesNotCascadeToBrave() {
        for(String failure:List.of("AUTH_OR_CONFIG","RATE_LIMIT","TIMEOUT_OR_BUDGET","PROVIDER_ERROR")){
            TraceStore.clear();TraceStore.put("conversate.web.singleCycle",true);
            var naver=usableNaver();var brave=usableBrave();var gateway=mock(GeminiGateway.class);
            var trace=new NaverSearchService.SearchTrace();ReflectionTestUtils.setField(trace,"outcomeClass",failure);
            org.mockito.Mockito.doReturn(new NaverSearchService.SearchResult(List.of(),trace)).when(naver).searchWithTraceSync(anyString(),eq(3));
            assertEquals(List.of(),boundedProvider(naver,brave,gateway).search("서울 지하철 이용 방법",3));
            verify(brave,never()).searchWithMeta(anyString(),eq(3));
            assertEquals(failure,TraceStore.get("web.boundedRoute.terminalReason"));
        }
    }

    @Test void conversateLatestRequestMergesNaverThenBrave() {
        TraceStore.put("conversate.web.singleCycle",true);
        var naver=usableNaver();var brave=usableBrave();var gateway=mock(GeminiGateway.class);
        when(naver.searchSnippetsSync(anyString(),eq(3))).thenReturn(List.of("서울 최신 교통 안내"));
        when(brave.search(anyString(),eq(3))).thenReturn(List.of("서울 최신 교통 교차검증"));
        assertEquals(List.of("서울 최신 교통 안내","서울 최신 교통 교차검증"),boundedProvider(naver,brave,gateway).search("서울 최신 교통 정보",3));
        var order=inOrder(naver,brave);order.verify(naver).searchWithTraceSync(anyString(),eq(3));order.verify(brave).searchWithMeta(anyString(),eq(3));
    }

    @Test void conversateLowRelevanceAndTrueZeroUseOnlyOneBraveSupplement() {
        for(var hits:List.of(List.<String>of(),List.of("축구 경기 결과","영화 개봉 안내","게임 소식"))){
            TraceStore.clear();TraceStore.put("conversate.web.singleCycle",true);
            var naver=usableNaver();var brave=usableBrave();var gateway=mock(GeminiGateway.class);
            when(naver.searchSnippetsSync(anyString(),eq(3))).thenReturn(hits);
            when(brave.search(anyString(),eq(3))).thenReturn(List.of("서울 지하철 이용 안내"));
            var results=boundedProvider(naver,brave,gateway).search("서울 지하철 이용 방법",3);
            assertEquals(List.of("서울 지하철 이용 안내"),results);
            verify(brave,times(1)).searchWithMeta(anyString(),eq(3));
            verify(naver,times(1)).searchWithTraceSync(anyString(),eq(3));
            verify(gateway,never()).expandSearchQueryOnce(anyString());
            var receipt=(java.util.Map<?,?>)TraceStore.get("conversate.search.BRAVE");
            assertEquals(hits.isEmpty()?"insufficient_results":"low_relevance_or_coverage",receipt.get("fallbackReason"));
            assertFalse(receipt.toString().contains("서울"));
        }
    }

    @Test
    void conversateSingleCycleNeverExpandsEvenWhenGlobalBoundedModeIsOff() {
        for (boolean bounded : List.of(true, false)) {
            TraceStore.clear();TraceStore.put("conversate.web.singleCycle",true);
            var brave=usableBrave();var naver=usableNaver();var gateway=mock(GeminiGateway.class);
            when(brave.search(anyString(),eq(3))).thenReturn(List.of());
            when(naver.searchSnippetsSync(anyString(),eq(3))).thenReturn(List.of());
            when(gateway.expandSearchQueryOnce(anyString())).thenReturn(Mono.just(expansion("expanded query","")));
            var provider=boundedProvider(naver,brave,gateway);
            ReflectionTestUtils.setField(provider,"boundedFallbackEnabled",bounded);
            assertEquals(List.of(),provider.search("synthetic current subject",3));
            verify(brave,times(1)).searchWithMeta(anyString(),eq(3));
            verify(naver,times(1)).searchWithTraceSync(anyString(),eq(3));
            verify(gateway,never()).expandSearchQueryOnce(anyString());
            assertEquals(1,TraceStore.get("web.boundedRoute.providerCycles"));
        }
    }

    @Test
    void providerFailuresAreNotZeroResultsAndDoNotTriggerGeminiExpansion() {
        var statuses=List.of(com.example.lms.service.web.BraveSearchResult.Status.HTTP_ERROR,
                com.example.lms.service.web.BraveSearchResult.Status.HTTP_429,
                com.example.lms.service.web.BraveSearchResult.Status.HTTP_ERROR,
                com.example.lms.service.web.BraveSearchResult.Status.EXCEPTION);
        var codes=List.of(401,429,504,200);
        var reasons=List.of("AUTH_OR_CONFIG","RATE_LIMIT","TIMEOUT_OR_BUDGET","PARSE_ERROR");
        for(int i=0;i<statuses.size();i++){
            TraceStore.clear();var brave=usableBrave();var naver=mock(NaverSearchService.class);var gateway=mock(GeminiGateway.class);
            org.mockito.Mockito.doReturn(new com.example.lms.service.web.BraveSearchResult(List.of(),statuses.get(i),codes.get(i),0,i==3?"json-parse-error":"synthetic-private-error",0)).when(brave).searchWithMeta(anyString(),eq(3));
            when(gateway.expandSearchQueryOnce(anyString())).thenReturn(Mono.just(expansion("unnecessary expanded query","")));
            var result=boundedProvider(naver,brave,gateway).searchWithTrace("synthetic original query",3);
            assertEquals("BOUNDED:"+reasons.get(i),result.trace().steps.get(0).query);
            verify(gateway,never()).expandSearchQueryOnce(anyString());verify(brave,times(1)).searchWithMeta(anyString(),eq(3));
        }
    }

    @Test
    void completedCacheHitAvoidsOutboundMetadataAndFallbackCalls() {
        NaverSearchService naver=usableNaver();BraveSearchService brave=usableBrave();
        GeminiGateway gateway=mock(GeminiGateway.class);
        when(brave.searchCacheOnly("cached subject",3)).thenReturn(List.of("cached result"));
        assertEquals(List.of("cached result"),boundedProvider(naver,brave,gateway).search("cached subject",3));
        verify(brave,times(1)).searchCacheOnly("cached subject",3);
        verify(brave,never()).searchWithMeta(anyString(),org.mockito.ArgumentMatchers.anyInt());
        verify(naver,never()).searchWithTraceSync(anyString(),org.mockito.ArgumentMatchers.anyInt());
        verify(gateway,never()).expandSearchQueryOnce(anyString());
    }
    @Test void postRagSupplementNeverRepeatsNaverOrBrave(){
        TraceStore.put("conversate.web.singleCycle",true);
        var naver=usableNaver();var brave=usableBrave();var gateway=mock(GeminiGateway.class);
        when(naver.searchSnippetsSync(anyString(),eq(3))).thenReturn(List.of("서울 지하철 첫 안내","서울 지하철 둘째 안내","서울 지하철 셋째 안내"));
        when(brave.search(anyString(),eq(3))).thenReturn(List.of("서울 지하철 추가 근거"));
        var provider=boundedProvider(naver,brave,gateway);
        provider.search("서울 지하철",3);verify(brave,never()).searchWithMeta(anyString(),org.mockito.ArgumentMatchers.anyInt());
        TraceStore.put("conversate.web.ragSupplement",true);
        assertEquals(List.of("서울 지하철 추가 근거"),provider.search("서울 지하철",3));
        assertEquals(List.of(),provider.search("서울 지하철",3));
        verify(naver,times(1)).searchWithTraceSync(anyString(),eq(3));verify(brave,times(1)).searchWithMeta(anyString(),eq(3));
        verify(gateway,never()).expandSearchQueryOnce(anyString());
        assertEquals("rag_evidence_insufficient",((java.util.Map<?,?>)TraceStore.get("conversate.search.BRAVE")).get("fallbackReason"));
    }
    @Test void postRagSupplementRejectsMissingOrChangedInitialQueryAndPrivacyBlock(){
        TraceStore.put("conversate.web.singleCycle",true);TraceStore.put("conversate.web.ragSupplement",true);
        var naver=usableNaver();var brave=usableBrave();var provider=boundedProvider(naver,brave,null);
        assertEquals(List.of(),provider.search("서울 지하철",3));
        TraceStore.context().remove("conversate.web.ragSupplement");
        when(naver.searchSnippetsSync(anyString(),eq(3))).thenReturn(List.of("서울 지하철 안내1","서울 지하철 안내2"));
        provider.search("서울 지하철",3);TraceStore.put("conversate.web.ragSupplement",true);
        assertEquals(List.of(),provider.search("다른 질문",3));
        ReflectionTestUtils.setField(provider,"blockWebSearch",true);
        assertEquals(List.of(),provider.search("서울 지하철",3));
        verify(naver,times(1)).searchWithTraceSync(anyString(),eq(3));verify(brave,never()).searchWithMeta(anyString(),org.mockito.ArgumentMatchers.anyInt());
    }
    @Test void postRagBraveFailuresAndCacheHitsAreTerminal(){
        for(String mode:List.of("cache","disabled","quota","timeout","error","zero")){
            TraceStore.clear();TraceStore.put("conversate.web.singleCycle",true);
            var naver=usableNaver();var brave=usableBrave();var gateway=mock(GeminiGateway.class);
            when(naver.searchSnippetsSync(anyString(),eq(3))).thenReturn(List.of("서울 지하철 안내1","서울 지하철 안내2"));
            var provider=boundedProvider(naver,brave,gateway);provider.search("서울 지하철",3);
            String expectedFailure=switch(mode){case "disabled"->"AUTH_OR_CONFIG";case "quota"->"RATE_LIMIT";case "timeout"->"TIMEOUT_OR_BUDGET";case "error"->"PROVIDER_ERROR";case "zero"->"TRUE_ZERO";default->"NONE";};
            if(mode.equals("cache"))when(brave.searchCacheOnly("서울 지하철",3)).thenReturn(List.of("캐시된 추가 자료"));
            else if(mode.equals("disabled"))when(brave.isEnabled()).thenReturn(false);
            else {
                int status=switch(mode){case "quota"->429;case "timeout"->504;case "error"->500;default->200;};
                var state=mode.equals("quota")?com.example.lms.service.web.BraveSearchResult.Status.HTTP_429:
                        mode.equals("zero")?com.example.lms.service.web.BraveSearchResult.Status.OK:com.example.lms.service.web.BraveSearchResult.Status.HTTP_ERROR;
                org.mockito.Mockito.doReturn(new com.example.lms.service.web.BraveSearchResult(List.of(),state,status,0,"synthetic",0))
                        .when(brave).searchWithMeta("서울 지하철",3);
            }
            TraceStore.put("conversate.web.ragSupplement",true);var result=provider.search("서울 지하철",3);
            assertEquals(mode.equals("cache")?List.of("캐시된 추가 자료"):List.of(),result);
            assertEquals(List.of(),provider.search("서울 지하철",3));
            var receipt=(java.util.Map<?,?>)TraceStore.get("conversate.search.BRAVE");
            assertEquals(expectedFailure,receipt.get("failureReason"));
            assertEquals(mode.equals("cache")||mode.equals("disabled")?0:1,receipt.get("requestCount"));
            verify(naver,times(1)).searchWithTraceSync(anyString(),eq(3));verify(gateway,never()).expandSearchQueryOnce(anyString());
        }
    }
    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void braveHitStopsBeforeNaverLocalRewriteAndGemini() {
        NaverSearchService naver = usableNaver();
        BraveSearchService brave = usableBrave();
        GeminiGateway gateway = mock(GeminiGateway.class);
        when(brave.search(anyString(), eq(5))).thenReturn(List.of("brave-hit"));
        HybridWebSearchProvider provider = boundedProvider(naver, brave, gateway);

        assertEquals(List.of("brave-hit"), provider.search("bounded search query", 5));

        verify(brave, times(1)).search("bounded search query", 5);
        verify(naver, never()).searchSnippetsSync(anyString(), eq(5));
        verify(gateway, never()).expandSearchQueryOnce(anyString());
    }

    @Test
    void emptyInitialCycleUsesOneGeminiExpansionAndExactlyOneResearchCycle() {
        NaverSearchService naver = usableNaver();
        BraveSearchService brave = usableBrave();
        GeminiGateway gateway = mock(GeminiGateway.class);
        String original = "What are the verified official release date details for Alpha Product";
        String expanded = original + " documentation";
        when(brave.search(anyString(), eq(5))).thenReturn(List.of(), List.of());
        when(naver.searchSnippetsSync(anyString(), eq(5)))
                .thenReturn(List.of(), List.of("naver-research-hit"));
        when(gateway.expandSearchQueryOnce(anyString()))
                .thenReturn(Mono.just(expansion(expanded, "")));
        HybridWebSearchProvider provider = boundedProvider(naver, brave, gateway);

        assertEquals(List.of("naver-research-hit"), provider.search(original, 5));

        verify(brave, times(2)).search(anyString(), eq(5));
        verify(brave, times(2)).searchCacheOnly(anyString(), eq(5));
        verify(brave, times(2)).searchWithMeta(anyString(), eq(5));
        verify(naver, times(2)).searchWithTraceSync(anyString(), eq(5));
        verify(naver, times(2)).searchSnippetsSync(anyString(), eq(5));
        verify(gateway, times(1)).expandSearchQueryOnce(anyString());
        InOrder order = inOrder(brave, naver, gateway);
        order.verify(brave).search(original, 5);
        order.verify(naver).searchSnippetsSync(original, 5);
        order.verify(gateway).expandSearchQueryOnce(anyString());
        order.verify(brave).search(expanded, 5);
        order.verify(naver).searchSnippetsSync(expanded, 5);
    }

    @Test
    void disabledOrBlankGeminiExpansionDoesNotResearch() {
        NaverSearchService naver = usableNaver();
        BraveSearchService brave = usableBrave();
        GeminiGateway gateway = mock(GeminiGateway.class);
        when(brave.search(anyString(), eq(5))).thenReturn(List.of());
        when(naver.searchSnippetsSync(anyString(), eq(5))).thenReturn(List.of());
        when(gateway.expandSearchQueryOnce(anyString()))
                .thenReturn(Mono.just(expansion("", "purpose-disabled")));
        HybridWebSearchProvider provider = boundedProvider(naver, brave, gateway);

        assertEquals(List.of(), provider.search("empty bounded search query", 5));

        verify(brave, times(1)).search(anyString(), eq(5));
        verify(naver, times(1)).searchSnippetsSync(anyString(), eq(5));
        verify(gateway, times(1)).expandSearchQueryOnce(anyString());
    }

    @Test
    void emptyResearchCycleTerminatesWithoutThirdCycleOrRecursion() {
        NaverSearchService naver = usableNaver();
        BraveSearchService brave = usableBrave();
        GeminiGateway gateway = mock(GeminiGateway.class);
        when(brave.search(anyString(), eq(5))).thenReturn(List.of());
        when(naver.searchSnippetsSync(anyString(), eq(5))).thenReturn(List.of());
        when(gateway.expandSearchQueryOnce(anyString()))
                .thenReturn(Mono.just(expansion("empty bounded search query evidence", "")));
        HybridWebSearchProvider provider = boundedProvider(naver, brave, gateway);

        assertEquals(List.of(), provider.search("empty bounded search query", 5));

        verify(brave, times(2)).search(anyString(), eq(5));
        verify(brave, times(2)).searchCacheOnly(anyString(), eq(5));
        verify(brave, times(2)).searchWithMeta(anyString(), eq(5));
        verify(naver, times(2)).searchWithTraceSync(anyString(), eq(5));
        verify(naver, times(2)).searchSnippetsSync(anyString(), eq(5));
        verify(gateway, times(1)).expandSearchQueryOnce(anyString());
    }

    @Test
    void traceEntryPointReusesTheSameBoundedProviderBudget() {
        NaverSearchService naver = usableNaver();
        BraveSearchService brave = usableBrave();
        GeminiGateway gateway = mock(GeminiGateway.class);
        when(brave.search(anyString(), eq(4))).thenReturn(List.of());
        when(naver.searchSnippetsSync(anyString(), eq(4))).thenReturn(List.of());
        when(gateway.expandSearchQueryOnce(anyString()))
                .thenReturn(Mono.just(expansion("", "purpose-disabled")));
        HybridWebSearchProvider provider = boundedProvider(naver, brave, gateway);

        NaverSearchService.SearchResult result = provider.searchWithTrace("bounded trace query", 4);

        assertEquals(List.of(), result.snippets());
        assertEquals(1, result.trace().steps.size());
        assertEquals("BOUNDED:purpose-disabled", result.trace().steps.get(0).query);
        assertEquals(0, result.trace().steps.get(0).returned);
        assertEquals(0, result.trace().steps.get(0).afterFilter);
        verify(brave, times(1)).search(anyString(), eq(4));
        verify(naver, times(1)).searchSnippetsSync(anyString(), eq(4));
        verify(gateway, times(1)).expandSearchQueryOnce(anyString());
    }

    @Test
    void boundedTraceDistinguishesEmptyResearchFromExpansionFailure() {
        NaverSearchService naver = usableNaver();
        BraveSearchService brave = usableBrave();
        GeminiGateway gateway = mock(GeminiGateway.class);
        when(brave.search(anyString(), eq(4))).thenReturn(List.of());
        when(naver.searchSnippetsSync(anyString(), eq(4))).thenReturn(List.of());
        when(gateway.expandSearchQueryOnce(anyString()))
                .thenReturn(Mono.just(expansion("synthetic expanded query", "")))
                .thenReturn(Mono.error(new IllegalStateException("synthetic private error")));
        HybridWebSearchProvider provider = boundedProvider(naver, brave, gateway);

        NaverSearchService.SearchResult empty = provider.searchWithTrace("synthetic query", 4);
        NaverSearchService.SearchResult failed = provider.searchWithTrace("synthetic query", 4);

        assertEquals(List.of(), empty.snippets());
        assertEquals(List.of(), failed.snippets());
        assertEquals("BOUNDED:research-empty", empty.trace().steps.get(0).query);
        assertEquals("BOUNDED:gemini-expansion-failed", failed.trace().steps.get(0).query);
        verify(brave, times(3)).search(anyString(), eq(4));
        verify(naver, times(3)).searchSnippetsSync(anyString(), eq(4));
        verify(gateway, times(2)).expandSearchQueryOnce(anyString());
    }

    @Test
    void boundedTraceRedactsQueryAndReportsReturnedResults() {
        NaverSearchService naver = usableNaver();
        BraveSearchService brave = usableBrave();
        when(brave.search(anyString(), eq(4))).thenReturn(List.of("synthetic result"));
        HybridWebSearchProvider provider = boundedProvider(naver, brave, null);
        String query = "synthetic confidential query";

        NaverSearchService.SearchResult result = provider.searchWithTrace(query, 4);

        assertEquals(List.of("synthetic result"), result.snippets());
        assertNotNull(result.trace().queryHash);
        assertFalse(result.trace().queryHash.isBlank());
        assertFalse(result.trace().query.contains(query));
        assertEquals(query.length(), result.trace().queryLength);
        assertEquals("BOUNDED:initial-hit", result.trace().steps.get(0).query);
        assertEquals(1, result.trace().steps.get(0).returned);
        assertEquals(1, result.trace().steps.get(0).afterFilter);
        assertEquals(result.trace().totalMs, result.trace().steps.get(0).tookMs);
    }

    @Test
    void guardedTraceDoesNotReuseThePreviousTerminalReason() {
        NaverSearchService naver = usableNaver();
        BraveSearchService brave = usableBrave();
        when(brave.search(anyString(), eq(4))).thenReturn(List.of("synthetic result"));
        HybridWebSearchProvider provider = boundedProvider(naver, brave, null);
        provider.searchWithTrace("synthetic query", 4);

        assertEquals("BOUNDED:blank-query", provider.searchWithTrace(" ", 4).trace().steps.get(0).query);
        ReflectionTestUtils.setField(provider, "blockWebSearch", true);
        assertEquals("BOUNDED:privacy-blocked", provider.searchWithTrace("synthetic query", 4)
                .trace().steps.get(0).query);
        try {
            Thread.currentThread().interrupt();
            assertNull(provider.searchWithTrace("synthetic query", 4).trace());
        } finally {
            Thread.interrupted();
        }
        verify(brave, times(1)).search(anyString(), eq(4));
        verify(naver, never()).searchSnippetsSync(anyString(), eq(4));
    }

    private static HybridWebSearchProvider boundedProvider(
            NaverSearchService naver,
            BraveSearchService brave,
            GeminiGateway gateway) {
        HybridWebSearchProvider provider = new HybridWebSearchProvider(naver, brave);
        ReflectionTestUtils.setField(provider, "boundedFallbackEnabled", true);
        ReflectionTestUtils.setField(provider, "geminiGateway", gateway);
        return provider;
    }

    private static BraveSearchService usableBrave() {
        BraveSearchService brave = mock(BraveSearchService.class);
        when(brave.isEnabled()).thenReturn(true);
        when(brave.isCoolingDown()).thenReturn(false);
        // The existing list fixtures also supply the richer outcome consumed by the bounded path.
        when(brave.searchWithMeta(anyString(),org.mockito.ArgumentMatchers.anyInt())).thenAnswer(call->
                com.example.lms.service.web.BraveSearchResult.ok(brave.search(call.getArgument(0),call.getArgument(1)),0));
        return brave;
    }

    private static NaverSearchService usableNaver() {
        NaverSearchService naver = mock(NaverSearchService.class);
        when(naver.isEnabled()).thenReturn(true);
        when(naver.searchWithTraceSync(anyString(),org.mockito.ArgumentMatchers.anyInt())).thenAnswer(call->{
            List<String> snippets=naver.searchSnippetsSync(call.getArgument(0),call.getArgument(1));
            var trace=new NaverSearchService.SearchTrace();
            ReflectionTestUtils.setField(trace,"outcomeClass",snippets.isEmpty()?"TRUE_ZERO":"NONE");
            return new NaverSearchService.SearchResult(snippets,trace);
        });
        return naver;
    }

    @Test
    void expansionCannotDropOriginalSubjectDateSourceOrExclusion(){
        var brave=usableBrave();var naver=usableNaver();var gateway=mock(GeminiGateway.class);
        when(brave.search(anyString(),eq(3))).thenReturn(List.of());
        when(naver.searchSnippetsSync(anyString(),eq(3))).thenReturn(List.of());
        when(gateway.expandSearchQueryOnce(anyString())).thenReturn(Mono.just(expansion("Beta Product official sources","")));
        String original="Alpha Product after:2026-09-01 site:example.org -rumor";
        var result=boundedProvider(naver,brave,gateway).searchWithTrace(original,3);
        assertEquals("BOUNDED:query-constraints-changed",result.trace().steps.get(0).query);
        verify(gateway).expandSearchQueryOnce(original);
        verify(brave,times(1)).search(anyString(),eq(3));verify(naver,times(1)).searchSnippetsSync(anyString(),eq(3));
    }

    private static GeminiGateway.SearchExpansion expansion(String query, String fallbackReason) {
        return new GeminiGateway.SearchExpansion(query, new GeminiGateway.ProviderStatus(
                "gemini",
                "search-expansion",
                "gemini-2.5-flash-test",
                fallbackReason == null || fallbackReason.isBlank(),
                true,
                1,
                200,
                1L,
                false,
                "allowed",
                fallbackReason == null ? "" : fallbackReason,
                ""));
    }
}
