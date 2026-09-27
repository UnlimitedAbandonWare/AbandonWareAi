package com.example.lms.service.fallback;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class SmartFallbackPromptBoundaryTest {

    @Test
    @SuppressWarnings("unchecked")
    void standardAndLegacyNoEvidenceCurrentlyProduceDifferentFallbackMetadata() {
        var provider = (org.springframework.beans.factory.ObjectProvider<dev.langchain4j.model.chat.ChatModel>)
                mock(org.springframework.beans.factory.ObjectProvider.class);
        var builder = mock(com.example.lms.prompt.PromptBuilder.class);
        SmartFallbackService service = new SmartFallbackService(provider, null, null, null, builder);
        ReflectionTestUtils.setField(service, "enabled", true);
        String query = "synthetic neutral record";
        String context = "synthetic nonempty retrieved context";
        String legacy = "?類ｋ궖 ??곸벉";

        assertTrue(com.example.lms.service.guard.InfoFailurePatterns.looksLikeFailure("정보 없음"));
        for (String answer : new String[]{"정보 없음", "  정보 없음  "}) {
            FallbackResult result = service.maybeSuggestDetailed(query, context, answer);
            assertNull(result.suggestion());
            assertFalse(result.isFallback(), "characterizes the current sentinel mismatch, not semantic acceptance");
        }
        for (String answer : new String[]{legacy, "  " + legacy + "  "}) {
            FallbackResult result = service.maybeSuggestDetailed(query, context, answer);
            assertNull(result.suggestion());
            assertTrue(result.isFallback());
        }
        assertTrue(service.maybeSuggestDetailed(query, "", "정보 없음").isFallback());
        assertFalse(service.maybeSuggestDetailed(query, context, "synthetic supported answer").isFallback());
        verifyNoInteractions(provider, builder);
    }

    @Test
    void fallbackLlmCallUsesPromptBuilderBoundary() throws Exception {
        String source = Files.readString(Path.of("main/java/com/example/lms/service/fallback/SmartFallbackService.java"));

        assertTrue(source.contains("private final PromptBuilder promptBuilder;"));
        assertTrue(source.contains("PromptContext.builder()"));
        assertTrue(source.contains("promptBuilder.build(ctx)"));
        assertFalse(source.contains("String prompt ="));
        assertFalse(source.contains("UserMessage.from(prompt)"));
        assertTrue(source.contains("String fallbackSuggestionPrompt = promptBuilder.build(ctx);"));
        assertTrue(source.contains("UserMessage.from(fallbackSuggestionPrompt)"));
        assertFalse(source.contains("String prompt = system + \"\\n\" + user;"));
        assertFalse(source.contains("UserMessage.from(system +"));
    }

    @Test
    void fallbackFailSoftCatchesLeaveStageBreadcrumbs() throws Exception {
        String service = Files.readString(Path.of("main/java/com/example/lms/service/fallback/SmartFallbackService.java"));
        String heuristics = Files.readString(Path.of("main/java/com/example/lms/service/fallback/FallbackHeuristics.java"));

        assertTrue(service.contains("log.debug(\"[SmartFallback] fail-soft stage={}\", \"knowledgeGap.logEvent\")"));
        assertTrue(heuristics.contains("LOG.log(System.Logger.Level.DEBUG, \"[FallbackHeuristics] fail-soft stage={0}\", \"domain.classify\")"));
    }
}
