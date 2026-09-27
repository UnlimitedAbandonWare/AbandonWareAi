package com.example.lms.search;

import com.example.lms.service.rag.handler.WebSearchHandler;
import com.example.lms.service.guard.GuardContextHolder;
import com.example.lms.search.terms.SelectedTerms;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelectedTermsTraceRedactionContractTest {

    @AfterEach
    void clearStores() {
        TraceStore.clear();
        GuardContextHolder.clear();
    }

    @Test
    void selectedTermsRuleTraceUsesSafeMessagesAtAppendBoundaries() throws Exception {
        String rawKey = "sk-" + "selected-terms-secret-abcdefghijklmnopqrstuvwxyz";
        Method traceRule = KeywordSelectionService.class.getDeclaredMethod("traceRule", String.class);
        traceRule.setAccessible(true);
        Method appendRule = WebSearchHandler.class.getDeclaredMethod("appendRule", String.class);
        appendRule.setAccessible(true);

        TraceStore.clear();
        traceRule.invoke(null, "keywordSelection.start domainProfile=" + rawKey);
        String keywordTrace = String.valueOf(TraceStore.getAll());
        assertTrue(keywordTrace.contains("web.selectedTerms.rules"));
        assertFalse(keywordTrace.contains(rawKey));
        assertFalse(keywordTrace.contains("domainProfile=" + rawKey));

        TraceStore.clear();
        appendRule.invoke(null, "applied.domain=" + rawKey);
        String webTrace = String.valueOf(TraceStore.getAll());
        assertTrue(webTrace.contains("web.selectedTerms.rules"));
        assertFalse(webTrace.contains(rawKey));
        assertFalse(webTrace.contains("applied.domain=" + rawKey));
    }

    @Test
    void keywordSelectionPromptCallsiteUsesStageSpecificPromptName() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/search/KeywordSelectionService.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("String prompt ="));
        assertFalse(source.contains("UserMessage.from(prompt)"));
        assertTrue(source.contains("String selectedTermsPrompt = prompts.buildSelectedTermsJsonPrompt("));
        assertTrue(source.contains("UserMessage.from(selectedTermsPrompt)"));
    }

    @Test
    void keywordSelectionServiceDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/search/KeywordSelectionService.java"),
                StandardCharsets.UTF_8);

        long exactEmptyCatchBlocks = Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}")
                .matcher(source)
                .results()
                .count();

        assertEquals(0L, exactEmptyCatchBlocks);
    }

    @Test
    void keywordSelectionBestEffortFallbacksLeaveTraceBreadcrumbs() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/search/KeywordSelectionService.java"),
                StandardCharsets.UTF_8);

        for (String expected : List.of(
                "traceSuppressed(\"rule.webSelectedTerms\", ignore);",
                "traceSuppressed(\"rule.keywordSelection\", ignore);",
                "traceSuppressed(\"breaker.otherAuxOpen\", ignore);",
                "traceSuppressed(\"breaker.otherAuxOpenBlocked\", ignore);",
                "traceSuppressed(\"qtxSoftCooldown.remaining\", ignore);",
                "traceSuppressed(\"qtxSoftCooldown.globalProbe\", ignore);",
                "traceSuppressed(\"noiseGate.compression\", ignore);",
                "traceSuppressed(\"noiseGate.otherAuxOpen\", ignore);",
                "traceSuppressed(\"blockedGuard.outer\", ignore);",
                "traceSuppressed(\"blank.cacheSeed\", ignore);",
                "traceSuppressed(\"emptyMust.degradedTrace\", ignore);",
                "traceSuppressed(\"cacheKey.guardContext\", ignore);",
                "traceSuppressed(\"forceMinMust.setExisting\", ignore);",
                "traceSuppressed(\"forceMinMust.setPartial\", ignore);",
                "traceSuppressed(\"fallbackSeed.trace\", ignore);",
                "traceSuppressed(\"fallbackSeed.outer\", ignore);",
                "traceSuppressed(\"fallbackExact.entityPhrase\", ignore);",
                "traceSuppressed(\"fallbackExact.exactPhrase\", ignore);",
                "traceSuppressed(\"fallbackExact.outer\", ignore);",
                "traceSuppressed(\"fallbackTerms.trace\", ignore);",
                "traceSuppressed(\"fallbackTerms.event\", ignore);")) {
            assertTrue(source.contains(expected), expected);
        }
    }

    @Test
    void keywordSelectionStartTraceUsesDomainProfileHashOnly() {
        String rawDomainProfile = "private-domain-profile";
        KeywordSelectionService service = new KeywordSelectionService(
                new BlankModel(),
                new com.example.lms.prompt.QueryKeywordPromptBuilder());

        service.select("USER: find public documentation", rawDomainProfile, 3);

        String trace = String.valueOf(TraceStore.getAll());
        String ruleTrace = String.valueOf(TraceStore.get("web.selectedTerms.rules"))
                + String.valueOf(TraceStore.get("keywordSelection.rules"));
        assertFalse(trace.contains(rawDomainProfile), trace);
        assertFalse(ruleTrace.contains("domainProfile="), ruleTrace);
        assertTrue(ruleTrace.contains("domainProfileHash="), ruleTrace);
        assertTrue(ruleTrace.contains("domainProfileLength="), ruleTrace);
    }

    @Test
    void malformedConversationTrimPropertiesDoNotForceFallbackException() {
        String charsBefore = System.getProperty("keywordSelection.maxConversationChars");
        String linesBefore = System.getProperty("keywordSelection.maxConversationLines");
        try {
            System.setProperty("keywordSelection.maxConversationChars", "not-a-number");
            System.setProperty("keywordSelection.maxConversationLines", "not-a-number");
            KeywordSelectionService service = new KeywordSelectionService(
                    new BlankModel(),
                    new com.example.lms.prompt.QueryKeywordPromptBuilder());

            Optional<SelectedTerms> selected = service.select("USER: find official docs", "general", 3);

            assertTrue(selected.isPresent());
            String trace = String.valueOf(TraceStore.getAll());
            assertFalse(trace.contains("fallback_exception"), trace);
            assertFalse(trace.contains("degraded:exception"), trace);
            assertEquals("invalid_number",
                    TraceStore.get("keywordSelection.suppressed.config.maxConversationChars.parse.errorType"));
            assertEquals("invalid_number",
                    TraceStore.get("keywordSelection.suppressed.config.maxConversationLines.parse.errorType"));
            assertFalse(trace.contains("NumberFormatException"), trace);
            assertFalse(trace.contains("not-a-number"), trace);
        } finally {
            restoreProperty("keywordSelection.maxConversationChars", charsBefore);
            restoreProperty("keywordSelection.maxConversationLines", linesBefore);
        }
    }

    @Test
    void diagnosticSmokeScopeSkipsKeywordSelectionLlm() {
        CountingJsonModel model = new CountingJsonModel();
        KeywordSelectionService service = new KeywordSelectionService(
                model,
                new com.example.lms.prompt.QueryKeywordPromptBuilder());
        TraceStore.put("aux.queryTransformer.diagnosticSmokeScope", true);

        Optional<SelectedTerms> selected = service.select(
                "USER: 랜덤 UI 스모크: 현재 RAG/AUTO 설정으로 한 문장 답변하고, 사용한 경로를 짧게 말해줘.",
                "general",
                3);

        assertTrue(selected.isPresent());
        assertEquals(0, model.calls.get());
        assertEquals(Boolean.TRUE, TraceStore.get("aux.keywordSelection.skipped"));
        assertEquals("diagnostic_smoke", TraceStore.get("aux.keywordSelection.skipReason"));
        assertEquals("fallback_diagnostic_smoke", TraceStore.get("keywordSelection.mode"));
        String trace = String.valueOf(TraceStore.getAll());
        assertFalse(trace.contains("called-llm"), trace);
    }

    @Test
    void fallbackSelectedTermsDoNotReturnSecretLikeConversationTokens() {
        String rawKey = "sk-" + "abcdefghijklmnopqrstuvwxyz123456";
        String rawOwnerToken = "raw-owner-token";
        KeywordSelectionService service = new KeywordSelectionService(
                new BlankModel(),
                new com.example.lms.prompt.QueryKeywordPromptBuilder());

        Optional<SelectedTerms> selected = service.select(
                "USER: search official docs for " + rawKey + " ownerToken=" + rawOwnerToken,
                "general",
                3);

        assertTrue(selected.isPresent());
        String rendered = String.join("\n",
                String.valueOf(selected.get().getExact()),
                String.valueOf(selected.get().getMust()),
                String.valueOf(selected.get().getShould()),
                String.valueOf(selected.get().getMaybe()),
                String.valueOf(selected.get().getNegative()),
                String.valueOf(selected.get().getDomains()),
                String.valueOf(selected.get().getAliases()),
                String.valueOf(selected.get().getDomainProfile()));
        assertFalse(rendered.contains(rawKey), rendered);
        assertFalse(rendered.contains("abcdefghijklmnopqrstuvwxyz123456"), rendered);
        assertFalse(rendered.contains(rawOwnerToken), rendered);
    }

    @Test
    void opaqueTokenDetectorDropsSupabaseKeyPrefixes() throws Exception {
        Method method = KeywordSelectionService.class.getDeclaredMethod("looksLikeOpaqueSecretToken", String.class);
        method.setAccessible(true);

        assertEquals(true, method.invoke(null, "sb_secret_" + "abcdefghij"));
        assertEquals(true, method.invoke(null, "sb_publishable_" + "abcdefghij"));
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private record BlankModel() implements ChatModel {
        @Override
        public ChatResponse chat(List<ChatMessage> messages) {
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(""))
                    .build();
        }
    }

    private static final class CountingJsonModel implements ChatModel {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public ChatResponse chat(List<ChatMessage> messages) {
            calls.incrementAndGet();
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from("{\"must\":[\"called-llm\"],\"domainProfile\":\"general\"}"))
                    .build();
        }
    }
}
