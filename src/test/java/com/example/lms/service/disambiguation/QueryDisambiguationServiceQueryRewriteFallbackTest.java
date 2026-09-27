package com.example.lms.service.disambiguation;

import com.example.lms.prompt.DisambiguationPromptBuilder;
import com.example.lms.search.NoiseClipper;
import com.example.lms.search.TraceStore;
import com.example.lms.service.correction.DomainTermDictionary;
import com.example.lms.service.guard.GuardContext;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.service.llm.LlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class QueryDisambiguationServiceQueryRewriteFallbackTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
        GuardContextHolder.clear();
    }

    @Test
    void blankDisambiguationFallbackLeavesQueryRewriteSuperTokenTrace() {
        LlmClient blankLlm = prompt -> "";
        DomainTermDictionary emptyDictionary = query -> Set.of();
        QueryDisambiguationService service = new QueryDisambiguationService(
                blankLlm,
                new ObjectMapper(),
                emptyDictionary,
                new DisambiguationPromptBuilder(),
                new NoiseClipper());

        DisambiguationResult result = service.clarify(
                "GraphRAG ops console debug smoke query rewrite",
                List.of());

        assertNotNull(result);
        assertEquals(Boolean.TRUE, TraceStore.get("queryTransformer.subQueries.superTokens.enabled"));
        assertEquals(3, TraceStore.get("queryTransformer.subQueries.superTokens.branchCount"));
        assertEquals(3, TraceStore.get("queryTransformer.subQueries.superTokens.tokenCount"));
        assertEquals(3, TraceStore.get("queryTransformer.subQueries.superTokens.subModelCount"));
        assertEquals(List.of("definition", "alias", "relation"),
                TraceStore.get("queryTransformer.subQueries.superTokens.axes"));
        assertEquals("disambiguation-blank-fallback",
                TraceStore.get("queryTransformer.subQueries.fallback.reason"));
    }

    @Test
    void diagnosticSmokeQuerySkipsDisambiguationLlm() {
        AtomicInteger calls = new AtomicInteger();
        LlmClient strictLlm = prompt -> {
            calls.incrementAndGet();
            throw new AssertionError("diagnostic smoke queries should not call the auxiliary LLM");
        };
        DomainTermDictionary emptyDictionary = query -> Set.of();
        QueryDisambiguationService service = new QueryDisambiguationService(
                strictLlm,
                new ObjectMapper(),
                emptyDictionary,
                new DisambiguationPromptBuilder(),
                new NoiseClipper());

        DisambiguationResult result = service.clarify(
                "랜덤 UI 스모크: 현재 RAG/AUTO 설정으로 한 문장 답변하고, 사용한 경로를 짧게 말해줘.",
                List.of());

        assertNotNull(result);
        assertEquals(0, calls.get());
        assertEquals(Boolean.TRUE, TraceStore.get("aux.disambiguation.skipped"));
        assertEquals("diagnostic_smoke", TraceStore.get("aux.disambiguation.skipReason"));
        assertEquals(Boolean.TRUE, TraceStore.get("aux.queryTransformer.diagnosticSmokeScope"));
    }

    @Test
    void cheapSearchModeSkipsDisambiguationLlm() {
        AtomicInteger calls = new AtomicInteger();
        LlmClient strictLlm = prompt -> {
            calls.incrementAndGet();
            throw new AssertionError("cheap search mode should not call the disambiguation LLM");
        };
        DomainTermDictionary emptyDictionary = query -> Set.of();
        QueryDisambiguationService service = new QueryDisambiguationService(
                strictLlm,
                new ObjectMapper(),
                emptyDictionary,
                new DisambiguationPromptBuilder(),
                new NoiseClipper());
        GuardContext ctx = GuardContext.defaultContext();
        ctx.setCheapSearchMode(true);
        GuardContextHolder.set(ctx);

        DisambiguationResult result = service.clarify(
                "LIGHT mode route should skip auxiliary disambiguation",
                List.of());

        assertNotNull(result);
        assertEquals(0, calls.get());
        assertEquals(Boolean.TRUE, TraceStore.get("aux.disambiguation.skipped"));
        assertEquals("cheap-search-mode", TraceStore.get("aux.disambiguation.skipReason"));
        assertEquals("blocked:cheap-search-mode", TraceStore.get("aux.disambiguation"));
    }
}
