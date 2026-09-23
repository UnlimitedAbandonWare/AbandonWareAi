package com.example.lms.service.rag;

import com.example.lms.search.TraceStore;
import com.example.lms.search.policy.SearchPolicyEngine;
import com.example.lms.search.provider.WebSearchProvider;
import com.example.lms.service.rag.pre.QueryContextPreprocessor;
import com.example.lms.service.routing.plan.RoutingPlanService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AnalyzeWebSearchRetrieverPreplannedTest {
    final WebSearchProvider provider=mock(WebSearchProvider.class);
    final QueryContextPreprocessor preprocessor=mock(QueryContextPreprocessor.class);
    final RoutingPlanService planner=mock(RoutingPlanService.class);
    final SearchPolicyEngine policy=mock(SearchPolicyEngine.class);
    AnalyzeWebSearchRetriever retriever(){
        when(preprocessor.enrich(eq("raw query"),anyMap())).thenReturn("guarded query");
        when(policy.tuneTopK(anyInt(),isNull())).thenAnswer(call->call.getArgument(0));
        return new AnalyzeWebSearchRetriever(null,provider,preprocessor,planner,policy,null);
    }
    @AfterEach void clear(){TraceStore.clear();}
    @Test void preplannedQueryKeepsGuardrailsAndPolicyWithoutCallingLlmPlanner(){
        var retriever=retriever();when(provider.search("guarded query",3)).thenReturn(List.of("synthetic evidence"));
        var query=QueryUtils.buildQuery("raw query",Map.of("webQueryAlreadyPlanned",true,"webTopK",3));
        assertEquals(1,retriever.retrieve(query).size());
        verify(preprocessor).enrich(eq("raw query"),anyMap());verify(policy).decide(eq("guarded query"),anyMap());
        verify(provider).search("guarded query",3);verifyNoInteractions(planner);
        assertEquals(true,TraceStore.get("web.analyze.queryPlanningSkipped"));
        assertTrue(TraceStore.get("web.analyze.preparationMs") instanceof Long);
    }
    @Test void ordinaryQueryStillUsesExistingPlanner(){
        var retriever=retriever();when(planner.plan("guarded query",null,8)).thenReturn(List.of("planned query"));
        when(provider.search("planned query",10)).thenReturn(List.of("synthetic evidence"));
        assertEquals(1,retriever.retrieve(QueryUtils.buildQuery("raw query")).size());
        verify(planner).plan("guarded query",null,8);verify(provider).search("planned query",10);
        assertEquals(false,TraceStore.get("web.analyze.queryPlanningSkipped"));
    }
    @Test void preplannedQueryCannotExpandThroughSearchPolicy(){
        var retriever=retriever();
        var decision=com.example.lms.search.policy.SearchPolicyDecision.off("fixture");
        when(policy.decide(eq("guarded query"),anyMap())).thenReturn(decision);
        when(policy.tuneTopK(3,decision)).thenReturn(3);
        when(policy.apply(anyList(),eq("guarded query"),eq(decision))).thenReturn(List.of("expanded one","expanded two"));
        when(provider.search("guarded query",3)).thenReturn(List.of("synthetic evidence"));
        assertEquals(1,retriever.retrieve(QueryUtils.buildQuery("raw query",Map.of("webQueryAlreadyPlanned",true,"webTopK",3))).size());
        verify(provider).search("guarded query",3);verifyNoMoreInteractions(provider);
        verify(policy,never()).apply(anyList(),anyString(),any());verifyNoInteractions(planner);
    }
}
