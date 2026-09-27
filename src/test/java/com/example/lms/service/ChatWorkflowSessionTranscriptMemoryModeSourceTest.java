package com.example.lms.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Session 56 회귀 소스 계약: 세션 transcript는 MemoryMode 게이트 아래 historyStr로
 * 조립되고, 모든 무근거/모델실패 폴백 호출부까지 동일 스냅샷으로 전달돼야 한다.
 * 또한 컨텍스트 주입 여부가 추적 키로 진단 가능해야 한다.
 */
class ChatWorkflowSessionTranscriptMemoryModeSourceTest {

    private static String workflowSource() throws Exception {
        return Files.readString(
                Path.of("main/java/com/example/lms/service/ChatWorkflow.java"),
                StandardCharsets.UTF_8);
    }

    @Test
    void everyNoEvidenceFallbackCallSitePassesSessionContext() throws Exception {
        String source = workflowSource();
        int searched = 0;
        int callSites = 0;
        while (true) {
            int call = source.indexOf("NoEvidenceChatFallback.orEvidenceFallback(", searched);
            if (call < 0) {
                break;
            }
            callSites++;
            int window = Math.min(source.length(), call + 1_200);
            String args = source.substring(call, window);
            assertTrue(args.contains("historyStr"),
                    "무근거 폴백 호출부가 세션 히스토리 스냅샷을 전달해야 한다: " + args);
            searched = call + 1;
        }
        assertEquals(4, callSites,
                "chat:draft 브레이커/요청예산/오픈서킷/모델실패 4개 호출부가 모두 동일 스냅샷을 써야 한다");
    }

    @Test
    void contextInjectionStagesAreDiagnosable() throws Exception {
        String source = workflowSource();

        assertTrue(source.contains("\"prompt.contextInjected.history\""),
                "히스토리 주입 여부가 추적 가능해야 한다");
        assertTrue(source.contains("\"prompt.contextInjected.lastAssistant\""),
                "직전 답변 주입 여부가 추적 가능해야 한다");
        assertTrue(source.contains("\"prompt.contextInjected.memory\""),
                "메모리 컨텍스트 주입 여부가 추적 가능해야 한다");
        assertTrue(source.contains("\"prompt.contextInjected.delivered\""),
                "실제 프롬프트 전달 여부가 추적 가능해야 한다");
    }

    @Test
    void emptyRescueFallbackReturnsNullInsteadOfGenericText() throws Exception {
        // 의미 없는 고정문구가 호출자 폴백을 가리지 않도록 무근거 tail은 null을 반환한다.
        String source = workflowSource();
        int rescueCheck = source.indexOf("if (rescueDocs.isEmpty())");
        assertTrue(rescueCheck > 0, "composeEvidenceFallback의 무근거 가드가 있어야 한다");
        String tail = source.substring(rescueCheck, Math.min(source.length(), rescueCheck + 200));
        assertTrue(tail.contains("return null"),
                "무근거 tail은 고정문구 대신 null을 반환해 orEvidenceFallback이 대체해야 한다: " + tail);
    }
}
