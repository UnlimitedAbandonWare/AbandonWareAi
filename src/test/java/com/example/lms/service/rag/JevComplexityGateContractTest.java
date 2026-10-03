package com.example.lms.service.rag;

import com.example.lms.assist.JevChoiceAdvisor.ChoiceObservation;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.OptionalDouble;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JevComplexityGateContractTest {
    private ChoiceObservation obs(String choice) {return new ChoiceObservation(choice,OptionalDouble.of(0.9),true,true);}
    @Test void koreanSimpleSkipsBothStagesWhenAccepted() {
        var gate=new QueryComplexityGate();
        assertEquals(QueryComplexityGate.Level.SIMPLE,gate.assess("이 용어를 쉽게 설명하고 예를 알려줘",obs("SIMPLE")));
    }
    @Test void ambiguousKeepsAnalyzeOnly() {
        var gate=new QueryComplexityGate();
        assertEquals(QueryComplexityGate.Level.AMBIGUOUS,gate.assess("정의",obs("AMBIGUOUS")));
    }
    @Test void complexEntersExistingSelfAsk() {
        var gate=new QueryComplexityGate();
        assertEquals(QueryComplexityGate.Level.COMPLEX,gate.assess("정의",obs("COMPLEX")));
    }
    @Test void failureDelegatesToExactExistingClassifier() {
        var delegate=mock(QueryComplexityClassifier.class);
        when(delegate.classify("synthetic")).thenReturn(QueryComplexityGate.Level.AMBIGUOUS);
        var adapter=new JevComplexityClassifier(delegate);
        assertEquals(QueryComplexityGate.Level.AMBIGUOUS,adapter.classify("synthetic"));
        for(var observation:new ChoiceObservation[]{null,new ChoiceObservation("SIMPLE",OptionalDouble.empty(),true,true),
                new ChoiceObservation("SIMPLE",OptionalDouble.of(0.9),true,false),
                new ChoiceObservation("unknown",OptionalDouble.of(0.9),true,true)}) {
            assertEquals(QueryComplexityGate.Level.AMBIGUOUS,adapter.classify("synthetic",observation));
        }
        verify(delegate,times(5)).classify("synthetic");
    }
    @Test void blankAndHighScoreBaselineRemainUnchanged() {
        var classifier=new ModelBasedQueryComplexityClassifier();classifier.init();
        assertEquals(QueryComplexityGate.Level.SIMPLE,classifier.classify(""));
        assertEquals(QueryComplexityGate.Level.COMPLEX,classifier.classify("compare analyze explain 123; why and how? therefore because"));
        assertEquals(QueryComplexityGate.Level.AMBIGUOUS,classifier.classify("정의"));
        var gate=new QueryComplexityGate();ReflectionTestUtils.setField(gate,"classifier",classifier);
        for(String query:new String[]{"","compare analyze explain 123; why and how? therefore because","정의"}) {
            assertEquals(gate.assess(query),gate.assess(query,null));
            assertEquals(gate.assess(query),gate.assess(query,new ChoiceObservation("SIMPLE",OptionalDouble.of(0.1),true,false)));
        }
    }
    @Test void noSecondHttpCallForNeedsSelfAskAndAnalyze() {
        var baseline=mock(QueryComplexityClassifier.class);
        var gate=new QueryComplexityGate();ReflectionTestUtils.setField(gate,"classifier",baseline);
        var level=gate.assess("synthetic",obs("COMPLEX"));
        assertTrue(level==QueryComplexityGate.Level.COMPLEX);
        assertTrue(level!=QueryComplexityGate.Level.SIMPLE);
        verifyNoInteractions(baseline);
    }
    @Test void noDuplicateClassifierBean() {
        assertFalse(JevComplexityClassifier.class.isAnnotationPresent(org.springframework.stereotype.Component.class));
        assertFalse(JevComplexityClassifier.class.isAnnotationPresent(org.springframework.context.annotation.Primary.class));
        assertFalse(JevComplexityClassifier.class.isAnnotationPresent(org.springframework.context.annotation.Configuration.class));
    }
}
