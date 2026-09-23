package com.example.lms.service;

import com.example.lms.domain.enums.RulePhase;
import com.example.lms.repository.RuleRepository;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuleEngineTraceTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void repositoryFailureReturnsOriginalTextAndLeavesRedactedTrace() {
        RuleRepository repository = mock(RuleRepository.class);
        when(repository.findByLangAndPhaseAndEnabled("ko", RulePhase.PRE, true))
                .thenThrow(new IllegalStateException("raw-secret-rule"));

        RuleEngine engine = new RuleEngine(repository);

        String out = engine.apply("private source text", "ko", RulePhase.PRE);

        assertEquals("private source text", out);
        assertEquals(Boolean.TRUE, TraceStore.get("ruleEngine.apply.suppressed"));
        assertEquals("PRE", TraceStore.get("ruleEngine.apply.suppressed.phase"));
        assertEquals("IllegalStateException", TraceStore.get("ruleEngine.apply.suppressed.errorType"));
        assertEquals(2, TraceStore.get("ruleEngine.apply.suppressed.langLength"));
        assertEquals("private source text".length(), TraceStore.get("ruleEngine.apply.suppressed.textLength"));
        String snapshot = String.valueOf(TraceStore.getAll());
        assertFalse(snapshot.contains("raw-secret-rule"));
        assertFalse(snapshot.contains("private source text"));
    }
}
