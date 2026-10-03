package com.example.lms.service.rag;

import com.example.lms.config.MoeRoutingProps;
import com.example.lms.search.TraceStore;
import com.example.lms.service.routing.RouterPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class P6SharedComplexityContractTest {
    @TempDir Path temporary;
    @AfterEach void cleanup() { TraceStore.clear(); }
    @Test void complexMainRequestUsesInjectedSharedGate() throws Exception {
        QueryComplexityGate gate = mock(QueryComplexityGate.class);
        when(gate.assess("fixture")).thenReturn(QueryComplexityGate.Level.COMPLEX, QueryComplexityGate.Level.SIMPLE);
        var constructor = assertDoesNotThrow(() -> RouterPolicy.class.getConstructor(MoeRoutingProps.class, QueryComplexityGate.class));
        RouterPolicy policy = constructor.newInstance(new MoeRoutingProps(), gate);
        assertTrue(policy.complexMainRequest("fixture", "GENERAL"));
        assertFalse(policy.complexMainRequest("fixture", "GENERAL"));
        verify(gate, times(2)).assess("fixture");
    }
    @Test void classifierExceptionFallsBackToExistingHeuristic() {
        QueryComplexityClassifier classifier = mock(QueryComplexityClassifier.class);
        when(classifier.classify(any())).thenThrow(new IllegalStateException("synthetic classifier failure"));
        QueryComplexityGate gate = new QueryComplexityGate();
        ReflectionTestUtils.setField(gate, "classifier", classifier);
        assertEquals(QueryComplexityGate.Level.COMPLEX, assertDoesNotThrow(() -> gate.assess("A vs B")));
        assertEquals("classifier_failure_fallback", TraceStore.get("rag.queryComplexity.reasonCode"));
    }
    @Test void filePresenceDoesNotClaimInferenceOrChangeVerdict() throws Exception {
        ModelBasedQueryComplexityClassifier missing = new ModelBasedQueryComplexityClassifier();
        missing.init();
        ModelBasedQueryComplexityClassifier present = new ModelBasedQueryComplexityClassifier();
        Path fixture = temporary.resolve("synthetic.onnx"); Files.writeString(fixture, "synthetic fixture only");
        ReflectionTestUtils.setField(present, "modelPath", fixture.toString()); present.init();
        assertEquals(missing.classify("정의"), present.classify("정의"));
    }
    @Test void unusableConfiguredPathIsFailSoft() {
        var classifier = new ModelBasedQueryComplexityClassifier();
        ReflectionTestUtils.setField(classifier, "modelPath", "\u0000");
        assertDoesNotThrow(classifier::init);
        assertEquals(QueryComplexityGate.Level.AMBIGUOUS, classifier.classify("정의"));
    }
}
