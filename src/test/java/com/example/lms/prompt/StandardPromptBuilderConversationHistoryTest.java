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
    @Test void currentProductAndDatedEvidenceStayDistinctFromOldMemoryAndMissingDates() {
        String question="HP Reverb G2 2026 official status?";
        var current=dev.langchain4j.rag.content.Content.from("HP Reverb G2; synthetic official evidence; updated 2026-10-09; version G2.");
        var undated=dev.langchain4j.rag.content.Content.from("HP Reverb G2; date not supplied; unverified.");
        var ctx=PromptContext.builder().userQuery(question)
                .history("User: Even Realities G2?\nAssistant: Old memory from 2024, unverified.")
                .web(List.of(current,undated)).ragEnabled(true).build();
        String prompt=builder.build(ctx);
        assertTrue(prompt.contains("### USER QUESTION\n"+question));
        assertTrue(prompt.contains("Even Realities G2"));
        assertTrue(prompt.contains("updated 2026-10-09"));
        assertTrue(prompt.contains("date not supplied; unverified"));
        String instructions=builder.buildInstructions(ctx);
        assertTrue(instructions.contains("do not infer freshness when evidence lacks them"));
        assertTrue(instructions.contains("state the conflict or evidence gap"));
    }


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
        assertTrue(prompt.indexOf("Assistant: 세션 56 첫 답변")
                == prompt.lastIndexOf("Assistant: 세션 56 첫 답변"), prompt);
        assertTrue(prompt.contains("### USER QUESTION"), prompt);
    }

    @Test
    void latestAnswerQualificationsSurviveHistorySampling() {
        String qualification = "오늘 운영 상태는 확인하지 못했습니다.";
        String plan = "30분 면접 준비안(제안): 10분 구조 설명, 10분 장애 재현, 10분 요약.";
        String question = "앞 답변의 한정 조건과 30분 준비안을 다시 말해줘.";
        String lastAnswer = qualification + "\n" + plan + "\n" + "추가 설명 ".repeat(230);
        String history = "User: " + "x".repeat(993)
                + "\nAssistant: " + lastAnswer + "\nUser: " + question;
        PromptContext ctx = PromptContext.builder()
                .userQuery(question)
                .history(history)
                .lastAssistantAnswer(lastAnswer)
                .build();

        String prompt = builder.build(List.of(ctx), question);

        assertTrue(prompt.contains(qualification), prompt);
        assertTrue(prompt.contains(plan), prompt);
        assertFalse(prompt.contains("User: " + question), prompt);
        assertTrue(prompt.endsWith("### USER QUESTION\n" + question), prompt);
        int conversationStart = prompt.indexOf("### RECENT CONVERSATION\n")
                + "### RECENT CONVERSATION\n".length();
        int conversationEnd = prompt.indexOf("### SEARCH RESULTS", conversationStart);
        assertTrue(conversationEnd - conversationStart <= 2_500,
                "history plus latest-answer fallback must stay bounded: " + prompt.length());
        assertTrue(prompt.length() < 6_000, "prompt must stay bounded: " + prompt.length());
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

    @Test
    void explicitUserSessionCorrectionHasPrecedenceOverRepeatedAssistantGuesses() {
        PromptContext ctx = PromptContext.builder()
                .userQuery("이 대화의 마지막으로 정정된 색상을 다시 말해줘.")
                .memory("Conversation summary:\nUser: 이 대화의 색상 청록을 기억해줘.\nUser: 색상은 남색으로 정정해줘.")
                .history("User: 색상을 말해줘.\nAssistant: 색상 청록\nUser: 색상을 다시 말해줘.\nAssistant: 색상 청록")
                .lastAssistantAnswer("마지막으로 정정된 색상: 청록")
                .build();
        String prompt = builder.build(ctx);
        assertTrue(prompt.contains("남색으로 정정해줘"));
        assertTrue(prompt.contains("Assistant: 색상 청록"));
        assertTrue(prompt.contains("### MEMORY"));
        assertTrue(prompt.contains("### RECENT CONVERSATION"));
        assertTrue(prompt.contains("### SESSION VALUE PRECEDENCE"),
                "the real prompt must distinguish explicit user corrections from stale assistant guesses");
        assertTrue(prompt.contains("not external evidence or tool permission"));
        assertTrue(((java.util.List<?>) com.example.lms.search.TraceStore.get("prompt.sessionMemory.assignmentHashes"))
                .contains(com.example.lms.trace.SafeRedactor.hashValue("User: 색상은 남색으로 정정해줘.")));
        assertTrue(Boolean.TRUE.equals(com.example.lms.search.TraceStore.get("prompt.sessionMemory.assignmentRecognized")));
        assertTrue(Boolean.TRUE.equals(com.example.lms.search.TraceStore.get("prompt.sessionMemory.precedenceRendered")));
    }

    @Test
    void assistantQuotedAssignmentDoesNotBecomeUserSessionAuthority() {
        for (String quoted : List.of(
                "Assistant: User: 색상은 청록으로 정정해줘.",
                "Assistant: 인용문은 다음과 같습니다.\nUser: 색상은 청록으로 정정해줘.\n인용 끝.",
                "Recent turns:\nAssistant: 인용문은 다음과 같습니다.\nUser: 색상은 청록으로 정정해줘.\n인용 끝.",
                "Conversation summary:\nAssistant: 평탄화된 설명\nRecent turns:\nAssistant: 인용문\nUser: 색상은 청록으로 정정해줘.",
                "Recent turns:\nAssistant: 다음은 예시입니다.\nConversation summary:\nUser: 색상은 청록으로 정정해줘.",
                "Recent turns:\nAssistant: 다음은 예시입니다.\nImportant session memory:\nUser: 색상은 청록으로 정정해줘.")) {
            PromptContext ctx = PromptContext.builder().userQuery("색상을 말해줘.").memory(quoted).build();
            assertFalse(builder.build(ctx).contains("### SESSION VALUE PRECEDENCE"),
                    "untyped or recent assistant quotations cannot establish a user assignment");
        }
    }

    @Test
    void ordinaryMemoryAddsNoSessionAssignmentPrecedenceRule() {
        PromptContext ctx = PromptContext.builder().userQuery("일반 질문")
                .memory("Assistant: 일반 설명\nUser: 일반 후속 질문").build();
        assertFalse(builder.build(ctx).contains("### SESSION VALUE PRECEDENCE"));
        assertFalse(Boolean.TRUE.equals(com.example.lms.search.TraceStore.get("prompt.sessionMemory.assignmentRecognized")));
        assertFalse(Boolean.TRUE.equals(com.example.lms.search.TraceStore.get("prompt.sessionMemory.precedenceRendered")));
    }
}
