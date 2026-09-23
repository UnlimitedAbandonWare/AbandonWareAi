package com.example.lms.aop;

import com.example.lms.diag.RetrievalDiagnosticsCollector;
import com.example.lms.search.TraceStore;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RetrievalDiagAspectTest {

    @AfterEach
    void tearDown() {
        TraceStore.clear();
    }

    @Test
    void handlerRuntimeFailurePropagatesWithRedactedSuppressionBreadcrumb() throws Throwable {
        RetrievalDiagAspect aspect = new RetrievalDiagAspect(new RetrievalDiagnosticsCollector());
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        Signature signature = mock(Signature.class);
        when(pjp.getSignature()).thenReturn(signature);
        when(signature.getDeclaringType()).thenReturn(TestHandler.class);
        when(pjp.proceed()).thenThrow(new IllegalStateException("ownerToken=raw-secret"));

        assertThrows(IllegalStateException.class, () -> aspect.aroundHandler(pjp));

        assertEquals(Boolean.TRUE, TraceStore.get("retrieval.diag.suppressed"));
        assertEquals("TestHandler", TraceStore.get("retrieval.diag.suppressed.stage"));
        assertEquals("IllegalStateException", TraceStore.get("retrieval.diag.suppressed.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("ownerToken"));
    }

    private static final class TestHandler {
    }
}
