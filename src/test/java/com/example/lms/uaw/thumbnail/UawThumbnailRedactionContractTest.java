package com.example.lms.uaw.thumbnail;

import com.example.lms.llm.ChatModel;
import com.example.lms.prompt.QueryKeywordPromptBuilder;
import com.example.lms.search.provider.WebSearchProvider;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UawThumbnailRedactionContractTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void thumbnailFailSoftLogsDoNotUseRawThrowableMessages() throws Exception {
        for (Path source : List.of(
                Path.of("main/java/com/example/lms/uaw/thumbnail/UawThumbnailService.java"),
                Path.of("main/java/com/example/lms/uaw/thumbnail/UawThumbnailBudgetManager.java"),
                Path.of("main/java/com/example/lms/uaw/thumbnail/UawThumbnailRunStateStore.java"),
                Path.of("main/java/com/example/lms/uaw/thumbnail/UawThumbnailPlanLoader.java"))) {
            String code = Files.readString(source, StandardCharsets.UTF_8);
            List<String> rawThrowableLogLines = code.lines()
                    .filter(line -> line.contains("log."))
                    .filter(line -> line.contains(".getMessage()") || line.contains(".toString()"))
                    .filter(line -> !line.contains("SafeRedactor.safeMessage("))
                    .toList();

            assertEquals(List.of(), rawThrowableLogLines, source + " logs raw throwable messages");
        }
    }

    @Test
    void thumbnailStateAndPlanLogsUsePathHashes() throws Exception {
        String stateStore = Files.readString(
                Path.of("main/java/com/example/lms/uaw/thumbnail/UawThumbnailRunStateStore.java"),
                StandardCharsets.UTF_8);
        String planLoader = Files.readString(
                Path.of("main/java/com/example/lms/uaw/thumbnail/UawThumbnailPlanLoader.java"),
                StandardCharsets.UTF_8);

        assertFalse(stateStore.contains("state load failed: {}"));
        assertFalse(stateStore.contains("state save failed: {}"));
        assertFalse(planLoader.contains("plan load failed: {}"));
        assertTrue(stateStore.contains("pathHash={} pathLength={}"));
        assertTrue(planLoader.contains("pathHash={} pathLength={}"));
        assertFalse(stateStore.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertFalse(planLoader.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(stateStore.contains("state load failed pathHash={} pathLength={} errorHash={} errorLength={}"));
        assertTrue(stateStore.contains("state save failed pathHash={} pathLength={} errorHash={} errorLength={}"));
        assertTrue(planLoader.contains("plan load failed pathHash={} pathLength={} -> using defaults. errorHash={} errorLength={}"));
        assertTrue(stateStore.contains("SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));
        assertTrue(planLoader.contains("SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));
    }

    @Test
    void thumbnailBudgetFailureLogUsesHashAndLengthOnly() throws Exception {
        String budgetManager = Files.readString(
                Path.of("main/java/com/example/lms/uaw/thumbnail/UawThumbnailBudgetManager.java"),
                StandardCharsets.UTF_8);

        assertFalse(budgetManager.contains("t.toString()"));
        assertFalse(budgetManager.contains("SafeRedactor.safeMessage(String.valueOf(t), 180)"));
        assertTrue(budgetManager.contains("errorHash={} errorLength={}"));
        assertTrue(budgetManager.contains("SafeRedactor.hashValue(messageOf(t)), messageLength(t)"));
    }

    @Test
    void thumbnailServiceFailSoftLogsUseHashAndLengthOnly() throws Exception {
        String service = Files.readString(
                Path.of("main/java/com/example/lms/uaw/thumbnail/UawThumbnailService.java"),
                StandardCharsets.UTF_8);

        assertFalse(service.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(service.contains("anchors llm failed -> fallback. errorHash={} errorLength={}"));
        assertTrue(service.contains("web search failed anchorHash={} errorHash={} errorLength={}"));
        assertTrue(service.contains("render failed topicHash={} errorHash={} errorLength={}"));
        assertTrue(service.contains("SafeRedactor.hashValue(messageOf(e)), messageLength(e)"));
        assertTrue(service.contains("[UAW_THUMB] evidence URL domain parse skipped"));
    }

    @Test
    void thumbnailServiceDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/uaw/thumbnail/UawThumbnailService.java"),
                StandardCharsets.UTF_8);

        assertFalse(Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")
                        .matcher(source)
                        .find(),
                "UAW thumbnail service needs fixed-stage breadcrumbs instead of exact empty catch bodies");
    }

    @Test
    void thumbnailSuppressedPostprocessStagesLeaveTraceBreadcrumbs() {
        ReflectionTestUtils.invokeMethod(UawThumbnailService.class, "traceSuppressed", "plan.render.options");

        assertEquals(Boolean.TRUE, TraceStore.get("uaw.thumbnail.suppressed"));
        assertEquals("plan.render.options", TraceStore.get("uaw.thumbnail.suppressed.stage"));
        assertEquals(1L, TraceStore.getLong("uaw.thumbnail.suppressed.plan.render.options"));
        String trace = TraceStore.getAll().toString();
        assertFalse(trace.contains("sk-"));
        assertFalse(trace.contains("Authorization"));
    }

    @Test
    void thumbnailEvidenceDomainParseFailureLeavesRedactedTraceBreadcrumb() {
        String rawUrl = "http://[ownerToken=thumb-secret";

        String domain = new UawThumbnailService.EvidenceItem("anchor", "title", rawUrl, "quote").domain();

        assertEquals("", domain);
        assertEquals(Boolean.TRUE, TraceStore.get("uaw.thumbnail.suppressed"));
        assertEquals("evidence.domainParse", TraceStore.get("uaw.thumbnail.suppressed.stage"));
        assertEquals(1L, TraceStore.getLong("uaw.thumbnail.suppressed.evidence.domainParse"));
        assertEquals("IllegalArgumentException",
                TraceStore.get("uaw.thumbnail.suppressed.evidence.domainParse.errorType"));
        assertNotNull(TraceStore.get("uaw.thumbnail.suppressed.evidence.domainParse.errorHash"));
        assertTrue((Integer) TraceStore.get("uaw.thumbnail.suppressed.evidence.domainParse.errorLength") > 0);
        String trace = TraceStore.getAll().toString();
        assertFalse(trace.contains(rawUrl));
        assertFalse(trace.contains("thumb-secret"));
    }

    @Test
    void thumbnailWebSearchFailureLeavesRedactedTraceBreadcrumb() {
        WebSearchProvider failingProvider = new WebSearchProvider() {
            @Override
            public List<String> search(String query, int topK) {
                throw new IllegalStateException("ownerToken=web-secret");
            }

            @Override
            public com.example.lms.service.NaverSearchService.SearchResult searchWithTrace(String query, int topK) {
                return null;
            }

            @Override
            public boolean isEnabled() {
                return true;
            }

            @Override
            public String getName() {
                return "failing-provider";
            }
        };
        UawThumbnailService service = new UawThumbnailService(
                new UawThumbnailProperties(), null, failingProvider, null, null, null, null);

        List<?> evidence = ReflectionTestUtils.invokeMethod(service, "collectEvidence",
                List.of("anchor ownerToken=anchor-secret"), null);

        assertNotNull(evidence);
        assertTrue(evidence.isEmpty());
        assertEquals(Boolean.TRUE, TraceStore.get("uaw.thumbnail.suppressed"));
        assertEquals("evidence.webSearch", TraceStore.get("uaw.thumbnail.suppressed.stage"));
        assertEquals(1L, TraceStore.getLong("uaw.thumbnail.suppressed.evidence.webSearch"));
        assertEquals("IllegalStateException",
                TraceStore.get("uaw.thumbnail.suppressed.evidence.webSearch.errorType"));
        assertNotNull(TraceStore.get("uaw.thumbnail.suppressed.evidence.webSearch.errorHash"));
        assertTrue((Integer) TraceStore.get("uaw.thumbnail.suppressed.evidence.webSearch.errorLength") > 0);
        String trace = TraceStore.getAll().toString();
        assertFalse(trace.contains("web-secret"));
        assertFalse(trace.contains("anchor-secret"));
    }

    @Test
    void thumbnailAnchorLlmFailureLeavesRedactedTraceBreadcrumb() {
        ChatModel failingChatModel = prompt -> {
            throw new IllegalStateException("ownerToken=anchor-llm-secret");
        };
        UawThumbnailService service = new UawThumbnailService(
                new UawThumbnailProperties(), null, null, new QueryKeywordPromptBuilder(),
                failingChatModel, null, null);

        List<?> anchors = ReflectionTestUtils.invokeMethod(service, "generateAnchors",
                "topic ownerToken=topic-secret", null);

        assertNotNull(anchors);
        assertFalse(anchors.isEmpty());
        assertEquals(Boolean.TRUE, TraceStore.get("uaw.thumbnail.suppressed"));
        assertEquals("anchors.llm", TraceStore.get("uaw.thumbnail.suppressed.stage"));
        assertEquals(1L, TraceStore.getLong("uaw.thumbnail.suppressed.anchors.llm"));
        assertEquals("IllegalStateException",
                TraceStore.get("uaw.thumbnail.suppressed.anchors.llm.errorType"));
        assertNotNull(TraceStore.get("uaw.thumbnail.suppressed.anchors.llm.errorHash"));
        assertTrue((Integer) TraceStore.get("uaw.thumbnail.suppressed.anchors.llm.errorLength") > 0);
        String trace = TraceStore.getAll().toString();
        assertFalse(trace.contains("anchor-llm-secret"));
        assertFalse(trace.contains("topic-secret"));
    }

    @Test
    void thumbnailRenderFailureLeavesRedactedTraceBreadcrumb() {
        ChatModel failingChatModel = prompt -> {
            throw new IllegalStateException("ownerToken=render-secret");
        };
        UawThumbnailService service = new UawThumbnailService(
                new UawThumbnailProperties(), null, null, new QueryKeywordPromptBuilder(),
                failingChatModel, null, null);

        Object rendered = ReflectionTestUtils.invokeMethod(service, "render",
                "topic ownerToken=topic-secret",
                "entity",
                List.of("anchor"),
                List.of(new UawThumbnailService.EvidenceItem(
                        "anchor", "title", "https://example.test/source", "quote")),
                null);

        assertNull(rendered);
        assertEquals(Boolean.TRUE, TraceStore.get("uaw.thumbnail.suppressed"));
        assertEquals("render.failed", TraceStore.get("uaw.thumbnail.suppressed.stage"));
        assertEquals(1L, TraceStore.getLong("uaw.thumbnail.suppressed.render.failed"));
        assertEquals("IllegalStateException",
                TraceStore.get("uaw.thumbnail.suppressed.render.failed.errorType"));
        assertNotNull(TraceStore.get("uaw.thumbnail.suppressed.render.failed.errorHash"));
        assertTrue((Integer) TraceStore.get("uaw.thumbnail.suppressed.render.failed.errorLength") > 0);
        String trace = TraceStore.getAll().toString();
        assertFalse(trace.contains("render-secret"));
        assertFalse(trace.contains("topic-secret"));
    }

    @Test
    void thumbnailGraphRagEventPublishFailureLeavesRedactedTraceBreadcrumb() {
        org.springframework.context.ApplicationEventPublisher failingPublisher = event -> {
            throw new IllegalStateException("ownerToken=event-secret");
        };
        UawThumbnailService service = new UawThumbnailService(
                new UawThumbnailProperties(), null, null, null, null, null, failingPublisher);
        UawThumbnailService.ThumbnailResult result = new UawThumbnailService.ThumbnailResult(
                "plan",
                "topic",
                "entity",
                "caption",
                List.of("anchor ownerToken=anchor-secret"),
                List.of(),
                0.8d);

        ReflectionTestUtils.invokeMethod(service, "publishGraphRagEvent", result);

        assertEquals(Boolean.TRUE, TraceStore.get("uaw.thumbnail.suppressed"));
        assertEquals("event.graphRagPublish", TraceStore.get("uaw.thumbnail.suppressed.stage"));
        assertEquals(1L, TraceStore.getLong("uaw.thumbnail.suppressed.event.graphRagPublish"));
        assertEquals("IllegalStateException",
                TraceStore.get("uaw.thumbnail.suppressed.event.graphRagPublish.errorType"));
        assertNotNull(TraceStore.get("uaw.thumbnail.suppressed.event.graphRagPublish.errorHash"));
        assertTrue((Integer) TraceStore.get("uaw.thumbnail.suppressed.event.graphRagPublish.errorLength") > 0);
        String trace = TraceStore.getAll().toString();
        assertFalse(trace.contains("event-secret"));
        assertFalse(trace.contains("anchor-secret"));
    }

    @Test
    void thumbnailOrchestratorDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/uaw/thumbnail/UawThumbnailOrchestrator.java"),
                StandardCharsets.UTF_8);

        assertFalse(Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")
                        .matcher(source)
                        .find(),
                "UAW thumbnail orchestrator probes need fixed-stage breadcrumbs instead of exact empty catch bodies");
        assertTrue(source.contains("[UAW_THUMB] generation failed topicHash={} topicLength={} errorType={}"));
    }

    @Test
    void thumbnailRenderPromptStaysInPromptBuilder() throws Exception {
        String service = Files.readString(
                Path.of("main/java/com/example/lms/uaw/thumbnail/UawThumbnailService.java"),
                StandardCharsets.UTF_8);
        String promptBuilder = Files.readString(
                Path.of("main/java/com/example/lms/prompt/QueryKeywordPromptBuilder.java"),
                StandardCharsets.UTF_8);

        assertFalse(service.contains("You are a strict summarizer."));
        assertFalse(service.contains("String prompt ="));
        assertFalse(service.contains("generate(prompt,"));
        assertTrue(promptBuilder.contains("buildUawThumbnailRenderPrompt("));
    }

    @Test
    void persistedEventMasksSecretPatternsBeforeGraphText() {
        String openAiKey = "sk-" + "abcdefghijklmnopqrstuvwxyz123456";
        String bearerToken = "Bearer " + "abc.def.ghi";
        String ownerToken = "ownerToken=secret-value";

        UawThumbnailPersistedEvent event = new UawThumbnailPersistedEvent(
                "UAW_thumbnail.v1",
                "UAW_THUMB",
                "THUMBNAIL",
                "caption " + openAiKey + " " + ownerToken,
                List.of("anchor " + bearerToken, "plain-anchor"),
                0.91d);

        String publicText = event + "\n" + event.graphText();
        assertFalse(publicText.contains(openAiKey));
        assertFalse(publicText.contains("abc.def.ghi"));
        assertFalse(publicText.contains("secret-value"));
        assertTrue(event.caption().contains("***"));
        assertTrue(event.anchors().contains("plain-anchor"));
    }

    @Test
    void applicationYamlProvidesDisabledByDefaultOcrRagAndThumbnailBridge() throws Exception {
        String yaml = Files.readString(Path.of("main/resources/application.yml"), StandardCharsets.UTF_8);

        assertTrue(yaml.contains("enabled: ${OCR_ENABLED:false}"));
        assertTrue(yaml.contains("timeout-ms: ${OCR_TIMEOUT_MS:900}"));
        assertTrue(yaml.contains("min-confidence: ${OCR_MIN_CONFIDENCE:0.65}"));
        assertTrue(yaml.contains("enabled: ${RAG_OCR_ENABLED:${OCR_ENABLED:false}}"));
        assertTrue(yaml.contains("top-k: ${RAG_OCR_TOP_K:6}"));
        assertTrue(yaml.contains("max-chars: ${RAG_OCR_MAX_CHARS:1200}"));
        assertTrue(yaml.contains("enabled: ${UAW_THUMBNAIL_ENABLED:false}"));
        assertTrue(yaml.contains("plan-id: ${UAW_THUMBNAIL_PLAN_ID:UAW_thumbnail.v1}"));
        assertTrue(yaml.contains("min-confidence: ${UAW_THUMBNAIL_MIN_CONFIDENCE:0.55}"));
    }
}
