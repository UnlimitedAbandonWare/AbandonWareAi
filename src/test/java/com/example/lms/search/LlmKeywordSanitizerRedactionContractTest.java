package com.example.lms.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

class LlmKeywordSanitizerRedactionContractTest {

    @Test
    void llmFailureLogDoesNotWriteRawThrowableObject() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/search/LlmKeywordSanitizer.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("log.warn(\"[Sanitizer] LLM call failed - bypassing filter\", e);"));
        assertFalse(source.contains("SafeRedactor.safeMessage(String.valueOf(e), 180)"));
        assertTrue(source.contains("[Sanitizer] LLM call failed - bypassing filter type={} errorHash={} errorLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(messageOf(e))"));
        assertTrue(source.contains("messageLength(e)"));
    }

    @Test
    void verifierPromptCallsiteUsesStageSpecificPromptName() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/search/LlmKeywordSanitizer.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("String prompt ="));
        assertFalse(source.contains("UserMessage.from(prompt)"));
        assertTrue(source.contains("String keywordVerifierPrompt = PROMPT.formatted("));
        assertTrue(source.contains("UserMessage.from(keywordVerifierPrompt)"));
    }

    @Test
    void verifierPromptRedactsSecretLikeInputFieldsBeforeJudgeCall() {
        AtomicReference<String> promptRef = new AtomicReference<>();
        LlmKeywordSanitizer sanitizer = new LlmKeywordSanitizer(new CapturingModel(promptRef));
        String rawKey = "sk-" + "1234567890abcdef1234";

        sanitizer.filter(
                "original " + rawKey,
                List.of("snippet one " + rawKey, "snippet two " + rawKey),
                List.of("candidate " + rawKey));

        String prompt = promptRef.get();
        assertTrue(prompt.contains("original "), prompt);
        assertTrue(prompt.contains("snippet one "), prompt);
        assertTrue(prompt.contains("candidate "), prompt);
        assertTrue(prompt.contains("***"), prompt);
        assertFalse(prompt.contains(rawKey), prompt);
        assertFalse(prompt.contains("1234567890abcdef1234"), prompt);
    }

    @Test
    void judgeCanApproveCandidateFromCanonicalResponse() {
        LlmKeywordSanitizer sanitizer = new LlmKeywordSanitizer(new FixedResponseModel(
                "[{\"kw\":\"allowed-term\",\"ok\":true},{\"kw\":\"rejected-term\",\"ok\":false}]"));

        List<String> filtered = sanitizer.filter(
                "question",
                List.of("evidence one", "evidence two"),
                List.of("allowed-term", "rejected-term"));

        assertEquals(List.of("allowed-term"), filtered);
    }

    @Test
    void judgeCannotIntroduceKeywordOutsideCandidateSet() {
        List<String> candidates = List.of("allowed-term");
        LlmKeywordSanitizer sanitizer = new LlmKeywordSanitizer(new FixedResponseModel(
                "[{\"kw\":\"invented-term\",\"ok\":true}]"));

        List<String> filtered = sanitizer.filter(
                "question",
                List.of("evidence one", "evidence two"),
                candidates);

        assertEquals(candidates, filtered);
    }

    private record CapturingModel(AtomicReference<String> promptRef) implements ChatModel {
        @Override
        public ChatResponse chat(List<ChatMessage> messages) {
            promptRef.set(messages == null || messages.isEmpty() ? "" : String.valueOf(messages.get(0)));
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from("[]"))
                    .build();
        }
    }

    private record FixedResponseModel(String response) implements ChatModel {
        @Override
        public ChatResponse chat(List<ChatMessage> messages) {
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(response))
                    .build();
        }
    }
}
