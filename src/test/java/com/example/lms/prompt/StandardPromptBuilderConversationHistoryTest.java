package com.example.lms.prompt;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Session 56 회귀: PromptContext에 실린 세션 대화(history/lastAssistantAnswer)가
 * 최종 프롬프트 본문에 실제로 렌더링돼야 한다. 저장만 되고 소비되지 않으면
 * 후속 발화가 맥락을 잃는다.
 */
class StandardPromptBuilderConversationHistoryTest {

    private final StandardPromptBuilder builder = new StandardPromptBuilder();

    @Test
    void recentConversationHistoryIsRendered() {
        PromptContext ctx = PromptContext.builder()
                .userQuery("와, 그걸 어떡해..")
                .history("User: 세션 56 컨텍스트 실험 질문\nAssistant: 세션 56 첫 답변")
                .lastAssistantAnswer("세션 56 첫 답변")
                .build();

        String prompt = builder.build(List.of(ctx), "와, 그걸 어떡해..");

        assertTrue(prompt.contains("### RECENT CONVERSATION"), prompt);
        assertTrue(prompt.contains("User: 세션 56 컨텍스트 실험 질문"), prompt);
        assertTrue(prompt.contains("Assistant: 세션 56 첫 답변"), prompt);
        assertTrue(prompt.contains("### USER QUESTION"), prompt);
    }

    @Test
    void lastAssistantAnswerRenderedWhenMissingFromHistory() {
        PromptContext ctx = PromptContext.builder()
                .userQuery("후속 질문")
                .history("User: 이전 질문")
                .lastAssistantAnswer("직전 답변 요약 본문")
                .build();

        String prompt = builder.build(List.of(ctx), "후속 질문");

        assertTrue(prompt.contains("### RECENT CONVERSATION"), prompt);
        assertTrue(prompt.contains("직전 답변 요약 본문"), prompt);
    }

    @Test
    void genericShortcutDoesNotDropSessionContext() {
        // generic 모드 조기 반환이 history/lastAssistantAnswer를 버리면 안 된다.
        PromptContext ctx = PromptContext.builder()
                .systemInstruction("간결하게 답하라")
                .history("User: 첫 질문\nAssistant: 첫 답변")
                .lastAssistantAnswer("첫 답변")
                .build();

        String prompt = builder.build(List.of(ctx), "후속 질문");

        assertTrue(prompt.contains("### RECENT CONVERSATION"), prompt);
        assertTrue(prompt.contains("첫 답변"), prompt);
    }

    @Test
    void blankHistoryAddsNoConversationBlock() {
        PromptContext ctx = PromptContext.builder()
                .userQuery("안녕")
                .build();

        String prompt = builder.build(List.of(ctx), "안녕");

        assertFalse(prompt.contains("### RECENT CONVERSATION"), prompt);
    }

    @Test
    void currentUserTurnIsNotDuplicated() {
        // 히스토리 마지막 User 라인이 이번 질문과 같으면 중복 렌더하지 않는다.
        PromptContext ctx = PromptContext.builder()
                .userQuery("와, 그걸 어떡해..")
                .history("User: 첫 질문\nAssistant: 첫 답변\nUser: 와, 그걸 어떡해..")
                .build();

        String prompt = builder.build(List.of(ctx), "와, 그걸 어떡해..");

        int first = prompt.indexOf("와, 그걸 어떡해..");
        int last = prompt.lastIndexOf("와, 그걸 어떡해..");
        assertTrue(first > 0 && first == last,
                "질문은 USER QUESTION 섹션에 한 번만 나와야 한다: " + prompt);
        assertFalse(prompt.contains("User: 와, 그걸 어떡해.."), prompt);
    }

    @Test
    void longHistoryIsBounded() {
        StringBuilder longHistory = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            longHistory.append("User: ").append("x".repeat(200)).append('\n');
        }
        PromptContext ctx = PromptContext.builder()
                .userQuery("후속")
                .history(longHistory.toString())
                .build();

        String prompt = builder.build(List.of(ctx), "후속");

        assertTrue(prompt.contains("### RECENT CONVERSATION"), prompt);
        assertTrue(prompt.length() < 6_000, "history 렌더는 상한 안에 있어야 한다: " + prompt.length());
    }
}
