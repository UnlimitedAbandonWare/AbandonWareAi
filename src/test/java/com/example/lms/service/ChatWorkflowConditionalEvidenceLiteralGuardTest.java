package com.example.lms.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ChatWorkflowConditionalEvidenceLiteralGuardTest {

    @Test
    void uncertainOfficialFactFallbackDoesNotBecomeDirectLiteralAnswer() {
        String query = "공식 공급자 문서 기준으로 gpt-5.5, openai/gpt-oss-120b, "
                + "gemini-2.5-pro가 현재 유효한 모델 ID인지 확인하고, "
                + "불확실하면 evidence_needed라고 답해줘.";

        assertFalse(ChatWorkflow.isDirectLiteralAnswerRequest(query));
        assertNull(ChatWorkflow.composeDirectLiteralAnswerFallback(query));
    }
}
