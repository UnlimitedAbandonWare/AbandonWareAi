package com.abandonware.ai.service;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NaverSearchServiceTraceTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void delegateFailureRecordsRedactedTrace() {
        NaverSearchService adapter = new NaverSearchService();
        com.example.lms.service.NaverSearchService delegate =
                mock(com.example.lms.service.NaverSearchService.class);
        when(delegate.searchSnippets("secret query", 3))
                .thenThrow(new IllegalStateException("boom raw-secret"));
        ReflectionTestUtils.setField(adapter, "delegate", delegate);

        List<String> out = adapter.searchSnippets("secret query", 3);

        assertTrue(out.isEmpty());
        assertEquals(Boolean.TRUE, TraceStore.get("web.naver.adapter.suppressed"));
        assertEquals("delegate.searchSnippets", TraceStore.get("web.naver.adapter.suppressed.stage"));
        assertEquals("IllegalStateException", TraceStore.get("web.naver.adapter.suppressed.errorClass"));
        String snapshot = TraceStore.getAll().toString();
        assertFalse(snapshot.contains("secret query"));
        assertFalse(snapshot.contains("raw-secret"));
    }

    @Test
    void naverFailSoftCatchesLeaveTraceBreadcrumbs() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/NaverSearchService.java"),
                StandardCharsets.UTF_8);
        String helper = Files.readString(Path.of("main/java/com/example/lms/service/NaverTraceSuppressions.java"),
                StandardCharsets.UTF_8);

        assertTrue(helper.contains("static void traceSuppressed(String stage, Throwable failure)"));
        assertFalse(source.contains("NaverTraceSuppressions.trace(\""));
        assertTraceStage(source, "syncBlockTimeout.override");
        assertTraceStage(source, "searchSnippetsSync");
        assertTraceStage(source, "cacheOnly.expandQueries");
        assertTraceStage(source, "cacheOnly.getIfPresent");
        assertTraceStage(source, "cacheOnly.getNow");
        assertTraceStage(source, "domainProfile.demotionTrace");
        assertTraceStage(source, "strictDomain.demotionTrace");
        assertTraceStage(source, "policy.rationaleTrace");
        assertTraceStage(source, "adaptive.telemetry");
        assertTraceStage(source, "combinedContext.localRag");
        assertTraceStage(source, "combinedContext.memory");
        assertTraceStage(source, "filter.domainProfileDemotion");
        assertTraceStage(source, "filter.domainProfileAllowed");
        assertTraceStage(source, "filter.summaryTrace");
        assertTraceStage(source, "filter.runAppendTrace");
        assertTraceStage(source, "searchWithTraceSync");
        assertTraceStage(source, "cooldown.lock");
        assertTraceStage(source, "filter.emptyRunTrace");
        assertTraceStage(source, "counts.trace");
        assertTraceStage(source, "failure.trace");
        assertTraceStage(source, "remoteCooldown.trace");
        assertTraceStage(source, "locationIntent.detect");
        assertTraceStage(source, "ctx.traceStoreSeed");
        assertTraceStage(source, "ctx.mdcRestore");
        assertTraceStage(source, "domain.allow.hostParse");
        assertTraceStage(source, "domain.allowCsv.hostParse");
        assertTraceStage(source, "domain.block.hostParse");
        assertTraceStage(source, "html.decode");
        assertTraceStage(source, "memory.snippetScore");
        assertTraceStage(source, "conversationContext.load");
        assertTraceStage(source, "assistant.score");
        assertTraceStage(source, "queryParam.decode");
        assertTraceStage(source, "policy.cacheKey");
        assertTraceStage(source, "ctx.traceStoreRead");
        assertTraceStage(source, "ctx.propagationTrace");
        assertTraceStage(source, "ctx.ablationPenalty");
        assertTraceStage(source, "api.webClientResponse");
        assertTraceStage(source, "api.interrupted");
        assertTraceStage(source, "api.failure");
        assertTraceStage(source, "snippetLegacy.hostParse");
        assertTraceStage(source, "traceFlag.parse");
    }

    private static void assertTraceStage(String source, String stage) {
        assertTrue(source.contains("NaverTraceSuppressions.traceSuppressed(\"" + stage + "\""),
                () -> "missing Naver suppressed trace stage: " + stage);
    }
}
