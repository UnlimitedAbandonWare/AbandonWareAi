package com.example.lms.service.rag.handler;

import com.example.lms.assist.*;
import com.example.lms.assist.JevChoiceAdvisor.*;
import com.example.lms.service.rag.*;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class JevComplexityStageContractTest {
    @Test void failedEnvelopeRetainsBaselineEvenWithValidComplexity() throws Exception {
        try(var h=harness("SIMPLE")){
            h.responseStatus=500;h.responseReason="upstream_error";
            var original=query(Map.of());var gate=new QueryComplexityGate();
            var baseline=gate.assess(original.text());
            h.wrap((q,a)->{
                JevRetrievalGateHandler.prefetch(q);
                assertSame(q,JevRetrievalGateHandler.applyHints(q));
                assertEquals(baseline,JevRetrievalGateHandler.complexityLevel(q,gate));
                assertEquals("baseline",JevRetrievalGateContractTest.trace("complexity.source"));
                assertNull(JevRetrievalGateContractTest.trace("complexity.level"));
                assertEquals(false,JevRetrievalGateContractTest.trace("complexity.applied"));
            },true).handle(original,new ArrayList<>());
        }
    }
    @Test void fallbackProvenanceSurvivesLaterObservation() throws Exception {
        try(var h=harness("SIMPLE")) {
            h.answers=Map.of("complexity",new ChoiceObservation("SIMPLE",OptionalDouble.of(0.1),true,false));
            var gate=spy(new QueryComplexityGate());
            h.wrap((q,a)->{
                JevRetrievalGateHandler.prefetch(q);
                var first=JevRetrievalGateHandler.complexityLevel(q,gate);
                assertNotNull(first);assertEquals("baseline",JevRetrievalGateContractTest.trace("complexity.source"));
                assertNull(JevRetrievalGateContractTest.trace("complexity.level"));
                assertEquals(false,JevRetrievalGateContractTest.trace("complexity.applied"));
                assertEquals(first.name(),JevRetrievalGateContractTest.trace("complexity.effectiveLevel"));
                h.env.setProperty("demo.jev.confidence.complexity-simple","0.05");
                assertEquals(first,JevRetrievalGateHandler.complexityLevel(q,gate));
                assertEquals("baseline",JevRetrievalGateContractTest.trace("complexity.source"));
                assertNull(JevRetrievalGateContractTest.trace("complexity.level"));
                verify(gate,times(1)).assess(any(),any());
            },true).handle(query(Map.of()),new ArrayList<>());
        }
    }
    @Test void acceptedClassificationRecordsJevProvenance() throws Exception {
        try(var h=harness("SIMPLE")) {
            h.wrap((q,a)->{
                JevRetrievalGateHandler.prefetch(q);
                assertEquals(QueryComplexityGate.Level.SIMPLE,JevRetrievalGateHandler.complexityLevel(q,new QueryComplexityGate()));
                assertEquals("jev",JevRetrievalGateContractTest.trace("complexity.source"));
                assertEquals("SIMPLE",JevRetrievalGateContractTest.trace("complexity.level"));
                assertEquals("SIMPLE",JevRetrievalGateContractTest.trace("complexity.effectiveLevel"));
                assertEquals(true,JevRetrievalGateContractTest.trace("complexity.applied"));
            },true).handle(query(Map.of()),new ArrayList<>());
        }
    }
    private JevRetrievalGateContractTest.Harness harness(String complexity) throws Exception {
        var h=new JevRetrievalGateContractTest.Harness();
        h.env.setProperty("demo.jev.seams.search-need","off");
        h.env.setProperty("demo.jev.seams.complexity","on");
        h.env.setProperty("demo.jev.confidence.default","0.8");
        h.env.setProperty("demo.jev.confidence.complexity-simple","0.8");
        h.answers=Map.of("complexity",new ChoiceObservation(complexity,OptionalDouble.of(0.9),true,false));
        return h;
    }
    private static final class Stages {
        final SelfAskWebSearchRetriever self=mock(SelfAskWebSearchRetriever.class);
        final AnalyzeWebSearchRetriever analyze=mock(AnalyzeWebSearchRetriever.class);
        final QueryComplexityGate gate=spy(new QueryComplexityGate());
        RetrievalHandler chain(boolean dynamic) {
            if(dynamic)return new DynamicRetrievalHandlerChain(null,self,analyze,null,null,null,null,null,gate,null,null,null,null,null,null);
            var orchestration=new OrchestrationGate(null);var first=new SelfAskHandler(self,orchestration);
            first.linkWith(new AnalyzeHandler(analyze,orchestration));return first;
        }
    }
    private Query query(Map<String,Object> hints) {return QueryUtils.buildQuery("복잡한 문제를 어떻게 해결하는지 알려줘",hints);}
    @Test void nightmareAndAuxDownOverrideComplex() throws Exception {
        for(boolean dynamic:List.of(false,true))for(String deny:List.of("nightmareMode","auxLlmDown","strikeMode"))
            try(var h=harness("COMPLEX")) {
                var s=new Stages();h.wrap(s.chain(dynamic),dynamic).handle(query(Map.of(deny,true)),new ArrayList<>());
                assertEquals(1,h.calls.get());verify(s.self,never()).retrieve(any());verify(s.analyze,never()).retrieve(any());
            }
    }
    @Test void compressionKeepsExistingAsymmetricPolicy() throws Exception {
        try(var h=harness("COMPLEX")) {
            var s=new Stages();h.wrap(s.chain(true),true).handle(query(Map.of("compressionMode",true)),new ArrayList<>());
            verify(s.self,never()).retrieve(any());verify(s.analyze,times(1)).retrieve(any());assertEquals(1,h.calls.get());
        }
    }
    @Test void sidecarHintsAreHonoredByDynamicConsumers() throws Exception {
        try(var h=harness("COMPLEX")) {
            var s=new Stages();h.wrap(s.chain(true),true).handle(query(Map.of("enableSelfAsk",false,"enableAnalyze",false)),new ArrayList<>());
            verify(s.self,never()).retrieve(any());verify(s.analyze,never()).retrieve(any());
            verify(s.gate,times(1)).assess(any(),any());assertEquals(1,h.calls.get());
        }
    }
    @Test void fixedFailureLeavesOriginalExecutionUnchanged() throws Exception {
        try(var h=harness("SIMPLE")) {
            h.answers=Map.of();var s=new Stages();var original=query(Map.of());
            h.wrap(s.chain(false),false).handle(original,new ArrayList<>());
            verify(s.self,times(1)).retrieve(same(original));verify(s.analyze,times(1)).retrieve(same(original));
        }
    }
    @Test void disagreementUsesPerQuestionFallback() throws Exception {
        try(var h=harness("SIMPLE")) {
            h.env.setProperty("demo.jev.seams.search-need","on");
            h.answers=Map.of("webNeed",new ChoiceObservation("DEEP",OptionalDouble.of(0.1),true,false),
                    "complexity",new ChoiceObservation("SIMPLE",OptionalDouble.of(0.9),true,false));
            var s=new Stages();var original=query(Map.of("allowWeb",true,"useWebSearch",true));
            h.wrap(s.chain(true),true).handle(original,new ArrayList<>());
            verify(s.self,never()).retrieve(any());verify(s.analyze,never()).retrieve(any());
            assertEquals(true,QueryUtils.metadata(original).get("allowWeb"));assertEquals(1,h.calls.get());
        }
    }
    @Test void requestScopeClearedAfterException() throws Exception {
        try(var h=harness("SIMPLE")) {
            assertThrows(IllegalStateException.class,()->h.wrap((q,a)->{throw new IllegalStateException("synthetic");},false).handle(query(Map.of()),new ArrayList<>()));
            assertNull(JevDecisionScope.capture());
            var s=new Stages();h.wrap(s.chain(false),false).handle(query(Map.of()),new ArrayList<>());
            verify(s.self,never()).retrieve(any());verify(s.analyze,never()).retrieve(any());assertEquals(2,h.calls.get());
        }
    }
    @Test void ambiguousAndComplexKeepOnlyExistingAllowedStages() throws Exception {
        for(boolean dynamic:List.of(false,true))for(String choice:List.of("AMBIGUOUS","COMPLEX"))try(var h=harness(choice)) {
            var s=new Stages();h.wrap(s.chain(dynamic),dynamic).handle(query(Map.of()),new ArrayList<>());
            verify(s.self,times(choice.equals("COMPLEX")?1:0)).retrieve(any());
            verify(s.analyze,times(1)).retrieve(any());assertEquals(1,h.calls.get());
        }
    }
    @Test void classifierFailurePreservesDynamicFailSoft() throws Exception {
        try(var h=harness("COMPLEX")) {
            var s=new Stages();
            doThrow(new IllegalStateException("synthetic classifier failure")).when(s.gate).assess(any(),any());
            assertDoesNotThrow(()->h.wrap(s.chain(true),true).handle(query(Map.of()),new ArrayList<>()));
            verify(s.self,times(1)).retrieve(any());verify(s.analyze,times(1)).retrieve(any());
            assertEquals(1,h.calls.get());assertNull(JevDecisionScope.capture());
        }
    }
    @Test void webDenyAndGlobalOffCannotBeOverridden() throws Exception {
        try(var h=harness("COMPLEX")) {
            var s=new Stages();h.wrap(s.chain(true),true).handle(query(Map.of("allowWeb",false)),new ArrayList<>());
            verify(s.self,never()).retrieve(any());verify(s.analyze,never()).retrieve(any());
        }
        try(var h=harness("SIMPLE")) {
            h.env.setProperty("demo.jev.mode","off");var s=new Stages();var original=query(Map.of());
            h.wrap(s.chain(false),false).handle(original,new ArrayList<>());
            verify(s.self,times(1)).retrieve(same(original));verify(s.analyze,times(1)).retrieve(same(original));
            assertEquals(0,h.calls.get());
        }
    }
}
