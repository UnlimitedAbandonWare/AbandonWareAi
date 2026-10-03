package com.example.lms.gptsearch.decision;

import com.example.lms.assist.JevChoiceAdvisor.ChoiceObservation;
import com.example.lms.gptsearch.dto.SearchMode;
import com.example.lms.gptsearch.web.ProviderId;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.util.List;
import java.util.OptionalDouble;
import static org.junit.jupiter.api.Assertions.*;

class JevSearchNeedAdvisorTest {
    private final MockEnvironment env = new MockEnvironment().withProperty("demo.jev.mode","on")
            .withProperty("demo.jev.choice.enabled","true")
            .withProperty("demo.jev.seams.search-need","on")
            .withProperty("demo.jev.confidence.web-disable","0.8")
            .withProperty("demo.jev.confidence.web-light","0.8")
            .withProperty("demo.jev.confidence.web-deep","0.8");
    private final JevSearchNeedAdvisor advisor = new JevSearchNeedAdvisor(env);
    private final JevSearchNeedAdvisor.SearchPermission allowed =
            new JevSearchNeedAdvisor.SearchPermission(true,true,true,false,true);
    private SearchDecision base(boolean search,String reason) {
        return new SearchDecision(search,SearchDecision.Depth.LIGHT,List.of(ProviderId.NAVER),7,reason);
    }
    private ChoiceObservation obs(String choice,double probability) {
        return new ChoiceObservation(choice,OptionalDouble.of(probability),true,true);
    }
    @Test void stableConceptCanDemoteOnlyHeuristicLight() {
        var result=advisor.apply(base(true,"Question detected triggers light search"),SearchMode.AUTO,obs("NONE",0.8),allowed);
        assertFalse(result.shouldSearch());
    }
    @Test void protectedReasonsNeverChange() {
        for(String reason:List.of("Explicit lookup","Fact check","Recency","Arithmetic","Session context","unknown","")) {
            var baseline=base(true,reason);
            assertSame(baseline,advisor.apply(baseline,SearchMode.AUTO,obs("NONE",1),allowed),reason);
        }
    }
    @Test void offAndForceModesNeverChange() {
        for(var mode:SearchMode.values())if(mode!=SearchMode.AUTO) {
            var baseline=base(true,"Question detected triggers light search");
            assertSame(baseline,advisor.apply(baseline,mode,obs("NONE",1),allowed));
        }
        var baseline=base(true,"Question detected triggers light search");
        env.setProperty("demo.jev.mode","off");
        assertSame(baseline,advisor.apply(baseline,SearchMode.AUTO,obs("NONE",1),allowed));
        env.setProperty("demo.jev.mode","shadow");
        assertSame(baseline,advisor.apply(baseline,SearchMode.AUTO,obs("NONE",1),allowed));
    }
    @Test void noneDoesNotAddDepthEnum() {
        assertEquals(List.of(SearchDecision.Depth.LIGHT,SearchDecision.Depth.DEEP),List.of(SearchDecision.Depth.values()));
        var baseline=base(true,"Question detected triggers light search");
        var result=advisor.apply(baseline,SearchMode.AUTO,obs("NONE",1),allowed);
        assertFalse(result.shouldSearch()); assertSame(baseline.depth(),result.depth());
    }
    @Test void thresholdBoundaryAndMissingProbability() {
        var baseline=base(true,"Question detected triggers light search");
        assertSame(baseline,advisor.apply(baseline,SearchMode.AUTO,obs("NONE",0.799),allowed));
        assertFalse(advisor.apply(baseline,SearchMode.AUTO,obs("NONE",0.8),allowed).shouldSearch());
        assertSame(baseline,advisor.apply(baseline,SearchMode.AUTO,new ChoiceObservation("NONE",OptionalDouble.empty(),true,true),allowed));
        for(String value:List.of("NaN","Infinity","-1","1.1","bad","")) {
            env.setProperty("demo.jev.confidence.web-disable",value);
            assertSame(baseline,advisor.apply(baseline,SearchMode.AUTO,obs("NONE",1),allowed),value);
        }
    }
    @Test void providersAndTopKRemainUnchanged() {
        var baseline=base(false,"No search needed (heuristic)");
        for(String choice:List.of("LIGHT","DEEP")) {
            var result=advisor.apply(baseline,SearchMode.AUTO,obs(choice,1),allowed);
            assertTrue(result.shouldSearch()); assertSame(baseline.providers(),result.providers());
            assertEquals(7,result.topK()); assertEquals(SearchDecision.Depth.valueOf(choice),result.depth());
        }
    }
    @Test void noPermissionCanBePromoted() {
        var baseline=base(false,"No search needed (heuristic)");
        for(var permission:List.of(
                new JevSearchNeedAdvisor.SearchPermission(false,true,true,false,true),
                new JevSearchNeedAdvisor.SearchPermission(true,false,true,false,true),
                new JevSearchNeedAdvisor.SearchPermission(true,true,false,false,true),
                new JevSearchNeedAdvisor.SearchPermission(true,true,true,true,true),
                new JevSearchNeedAdvisor.SearchPermission(true,true,true,false,false))) {
            assertSame(baseline,advisor.apply(baseline,SearchMode.AUTO,obs("DEEP",1),permission));
        }
    }
    @Test void missingFlagsAndUnacceptedObservationAreBaseline() {
        var baseline=base(true,"Question detected triggers light search");
        assertSame(baseline,new JevSearchNeedAdvisor(new MockEnvironment()).apply(baseline,SearchMode.AUTO,obs("NONE",1),allowed));
        assertSame(baseline,advisor.apply(baseline,SearchMode.AUTO,new ChoiceObservation("NONE",OptionalDouble.of(1),true,false),allowed));
        assertSame(baseline,advisor.apply(baseline,SearchMode.AUTO,null,allowed));
    }
}
