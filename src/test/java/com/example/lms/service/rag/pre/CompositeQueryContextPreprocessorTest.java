package com.example.lms.service.rag.pre;

import com.example.lms.config.rag.RagCognitiveProperties;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * CompositeQueryContextPreprocessor 의 다이나믹 fail-soft fast path 회귀 테스트.
 * - rag.cognitive.enabled=false 정적 스킵(기존)
 * - 요청 내 guardrail 단계 실패 후 후속 진입점 스킵(prior_stage_failure)
 * - cheapSearch/bypass GuardContext 경량 레인 스킵
 * - meta fast-path 힌트
 * - 비guardrail 실패는 fast-path 를 오염시키지 않음
 */
class CompositeQueryContextPreprocessorTest {

    @AfterEach
    void clear() {
        TraceStore.clear();
        GuardContextHolder.clear();
    }

    private static QueryContextPreprocessor tagger(String suffix) {
        return q -> q + suffix;
    }

    private static final class GuardrailStub extends GuardrailQueryPreprocessor {
        final AtomicInteger enrichCalls = new AtomicInteger();
        final AtomicInteger domainCalls = new AtomicInteger();
        private final RuntimeException failure;

        GuardrailStub(RuntimeException failure) {
            super(null, null, null, null);
            this.failure = failure;
        }

        @Override
        public String enrich(String q) {
            enrichCalls.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
            return q + " [g]";
        }

        @Override
        public String detectDomain(String q) {
            domainCalls.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
            return "STUB";
        }
    }

    private static CompositeQueryContextPreprocessor composite(
            RagCognitiveProperties props, QueryContextPreprocessor... delegates) {
        return new CompositeQueryContextPreprocessor(List.of(delegates), props);
    }

    @Test
    void healthyPathRunsAllDelegates() {
        RagCognitiveProperties props = new RagCognitiveProperties();
        GuardrailStub guard = new GuardrailStub(null);
        CompositeQueryContextPreprocessor c = composite(props, tagger("+t"), guard);

        assertEquals("q+t [g]", c.enrich("q"));
        assertEquals(1, guard.enrichCalls.get());
        assertNull(TraceStore.get("query.preprocessor.guardrailSkipped"));
    }

    @Test
    void cognitiveDisabledSkipsOnlyGuardrail() {
        RagCognitiveProperties props = new RagCognitiveProperties();
        props.setEnabled(false);
        GuardrailStub guard = new GuardrailStub(null);
        CompositeQueryContextPreprocessor c = composite(props, tagger("+t"), guard);

        assertEquals("q+t", c.enrich("q"));
        assertEquals(0, guard.enrichCalls.get());
        assertEquals(Boolean.TRUE, TraceStore.get("query.preprocessor.guardrailSkipped"));
        assertEquals("cognitive_disabled", TraceStore.get("query.preprocessor.guardrailSkipped.reason"));
    }

    @Test
    void guardrailFailureMarksFastPathAndLaterStagesSkip() {
        RagCognitiveProperties props = new RagCognitiveProperties();
        GuardrailStub guard = new GuardrailStub(new IllegalStateException("sensitive boom"));
        CompositeQueryContextPreprocessor c = composite(props, guard, tagger("+t"));

        // enrich still completes via the remaining delegates — fail-soft, no abort
        assertEquals("q+t", c.enrich("q"));
        assertEquals(1, guard.enrichCalls.get());
        assertEquals(Boolean.TRUE, TraceStore.get("query.preprocessor.guardrailStageFailed"));

        // dynamic fast path: a later stage entry point skips the failed stage
        String domain = c.detectDomain("q");
        assertEquals("GENERAL", domain);
        assertEquals(0, guard.domainCalls.get());
        assertEquals("prior_stage_failure", TraceStore.get("query.preprocessor.guardrailSkipped.reason"));
    }

    @Test
    void cheapSearchModeSkipsGuardrailStage() {
        GuardContext ctx = GuardContext.defaultContext();
        ctx.setCheapSearchMode(true);
        GuardContextHolder.set(ctx);

        RagCognitiveProperties props = new RagCognitiveProperties();
        GuardrailStub guard = new GuardrailStub(null);
        CompositeQueryContextPreprocessor c = composite(props, tagger("+t"), guard);

        assertEquals("q+t", c.enrich("q"));
        assertEquals(0, guard.enrichCalls.get());
        assertEquals("cheap_or_bypass_mode", TraceStore.get("query.preprocessor.guardrailSkipped.reason"));
    }

    @Test
    void metaFastPathFlagSkipsGuardrail() {
        RagCognitiveProperties props = new RagCognitiveProperties();
        GuardrailStub guard = new GuardrailStub(null);
        CompositeQueryContextPreprocessor c = composite(props, tagger("+t"), guard);

        assertEquals("q+t", c.enrich("q", Map.of("guardrailFastPath", true)));
        assertEquals(0, guard.enrichCalls.get());
        assertEquals("fast_path_flag", TraceStore.get("query.preprocessor.guardrailSkipped.reason"));
    }

    @Test
    void nonGuardrailFailureDoesNotTripFastPath() {
        QueryContextPreprocessor broken = q -> {
            throw new IllegalStateException("delegate failure");
        };
        RagCognitiveProperties props = new RagCognitiveProperties();
        GuardrailStub guard = new GuardrailStub(null);
        CompositeQueryContextPreprocessor c = composite(props, broken, guard);

        assertEquals("q [g]", c.enrich("q"));
        assertEquals(1, guard.enrichCalls.get());
        assertNotEquals(Boolean.TRUE, TraceStore.get("query.preprocessor.guardrailStageFailed"));
    }
}
